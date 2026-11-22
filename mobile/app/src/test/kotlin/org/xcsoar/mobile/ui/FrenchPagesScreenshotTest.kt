// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui

import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.xcsoar.mobile.core.AutoMacCreadyMode
import org.xcsoar.mobile.core.DataStatus
import org.xcsoar.mobile.core.FileStatus
import org.xcsoar.mobile.core.MapStatus
import org.xcsoar.mobile.core.ProfileInfo
import org.xcsoar.mobile.core.SafetyOption
import org.xcsoar.mobile.core.TaskInfo
import org.xcsoar.mobile.core.TaskPointInfo
import org.xcsoar.mobile.core.WeGlideTask
import org.xcsoar.mobile.core.WeGlideTaskList
import org.xcsoar.mobile.core.WindInfo
import org.xcsoar.mobile.core.WindOption
import org.xcsoar.mobile.core.WindSource
import org.xcsoar.mobile.ui.data.DataFilesContent
import org.xcsoar.mobile.ui.data.DataFilesState
import org.xcsoar.mobile.ui.profiles.ProfilesContent
import org.xcsoar.mobile.ui.settings.SafetyContent
import org.xcsoar.mobile.ui.settings.WindContent
import org.xcsoar.mobile.ui.setup.FlightSetup
import org.xcsoar.mobile.ui.setup.FlightSetupContent
import org.xcsoar.mobile.ui.task.TaskContent
import org.xcsoar.mobile.ui.task.TaskFilesContent
import org.xcsoar.mobile.ui.task.TaskState
import org.xcsoar.mobile.ui.task.WeGlideTasks
import org.xcsoar.mobile.ui.theme.XcsTheme
import org.xcsoar.mobile.ui.weather.WeatherContent
import org.xcsoar.mobile.ui.weather.WeatherState

/**
 * L4: some of the pages in a French phone (mobile/docs/DECISIONS.md D28);
 * TranslationsTest checks every text, these show the layout with the
 * longer French words.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "fr-w390dp-h844dp-xxhdpi")
class FrenchPagesScreenshotTest {
    private fun capture(name: String, content: @androidx.compose.runtime.Composable () -> Unit) =
        captureRoboImage("src/test/screenshots/fr_$name.png", content = content)

    @Test
    @Config(qualifiers = "fr-w390dp-h1300dp-xxhdpi")
    fun flightSetup() = capture("flight_setup") {
        XcsTheme(dark = false) {
            FlightSetupContent(
                FlightSetup(ballast = 80.0, maxBallast = 150.0, bugs = 0.9, wingLoading = 38.2,
                            crewMass = 90.0, qnh = 1021.0, staticPressure = 950.0,
                            baroAltitude = 642.0, macCready = 1.5, autoMacCready = true,
                            autoMode = AutoMacCreadyMode.BOTH),
                {}, {}, {}, {}, {}, {}, onOpenWind = {})
        }
    }

    @Test
    fun wind() = capture("wind") {
        XcsTheme(dark = false) {
            WindContent(WindInfo(WindSource.CIRCLING, manual = false, speed = 4.5, bearing = 272.0),
                        mapOf(WindOption.CIRCLING to true, WindOption.ZIG_ZAG to true,
                              WindOption.EXTERNAL to true, WindOption.TRAIL_DRIFT to false),
                        { _, _ -> }, { _, _ -> }, {}, {})
        }
    }

    @Test
    @Config(qualifiers = "fr-w390dp-h1300dp-xxhdpi")
    fun safety() = capture("safety") {
        XcsTheme(dark = false) {
            SafetyContent(mapOf(SafetyOption.ARRIVAL_HEIGHT to 300.0,
                                SafetyOption.TERRAIN_HEIGHT to 150.0,
                                SafetyOption.MC to 0.5, SafetyOption.STARTUP_MC to 0.0,
                                SafetyOption.RISK_FACTOR to 0.3, SafetyOption.ALTERNATES to 0.0,
                                SafetyOption.TURN_BACK_MARKER to 1.0),
                          { _, _ -> }, {})
        }
    }

    @Test
    fun dataFiles() = capture("data_files") {
        XcsTheme(dark = false) {
            DataFilesContent(
                DataFilesState(
                    DataStatus(
                        MapStatus(listOf("/data/XCSoarData/maps/alps_hd.xcm"), terrain = true),
                        FileStatus(emptyList(), 0),
                        FileStatus(listOf("/data/XCSoarData/waypoints/alps.cup"), 1250)),
                    error = "Airspace: unknown file format".asText()),
                {}, {}, {}, onDownload = {})
        }
    }

    @Test
    fun profiles() = capture("profiles") {
        XcsTheme(dark = false) {
            ProfilesContent(
                listOf(ProfileInfo("Club", "/x/profiles/Club.prf", password = true),
                       ProfileInfo("default", "/x/profiles/default.prf", current = true)),
                text(org.xcsoar.mobile.R.string.profiles_cannot_switch), flying = true,
                {}, {}, {}, {}, {})
        }
    }

    @Test
    fun taskEmpty() = capture("task_empty") {
        XcsTheme(dark = false) {
            TaskContent(TaskState(TaskInfo(0, "", valid = false)), {})
        }
    }

    @Test
    fun taskActive() = capture("task_active") {
        XcsTheme(dark = false) {
            TaskContent(TaskState(TaskInfo(
                4, "Racing", valid = true, distance = 64_700.0, active = 1,
                points = listOf(
                    TaskPointInfo(1, "Grenoble Le Versoud", "start", 1, "Ligne de départ", 1000.0),
                    TaskPointInfo(2, "Dent de Crolles", "turn", 8, "Cylindre", 500.0,
                                  leg = 14_200.0),
                    TaskPointInfo(1, "Grenoble Le Versoud", "finish", 14, "Cylindre d'arrivée",
                                  3000.0, leg = 31_900.0)))), {})
        }
    }

    @Test
    fun loadTask() = capture("task_load") {
        XcsTheme(dark = false) {
            TaskFilesContent(
                emptyList(), {}, {},
                WeGlideTasks(WeGlideTaskList.TODAY, listOf(
                    WeGlideTask(1, "Cévennes 300", "Jane Doe", 301_200.0, "triangle",
                                turnpoints = listOf("Grenoble", "Mens", "Die", "Grenoble")),
                    WeGlideTask(2, "", "John Roe", 182_000.0, "free")), null))
        }
    }

    @Test
    @Config(qualifiers = "fr-w390dp-h1300dp-xxhdpi")
    fun weatherEmpty() = capture("weather") {
        XcsTheme(dark = false) {
            WeatherContent(WeatherState(), {}, {}, {}, {}, {})
        }
    }
}
