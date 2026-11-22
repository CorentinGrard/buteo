// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.flights

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.xcsoar.mobile.R
import org.xcsoar.mobile.ui.LinkRow
import org.xcsoar.mobile.ui.PageLayout
import org.xcsoar.mobile.ui.ScreenHeader
import org.xcsoar.mobile.ui.SettingsGroup
import org.xcsoar.mobile.ui.theme.XcsTheme
import java.text.DateFormat
import java.util.Date
import java.util.TimeZone

@Composable
fun FlightsScreen(viewModel: FlightsViewModel, onOpen: (FlightLog) -> Unit,
                  onBack: () -> Unit) {
    val flights by viewModel.flights.collectAsStateWithLifecycle()
    val uploads by viewModel.uploads.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }
    BackHandler(onBack = onBack)
    FlightsContent(flights, onOpen, onBack, uploads = uploads)
}

/**
 * The IGC files of past flights, newest first; each opens its page
 * (the flight, Replay, Share, WeGlide, Delete).
 */
@Composable
fun FlightsContent(
    flights: List<FlightLog>?,
    onOpen: (FlightLog) -> Unit,
    onBack: () -> Unit,
    timeZone: TimeZone = TimeZone.getDefault(),
    uploads: Map<String, Upload> = emptyMap(),
) {
    val colors = XcsTheme.colors
    val onWeGlide = stringResource(R.string.flights_on_weglide)
    PageLayout {
        ScreenHeader(stringResource(R.string.menu_flights), onBack)

        if (flights != null && flights.isEmpty())
            Text(stringResource(R.string.flights_none),
                 color = colors.textSecondary, fontSize = 16.sp,
                 modifier = Modifier.padding(horizontal = 4.dp))

        if (!flights.isNullOrEmpty()) {
            val format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .apply { this.timeZone = timeZone }
            SettingsGroup {
                flights.forEachIndexed { i, flight ->
                    if (i > 0)
                        HorizontalDivider(Modifier.padding(horizontal = 14.dp),
                                          color = colors.panelBorder)
                    LinkRow(format.format(Date(flight.modified)),
                            listOfNotNull(flight.name, kilobytes(flight.size),
                                          onWeGlide.takeIf {
                                              uploads[flight.path] is Upload.Done
                                          }).joinToString(" · ")) {
                        onOpen(flight)
                    }
                }
            }
        }
    }
}

internal fun kilobytes(bytes: Long) = "${(bytes + 1023) / 1024} kB"

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun FlightsPreview() {
    XcsTheme(dark = false) {
        FlightsContent(listOf(
            FlightLog("/x/2026-10-02-XCS-AAA-01.igc", "2026-10-02-XCS-AAA-01.igc",
                      1_791_000_000_000, 412_000),
        ), {}, {})
    }
}
