// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.flights

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.xcsoar.mobile.ui.ActionButton
import org.xcsoar.mobile.ui.PageLayout
import org.xcsoar.mobile.ui.ScreenHeader
import org.xcsoar.mobile.ui.summary.FlightFacts
import org.xcsoar.mobile.ui.theme.XcsTheme
import java.text.DateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date
import java.util.TimeZone

/**
 * A recorded flight, opened from Flights ([FlightsViewModel.open]).
 *
 * @param onGround Replay and Delete are for the ground: in flight the
 * logger writes the newest file
 */
@Composable
fun FlightLogScreen(
    viewModel: FlightsViewModel,
    onGround: Boolean,
    onReplay: (FlightLog) -> Unit,
    onShare: (FlightLog) -> Unit,
    onOpenUrl: (String) -> Unit,
    onBack: () -> Unit,
) {
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val uploads by viewModel.uploads.collectAsStateWithLifecycle()
    val weGlideReady by viewModel.weGlideReady.collectAsStateWithLifecycle()
    BackHandler(onBack = onBack)
    val shown = detail ?: return
    val log = shown.log
    FlightLogContent(shown, weGlideReady, uploads[log.path], onGround,
                     onReplay = { onReplay(log) }, onShare = { onShare(log) },
                     onUpload = { viewModel.upload(log) }, onOpenUrl = onOpenUrl,
                     onDelete = { viewModel.delete(log, onBack) }, onBack = onBack)
}

/**
 * The flight from its IGC file (as the summary after a landing shows
 * it), Replay and Share within reach of the thumb, then WeGlide and
 * Delete.
 */
@Composable
fun FlightLogContent(
    detail: FlightDetail,
    weGlideReady: Boolean,
    upload: Upload?,
    onGround: Boolean,
    onReplay: () -> Unit = {},
    onShare: () -> Unit = {},
    onUpload: () -> Unit = {},
    onOpenUrl: (String) -> Unit = {},
    onDelete: () -> Unit = {},
    onBack: () -> Unit = {},
    timeZone: TimeZone = TimeZone.getDefault(),
) {
    val colors = XcsTheme.colors
    val log = detail.log
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    PageLayout(bottom = {
        ActionButton("Share", outlined = true, onClick = onShare)
        ActionButton("Replay", primary = true, enabled = onGround,
                     modifier = Modifier.weight(1f), onClick = onReplay)
    }) {
        ScreenHeader(title(detail, timeZone), onBack)

        val summary = detail.summary
        val times = summary?.times
        val line = listOfNotNull(
            times?.takeoffLocal?.ifEmpty { null }?.let { "Take-off $it" },
            times?.landingLocal?.ifEmpty { null }?.let { "landing $it" },
            summary?.contest?.name?.ifEmpty { null })
        if (line.isNotEmpty())
            Text(line.joinToString(" · "), color = colors.textSecondary, fontSize = 16.sp,
                 modifier = Modifier.padding(horizontal = 4.dp))

        when {
            detail.reading -> Hint("Reading the flight…")
            summary != null -> FlightFacts(summary)
            else -> Hint("No take-off in this file.")
        }

        Text("${log.name} · ${kilobytes(log.size)}", color = colors.textSecondary,
             fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
             modifier = Modifier.padding(horizontal = 4.dp))

        when {
            upload is Upload.Done -> Text(
                "On WeGlide: flight ${upload.flight.flightId} · Open",
                color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button) { onOpenUrl(upload.flight.url) }
                    .padding(horizontal = 4.dp, vertical = 12.dp))
            weGlideReady -> {
                ActionButton(if (upload == Upload.Running) "Uploading…" else "Upload to WeGlide",
                             outlined = true, enabled = upload != Upload.Running,
                             modifier = Modifier.fillMaxWidth(), onClick = onUpload)
                if (upload is Upload.Failed)
                    Text("WeGlide: ${upload.message}", color = colors.warning, fontSize = 15.sp,
                         modifier = Modifier.padding(horizontal = 4.dp))
            }
            else -> Hint("To upload to WeGlide, set your pilot ID: Menu → Aircraft & crew → " +
                             "Pilot.")
        }

        ActionButton("Delete", outlined = true, enabled = onGround,
                     modifier = Modifier.fillMaxWidth()) { confirmDelete = true }
        detail.error?.let {
            Text(it, color = colors.warning, fontSize = 15.sp,
                 modifier = Modifier.padding(horizontal = 4.dp))
        }
        if (!onGround)
            Hint("Replay and Delete: on the ground.")
    }

    if (confirmDelete)
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this flight?") },
            text = {
                Text("${log.name} goes from the phone for good. Copies shared or on " +
                         "WeGlide stay.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("Delete", color = colors.warning) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
}

/** The take-off's day, else the day the file was written. */
private fun title(detail: FlightDetail, timeZone: TimeZone): String =
    detail.date?.let {
        runCatching {
            LocalDate.parse(it).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
        }.getOrNull()
    } ?: DateFormat.getDateInstance(DateFormat.MEDIUM).apply { this.timeZone = timeZone }
        .format(Date(detail.log.modified))

@Composable
private fun Hint(text: String) {
    Text(text, color = XcsTheme.colors.textSecondary, fontSize = 15.sp,
         modifier = Modifier.padding(horizontal = 4.dp))
}
