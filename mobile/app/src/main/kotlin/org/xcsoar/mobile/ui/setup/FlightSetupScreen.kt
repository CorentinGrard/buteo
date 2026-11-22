// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.xcsoar.mobile.R
import org.xcsoar.mobile.core.AutoMacCreadyMode
import org.xcsoar.mobile.core.FlightState
import org.xcsoar.mobile.core.UnitGroup
import org.xcsoar.mobile.ui.Format
import org.xcsoar.mobile.ui.LinkRow
import org.xcsoar.mobile.ui.ScreenHeader
import org.xcsoar.mobile.ui.SettingsGroup
import org.xcsoar.mobile.ui.SwitchRow
import org.xcsoar.mobile.ui.flight.Caption
import org.xcsoar.mobile.ui.flight.Segmented
import org.xcsoar.mobile.ui.flight.Stepper
import org.xcsoar.mobile.ui.setup.FlightSetupViewModel.Companion.BALLAST_STEP
import org.xcsoar.mobile.ui.setup.FlightSetupViewModel.Companion.BUGS_STEP
import org.xcsoar.mobile.ui.setup.FlightSetupViewModel.Companion.MAX_BUGS_PERCENT
import org.xcsoar.mobile.ui.setup.FlightSetupViewModel.Companion.MAX_CREW_MASS
import org.xcsoar.mobile.ui.theme.XcsTheme

@Composable
fun FlightSetupScreen(viewModel: FlightSetupViewModel, onOpenWind: () -> Unit,
                      onBack: () -> Unit) {
    val setup by viewModel.setup.collectAsStateWithLifecycle()
    BackHandler(onBack = onBack)
    FlightSetupContent(setup, viewModel::changeBallast, viewModel::setBallast,
                       viewModel::changeBugs, viewModel::changeCrewMass, viewModel::changeQnh,
                       onBack, viewModel::changeMacCready, viewModel::setAutoMacCready,
                       viewModel::setAutoMode, onOpenWind)
}

/**
 * MacCready (by hand or XCSoar's automatic one), crew mass, water
 * ballast, bugs, the resulting wing loading, QNH, and the wind.
 */
@Composable
fun FlightSetupContent(
    setup: FlightSetup?,
    onChangeBallast: (Double) -> Unit,
    onSetBallast: (Double) -> Unit,
    onChangeBugs: (Int) -> Unit,
    onChangeCrewMass: (Int) -> Unit,
    onChangeQnh: (Int) -> Unit,
    onBack: () -> Unit,
    onChangeMacCready: (Int) -> Unit = {},
    onAutoMacCready: (Boolean) -> Unit = {},
    onAutoMode: (AutoMacCreadyMode) -> Unit = {},
    /** null hides the Wind row */
    onOpenWind: (() -> Unit)? = null,
) {
    val colors = XcsTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.sheet)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader(stringResource(R.string.menu_flight_setup), onBack)

        MacCreadySection(setup, onChangeMacCready, onAutoMacCready, onAutoMode)

        Caption(stringResource(R.string.setup_crew), Modifier.padding(start = 4.dp))
        val crew = setup?.crewMass
        val mass = Format.mass(crew)
        Stepper(stringResource(R.string.setup_crew_mass_name),
                "${stringResource(R.string.setup_crew_mass)} · ${mass.unit}", mass.text, "${mass.text} ${mass.unit}",
                canDecrease = crew != null && crew > 0,
                canIncrease = crew != null && crew < MAX_CREW_MASS,
                onDecrease = { onChangeCrewMass(-1) },
                onIncrease = { onChangeCrewMass(+1) },
                modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.setup_crew_hint),
             color = colors.textSecondary, fontSize = 15.sp,
             modifier = Modifier.padding(horizontal = 4.dp))

        val ballastable = setup != null && setup.maxBallast > 0
        Caption(stringResource(R.string.plane_ballast), Modifier.padding(start = 4.dp))
        if (setup != null && !ballastable) {
            Text(stringResource(R.string.setup_no_ballast), color = colors.textSecondary,
                 fontSize = 15.sp, modifier = Modifier.padding(horizontal = 4.dp))
        } else {
            val ballast = setup?.let { Format.ballast(it.ballast).text } ?: Format.INVALID
            Stepper(stringResource(R.string.setup_ballast_name),
                    "${stringResource(R.string.setup_ballast)} · l", ballast,
                    stringResource(R.string.plane_spoken_litres, ballast),
                    canDecrease = setup != null && setup.ballast > 0,
                    canIncrease = setup != null && setup.ballast < setup.maxBallast,
                    onDecrease = { onChangeBallast(-BALLAST_STEP) },
                    onIncrease = { onChangeBallast(+BALLAST_STEP) },
                    modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(stringResource(R.string.setup_empty), enabled = setup != null, outlined = true,
                           modifier = Modifier.weight(1f)) { onSetBallast(0.0) }
                TextButton(stringResource(R.string.setup_full), enabled = setup != null, outlined = true,
                           modifier = Modifier.weight(1f)) {
                    setup?.let { onSetBallast(it.maxBallast) }
                }
            }
        }

        Caption(stringResource(R.string.setup_bugs), Modifier.padding(start = 4.dp))
        val percent = setup?.let { Format.bugsPercent(it.bugs) }
        val bugs = setup?.let { Format.bugs(it.bugs).text } ?: Format.INVALID
        Stepper(stringResource(R.string.setup_bugs_name),
                "${stringResource(R.string.setup_bugs_lost)} · %", bugs,
                stringResource(R.string.plane_spoken_percent, bugs),
                canDecrease = percent != null && percent > 0,
                canIncrease = percent != null && percent < MAX_BUGS_PERCENT,
                onDecrease = { onChangeBugs(-BUGS_STEP) },
                onIncrease = { onChangeBugs(+BUGS_STEP) },
                modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.setup_bugs_hint, 0, 50),
             color = colors.textSecondary, fontSize = 15.sp,
             modifier = Modifier.padding(horizontal = 4.dp))

        // upstream's Flight setup: the wing loading in red above MTOW
        val overMtow = setup?.overMaxTakeoffMass == true
        setup?.wingLoading?.let {
            ValueRow(stringResource(R.string.units_wing_loading), Format.wingLoading(it),
                     if (overMtow) colors.warning else colors.text)
        }
        if (overMtow)
            Text(stringResource(R.string.setup_over_mtow),
                 color = colors.warning, fontSize = 15.sp,
                 modifier = Modifier.padding(horizontal = 4.dp))

        Caption(stringResource(R.string.setup_altimeter), Modifier.padding(start = 4.dp))
        val qnh = setup?.let { Format.pressure(it.qnh ?: FlightState.STANDARD_QNH) }
        val qnhText = qnh?.text ?: Format.INVALID
        val unit = Format.unit(UnitGroup.PRESSURE).name
        Stepper("qnh", "QNH · $unit", qnhText, "QNH $qnhText $unit",
                canDecrease = setup != null &&
                    (setup.qnh ?: FlightState.STANDARD_QNH) > FlightState.MIN_QNH,
                canIncrease = setup != null &&
                    (setup.qnh ?: FlightState.STANDARD_QNH) < FlightState.MAX_QNH,
                onDecrease = { onChangeQnh(-1) },
                onIncrease = { onChangeQnh(+1) },
                modifier = Modifier.fillMaxWidth())
        if (setup != null) {
            val hint = when {
                setup.staticPressure == null -> stringResource(R.string.setup_no_baro)
                setup.qnh == null -> stringResource(R.string.setup_qnh_unset)
                else -> null
            }
            hint?.let {
                Text(it, color = colors.textSecondary, fontSize = 15.sp,
                     modifier = Modifier.padding(horizontal = 4.dp))
            }
            setup.baroAltitude?.let { ValueRow(stringResource(R.string.setup_baro_altitude), Format.altitude(it)) }
        }

        if (onOpenWind != null)
            SettingsGroup(Modifier.padding(top = 8.dp)) {
                LinkRow(stringResource(R.string.wind), stringResource(R.string.setup_wind_detail),
                        onClick = onOpenWind)
            }
    }
}

/**
 * The MacCready the glide computer flies with, and XCSoar's automatic
 * MacCready (MacCreadySetupPanel, GlideComputerConfigPanel).
 */
@Composable
private fun MacCreadySection(
    setup: FlightSetup?,
    onChange: (Int) -> Unit,
    onAuto: (Boolean) -> Unit,
    onAutoMode: (AutoMacCreadyMode) -> Unit,
) {
    val colors = XcsTheme.colors
    Caption("MacCready", Modifier.padding(start = 4.dp))
    val mc = setup?.macCready
    val unit = Format.unit(UnitGroup.VERTICAL_SPEED).name
    val text = mc?.let { Format.macCready(it).text } ?: Format.INVALID
    val auto = setup?.autoMacCready == true
    Stepper(stringResource(R.string.mac_cready),
            "${stringResource(if (auto) R.string.auto else R.string.mac_cready)} · $unit", text,
            if (auto) stringResource(R.string.setup_spoken_auto, "$text $unit") else "$text $unit",
            canDecrease = mc != null && mc > 0,
            canIncrease = mc != null && mc < FlightSetupViewModel.MAX_MC,
            onDecrease = { onChange(-1) },
            onIncrease = { onChange(+1) },
            modifier = Modifier.fillMaxWidth())
    SettingsGroup {
        SwitchRow(stringResource(R.string.setup_auto_mc),
                  stringResource(R.string.setup_auto_mc_detail),
                  setup?.autoMacCready, enabled = setup != null, onChange = onAuto)
    }
    val mode = setup?.autoMode
    Segmented(AUTO_MODES.map { stringResource(it.second) }, selected = AUTO_MODES.indexOfFirst { it.first == mode },
              onSelect = { onAutoMode(AUTO_MODES[it].first) },
              modifier = Modifier.fillMaxWidth().alpha(if (auto) 1f else 0.6f))
    Text(when (mode) {
             AutoMacCreadyMode.FINAL_GLIDE -> stringResource(R.string.setup_mode_final_glide_hint)
             AutoMacCreadyMode.CLIMB_AVERAGE -> stringResource(R.string.setup_mode_climbs_hint)
             AutoMacCreadyMode.BOTH -> stringResource(R.string.setup_mode_both_hint)
             null -> ""
         },
         color = colors.textSecondary, fontSize = 15.sp,
         modifier = Modifier.padding(horizontal = 4.dp))
}

/** XCSoar's auto MacCready modes, in its order, as the choice shows them. */
private val AUTO_MODES = listOf(
    AutoMacCreadyMode.FINAL_GLIDE to R.string.voice_final_glide_title,
    AutoMacCreadyMode.CLIMB_AVERAGE to R.string.voice_climbs_title,
    AutoMacCreadyMode.BOTH to R.string.setup_mode_both,
)

/** A read-only value, like the wing loading. */
@Composable
internal fun ValueRow(label: String, value: Format.Value,
                      valueColor: Color = XcsTheme.colors.text) {
    val colors = XcsTheme.colors
    Row(Modifier
            .fillMaxWidth()
            .background(colors.panel, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = colors.text, fontSize = 17.sp,
             fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text("${value.text} ${value.unit}", color = valueColor,
             style = XcsTheme.numberStyle, fontSize = 20.sp,
             fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TextButton(
    label: String,
    enabled: Boolean = true,
    outlined: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = XcsTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Box(modifier
            .height(56.dp)
            .widthIn(min = 96.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .then(if (outlined) Modifier
                      .background(colors.control, shape)
                      .border(1.dp, colors.panelBorder, shape)
                  else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center) {
        Text(label, color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun FlightSetupPreview() {
    XcsTheme(dark = false) {
        FlightSetupContent(FlightSetup(ballast = 80.0, maxBallast = 150.0, bugs = 0.9,
                                       wingLoading = 38.2, crewMass = 90.0),
                           {}, {}, {}, {}, {}, {})
    }
}
