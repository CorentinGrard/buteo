// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.xcsoar.mobile.FlightData
import org.xcsoar.mobile.core.DataDownload
import org.xcsoar.mobile.core.PreflightCheck
import org.xcsoar.mobile.ui.UiText
import org.xcsoar.mobile.ui.toText

data class PreflightState(
    /** Null until the first check is done. */
    val check: PreflightCheck? = null,
    val checking: Boolean = false,
    /** The check failed (no core yet). */
    val error: UiText? = null,
)

/**
 * The pre-flight page: the data the flight needs ([FlightData]), and
 * its downloads.
 *
 * @param check the check, reading the indexes again when fresh
 * @param startDownload starts fetching files and using them, in the app's
 *   scope (the page may close)
 * @param automatic starts what downloads by itself (on Wi-Fi, on the
 *   ground), now
 * @param downloads the downloads' progress, kept by the app
 */
class PreflightViewModel(
    private val check: suspend (fresh: Boolean) -> PreflightCheck,
    private val startDownload: (List<DataDownload>) -> Unit,
    private val automatic: () -> Unit,
    val downloads: StateFlow<FlightData.State>,
) : ViewModel() {
    private val stateFlow = MutableStateFlow(PreflightState())
    val state: StateFlow<PreflightState> = stateFlow.asStateFlow()

    private var job: Job? = null

    /**
     * Check again; [fresh] reads the indexes again (the pilot asked).
     * On Wi-Fi, what the check finds downloads now rather than within
     * the next minutes.
     */
    fun refresh(fresh: Boolean = false) {
        job?.cancel()
        stateFlow.update { it.copy(checking = true, error = null) }
        job = viewModelScope.launch {
            checkNow(fresh)
            if (stateFlow.value.check?.automatic?.isNotEmpty() == true)
                automatic()
        }
    }

    /** The downloads ended: what they changed, without starting others. */
    fun recheck() {
        job?.cancel()
        job = viewModelScope.launch { checkNow(false) }
    }

    /** Download [files]: the pilot asked, on any network. */
    fun download(files: List<DataDownload>) = startDownload(files)

    private suspend fun checkNow(fresh: Boolean) {
        try {
            val result = check(fresh)
            stateFlow.update { it.copy(check = result, checking = false) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            stateFlow.update { it.copy(checking = false, error = e.toText()) }
        }
    }
}
