// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.data

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.xcsoar.mobile.FlightData
import org.xcsoar.mobile.R
import org.xcsoar.mobile.core.DataCheck
import org.xcsoar.mobile.core.DataDownload
import org.xcsoar.mobile.core.DataFile
import org.xcsoar.mobile.core.DataKind
import org.xcsoar.mobile.core.FlightArea
import org.xcsoar.mobile.core.GeoPosition
import org.xcsoar.mobile.core.MapPoint
import org.xcsoar.mobile.core.PreflightCheck
import org.xcsoar.mobile.ui.ActionButton
import org.xcsoar.mobile.ui.Format
import org.xcsoar.mobile.ui.PageLayout
import org.xcsoar.mobile.ui.ScreenHeader
import org.xcsoar.mobile.ui.flight.Caption
import org.xcsoar.mobile.ui.theme.XcsTheme
import java.text.DateFormat
import java.util.Date

@Composable
fun PreflightScreen(viewModel: PreflightViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }
    // a download ended: what it changed
    val downloading = downloads.downloading != null
    LaunchedEffect(downloading) { if (!downloading) viewModel.recheck() }
    BackHandler(onBack = onBack)
    PreflightContent(state, downloads, onBack,
                     onCheckAgain = { viewModel.refresh(fresh = true) },
                     onDownload = viewModel::download)
}

/**
 * Before the flight: whether the terrain, the vector map, airspace
 * and waypoints reach along the task (else around the glider), what
 * downloads by itself on Wi-Fi, and another file to choose where one
 * falls short.
 */
@Composable
fun PreflightContent(
    state: PreflightState,
    downloads: FlightData.State,
    onBack: () -> Unit,
    onCheckAgain: () -> Unit,
    onDownload: (List<DataDownload>) -> Unit,
) {
    val colors = XcsTheme.colors
    val check = state.check
    val pending = check?.automatic.orEmpty()
    PageLayout(bottom = {
        ActionButton(stringResource(if (state.checking) R.string.preflight_checking
                                    else R.string.preflight_check_again),
                     Modifier.weight(1f), outlined = true, enabled = !state.checking,
                     onClick = onCheckAgain)
        if (pending.isNotEmpty())
            ActionButton(stringResource(R.string.preflight_download_all, pending.size),
                         Modifier.weight(1f), primary = true,
                         enabled = downloads.downloading == null,
                         onClick = { onDownload(pending) })
    }) {
        ScreenHeader(stringResource(R.string.menu_preflight), onBack)
        if (check != null) {
            Text(areaText(check.area), color = colors.textSecondary, fontSize = 16.sp)
            for (c in check.checks)
                CheckCard(c, check.area, busy = downloads.downloading != null, onDownload)
        }
        state.error?.let { Text(it.text(), color = colors.warning, fontSize = 16.sp) }
        DownloadsFooter(downloads)
    }
}

@Composable
private fun areaText(area: FlightArea?): String = when {
    area == null -> stringResource(R.string.preflight_no_area)
    area.isTask -> stringResource(R.string.preflight_along_task,
                                  area.points.joinToString(" – ") { it.name })
    else -> stringResource(R.string.preflight_around, distance(area.margin))
}

/** "50 km", in the pilot's unit. */
private fun distance(m: Double) = Format.distance(m).let { "${it.text} ${it.unit}" }

@Composable
private fun CheckCard(check: DataCheck, area: FlightArea?, busy: Boolean,
                      onDownload: (List<DataDownload>) -> Unit) {
    val colors = XcsTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.panel, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier
                    .size(12.dp)
                    .background(if (check.ok) colors.safe else colors.caution, CircleShape))
            Caption(stringResource(when (check.kind) {
                DataKind.TERRAIN -> R.string.terrain
                DataKind.VECTOR_MAP -> R.string.preflight_vector_map
                DataKind.AIRSPACE -> R.string.airspace
                DataKind.WAYPOINTS -> R.string.data_waypoints
            }))
        }
        val files = check.files.joinToString(" + ").ifEmpty { null }
        Text(files ?: stringResource(R.string.data_no_file),
             color = if (files != null) colors.text else colors.textSecondary,
             fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2,
             overflow = TextOverflow.Ellipsis)
        Text(statusText(check, area), color = if (check.ok) colors.safe else colors.caution,
             fontSize = 15.sp)
        check.date?.let {
            Text(stringResource(R.string.preflight_data_of, Format.day(it)),
                 color = colors.textSecondary, fontSize = 15.sp)
        }

        if (check.updates.isNotEmpty()) {
            Text(updatesText(check.updates), color = colors.textSecondary, fontSize = 15.sp)
            ActionButton(stringResource(R.string.preflight_download_now), outlined = true,
                         enabled = !busy, onClick = { onDownload(check.updates) })
        }
        check.suggestion?.let { file ->
            Text(stringResource(R.string.preflight_suggestion, file.title),
                 color = colors.textSecondary, fontSize = 15.sp)
            ActionButton(stringResource(R.string.download), outlined = true,
                         enabled = !busy, onClick = { onDownload(listOf(file)) })
        }
    }
}

@Composable
private fun statusText(check: DataCheck, area: FlightArea?): String {
    val around = area?.let { distance(it.margin) }
    val task = area?.isTask == true
    return when (check.kind) {
        DataKind.TERRAIN, DataKind.VECTOR_MAP -> when {
            check.files.isEmpty() && check.kind == DataKind.VECTOR_MAP ->
                stringResource(R.string.preflight_no_regions)
            !check.ok && !check.gap -> stringResource(R.string.preflight_no_terrain)
            check.gap && check.gapNear != null ->
                stringResource(R.string.preflight_stops_near, check.gapNear!!)
            check.gap -> stringResource(R.string.preflight_stops_around, around ?: "")
            area == null -> stringResource(R.string.preflight_loaded)
            task -> stringResource(R.string.preflight_covers_task)
            else -> stringResource(R.string.preflight_covers_around, around ?: "")
        }
        DataKind.AIRSPACE, DataKind.WAYPOINTS -> {
            val airspace = check.kind == DataKind.AIRSPACE
            val count = check.count
            when {
                check.files.isEmpty() && !check.ok && count == null ->
                    stringResource(if (airspace) R.string.preflight_no_airspace
                                   else R.string.preflight_no_waypoints)
                count == null -> stringResource(R.string.preflight_loaded)
                count == 0 -> stringResource(if (airspace) R.string.preflight_airspace_none
                                             else R.string.preflight_landables_none)
                task -> stringResource(if (airspace) R.string.preflight_airspace_near_task
                                       else R.string.preflight_landables_near_task, count)
                else -> stringResource(if (airspace) R.string.preflight_airspace_around
                                       else R.string.preflight_landables_around,
                                       around ?: "", count)
            }
        }
    }
}

@Composable
private fun updatesText(updates: List<DataDownload>): String {
    val context = LocalContext.current
    // a region: its title and size; a newer file: the day of its data
    val regions = updates.filter { it.use == null }
    return if (regions.isNotEmpty())
        stringResource(R.string.preflight_regions_to_get, regions.joinToString(", ") { r ->
            // "12 MB" on one line
            r.size?.let { size ->
                val text = Formatter.formatShortFileSize(context, size).replace(' ', '\u00A0')
                "${r.title}\u00A0($text)"
            } ?: r.title
        })
    else
        stringResource(R.string.preflight_update, updates.first().date?.let(Format::day) ?: "?")
}

@Composable
private fun DownloadsFooter(downloads: FlightData.State) {
    val colors = XcsTheme.colors
    val progress = downloads.downloading
    if (progress != null) {
        Text(stringResource(R.string.preflight_downloading, progress.title),
             color = colors.text, fontSize = 16.sp)
        if (progress.fraction >= 0)
            LinearProgressIndicator(progress = { progress.fraction },
                                    Modifier.fillMaxWidth(), color = colors.selected)
        else
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.selected)
    }
    downloads.error?.let { Text(it.text(), color = colors.warning, fontSize = 16.sp) }
    Text(stringResource(R.string.preflight_automatic), color = colors.textSecondary,
         fontSize = 14.sp)
    downloads.lastCheck?.let {
        Text(stringResource(R.string.preflight_last_check,
                            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))),
             color = colors.textSecondary, fontSize = 14.sp)
    }
}

@Preview(widthDp = 360, heightDp = 1100)
@Composable
private fun PreflightPreview() {
    val area = FlightArea.of(
        org.xcsoar.mobile.core.TaskOutline(
            listOf(MapPoint("Montélimar", GeoPosition(44.58, 4.74)),
                   MapPoint("Sagy", GeoPosition(46.62, 4.94))), emptyList()), null)
    val region = DataDownload("bourgogne.pmtiles", "Bourgogne", "", null, 12_000_000, "maps",
                              null, "2026-11-24")
    XcsTheme(dark = false) {
        PreflightContent(
            PreflightState(PreflightCheck(area, listOf(
                DataCheck(DataKind.TERRAIN, listOf("FRA_ALPS.xcm"), ok = true,
                          date = "2019-05-01"),
                DataCheck(DataKind.VECTOR_MAP, listOf("ra-nord", "ra-sud"), ok = false,
                          gap = true, gapNear = "Sagy", date = "2026-11-24",
                          updates = listOf(region)),
                DataCheck(DataKind.AIRSPACE, listOf("FR-ASP-National-OpenAIP.txt"), ok = true,
                          count = 40, date = "2026-11-20"),
                DataCheck(DataKind.WAYPOINTS, listOf("FR-WPT-National-XCSoar.cup"), ok = false,
                          count = 0,
                          suggestion = DataDownload("FR-WPT-Airports-OpenAIP.cupx",
                                                    "FR-WPT-Airports-OpenAIP.cupx", "", null,
                                                    null, "waypoints", DataFile.WAYPOINTS,
                                                    null)),
            ))),
            FlightData.State(downloading = FlightData.Progress("Bourgogne", 0.4f),
                             lastCheck = 0L),
            {}, {}, {})
    }
}
