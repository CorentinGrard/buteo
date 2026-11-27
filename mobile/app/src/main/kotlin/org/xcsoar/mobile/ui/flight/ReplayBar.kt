// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.flight

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.xcsoar.mobile.ui.theme.XcsTheme
import java.util.Locale
import org.xcsoar.mobile.R
import org.xcsoar.mobile.ui.upperStringResource

/**
 * Over the map while a flight is replayed, as a video's bar: pause or
 * play, the replayed time (UTC) and how far into the flight, stop, and
 * a slider over the flight from its take-off to its landing ([window])
 * that follows the replay and goes anywhere in it when let go.  It
 * takes the place of the "REPLAY" chip.
 *
 * @param timeUtc the replayed time, seconds since 1970
 * @param window what the replay spans; null: no slider (a file without
 * a take-off)
 * @param seeking where the replay is going while the core gets there
 */
@Composable
fun ReplayBar(
    timeUtc: Double?,
    paused: Boolean,
    window: ReplayWindow?,
    seeking: Double?,
    onPause: (Boolean) -> Unit,
    onSeek: (Double) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = XcsTheme.colors
    // the slider's place while a finger moves it, not yet let go
    var dragged by remember { mutableStateOf<Double?>(null) }
    val position = dragged ?: seeking ?: window?.let { w -> timeUtc?.let(w::at) }
    val shownUtc = if (window != null && position != null) window.dayStartUtc + position else timeUtc
    FloatingCard(modifier.fillMaxWidth()) {
        BarButton(stringResource(if (paused) R.string.replay_play else R.string.replay_pause),
                  { onPause(!paused) }) {
            Canvas(Modifier.size(18.dp)) {
                if (paused)
                    drawPath(Path().apply {
                        moveTo(size.width * 0.15f, 0f)
                        lineTo(size.width, size.height / 2)
                        lineTo(size.width * 0.15f, size.height)
                        close()
                    }, colors.text)
                else
                    for (x in listOf(0.15f, 0.6f))
                        drawRect(colors.text, Offset(size.width * x, 0f),
                                 Size(size.width * 0.25f, size.height))
            }
        }
        Column(Modifier.weight(1f).padding(top = 8.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(upperStringResource(R.string.replay_status), color = colors.neutralSafe,
                     fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.06.em,
                     maxLines = 1, modifier = Modifier.alignByBaseline())
                // dimmed while the core computes its way there
                Text(shownUtc?.let(::utcTime) ?: "--:--:--",
                     color = if (seeking != null) colors.textSecondary else colors.text,
                     style = XcsTheme.numberStyle, fontSize = 18.sp, maxLines = 1,
                     modifier = Modifier.padding(start = 8.dp).weight(1f).alignByBaseline())
                if (window != null && position != null)
                    Text("${flightTime(position - window.start)} / " +
                             flightTime(window.end - window.start),
                         color = colors.textSecondary, style = XcsTheme.numberStyle,
                         fontSize = 14.sp, maxLines = 1, modifier = Modifier.alignByBaseline())
            }
            if (window != null) {
                val description = stringResource(R.string.replay_position)
                Slider(
                    value = (position ?: window.start).toFloat()
                        .coerceIn(window.start.toFloat(), window.end.toFloat()),
                    onValueChange = { dragged = it.toDouble() },
                    onValueChangeFinished = {
                        dragged?.let(onSeek)
                        dragged = null
                    },
                    valueRange = window.start.toFloat()..window.end.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = colors.text, activeTrackColor = colors.text,
                        inactiveTrackColor = colors.panelBorder),
                    modifier = Modifier.height(32.dp).semantics { contentDescription = description },
                )
            } else
                Spacer(Modifier.height(8.dp))
        }
        BarButton(stringResource(R.string.replay_stop), onStop) {
            BarText(stringResource(R.string.stop))
        }
    }
}

/** A 56 dp button of the bar; [label] is what a screen reader says. */
@Composable
private fun BarButton(label: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .height(56.dp)
            .widthIn(min = 56.dp)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun BarText(text: String) {
    Text(text, color = XcsTheme.colors.text, style = XcsTheme.numberStyle, fontSize = 18.sp,
         fontWeight = FontWeight.Bold, maxLines = 1)
}

/** "1:05": hours and minutes of flight. */
internal fun flightTime(seconds: Double): String {
    val m = (seconds.coerceAtLeast(0.0) / 60).toLong()
    return String.format(Locale.ROOT, "%d:%02d", m / 60, m % 60)
}

/** "14:02:31": the time of day in UTC, like the times of an IGC file. */
internal fun utcTime(seconds: Double): String {
    val s = Math.floorMod(seconds.toLong(), 86_400L)
    return String.format(Locale.ROOT, "%02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
}

@Preview(widthDp = 390)
@Composable
private fun ReplayBarPreview() {
    XcsTheme(dark = false) {
        Box(Modifier.padding(12.dp)) {
            ReplayBar(1_791_000_000.0, paused = false,
                      ReplayWindow(1_790_985_600.0, 9_000.0, 27_000.0), seeking = null,
                      {}, {}, {})
        }
    }
}
