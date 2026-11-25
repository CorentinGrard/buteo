// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.xcsoar.mobile.core.DataDownload
import org.xcsoar.mobile.core.DataFile
import org.xcsoar.mobile.core.FlightArea
import org.xcsoar.mobile.core.MapFeatures
import org.xcsoar.mobile.core.MapRegion
import org.xcsoar.mobile.core.OfflineMap
import org.xcsoar.mobile.core.PreflightCheck
import org.xcsoar.mobile.core.PreflightData
import org.xcsoar.mobile.core.REPOSITORY_URI
import org.xcsoar.mobile.core.StoredRegions
import org.xcsoar.mobile.core.TaskOutline
import org.xcsoar.mobile.ui.UiText
import org.xcsoar.mobile.ui.toText
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * The data a flight needs (D31): checked against the task, or the area
 * around the glider ([PreflightCheck], the pre-flight page), and kept
 * up to date.  On an unmetered network, on the ground, while the app
 * runs, it downloads without asking what the check finds: newer
 * versions of the files in use from XCSoar's repository, and the
 * vector map's regions the flight reaches.  Another file to cover the
 * flight (a larger map) is the pilot's choice, on the page.
 */
class FlightData(private val app: XcsoarApp, private val scope: CoroutineScope) {
    /** A file coming down: its title and progress (0..1, -1 unknown). */
    data class Progress(val title: String, val fraction: Float)

    data class State(
        val downloading: Progress? = null,
        /** The last download that failed, and why. */
        val error: UiText? = null,
        /** When the automatic downloads last looked, ms since the epoch. */
        val lastCheck: Long? = null,
        /** Counts changes of the vector map's region files: the map opens them again. */
        val regionsVersion: Int = 0,
    )

    private val stateFlow = MutableStateFlow(State())
    val state: StateFlow<State> = stateFlow.asStateFlow()

    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val downloads = Mutex()

    /** Where the vector map's region files are. */
    val regionDir: File get() = File(app.xcsoarDataDir, "maps")

    init {
        scope.launch {
            delay(START_DELAY_MS)
            while (true) {
                try {
                    automatic()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // the core may not run yet, or the network went: later
                    Log.d(TAG, "flight data: ${e.message}")
                }
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    /** [automatic] now, in the app's scope (the pre-flight page opened). */
    fun automaticNow() {
        scope.launch {
            try {
                automatic()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "flight data: ${e.message}")
            }
        }
    }

    /** [download] in the app's scope: it goes on when the page closes. */
    fun downloadLater(files: List<DataDownload>) {
        scope.launch { download(files) }
    }

    /**
     * On an unmetered network and on the ground, unless downloading
     * already: what the check finds to download.
     */
    private suspend fun automatic() {
        val capabilities = connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
        if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true ||
            app.anyCore.flightState.value?.flying == true || downloads.isLocked)
            return
        val check = check()
        stateFlow.update { it.copy(lastCheck = System.currentTimeMillis()) }
        if (check.automatic.isNotEmpty()) {
            Log.i(TAG, "downloading for the flight: ${check.automatic.map { it.name }}")
            download(check.automatic)
        }
    }

    /**
     * The data against the task (else around the glider or the phone's
     * last position).
     *
     * @param fresh read the indexes again (the pilot asked)
     */
    suspend fun check(fresh: Boolean = false): PreflightCheck {
        val core = app.anyCore
        val task = core.mapFeatures(MapFeatures.TASK)?.let {
            try {
                TaskOutline.parse(it)
            } catch (e: Exception) {
                null
            }
        }
        val area = FlightArea.of(task, core.flightState.value?.position
                                       ?: app.lastKnownPosition())
        val status = core.dataStatus()
        val coverage = area?.let { core.dataCoverage(it.lines, it.margin) }
        val repository = try {
            core.repositoryFiles(app.downloader.index(REPOSITORY_URI, fresh = fresh).path)
        } catch (e: Exception) {
            Log.d(TAG, "repository index: ${e.message}")
            null
        }
        val vector = app.vectorMap
        val regionIndex = if (!vector) null else try {
            val index = app.downloader.index(BuildConfig.MAP_REGIONS_URL, "map-regions.json", fresh)
            MapRegion.parseIndex(withContext(Dispatchers.IO) { index.readText() },
                                 BuildConfig.MAP_REGIONS_URL)
        } catch (e: Exception) {
            Log.d(TAG, "map regions index: ${e.message}")
            null
        }
        val fileDates = withContext(Dispatchers.IO) {
            (status.map.files + status.airspace.files + status.waypoints.files)
                .map(::File).filter { it.isFile }
                .associate { it.name to day(it.lastModified()) }
        }
        val regions = if (!vector) null else withContext(Dispatchers.IO) {
            OfflineMap.open(regionDir) { file, e -> Log.w(TAG, "region map $file", e) }?.use { map ->
                StoredRegions(map.regions.associate { it.name to it.date },
                              gap = area?.let { map.firstGap(it.lines, it.margin) })
            } ?: StoredRegions(emptyMap(), gap = null)
        }
        return PreflightCheck.of(area, PreflightData(status, coverage, repository, fileDates,
                                                     regions, regionIndex))
    }

    /**
     * Download [files] one after the other (one list at a time) and use
     * them: a data file replaces the one of its kind, a region joins
     * the vector map's.  A failure is kept in [state] and the next
     * file comes.
     */
    suspend fun download(files: List<DataDownload>) = downloads.withLock {
        try {
            for (file in files)
                fetch(file)
        } finally {
            stateFlow.update { it.copy(downloading = null) }
        }
    }

    private suspend fun fetch(file: DataDownload) {
        stateFlow.update { it.copy(downloading = Progress(file.title, 0f), error = null) }
        try {
            val target = File(File(app.xcsoarDataDir, file.folder), file.name)
            app.downloader.download(file.url, target, file.sha256) { p ->
                stateFlow.update { it.copy(downloading = Progress(file.title, p)) }
            }
            val use = file.use
            if (use == null) {
                stateFlow.update { it.copy(regionsVersion = it.regionsVersion + 1) }
            } else if (use(use, target)) {
                app.anyCore.setDataFile(use, target.path)
            }
            Log.i(TAG, "downloaded ${file.name}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "download of ${file.name}", e)
            stateFlow.update { it.copy(error = e.toText()) }
        }
    }

    /**
     * Whether to give the core [target] as its [kind] of file: a new
     * file, or the one file in use (loaded again).  With several files
     * of the kind, setting one would drop the others: the newer copy
     * loads at the next start.
     */
    private suspend fun use(kind: DataFile, target: File): Boolean {
        val status = app.anyCore.dataStatus()
        val inUse = when (kind) {
            DataFile.MAP -> status.map.files
            DataFile.AIRSPACE -> status.airspace.files
            DataFile.WAYPOINTS -> status.waypoints.files
            DataFile.RASP -> status.rasp.files
        }.map { File(it).name }
        return target.name !in inUse || inUse.size == 1
    }

    private companion object {
        const val TAG = "XCSoar"
        const val START_DELAY_MS = 20_000L
        const val CHECK_INTERVAL_MS = 5 * 60_000L

        /** "2026-11-24" in the phone's time zone. */
        fun day(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
            .toLocalDate().toString()
    }
}
