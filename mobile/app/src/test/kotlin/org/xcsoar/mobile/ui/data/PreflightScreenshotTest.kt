// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.data

import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.xcsoar.mobile.FlightData
import org.xcsoar.mobile.core.DataCheck
import org.xcsoar.mobile.core.DataDownload
import org.xcsoar.mobile.core.DataFile
import org.xcsoar.mobile.core.DataKind
import org.xcsoar.mobile.core.FlightArea
import org.xcsoar.mobile.core.GeoPosition
import org.xcsoar.mobile.core.MapPoint
import org.xcsoar.mobile.core.PreflightCheck
import org.xcsoar.mobile.core.TaskOutline
import org.xcsoar.mobile.ui.theme.XcsTheme

/** L4: the pre-flight check (D31). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w390dp-h1200dp-xxhdpi")
class PreflightScreenshotTest {
    private val area = FlightArea.of(
        TaskOutline(listOf(MapPoint("Montélimar", GeoPosition(44.58, 4.74)),
                           MapPoint("Vienne", GeoPosition(45.52, 4.87)),
                           MapPoint("Sagy", GeoPosition(46.62, 4.94))), emptyList()), null)

    @Test
    fun regionToDownload() = captureRoboImage("src/test/screenshots/preflight.png") {
        val region = DataDownload("bourgogne.pmtiles", "Bourgogne", "u", null, 12_400_000,
                                  "maps", null, "2026-11-24")
        val airports = DataDownload("FR-WPT-Airports-OpenAIP.cupx", "FR-WPT-Airports-OpenAIP.cupx",
                                    "u", null, null, "waypoints", DataFile.WAYPOINTS, null)
        XcsTheme(dark = false) {
            PreflightContent(
                PreflightState(PreflightCheck(area, listOf(
                    DataCheck(DataKind.TERRAIN, listOf("FRA_ALPS.xcm"), ok = true,
                              date = "2026-08-23"),
                    DataCheck(DataKind.VECTOR_MAP, listOf("rhone-alpes"), ok = false,
                              gap = true, gapNear = "Sagy", date = "2026-11-24",
                              updates = listOf(region)),
                    DataCheck(DataKind.AIRSPACE, listOf("FR-ASP-National-OpenAIP.txt"),
                              ok = true, count = 66, date = "2026-11-02"),
                    DataCheck(DataKind.WAYPOINTS, listOf("alps.cup"), ok = false, count = 0,
                              suggestion = airports),
                ))),
                FlightData.State(downloading = FlightData.Progress("Bourgogne", 0.4f)),
                {}, {}, {})
        }
    }
}
