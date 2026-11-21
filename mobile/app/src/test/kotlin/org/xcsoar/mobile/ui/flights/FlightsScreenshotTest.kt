// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.flights

import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.xcsoar.mobile.core.Analysis
import org.xcsoar.mobile.core.WeGlideFlight
import org.xcsoar.mobile.ui.summary.FlightSummary
import org.xcsoar.mobile.ui.theme.XcsTheme
import java.util.TimeZone

/** L4: the flight list and a flight's page (mobile/docs/ARCHITECTURE.md §6). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w390dp-h844dp-xxhdpi")
class FlightsScreenshotTest {
    private val utc = TimeZone.getTimeZone("UTC")

    @Test
    fun twoFlights() = captureRoboImage("src/test/screenshots/flights.png") {
        XcsTheme(dark = false) {
            FlightsContent(listOf(
                FlightLog("/x/2026-10-02-XCS-AAA-02.igc", "2026-10-02-XCS-AAA-02.igc",
                          1790959200000L, 412_300),
                FlightLog("/x/2026-09-27-XCS-AAA-01.igc", "2026-09-27-XCS-AAA-01.igc",
                          1790521500000L, 98_000),
            ), {}, {}, utc)
        }
    }

    /* one flight on WeGlide */
    @Test
    fun weGlideUploads() = captureRoboImage("src/test/screenshots/flights_weglide.png") {
        XcsTheme(dark = false) {
            val done = "/x/2026-10-02-XCS-AAA-02.igc"
            val failed = "/x/2026-09-27-XCS-AAA-01.igc"
            FlightsContent(listOf(
                FlightLog(done, "2026-10-02-XCS-AAA-02.igc", 1790959200000L, 412_300),
                FlightLog(failed, "2026-09-27-XCS-AAA-01.igc", 1790521500000L, 98_000),
                FlightLog("/x/2026-09-20-XCS-AAA-01.igc", "2026-09-20-XCS-AAA-01.igc",
                          1789916700000L, 250_000),
            ), {}, {}, utc, uploads = mapOf(
                done to Upload.Done(WeGlideFlight(123456, "https://www.weglide.org/flight/123456")),
                failed to Upload.Failed("422: This flight was already uploaded"),
            ))
        }
    }

    private val log = FlightLog("/x/2026-10-02-XCS-AAA-02.igc", "2026-10-02-XCS-AAA-02.igc",
                                1790959200000L, 412_300)

    /* a triangle from Grenoble, as the core reads it from the file */
    private val analysis = Analysis.parse("""
        {"date": "2026-10-02",
         "flight": {"takeoff": 41520, "takeoff_local": "12:32", "landing": 56880,
                    "landing_local": "16:48", "duration": 15360},
         "barograph": {"altitude": [[0.0, 220], [1.5, 2840], [4.2, 230]]},
         "climb": {"thermals": [[0.3, 1.2, 0.1], [1.4, 2.6, 0.05], [2.8, 1.9, 0.08]],
                   "average": 1.8},
         "contest": {"name": "WeGlide",
                     "results": [{"label": "Free", "distance": 302000, "score": 287.4,
                                  "time": 14400, "speed": 21.0,
                                  "points": [[45.36, 5.85], [45.9, 6.4], [45.2, 6.9],
                                             [45.36, 5.85]]}],
                     "trace": [[45.36, 5.85], [45.6, 6.1], [45.9, 6.4], [45.5, 6.7],
                               [45.2, 6.9], [45.3, 6.3], [45.36, 5.85]]}}
    """.trimIndent())

    /* on the ground, WeGlide set up */
    @Test
    fun flightPage() = captureRoboImage("src/test/screenshots/flight_log.png") {
        XcsTheme(dark = false) {
            FlightLogContent(FlightDetail(log, reading = false, date = analysis.date,
                                          summary = FlightSummary.of(analysis, replay = false)),
                             weGlideReady = true, upload = null, onGround = true,
                             timeZone = utc)
        }
    }

    /* in flight, while the core reads the file: no Replay or Delete */
    @Test
    fun flightPageReading() = captureRoboImage("src/test/screenshots/flight_log_reading.png") {
        XcsTheme(dark = true) {
            FlightLogContent(FlightDetail(log), weGlideReady = false, upload = null,
                             onGround = false, timeZone = utc)
        }
    }

    @Test
    fun noFlights() = captureRoboImage("src/test/screenshots/flights_empty.png") {
        XcsTheme(dark = false) {
            FlightsContent(emptyList(), {}, {}, utc)
        }
    }
}
