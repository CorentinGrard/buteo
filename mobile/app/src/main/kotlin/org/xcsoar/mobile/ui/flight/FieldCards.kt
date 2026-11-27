// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.flight

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.xcsoar.mobile.R
import org.xcsoar.mobile.core.Alternate
import org.xcsoar.mobile.core.NextWaypoint
import org.xcsoar.mobile.ui.Format
import org.xcsoar.mobile.ui.theme.XcsTheme
import org.xcsoar.mobile.ui.upperStringResource

/**
 * How the top of the flight screen shows the best field in glide (XCSoar's
 * best alternate) beside the next point: an experiment, the pilot to
 * choose (2026-11-27).
 */
enum class FieldCardStyle(val label: Int) {
    /** Under the next point, in the same card. */
    ROWS(R.string.field_card_rows),
    /** The card swipes between the next point and the field. */
    PAGES(R.string.field_card_pages),
    /** A chip beside the wind. */
    CHIP(R.string.field_card_chip),
}

/**
 * The next point and the best field, as [style] says; the chip style
 * is the plain next card here (the chip goes with the wind's).
 */
@Composable
fun TopCards(
    next: NextWaypoint?,
    alternate: Alternate?,
    style: FieldCardStyle,
    timeRemaining: Double?,
    onNext: () -> Unit,
    onField: (id: Int) -> Unit,
    modifier: Modifier = Modifier,
    elevated: Boolean = true,
) {
    when {
        // the field only once there is one (a fix, a polar, landables)
        alternate == null || style == FieldCardStyle.CHIP ->
            NextWaypointCard(next, modifier, timeRemaining, onClick = onNext, elevated = elevated)
        style == FieldCardStyle.ROWS -> FloatingCard(modifier, elevated = elevated) {
            Column(Modifier.weight(1f)) {
                if (next != null)
                    NextRow(next, timeRemaining, Modifier.fillMaxWidth()
                        .clickable(role = Role.Button, onClick = onNext))
                else
                    NextWaypointCard(null, Modifier.fillMaxWidth(), onClick = onNext,
                                     elevated = false)
                HorizontalDivider(color = XcsTheme.colors.panelBorder,
                                  modifier = Modifier.padding(horizontal = 12.dp))
                FieldRow(alternate, small = true, modifier = Modifier.fillMaxWidth()
                    .clickable(role = Role.Button) { onField(alternate.id) })
            }
        }
        else -> Column(modifier, horizontalAlignment = Alignment.CenterHorizontally,
                       verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val pager = rememberPagerState { 2 }
            HorizontalPager(pager, Modifier.fillMaxWidth(), pageSpacing = 8.dp) { page ->
                if (page == 0)
                    NextWaypointCard(next, Modifier.fillMaxWidth(), timeRemaining,
                                     onClick = onNext, elevated = elevated)
                else FloatingCard(Modifier.fillMaxWidth(), elevated = elevated,
                                  onClick = { onField(alternate.id) }) {
                    FieldRow(alternate, small = false, modifier = Modifier.weight(1f))
                }
            }
            PageDots(pager.currentPage, 2)
        }
    }
}

/** The best field as a chip: "⌂ LF3852 Frontonas 5.3 km −120 m". */
@Composable
fun FieldChip(alternate: Alternate, onField: (id: Int) -> Unit) {
    val colors = XcsTheme.colors
    val distance = Format.distance(alternate.distance)
    val margin = Format.altitudeDifference(alternate.altitudeDifference)
    FloatingCard(shape = RoundedCornerShape(12.dp), onClick = { onField(alternate.id) },
                 onClickLabel = alternate.name) {
        Row(Modifier.padding(start = 6.dp, end = 10.dp, top = 6.dp, bottom = 6.dp)
                .heightIn(min = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(32.dp).background(marginColor(alternate), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center) {
                DirectionArrow(alternate.bearing, colors.onSelected, Modifier.size(20.dp))
            }
            Text(shortName(alternate.name), color = colors.text, fontSize = 16.sp,
                 fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                 modifier = Modifier.weight(1f, fill = false))
            Text("${distance.text} ${distance.unit}", color = colors.text,
                 style = XcsTheme.numberStyle, fontSize = 16.sp, maxLines = 1)
            Text("${margin.text} ${margin.unit}", color = marginColor(alternate),
                 style = XcsTheme.numberStyle, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                 maxLines = 1)
        }
    }
}

/**
 * The best field: an arrow to it on the colour of its margin, FIELD,
 * bearing, name, distance and the margin above the arrival height.
 */
@Composable
private fun FieldRow(alternate: Alternate, small: Boolean, modifier: Modifier = Modifier) {
    val colors = XcsTheme.colors
    val distance = Format.distance(alternate.distance)
    val bearing = Format.bearing(alternate.bearing)
    val margin = Format.altitudeDifference(alternate.altitudeDifference)
    val color = marginColor(alternate)
    Row(modifier.padding(horizontal = 12.dp, vertical = if (small) 6.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(if (small) 32.dp else 44.dp).background(color, CircleShape),
            contentAlignment = Alignment.Center) {
            DirectionArrow(alternate.bearing, colors.onSelected,
                           Modifier.size(if (small) 18.dp else 24.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(upperStringResource(R.string.field_card_tag), color = colors.onSelected,
                     fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.08.em,
                     modifier = Modifier
                         .background(color, RoundedCornerShape(5.dp))
                         .padding(horizontal = 6.dp, vertical = 1.dp))
                Text("${bearing.text}${bearing.unit}", color = colors.textSecondary,
                     fontSize = 13.sp)
            }
            Text(alternate.name, color = colors.text, fontSize = if (small) 17.sp else 22.sp,
                 fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End,
               verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(valueWithUnit(distance, if (small) 19.sp else 24.sp, colors.text,
                               colors.textSecondary),
                 style = XcsTheme.numberStyle, maxLines = 1)
            Text(valueWithUnit(margin, if (small) 15.sp else 18.sp, color, colors.textSecondary),
                 style = XcsTheme.numberStyle, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

/** In the cone (safe) or not (caution). */
@Composable
private fun marginColor(alternate: Alternate): Color =
    if (alternate.altitudeDifference >= 0) XcsTheme.colors.safe else XcsTheme.colors.caution

/** "LF3852 Frontonas" → "Frontonas": the code is long on a chip. */
private fun shortName(name: String): String =
    name.substringAfter(' ', name).ifBlank { name }

/** Which page of [count] shows. */
@Composable
private fun PageDots(current: Int, count: Int) {
    val colors = XcsTheme.colors
    // on a pill: the map under them may be of any colour
    Row(Modifier.background(colors.card, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { i ->
            Box(Modifier.size(7.dp).background(
                if (i == current) colors.text else colors.textSecondary.copy(alpha = 0.4f),
                CircleShape))
        }
    }
}
