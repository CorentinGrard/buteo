// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.settings

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
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
import org.xcsoar.mobile.core.WindInfo
import org.xcsoar.mobile.core.WindOption
import org.xcsoar.mobile.core.WindSource
import org.xcsoar.mobile.core.XcsoarCore
import org.xcsoar.mobile.ui.ActionButton
import org.xcsoar.mobile.ui.Format
import org.xcsoar.mobile.ui.PageLayout
import org.xcsoar.mobile.ui.ScreenHeader
import org.xcsoar.mobile.ui.SettingsGroup
import org.xcsoar.mobile.ui.SwitchRow
import org.xcsoar.mobile.ui.flight.Caption
import org.xcsoar.mobile.ui.flight.Stepper
import org.xcsoar.mobile.ui.theme.XcsTheme
import kotlin.math.roundToInt

class WindViewModel(private val core: XcsoarCore) : ViewModel() {
    private val windFlow = MutableStateFlow<WindInfo?>(null)
    /** The wind in use; null until read. */
    val wind: StateFlow<WindInfo?> = windFlow.asStateFlow()

    private val optionsFlow = MutableStateFlow<Map<WindOption, Boolean>>(emptyMap())
    val options: StateFlow<Map<WindOption, Boolean>> = optionsFlow.asStateFlow()

    /** Reads the settings, then the wind once a second while the page shows. */
    suspend fun refreshWhileShown() {
        readOptions()
        while (true) {
            readWind()
            delay(REFRESH_MS)
        }
    }

    private suspend fun readOptions() {
        optionsFlow.value = WindOption.entries.mapNotNull { option ->
            try {
                core.windOption(option)?.let { option to it }
            } catch (_: Exception) {
                null
            }
        }.toMap()
    }

    private suspend fun readWind() {
        try {
            windFlow.value = core.wind()
        } catch (e: CancellationException) {
            // the page was left
            throw e
        } catch (e: Exception) {
            Log.w("XCSoar", "wind not read", e)
        }
    }

    fun setOption(option: WindOption, value: Boolean) {
        optionsFlow.value = optionsFlow.value + (option to value)
        viewModelScope.launch {
            try {
                core.setWindOption(option, value)
            } catch (_: Exception) {
                readOptions()
            }
        }
    }

    /** The pilot's wind, in use at once (like upstream's wind dialog). */
    fun setManual(speed: Double, bearing: Double) {
        windFlow.value = WindInfo(WindSource.MANUAL, manual = true, speed, bearing)
        viewModelScope.launch {
            try {
                core.setManualWind(speed, bearing)
            } catch (_: Exception) {
                readWind()
            }
        }
    }

    fun clearManual() {
        viewModelScope.launch {
            try {
                core.clearManualWind()
            } finally {
                readWind()
            }
        }
    }

    companion object {
        const val REFRESH_MS = 1_000L
        /** upstream's limit of the manual wind: 200 km/h */
        const val MAX_SPEED = 200 / 3.6
        /** degrees per step of the manual direction, as upstream */
        const val BEARING_STEP = 5
    }
}

@Composable
fun WindScreen(viewModel: WindViewModel, onBack: () -> Unit) {
    val wind by viewModel.wind.collectAsStateWithLifecycle()
    val options by viewModel.options.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.refreshWhileShown() }
    BackHandler(onBack = onBack)
    WindContent(wind, options, viewModel::setOption, viewModel::setManual,
                viewModel::clearManual, onBack)
}

/**
 * XCSoar's wind settings: the wind in use and where it comes from, the
 * wind set by hand (in use at once, until a newer estimate), and which
 * estimates XCSoar makes.
 */
@Composable
fun WindContent(
    wind: WindInfo?,
    options: Map<WindOption, Boolean>,
    onOption: (WindOption, Boolean) -> Unit,
    onManual: (speed: Double, bearing: Double) -> Unit,
    onClearManual: () -> Unit,
    onBack: () -> Unit,
) {
    PageLayout {
        ScreenHeader(stringResource(R.string.wind), onBack)

        CurrentWind(wind)

        Caption(stringResource(R.string.wind_manual), Modifier.padding(start = 4.dp, top = 8.dp))
        val known = wind != null && wind.source != WindSource.NONE
        val speed = if (known) wind.speed else 0.0
        val bearing = if (known) wind.bearing.roundToInt() else 0
        val direction = Format.bearing(bearing.toDouble())
        Stepper(stringResource(R.string.wind_direction_name),
                "${stringResource(R.string.sound_from)} · °", direction.text,
                stringResource(R.string.wind_spoken_direction, direction.text),
                canDecrease = wind != null, canIncrease = wind != null,
                onDecrease = { onManual(speed, stepBearing(bearing, -1)) },
                onIncrease = { onManual(speed, stepBearing(bearing, +1)) },
                modifier = Modifier.fillMaxWidth())
        val value = Format.windSpeed(speed)
        Stepper(stringResource(R.string.wind_speed_name),
                "${stringResource(R.string.wind_speed)} · ${value.unit}", value.text, "${value.text} ${value.unit}",
                canDecrease = wind != null && speed > 0.01,
                canIncrease = wind != null && speed < WindViewModel.MAX_SPEED - 0.01,
                onDecrease = {
                    onManual(Format.stepWindSpeed(speed, -1).coerceIn(0.0, WindViewModel.MAX_SPEED),
                             bearing.toDouble())
                },
                onIncrease = {
                    onManual(Format.stepWindSpeed(speed, +1).coerceIn(0.0, WindViewModel.MAX_SPEED),
                             bearing.toDouble())
                },
                modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton(stringResource(R.string.wind_clear), enabled = wind?.manual == true, onClick = onClearManual)
            Explanation(stringResource(R.string.wind_manual_hint))
        }

        Caption(stringResource(R.string.wind_estimates), Modifier.padding(start = 4.dp, top = 8.dp))
        SettingsGroup {
            SwitchRow(stringResource(R.string.wind_circling),
                      stringResource(R.string.wind_circling_detail),
                      options[WindOption.CIRCLING]) { onOption(WindOption.CIRCLING, it) }
            SwitchRow(stringResource(R.string.wind_zigzag),
                      stringResource(R.string.wind_zigzag_detail),
                      options[WindOption.ZIG_ZAG]) { onOption(WindOption.ZIG_ZAG, it) }
            SwitchRow(stringResource(R.string.wind_external),
                      stringResource(R.string.wind_external_detail),
                      options[WindOption.EXTERNAL]) { onOption(WindOption.EXTERNAL, it) }
        }
        SettingsGroup {
            SwitchRow(stringResource(R.string.wind_drift),
                      stringResource(R.string.wind_drift_detail),
                      options[WindOption.TRAIL_DRIFT]) { onOption(WindOption.TRAIL_DRIFT, it) }
        }
    }
}

/** "270° 15 km/h" and where it comes from, in large type. */
@Composable
private fun CurrentWind(wind: WindInfo?) {
    val colors = XcsTheme.colors
    val known = wind != null && wind.source != WindSource.NONE
    val direction = Format.bearing(if (known) wind.bearing else null)
    val speed = Format.windSpeed(if (known) wind.speed else null)
    val source = describe(wind)
    val spoken = if (known)
        stringResource(R.string.wind_spoken, direction.text, speed.text, speed.unit, source)
    else stringResource(R.string.wind_spoken_none, source)
    Column(Modifier
               .fillMaxWidth()
               .background(colors.panel, RoundedCornerShape(14.dp))
               .padding(16.dp)
               .clearAndSetSemantics {
                   contentDescription = spoken
               }) {
        Caption(stringResource(R.string.wind_in_use))
        Text(if (known) "${direction.text}${direction.unit}  ${speed.text} ${speed.unit}"
             else Format.INVALID,
             color = colors.text, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Text(source, color = colors.textSecondary, fontSize = 15.sp)
    }
}

/** Where the wind comes from, as upstream's wind dialog says it. */
@Composable
private fun describe(wind: WindInfo?): String = stringResource(when (wind?.source) {
    null -> R.string.status_reading
    WindSource.NONE -> R.string.wind_source_none
    WindSource.MANUAL -> R.string.wind_manual
    WindSource.CIRCLING -> R.string.wind_source_circling
    WindSource.ZIG_ZAG -> R.string.wind_source_zigzag
    WindSource.EXTERNAL -> R.string.wind_source_external
})

/** One step of the manual direction, on the 5° grid, wrapping at 360°. */
private fun stepBearing(bearing: Int, direction: Int): Double {
    val step = WindViewModel.BEARING_STEP
    val grid = Math.floorDiv(bearing + if (direction > 0) step else -1, step) * step
    return Math.floorMod(grid, 360).toDouble()
}
