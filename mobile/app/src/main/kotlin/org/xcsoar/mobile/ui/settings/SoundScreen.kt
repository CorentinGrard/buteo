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
import androidx.compose.ui.draw.alpha
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
import org.xcsoar.mobile.core.SoundOption
import org.xcsoar.mobile.core.UnitGroup
import org.xcsoar.mobile.core.XcsoarCore
import org.xcsoar.mobile.ui.Format
import org.xcsoar.mobile.ui.PageLayout
import org.xcsoar.mobile.ui.ScreenHeader
import org.xcsoar.mobile.ui.SettingsGroup
import org.xcsoar.mobile.ui.SwitchRow
import org.xcsoar.mobile.ui.flight.CalloutSettings
import org.xcsoar.mobile.ui.flight.Caption
import org.xcsoar.mobile.ui.flight.Segmented
import org.xcsoar.mobile.ui.flight.Stepper
import org.xcsoar.mobile.ui.theme.XcsTheme
import kotlin.math.roundToInt

class VarioSoundViewModel(private val core: XcsoarCore) : ViewModel() {
    private val valuesFlow = MutableStateFlow<Map<SoundOption, Int>?>(null)
    /** The sound options; null until read, empty if the device has no vario sound. */
    val values: StateFlow<Map<SoundOption, Int>?> = valuesFlow.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            valuesFlow.value = SoundOption.entries.mapNotNull { option ->
                try {
                    core.soundOption(option)?.let { option to it }
                } catch (_: Exception) {
                    null
                }
            }.toMap()
        }
    }

    /** Change one option; shown at once, read back if the core refuses. */
    fun set(option: SoundOption, value: Int) {
        valuesFlow.value = valuesFlow.value.orEmpty() + (option to value)
        viewModelScope.launch {
            try {
                core.setSoundOption(option, value)
            } catch (_: Exception) {
                refresh()
            }
        }
    }
}

@Composable
fun SoundScreen(
    vario: VarioSoundViewModel,
    callouts: CalloutSettings,
    voiceAvailable: Boolean?,
    onCallouts: (CalloutSettings) -> Unit,
    onSampleCallout: () -> Unit,
    onBack: () -> Unit,
) {
    val values by vario.values.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vario.refresh() }
    BackHandler(onBack = onBack)
    SoundContent(values, vario::set, callouts, voiceAvailable, onCallouts, onSampleCallout,
                 onBack)
}

/** What Buteo plays and says in flight: the vario tone, then the voice callouts. */
@Composable
fun SoundContent(
    values: Map<SoundOption, Int>?,
    onSet: (SoundOption, Int) -> Unit,
    callouts: CalloutSettings,
    voiceAvailable: Boolean?,
    onCallouts: (CalloutSettings) -> Unit,
    onSampleCallout: () -> Unit,
    onBack: () -> Unit,
) {
    PageLayout {
        ScreenHeader(stringResource(R.string.menu_sound), onBack)
        VarioSound(values, onSet)
        Caption(stringResource(R.string.sound_callouts), Modifier.padding(start = 4.dp, top = 8.dp))
        VoiceCallouts(callouts, voiceAvailable, onCallouts, onSampleCallout)
    }
}

/**
 * XCSoar's vario tone: on or off, its volume, vario or speed to fly in
 * cruise, and a quiet band around zero.
 */
@Composable
private fun VarioSound(values: Map<SoundOption, Int>?, onSet: (SoundOption, Int) -> Unit) {
    val colors = XcsTheme.colors
    if (values != null && values.isEmpty()) {
        Text(stringResource(R.string.sound_no_output),
             color = colors.textSecondary, fontSize = 16.sp,
             modifier = Modifier.padding(horizontal = 4.dp))
        return
    }

    val on = values?.get(SoundOption.VARIO)?.let { it != 0 }
    SettingsGroup {
        SwitchRow(stringResource(R.string.menu_vario_sound),
                  stringResource(R.string.sound_vario_detail), on) {
            onSet(SoundOption.VARIO, if (it) 1 else 0)
        }
    }

    val volume = values?.get(SoundOption.VARIO_VOLUME)
    Stepper(stringResource(R.string.sound_volume_name),
            "${stringResource(R.string.sound_volume)} · %", volume?.toString() ?: Format.INVALID,
            stringResource(R.string.plane_spoken_percent, volume ?: Format.INVALID),
            canDecrease = volume != null && volume > 0,
            canIncrease = volume != null && volume < 100,
            onDecrease = { volume?.let { onSet(SoundOption.VARIO_VOLUME, step5(it, -1)) } },
            onIncrease = { volume?.let { onSet(SoundOption.VARIO_VOLUME, step5(it, +1)) } },
            modifier = Modifier.fillMaxWidth())
    Explanation(stringResource(R.string.sound_volume_hint))

    Caption(stringResource(R.string.sound_tone), Modifier.padding(start = 4.dp, top = 8.dp))
    Segmented(listOf(stringResource(R.string.sound_tone_vario),
                         stringResource(R.string.auto)),
              selected = values?.get(SoundOption.VARIO_SWITCHING) ?: -1,
              onSelect = { onSet(SoundOption.VARIO_SWITCHING, it) },
              modifier = Modifier.fillMaxWidth())
    Explanation(stringResource(R.string.sound_tone_hint))

    Caption(stringResource(R.string.sound_quiet), Modifier.padding(start = 4.dp, top = 8.dp))
    val deadBand = values?.get(SoundOption.VARIO_DEAD_BAND)?.let { it != 0 }
    SettingsGroup {
        SwitchRow(stringResource(R.string.sound_dead_band),
                  stringResource(R.string.sound_dead_band_detail),
                  deadBand) {
            onSet(SoundOption.VARIO_DEAD_BAND, if (it) 1 else 0)
        }
    }
    val enabled = deadBand == true
    DeadBandStepper(stringResource(R.string.sound_lower_name),
                    stringResource(R.string.sound_from), values?.get(SoundOption.VARIO_DEAD_BAND_MIN),
                    -500, 0, enabled) {
        onSet(SoundOption.VARIO_DEAD_BAND_MIN, it)
    }
    DeadBandStepper(stringResource(R.string.sound_upper_name),
                    stringResource(R.string.sound_to), values?.get(SoundOption.VARIO_DEAD_BAND_MAX),
                    0, 200, enabled) {
        onSet(SoundOption.VARIO_DEAD_BAND_MAX, it)
    }
}

/** 5 % volume steps. */
private fun step5(value: Int, direction: Int) =
    ((value / 5 + direction) * 5).coerceIn(0, 100)

/** A dead band edge in cm/s, shown and stepped in the lift unit. */
@Composable
private fun DeadBandStepper(name: String, caption: String, cms: Int?, min: Int, max: Int,
                            enabled: Boolean, onChange: (Int) -> Unit) {
    val unit = Format.unit(UnitGroup.VERTICAL_SPEED).name
    val text = cms?.let { Format.vario(it / 100.0).text } ?: Format.INVALID
    fun step(direction: Int) = cms?.let {
        onChange((Format.stepVerticalSpeed(it / 100.0, direction) * 100).roundToInt()
                     .coerceIn(min, max))
    }
    Stepper(name, "$caption · $unit", text, "$text $unit",
            canDecrease = enabled && cms != null && cms > min,
            canIncrease = enabled && cms != null && cms < max,
            onDecrease = { step(-1) }, onIncrease = { step(+1) },
            modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.4f))
}
