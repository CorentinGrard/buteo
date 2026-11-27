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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.xcsoar.mobile.R
import org.xcsoar.mobile.core.SafetyOption
import org.xcsoar.mobile.core.UnitGroup
import org.xcsoar.mobile.core.XcsoarCore
import org.xcsoar.mobile.ui.Format
import org.xcsoar.mobile.ui.PageLayout
import org.xcsoar.mobile.ui.ScreenHeader
import org.xcsoar.mobile.ui.SettingsGroup
import org.xcsoar.mobile.ui.SwitchRow
import org.xcsoar.mobile.ui.flight.Caption
import org.xcsoar.mobile.ui.flight.Segmented
import org.xcsoar.mobile.ui.flight.Stepper
import org.xcsoar.mobile.ui.theme.XcsTheme
import java.util.Locale
import kotlin.math.roundToInt

class SafetyViewModel(private val core: XcsoarCore) : ViewModel() {
    private val valuesFlow = MutableStateFlow<Map<SafetyOption, Double>>(emptyMap())
    /** The margins read from the core (SI units); missing until read. */
    val values: StateFlow<Map<SafetyOption, Double>> = valuesFlow.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            valuesFlow.value = SafetyOption.entries.mapNotNull { option ->
                try {
                    core.safetyOption(option)?.let { option to it }
                } catch (_: Exception) {
                    null
                }
            }.toMap()
        }
    }

    /** Change one margin; shown at once, read back if the core refuses. */
    fun set(option: SafetyOption, value: Double) {
        valuesFlow.value = valuesFlow.value + (option to value)
        viewModelScope.launch {
            try {
                core.setSafetyOption(option, value)
            } catch (_: Exception) {
                refresh()
            }
        }
    }
}

@Composable
fun SafetyScreen(viewModel: SafetyViewModel, onBack: () -> Unit) {
    val values by viewModel.values.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }
    BackHandler(onBack = onBack)
    SafetyContent(values, viewModel::set, onBack)
}

/**
 * XCSoar's safety factors: the heights the glide computer keeps above
 * the field and the terrain, the safety glide cone (reach at a glide
 * ratio, Safety MacCready zero), the MacCready of reach and arrival
 * heights, the speed-to-fly risk factor, the order of the alternates
 * and the turn back marker.
 */
@Composable
fun SafetyContent(
    values: Map<SafetyOption, Double>,
    onSet: (SafetyOption, Double) -> Unit,
    onBack: () -> Unit,
) {
    PageLayout {
        ScreenHeader(stringResource(R.string.menu_safety), onBack)

        Caption(stringResource(R.string.safety_margins), Modifier.padding(start = 4.dp))
        HeightStepper(stringResource(R.string.safety_arrival_name),
                      stringResource(R.string.safety_arrival),
                      values[SafetyOption.ARRIVAL_HEIGHT], 2000.0) {
            onSet(SafetyOption.ARRIVAL_HEIGHT, it)
        }
        Explanation(stringResource(R.string.safety_arrival_hint))
        val ratio = values[SafetyOption.GLIDE_RATIO]
        val cone = ratio != null && ratio > 0
        val ratioText = when {
            ratio == null -> Format.INVALID
            cone -> ratio.roundToInt().toString()
            else -> stringResource(R.string.off)
        }
        Stepper(stringResource(R.string.safety_cone_name),
                stringResource(R.string.safety_cone), ratioText, ratioText,
                canDecrease = cone,
                canIncrease = ratio != null && ratio < GLIDE_RATIO_MAX,
                onDecrease = {
                    ratio?.let {
                        onSet(SafetyOption.GLIDE_RATIO,
                              if (it - 1 < GLIDE_RATIO_MIN) 0.0 else it - 1)
                    }
                },
                onIncrease = {
                    ratio?.let {
                        onSet(SafetyOption.GLIDE_RATIO,
                              if (it > 0) it + 1 else GLIDE_RATIO_FIRST)
                    }
                },
                modifier = Modifier.fillMaxWidth())
        Explanation(stringResource(R.string.safety_cone_hint))
        HeightStepper(stringResource(R.string.safety_terrain_name),
                      stringResource(R.string.safety_terrain),
                      values[SafetyOption.TERRAIN_HEIGHT], 1000.0) {
            onSet(SafetyOption.TERRAIN_HEIGHT, it)
        }
        Explanation(stringResource(R.string.safety_terrain_hint))

        Caption(stringResource(R.string.safety_computer), Modifier.padding(start = 4.dp, top = 8.dp))
        // the cone glides at Safety MacCready zero
        val mc = if (cone) 0.0 else values[SafetyOption.MC]
        val mcUnit = Format.unit(UnitGroup.VERTICAL_SPEED).name
        val mcText = mc?.let { Format.macCready(it).text } ?: Format.INVALID
        Stepper(stringResource(R.string.safety_mc_name),
                "${stringResource(R.string.safety_mc)} · $mcUnit", mcText, "$mcText $mcUnit",
                canDecrease = !cone && mc != null && mc > 0,
                canIncrease = !cone && mc != null && mc < 10,
                onDecrease = {
                    mc?.let { onSet(SafetyOption.MC, Format.stepVerticalSpeed(it, -1)
                        .coerceIn(0.0, 10.0)) }
                },
                onIncrease = {
                    mc?.let { onSet(SafetyOption.MC, Format.stepVerticalSpeed(it, +1)
                        .coerceIn(0.0, 10.0)) }
                },
                modifier = Modifier.fillMaxWidth())
        Explanation(stringResource(if (cone) R.string.safety_mc_cone_hint
                                   else R.string.safety_mc_hint))

        val startup = values[SafetyOption.STARTUP_MC]
        val startupText = startup?.let { Format.macCready(it).text } ?: Format.INVALID
        Stepper(stringResource(R.string.safety_start_mc_name),
                "${stringResource(R.string.safety_start_mc)} · $mcUnit", startupText,
                "$startupText $mcUnit",
                canDecrease = startup != null && startup > 0,
                canIncrease = startup != null && startup < STARTUP_MC_MAX,
                onDecrease = {
                    startup?.let { onSet(SafetyOption.STARTUP_MC, Format.stepVerticalSpeed(it, -1)
                        .coerceIn(0.0, STARTUP_MC_MAX)) }
                },
                onIncrease = {
                    startup?.let { onSet(SafetyOption.STARTUP_MC, Format.stepVerticalSpeed(it, +1)
                        .coerceIn(0.0, STARTUP_MC_MAX)) }
                },
                modifier = Modifier.fillMaxWidth())
        Explanation(stringResource(R.string.safety_start_mc_hint))

        val risk = values[SafetyOption.RISK_FACTOR]
        val riskText = risk?.let { String.format(Locale.ROOT, "%.1f", it) } ?: Format.INVALID
        Stepper(stringResource(R.string.safety_risk_name),
                stringResource(R.string.safety_risk), riskText, riskText,
                canDecrease = risk != null && risk > 0.05,
                canIncrease = risk != null && risk < 0.95,
                onDecrease = { risk?.let { onSet(SafetyOption.RISK_FACTOR, tenths(it - 0.1)) } },
                onIncrease = { risk?.let { onSet(SafetyOption.RISK_FACTOR, tenths(it + 0.1)) } },
                modifier = Modifier.fillMaxWidth())
        Explanation(stringResource(R.string.safety_risk_hint))

        Caption(stringResource(R.string.safety_alternates), Modifier.padding(start = 4.dp, top = 8.dp))
        Segmented(listOf(stringResource(R.string.safety_nearest),
                         stringResource(R.string.safety_along_task),
                         stringResource(R.string.safety_toward_home)),
                  selected = values[SafetyOption.ALTERNATES]?.roundToInt() ?: -1,
                  onSelect = { onSet(SafetyOption.ALTERNATES, it.toDouble()) },
                  modifier = Modifier.fillMaxWidth())
        Explanation(stringResource(R.string.safety_alternates_hint))

        SettingsGroup {
            SwitchRow(stringResource(R.string.safety_turn_back),
                      stringResource(R.string.safety_turn_back_detail),
                      values[SafetyOption.TURN_BACK_MARKER]?.let { it != 0.0 }) {
                onSet(SafetyOption.TURN_BACK_MARKER, if (it) 1.0 else 0.0)
            }
        }
    }
}

/** TaskBehaviour::TASK_MC_MAX */
private const val STARTUP_MC_MAX = 5.0

/* the safety glide ratio: off below the first, the core takes 5..60 */
private const val GLIDE_RATIO_MIN = 10.0
private const val GLIDE_RATIO_MAX = 40.0
/** What + on "Off" gives: the ratio clubs teach. */
private const val GLIDE_RATIO_FIRST = 20.0

private fun tenths(value: Double) = ((value * 10).roundToInt() / 10.0).coerceIn(0.0, 1.0)

@Composable
private fun HeightStepper(name: String, caption: String, metres: Double?, max: Double,
                          onChange: (Double) -> Unit) {
    val value = Format.altitude(metres)
    Stepper(name, "$caption · ${value.unit}", value.text, "${value.text} ${value.unit}",
            canDecrease = metres != null && metres > 0,
            canIncrease = metres != null && metres < max,
            onDecrease = {
                metres?.let { onChange(Format.stepAltitude(it, -1).coerceIn(0.0, max)) }
            },
            onIncrease = {
                metres?.let { onChange(Format.stepAltitude(it, +1).coerceIn(0.0, max)) }
            },
            modifier = Modifier.fillMaxWidth())
}

/** A line under a setting saying what it does. */
@Composable
internal fun Explanation(text: String) {
    Text(text, color = XcsTheme.colors.textSecondary, fontSize = 15.sp,
         modifier = Modifier.padding(horizontal = 4.dp))
}
