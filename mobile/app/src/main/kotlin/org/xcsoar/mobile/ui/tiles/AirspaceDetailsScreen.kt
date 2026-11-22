// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.tiles

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.xcsoar.mobile.R
import org.xcsoar.mobile.core.AirspaceDetails
import org.xcsoar.mobile.core.RadioSlot
import org.xcsoar.mobile.core.XcsoarCore
import org.xcsoar.mobile.ui.ActionButton
import org.xcsoar.mobile.ui.PageLayout
import org.xcsoar.mobile.ui.ScreenHeader
import org.xcsoar.mobile.ui.ValueList
import org.xcsoar.mobile.ui.settings.Explanation
import org.xcsoar.mobile.ui.theme.XcsTheme

/** What the airspace page shows: [details] null when there is no airspace. */
data class AirspaceDetailsState(
    val read: Boolean = false,
    val details: AirspaceDetails? = null,
    /** The slot a frequency was just set in, for a confirmation. */
    val frequencySet: RadioSlot? = null,
)

class AirspaceDetailsViewModel(private val core: XcsoarCore) : ViewModel() {
    private val stateFlow = MutableStateFlow(AirspaceDetailsState())
    val state: StateFlow<AirspaceDetailsState> = stateFlow.asStateFlow()

    /** A new airspace was asked for: forget the old one's state. */
    fun reset() {
        stateFlow.value = AirspaceDetailsState()
    }

    /** Reads it once a second while the page shows (the distance moves). */
    suspend fun refreshWhileShown() {
        while (true) {
            read()
            delay(REFRESH_MS)
        }
    }

    private suspend fun read() {
        try {
            val details = core.airspaceDetails()
            stateFlow.value = stateFlow.value.copy(read = true, details = details)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("XCSoar", "airspace not read", e)
        }
    }

    /** "Ack Day" (true) or "Enable" (false). */
    fun acknowledge(acknowledge: Boolean) {
        viewModelScope.launch {
            try {
                core.acknowledgeAirspaceDetails(acknowledge)
            } catch (e: Exception) {
                Log.w("XCSoar", "airspace not acknowledged", e)
            }
            read()
        }
    }

    /** "Set Active Frequency" / "Set Standby Frequency", named after the
        station (else the airspace), as upstream does. */
    fun setFrequency(slot: RadioSlot) {
        val details = stateFlow.value.details ?: return
        viewModelScope.launch {
            try {
                core.setRadioFrequency(slot, details.frequency,
                                       details.station.ifEmpty { details.name })
                stateFlow.value = stateFlow.value.copy(frequencySet = slot)
            } catch (e: Exception) {
                Log.w("XCSoar", "frequency not set", e)
            }
        }
    }

    companion object {
        const val REFRESH_MS = 1_000L
    }
}

@Composable
fun AirspaceDetailsScreen(viewModel: AirspaceDetailsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.refreshWhileShown() }
    BackHandler(onBack = onBack)
    AirspaceDetailsContent(state, viewModel::acknowledge, viewModel::setFrequency, onBack)
}

/**
 * XCSoar's airspace details, for the nearest airspace a tile asked for:
 * its class, limits, radio and distance; its frequency can go to the
 * radio, and its warnings can be silenced until tomorrow.
 */
@Composable
fun AirspaceDetailsContent(
    state: AirspaceDetailsState,
    onAcknowledge: (Boolean) -> Unit,
    onFrequency: (RadioSlot) -> Unit,
    onBack: () -> Unit,
) {
    PageLayout {
        ScreenHeader(stringResource(R.string.airspace), onBack)

        val details = state.details
        if (details == null) {
            Explanation(stringResource(if (state.read) R.string.airdetails_none
                                    else R.string.status_reading))
            return@PageLayout
        }

        Text(details.name, color = XcsTheme.colors.text, fontSize = 22.sp,
             fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp))
        ValueList(buildList {
            add(stringResource(R.string.airdetails_class) to details.airspaceClass)
            add(stringResource(R.string.airdetails_type) to details.type)
            add(stringResource(R.string.airdetails_top) to details.top)
            add(stringResource(R.string.airdetails_base) to details.base)
            add(stringResource(R.string.units_distance) to details.distance)
            if (details.frequency.isNotEmpty()) add(stringResource(R.string.radio_title) to "${details.frequency} MHz")
            if (details.station.isNotEmpty()) add(stringResource(R.string.airdetails_station) to details.station)
            if (details.squawk.isNotEmpty()) add(stringResource(R.string.airdetails_squawk) to details.squawk)
        })

        if (details.frequency.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(stringResource(R.string.radio_set_active), outlined = true, modifier = Modifier.weight(1f)) {
                    onFrequency(RadioSlot.ACTIVE)
                }
                ActionButton(stringResource(R.string.radio_set_standby), outlined = true, modifier = Modifier.weight(1f)) {
                    onFrequency(RadioSlot.STANDBY)
                }
            }
            // one line either way: the button below does not move
            Explanation(state.frequencySet?.let {
                stringResource(if (it == RadioSlot.ACTIVE) R.string.airdetails_now_active
                               else R.string.airdetails_now_standby, details.frequency)
            } ?: stringResource(R.string.airdetails_sends, details.frequency))
        }

        ActionButton(stringResource(if (details.acknowledged) R.string.airdetails_enable
                               else R.string.airdetails_ack_day), outlined = true,
                     modifier = Modifier.fillMaxWidth()) {
            onAcknowledge(!details.acknowledged)
        }
        Explanation(stringResource(if (details.acknowledged) R.string.airdetails_acked
                                    else R.string.airdetails_ack_hint))
    }
}
