// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    Text("Short callouts in flight through the phone's voice, so your eyes stay " +
             "outside; the screen may be off.",
         color = colors.textSecondary, fontSize = 16.sp,
         modifier = Modifier.padding(horizontal = 4.dp))
    if (available == false)
        Text("This phone has no text-to-speech voice: Android's settings install one " +
                 "(Text-to-speech output).",
             color = colors.caution, fontSize = 16.sp,
             modifier = Modifier.padding(horizontal = 4.dp))
    SettingsGroup {
        SwitchRow("Airspace", "Ahead and inside: “Airspace ahead: Grenoble CTR, " +
                      "in 40 seconds”", settings.airspace) {
            onChange(settings.copy(airspace = it))
        }
        Divider()
        SwitchRow("Final glide", "Above it, and dropped below: “Above final glide " +
                      "by 120 metres”", settings.finalGlide) {
            onChange(settings.copy(finalGlide = it))
        }
        Divider()
        SwitchRow("Task points", "Start, each turn point and the finish, with the " +
                      "next point", settings.taskPoints) {
            onChange(settings.copy(taskPoints = it))
        }
        Divider()
        SwitchRow("Climbs", "Leaving a climb, its average and the height gained: " +
                      "“Climb 2.1, gained 450”", settings.climbs) {
            onChange(settings.copy(climbs = it))
        }
    }
    ActionButton("Say a callout", outlined = true, enabled = available == true,
                 modifier = Modifier.fillMaxWidth(), onClick = onSample)
}

@Composable
private fun Divider() {
    HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = XcsTheme.colors.panelBorder)
}
