// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.xcsoar.mobile.R
import org.xcsoar.mobile.ui.ActionButton
import org.xcsoar.mobile.ui.SettingsGroup
import org.xcsoar.mobile.ui.SwitchRow
import org.xcsoar.mobile.ui.flight.CalloutSettings
import org.xcsoar.mobile.ui.theme.XcsTheme

/**
 * Which callouts the voice says in flight: a section of the Sound page.
 *
 * @param available whether the phone has a text-to-speech voice; null
 * while it starts
 * @param onSample say a callout, to hear the voice
 */
@Composable
fun VoiceCallouts(
    settings: CalloutSettings,
    available: Boolean?,
    onChange: (CalloutSettings) -> Unit,
    onSample: () -> Unit,
) {
    val colors = XcsTheme.colors
    Text(stringResource(R.string.voice_intro),
         color = colors.textSecondary, fontSize = 16.sp,
         modifier = Modifier.padding(horizontal = 4.dp))
    if (available == false)
        Text(stringResource(R.string.voice_none),
             color = colors.caution, fontSize = 16.sp,
             modifier = Modifier.padding(horizontal = 4.dp))
    SettingsGroup {
        SwitchRow(stringResource(R.string.airspace), stringResource(R.string.voice_airspace),
                  settings.airspace) {
            onChange(settings.copy(airspace = it))
        }
        Divider()
        SwitchRow(stringResource(R.string.voice_final_glide_title),
                  stringResource(R.string.voice_final_glide), settings.finalGlide) {
            onChange(settings.copy(finalGlide = it))
        }
        Divider()
        SwitchRow(stringResource(R.string.voice_task_points_title),
                  stringResource(R.string.voice_task_points), settings.taskPoints) {
            onChange(settings.copy(taskPoints = it))
        }
        Divider()
        SwitchRow(stringResource(R.string.voice_climbs_title),
                  stringResource(R.string.voice_climbs), settings.climbs) {
            onChange(settings.copy(climbs = it))
        }
    }
    ActionButton(stringResource(R.string.voice_say), outlined = true, enabled = available == true,
                 modifier = Modifier.fillMaxWidth(), onClick = onSample)
}

@Composable
private fun Divider() {
    HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = XcsTheme.colors.panelBorder)
}
