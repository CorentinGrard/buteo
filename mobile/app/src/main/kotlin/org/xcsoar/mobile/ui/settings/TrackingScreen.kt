// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.xcsoar.mobile.R
import org.xcsoar.mobile.core.TrackingSettings
import org.xcsoar.mobile.core.XcsoarCore
import org.xcsoar.mobile.ui.ActionButton
import org.xcsoar.mobile.ui.InputField
import org.xcsoar.mobile.ui.PageLayout
import org.xcsoar.mobile.ui.ScreenHeader
import org.xcsoar.mobile.ui.SettingsGroup
import org.xcsoar.mobile.ui.SwitchRow
import org.xcsoar.mobile.ui.UiText
import org.xcsoar.mobile.ui.flight.Caption
import org.xcsoar.mobile.ui.flight.Segmented
import org.xcsoar.mobile.ui.flight.Stepper
import org.xcsoar.mobile.ui.text
import org.xcsoar.mobile.ui.theme.XcsTheme

/** The live tracking settings as edited; saved with "Save". */
data class TrackingState(
    val loaded: Boolean = false,
    val settings: TrackingSettings = TrackingSettings(),
    val error: UiText? = null,
) {
    private val skyLinesKeyValid get() = TrackingSettings.isValidKey(settings.skylines.key)
    val canSave get() = loaded && skyLinesKeyValid && settings.livetrack24.let {
        TrackingSettings.fits(it.username) && TrackingSettings.fits(it.password) &&
            TrackingSettings.fits(it.vehicleName)
    }
    val keyError get() = !skyLinesKeyValid
}

class TrackingViewModel(private val core: XcsoarCore) : ViewModel() {
    private val stateFlow = MutableStateFlow(TrackingState())
    val state: StateFlow<TrackingState> = stateFlow.asStateFlow()

    fun load() {
        viewModelScope.launch {
            val settings = try { core.trackingSettings() } catch (_: Exception) { null }
            stateFlow.value = TrackingState(loaded = settings != null,
                                            settings = settings ?: TrackingSettings())
        }
    }

    fun change(edit: (TrackingSettings) -> TrackingSettings) =
        stateFlow.update { it.copy(settings = edit(it.settings), error = null) }

    fun save(onSaved: () -> Unit) {
        val s = stateFlow.value
        if (!s.canSave) return
        viewModelScope.launch {
            try {
                core.setTrackingSettings(s.settings)
                onSaved()
            } catch (_: Exception) {
                stateFlow.update { it.copy(error = text(R.string.crew_could_not_save)) }
            }
        }
    }
}

@Composable
fun TrackingScreen(viewModel: TrackingViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.load() }
    BackHandler(onBack = onBack)
    TrackingContent(state, viewModel::change, onSave = { viewModel.save(onBack) }, onBack)
}

/**
 * XCSoar's live tracking: the XCSoar Cloud (position out, nearby
 * traffic and thermals in), SkyLines and LiveTrack24.  Positions go
 * out only while the phone has mobile data.
 */
@Composable
fun TrackingContent(
    state: TrackingState,
    onChange: ((TrackingSettings) -> TrackingSettings) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = XcsTheme.colors
    val s = state.settings
    PageLayout(bottom = {
        ActionButton(stringResource(R.string.save), primary = true, enabled = state.canSave,
                     modifier = Modifier.weight(1f), onClick = onSave)
    }) {
        ScreenHeader(stringResource(R.string.menu_tracking), onBack)

        Caption(stringResource(R.string.track_cloud), Modifier.padding(start = 4.dp))
        SettingsGroup {
            SwitchRow(stringResource(R.string.track_share),
                      stringResource(R.string.track_share_detail),
                      s.cloud.enabled == true, enabled = state.loaded) { on ->
                onChange { it.copy(cloud = it.cloud.copy(enabled = on)) }
            }
            if (s.cloud.enabled == true) {
                SwitchRow(stringResource(R.string.track_traffic),
                          stringResource(R.string.track_traffic_detail),
                          s.cloud.showTraffic) { on ->
                    onChange { it.copy(cloud = it.cloud.copy(showTraffic = on)) }
                }
                SwitchRow(stringResource(R.string.track_thermals),
                          stringResource(R.string.track_thermals_detail), s.cloud.showThermals) { on ->
                    onChange { it.copy(cloud = it.cloud.copy(showThermals = on)) }
                }
                SwitchRow(stringResource(R.string.track_roaming),
                          stringResource(R.string.track_roaming_detail), s.cloud.roaming) { on ->
                    onChange { it.copy(cloud = it.cloud.copy(roaming = on)) }
                }
            }
        }

        Caption("SkyLines", Modifier.padding(start = 4.dp, top = 8.dp))
        SettingsGroup {
            SwitchRow(stringResource(R.string.track_skylines),
                      stringResource(R.string.track_skylines_detail),
                      s.skylines.enabled, enabled = state.loaded) { on ->
                onChange { it.copy(skylines = it.skylines.copy(enabled = on)) }
            }
        }
        if (s.skylines.enabled) {
            InputField(s.skylines.key.takeUnless { it == "0" }.orEmpty(),
                       { key -> onChange { it.copy(skylines = it.skylines.copy(
                           key = key.trim().uppercase().ifEmpty { "0" })) } },
                       stringResource(R.string.track_key),
                       capitalization = KeyboardCapitalization.Characters)
            if (state.keyError)
                Text(stringResource(R.string.track_key_error), color = colors.warning,
                     fontSize = 15.sp, modifier = Modifier.padding(horizontal = 4.dp))
            else
                Explanation(stringResource(R.string.track_key_hint))
            IntervalStepper(stringResource(R.string.track_skylines_interval), s.skylines.interval) { i ->
                onChange { it.copy(skylines = it.skylines.copy(interval = i)) }
            }
            SettingsGroup {
                SwitchRow(stringResource(R.string.track_friends),
                          stringResource(R.string.track_friends_detail), s.skylines.traffic) { on ->
                    onChange { it.copy(skylines = it.skylines.copy(traffic = on)) }
                }
                SwitchRow(stringResource(R.string.track_nearby),
                          stringResource(R.string.track_nearby_detail),
                          s.skylines.nearTraffic) { on ->
                    onChange { it.copy(skylines = it.skylines.copy(nearTraffic = on)) }
                }
                SwitchRow(stringResource(R.string.track_roaming),
                          stringResource(R.string.track_roaming_detail), s.skylines.roaming) { on ->
                    onChange { it.copy(skylines = it.skylines.copy(roaming = on)) }
                }
            }
        }

        Caption("LiveTrack24", Modifier.padding(start = 4.dp, top = 8.dp))
        SettingsGroup {
            SwitchRow(stringResource(R.string.track_livetrack),
                      stringResource(R.string.track_livetrack_detail),
                      s.livetrack24.enabled, enabled = state.loaded) { on ->
                onChange { it.copy(livetrack24 = it.livetrack24.copy(enabled = on)) }
            }
        }
        if (s.livetrack24.enabled) {
            val servers = TrackingSettings.SERVERS
            Segmented(listOf("LiveTrack24", stringResource(R.string.track_test), "DHV"),
                      selected = servers.indexOf(s.livetrack24.server),
                      onSelect = { i ->
                          onChange { it.copy(livetrack24 = it.livetrack24.copy(server = servers[i])) }
                      },
                      modifier = Modifier.fillMaxWidth())
            Explanation(stringResource(R.string.track_server, s.livetrack24.server))
            InputField(s.livetrack24.username,
                       { v -> onChange { it.copy(livetrack24 = it.livetrack24.copy(username = v)) } },
                       stringResource(R.string.track_username))
            InputField(s.livetrack24.password,
                       { v -> onChange { it.copy(livetrack24 = it.livetrack24.copy(password = v)) } },
                       stringResource(R.string.track_password), password = true)
            InputField(s.livetrack24.vehicleName,
                       { v ->
                           onChange { it.copy(livetrack24 = it.livetrack24.copy(vehicleName = v)) }
                       },
                       stringResource(R.string.glider), capitalization = KeyboardCapitalization.Words)
            if (!state.canSave && !state.keyError)
                Text(stringResource(R.string.track_at_most, TrackingSettings.MAX_TEXT_BYTES),
                     color = colors.warning, fontSize = 15.sp,
                     modifier = Modifier.padding(horizontal = 4.dp))
            IntervalStepper(stringResource(R.string.track_livetrack_interval), s.livetrack24.interval) { i ->
                onChange { it.copy(livetrack24 = it.livetrack24.copy(interval = i)) }
            }
        }

        Explanation(stringResource(R.string.track_footer))
        state.error?.let { Text(it.text(), color = colors.warning, fontSize = 16.sp) }
    }
}

/** One of XCSoar's tracking intervals. */
@Composable
private fun IntervalStepper(name: String, seconds: Int, onChange: (Int) -> Unit) {
    val intervals = TrackingSettings.INTERVALS
    val index = intervals.indexOf(seconds).takeIf { it >= 0 }
        ?: intervals.indexOfFirst { it >= seconds }.takeIf { it >= 0 } ?: intervals.lastIndex
    val text = formatInterval(seconds)
    Stepper(name, stringResource(R.string.track_every), text, text,
            canDecrease = index > 0, canIncrease = index < intervals.lastIndex,
            onDecrease = { onChange(intervals[index - 1]) },
            onIncrease = { onChange(intervals[index + 1]) },
            modifier = Modifier.fillMaxWidth())
}

internal fun formatInterval(seconds: Int) =
    if (seconds < 60) "$seconds s" else "${seconds / 60} min"
