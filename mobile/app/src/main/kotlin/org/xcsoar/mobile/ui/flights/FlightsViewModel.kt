// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.flights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xcsoar.mobile.R
import org.xcsoar.mobile.core.Analysis
import org.xcsoar.mobile.core.WeGlideFlight
import org.xcsoar.mobile.core.XcsoarCore
import org.xcsoar.mobile.ui.UiText
import org.xcsoar.mobile.ui.summary.FlightSummary
import java.io.File
import org.xcsoar.mobile.ui.text

/** One IGC file XCSoar's logger wrote. */
data class FlightLog(
    val path: String,
    /** The file name, e.g. "2026-10-02-XCS-AAA-01.igc". */
    val name: String,
    /** Last written, ms since the epoch. */
    val modified: Long,
    /** Bytes. */
    val size: Long,
    /**
     * The flight's first fix, from the file (ms since the epoch); null
     * if it has none.  A flight copied in has the copy's [modified].
     */
    val started: Long? = null,
) {
    /** When the flight was: its first fix, else when the file was written. */
    val time get() = started ?: modified
}

/** How the upload of one flight to WeGlide went. */
sealed interface Upload {
    data object Running : Upload
    data class Done(val flight: WeGlideFlight) : Upload
    /** [message]: what WeGlide or the network said, if anything. */
    data class Failed(val message: String?) : Upload
}

/** Where the flights uploaded to WeGlide are remembered, by file name. */
interface UploadStore {
    suspend fun load(): Map<String, WeGlideFlight>
    suspend fun save(name: String, flight: WeGlideFlight)
    /** The file is gone: a new one may get its name. */
    suspend fun remove(name: String)
}

/** A recorded flight opened from the list ([FlightsViewModel.open]). */
data class FlightDetail(
    val log: FlightLog,
    /** While the core reads the file. */
    val reading: Boolean = true,
    /** Null while reading, and when the file has no take-off. */
    val summary: FlightSummary? = null,
    /** The take-off's day, "YYYY-MM-DD" (UTC). */
    val date: String? = null,
    /** The core's analysis of the file (its replay reads it); null while reading. */
    val analysis: Analysis? = null,
    /** Why the file could not be deleted. */
    val error: UiText? = null,
)

/**
 * The recorded flights, newest flight first (by the IGC file's own date,
 * not the file's), and their WeGlide uploads: those
 * of earlier sessions from [uploadStore] (WeGlide refuses a file twice).
 */
class FlightsViewModel(
    private val list: suspend () -> List<FlightLog>,
    private val core: XcsoarCore? = null,
    private val uploadStore: UploadStore? = null,
    /** Where files are deleted (tests run it at once). */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val flightsFlow = MutableStateFlow<List<FlightLog>?>(null)
    /** `null` while loading. */
    val flights: StateFlow<List<FlightLog>?> = flightsFlow.asStateFlow()

    private val weGlideFlow = MutableStateFlow(false)
    /** WeGlide is set up: flights can be uploaded. */
    val weGlideReady: StateFlow<Boolean> = weGlideFlow.asStateFlow()

    private val uploadsFlow = MutableStateFlow<Map<String, Upload>>(emptyMap())
    /** Uploads, by IGC path: done ones remembered, others of this session. */
    val uploads: StateFlow<Map<String, Upload>> = uploadsFlow.asStateFlow()

    private val detailFlow = MutableStateFlow<FlightDetail?>(null)
    /** The flight opened last; null before. */
    val detail: StateFlow<FlightDetail?> = detailFlow.asStateFlow()

    /* the analyses read this session, by path and time written */
    private val analyses = mutableMapOf<Pair<String, Long>, Analysis>()
    private var openJob: Job? = null

    /** Show [flight]: its analysis from the core, seconds the first time. */
    fun open(flight: FlightLog) {
        openJob?.cancel()
        detailFlow.value = FlightDetail(flight)
        openJob = viewModelScope.launch {
            val key = flight.path to flight.modified
            val analysis = analyses[key] ?: try {
                core?.analyseFlightFile(flight.path)?.also { analyses[key] = it }
            } catch (_: Exception) {
                null
            }
            detailFlow.value = FlightDetail(
                flight, reading = false, date = analysis?.date,
                analysis = analysis,
                summary = analysis?.takeIf { it.flight != null }
                    ?.let { FlightSummary.of(it, replay = false) })
        }
    }

    /**
     * Delete [flight]'s IGC file, and the WeGlide upload remembered for
     * it; [onDeleted] once it is gone.
     */
    fun delete(flight: FlightLog, onDeleted: () -> Unit) {
        viewModelScope.launch {
            if (!withContext(io) { File(flight.path).delete() }) {
                detailFlow.update { it?.copy(error = text(R.string.flights_cannot_delete, flight.name)) }
                return@launch
            }
            try {
                uploadStore?.remove(flight.name)
            } catch (_: Exception) {
                // a stale upload only shows on a later file of that name
            }
            uploadsFlow.update { it - flight.path }
            analyses.keys.removeAll { it.first == flight.path }
            refresh()
            onDeleted()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val flights = try {
                list().sortedByDescending { it.time }
            } catch (_: Exception) {
                emptyList()
            }
            val saved = try {
                uploadStore?.load().orEmpty()
            } catch (_: Exception) {
                emptyMap()
            }
            uploadsFlow.update { current ->
                flights.mapNotNull { flight ->
                    saved[flight.name]?.let { flight.path to Upload.Done(it) }
                }.toMap() + current
            }
            flightsFlow.value = flights
            weGlideFlow.value = try {
                core?.weGlideSettings()?.isConfigured == true
            } catch (_: Exception) {
                false
            }
        }
    }

    fun upload(flight: FlightLog) {
        val core = core ?: return
        if (uploadsFlow.value[flight.path] == Upload.Running) return
        uploadsFlow.update { it + (flight.path to Upload.Running) }
        viewModelScope.launch {
            val result = try {
                Upload.Done(core.uploadToWeGlide(flight.path)).also {
                    try {
                        uploadStore?.save(flight.name, it.flight)
                    } catch (_: Exception) {
                        // shown for this session at least
                    }
                }
            } catch (e: Exception) {
                Upload.Failed(e.message)
            }
            uploadsFlow.update { it + (flight.path to result) }
        }
    }
}
