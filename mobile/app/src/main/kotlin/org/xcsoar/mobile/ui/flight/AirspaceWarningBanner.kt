// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.flight

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.xcsoar.mobile.core.AirspaceWarningInfo
import org.xcsoar.mobile.ui.Format
import org.xcsoar.mobile.ui.theme.XcsTheme
import kotlin.math.roundToInt
import org.xcsoar.mobile.R
import org.xcsoar.mobile.ui.upperStringResource

/**
 * The most severe airspace warning, over everything else on the map
 * (overlays stack alert > caution > info): red inside, orange before.
 * "Ack" acknowledges it until it changes, "Day" for the rest of the day.
 * With [hideTimer], a bar along the bottom counts down until it hides
 * itself.
 */
@Composable
fun AirspaceWarningBanner(
    warning: AirspaceWarningInfo,
    more: Int,
    onAcknowledge: (day: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    hideTimer: HideTimer? = null,
) {
    val colors = XcsTheme.colors
    val background = if (warning.inside) colors.warningContainer else colors.cautionContainer
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.clip(shape).background(background)) {
        BannerContent(warning, more, onAcknowledge)
        hideTimer?.let { HideCountdown(it) }
    }
}

/** A bar that empties until the banner hides itself. */
@Composable
private fun HideCountdown(timer: HideTimer) {
    val colors = XcsTheme.colors
    fun remaining() = ((timer.start + timer.duration - SystemClock.uptimeMillis()).toFloat() /
                       timer.duration).coerceIn(0f, 1f)
    val progress = remember(timer) { Animatable(remaining()) }
    LaunchedEffect(timer) {
        progress.animateTo(0f, tween((remaining() * timer.duration).toInt(),
                                     easing = LinearEasing))
    }
    Box(Modifier.fillMaxWidth().height(4.dp).background(colors.onAlert.copy(alpha = 0.3f))) {
        Box(Modifier
            .fillMaxWidth(progress.value)
            .fillMaxHeight()
            .background(colors.onAlert))
    }
}

@Composable
private fun BannerContent(
    warning: AirspaceWarningInfo,
    more: Int,
    onAcknowledge: (day: Boolean) -> Unit,
) {
    val colors = XcsTheme.colors
    Row(
        Modifier
            .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Assertive },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(headline(warning), color = colors.onAlert,
                 fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(warning.name, color = colors.onAlert, fontSize = 18.sp,
                 fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(warning.`class`, "${warning.base} – ${warning.top}",
                        if (more > 0) stringResource(R.string.warning_more, more) else "")
                     .filter { it.isNotBlank() }.joinToString(" · "),
                 color = colors.onAlert, fontSize = 14.sp, maxLines = 1,
                 overflow = TextOverflow.Ellipsis)
        }
        AckButton(stringResource(R.string.warning_ack),
                  stringResource(R.string.warning_ack_spoken, warning.name)) {
            onAcknowledge(false)
        }
        AckButton(stringResource(R.string.warning_day),
                  stringResource(R.string.warning_day_spoken, warning.name)) {
            onAcknowledge(true)
        }
    }
}

/** "INSIDE AIRSPACE", or "AIRSPACE AHEAD · IN 1:25 · 1.2 KM" before entering. */
@Composable
private fun headline(w: AirspaceWarningInfo): String {
    if (w.inside) return upperStringResource(R.string.warning_inside)
    val parts = mutableListOf(stringResource(if (w.state == "task") R.string.warning_task_crosses
                                             else R.string.warning_ahead))
    w.time?.takeIf { it >= 0 }?.let {
        val s = it.roundToInt()
        parts += stringResource(R.string.warning_in_time, "%d:%02d".format(s / 60, s % 60))
    }
    w.distance?.takeIf { it >= 0 }?.let {
        val d = Format.distance(it)
        parts += "${d.text} ${d.unit}"
    }
    return parts.joinToString(" · ").uppercase(LocalConfiguration.current.locales[0])
}

@Composable
private fun AckButton(label: String, description: String, onClick: () -> Unit) {
    val colors = XcsTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .height(56.dp)
            .widthIn(min = 56.dp)
            .border(2.dp, colors.onAlert, shape)
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = colors.onAlert, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}
