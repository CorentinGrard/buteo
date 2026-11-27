// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.waypoints

import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.xcsoar.mobile.core.WaypointDetails
import org.xcsoar.mobile.core.WeatherStation
import org.xcsoar.mobile.ui.theme.XcsTheme

/** L4: a field's page, from a tap on the map. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w390dp-h1300dp-xxhdpi")
class WaypointDetailsScreenshotTest {
    private val field = WaypointDetails(
        id = 42, name = "LFLQ Montelimar Ancone", code = "LFLQ", landable = true,
        airport = true, latitude = 44.58, longitude = 4.74, elevation = 73.0, runway = 20,
        runwayLength = 1200, frequency = "123.500",
        comment = "LFLQ - Montélimar Ancône (26). AD Ouvert a la CAP",
        distance = 23400.0, bearing = 41.0, reachable = true, arrival = 340)

    private val weather = WeatherStation(
        code = "LFLQ", metar = "LFLQ 271400Z 34012KT CAVOK 12/02 Q1021",
        metarTime = "2026-11-27T14:00:00Z", qnh = 1021.0, windBearing = 340.0,
        windSpeed = 6.2, temperature = 285.15, dewPoint = 275.15, cavok = true,
        text = "LFLQ 271400Z 34012KT CAVOK 12/02 Q1021\nWind: 340° 22 km/h")

    @Test
    fun airfield() = captureRoboImage("src/test/screenshots/waypoint_details.png") {
        XcsTheme(dark = false) {
            WaypointDetailsContent(
                WaypointDetailsState(read = true, details = field,
                                     weather = FieldWeather.Report(weather)),
                {}, {}, {}, {})
        }
    }

    @Test
    @Config(qualifiers = "fr-w320dp-h1300dp-xxhdpi")
    fun outlandingFrench() = captureRoboImage("src/test/screenshots/fr_waypoint_details_320.png") {
        XcsTheme(dark = false) {
            WaypointDetailsContent(
                WaypointDetailsState(read = true, details = WaypointDetails(
                    id = 7, name = "LF3852 Frontonas", code = "LF3852", landable = true,
                    latitude = 45.64, longitude = 5.18, elevation = 207.0, runway = 40,
                    runwayLength = 150,
                    comment = "LF3852 - Frontonas (38). BASULM Base ULM Autorisation OBLIGATOIRE",
                    distance = 5350.0, bearing = 337.0, reachable = false, arrival = -120)),
                {}, {}, {}, {})
        }
    }

    @Test
    fun icaoOnlyForLetters() {
        assertEquals("LFLQ", field.icao)
        assertNull(field.copy(code = "LF3852").icao)
        assertNull(field.copy(code = "").icao)
    }
}
