// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.task

import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.xcsoar.mobile.core.GotoTarget
import org.xcsoar.mobile.core.TaskFileInfo
import org.xcsoar.mobile.core.TaskInfo
import org.xcsoar.mobile.core.WeGlideTask
import org.xcsoar.mobile.core.WeGlideTaskList
import org.xcsoar.mobile.core.AreaStats
import org.xcsoar.mobile.core.OptionalStart
import org.xcsoar.mobile.core.TargetInfo
import org.xcsoar.mobile.core.TaskPointInfo
import org.xcsoar.mobile.core.TaskRules
import org.xcsoar.mobile.core.TaskTypeChoice
import org.xcsoar.mobile.core.ZoneInfo
import org.xcsoar.mobile.ui.theme.XcsTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** L4: the task screen (mobile/docs/ARCHITECTURE.md §6). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
class TaskScreenshotTest {
    private val turnTypes = listOf(TaskTypeChoice(3, "FAI quadrant"),
                                   TaskTypeChoice(8, "Turn point cylinder"),
                                   TaskTypeChoice(4, "Keyhole sector"))

    private val points = listOf(
        TaskPointInfo(1, "Grenoble Le Versoud", "start", 1, "Start line", 1000.0,
                      zone = ZoneInfo("line", 1000.0)),
        TaskPointInfo(2, "Dent de Crolles", "turn", 8, "Turn point cylinder", 500.0,
                      leg = 14_200.0, types = turnTypes, zone = ZoneInfo("cylinder", 500.0)),
        TaskPointInfo(3, "Mont Granier", "turn", 8, "Turn point cylinder", 500.0,
                      leg = 18_600.0, types = turnTypes, zone = ZoneInfo("cylinder", 500.0)),
        TaskPointInfo(1, "Grenoble Le Versoud", "finish", 14, "Finish cylinder", 3000.0,
                      leg = 31_900.0, zone = ZoneInfo("cylinder", 3000.0)),
    )

    private val areaTypes = listOf(TaskTypeChoice(10, "Area cylinder"),
                                   TaskTypeChoice(11, "Area sector"),
                                   TaskTypeChoice(16, "Area sector with inner radius"))

    /** An area task: a sector with an inner radius, the start with another start. */
    private fun areaTask() = TaskInfo(
        5, "AAT", editing = true, valid = true, distance = 64_700.0,
        distanceMin = 58_000.0, distanceMax = 71_300.0, aatMinTime = 10_800.0,
        types = listOf(TaskTypeChoice(4, "Racing"), TaskTypeChoice(5, "AAT")),
        points = listOf(
            points[0],
            TaskPointInfo(2, "Dent de Crolles", "turn", 16, "Area sector with inner radius",
                          20_000.0, leg = 14_200.0, types = areaTypes,
                          zone = ZoneInfo("annular_sector", 20_000.0, innerRadius = 5000.0,
                                          startRadial = 300.0, endRadial = 60.0)),
            points[3]),
        optionalStarts = listOf(OptionalStart(4, "Saint-Hilaire du Touvet")),
        rules = TaskRules(startOpen = 12 * 60 + 35, startMaxHeight = 1500.0,
                          startHeightRef = "msl"))

    private val savedRoutes = listOf(
        TaskFileInfo("Le Versoud - Dent de Crolles", "/x/tasks/Le Versoud - Dent de Crolles.tsk"),
        TaskFileInfo("Vercors 300", "/x/tasks/Vercors 300.tsk"),
        TaskFileInfo("Chartreuse triangle", "/x/tasks/Chartreuse triangle.tsk"))

    @Test
    fun activeRacingTask() = captureRoboImage("src/test/screenshots/task_active.png") {
        XcsTheme(dark = false) {
            // alone: the saved routes come after Stop
            TaskContent(TaskState(TaskInfo(4, "Racing", name = "Le Versoud - Dent de Crolles",
                                           valid = true, distance = 64_700.0,
                                           active = 2, points = points),
                                  files = savedRoutes), {})
        }
    }

    /** No route flown: the saved ones, a tap shows one on the map. */
    @Test
    fun savedRoutes() = captureRoboImage("src/test/screenshots/task_routes.png") {
        XcsTheme(dark = false) {
            TaskContent(TaskState(TaskInfo(4, "Racing", valid = true), files = savedRoutes), {})
        }
    }

    /** "Go to" flies elsewhere: the route says it waits, and resumes. */
    @Test
    fun pausedByGoTo() = captureRoboImage("src/test/screenshots/task_paused.png") {
        XcsTheme(dark = false) {
            TaskContent(TaskState(TaskInfo(4, "Racing", valid = true, distance = 64_700.0,
                                           active = 1, points = points, mode = "goto",
                                           goto = GotoTarget(9, "Saint-Hilaire"))), {})
        }
    }

    @Test
    fun editingAreaTask() = captureRoboImage("src/test/screenshots/task_edit.png") {
        XcsTheme(dark = false) {
            TaskContent(TaskState(
                TaskInfo(5, "AAT", editing = true, valid = true, distance = 64_700.0,
                         distanceMin = 58_000.0, distanceMax = 71_300.0, aatMinTime = 10_800.0,
                         types = listOf(TaskTypeChoice(4, "Racing"), TaskTypeChoice(5, "AAT")),
                         points = points),
                selected = 1), {})
        }
    }

    @Test
    @Config(qualifiers = "w390dp-h1200dp-xxhdpi")
    fun editingAreaSector() = captureRoboImage("src/test/screenshots/task_edit_sector.png") {
        XcsTheme(dark = false) { TaskContent(TaskState(areaTask(), selected = 1), {}) }
    }

    @Test
    fun editingStartWithOtherStarts() =
        captureRoboImage("src/test/screenshots/task_edit_starts.png") {
            XcsTheme(dark = false) { TaskContent(TaskState(areaTask(), selected = 0), {}) }
        }

    @Test
    @Config(qualifiers = "w390dp-h1000dp-xxhdpi")
    fun rules() = captureRoboImage("src/test/screenshots/task_rules.png") {
        XcsTheme(dark = false) {
            TaskRulesContent(TaskRules(startOpen = 12 * 60 + 35, startClose = 14 * 60,
                                       startMaxSpeed = 47.2, startMaxHeight = 1500.0,
                                       startHeightRef = "msl", faiAllowed = true,
                                       pevStartWait = 300.0), {})
        }
    }

    /** The panel under the map, on the flight screen's sheet. */
    @Composable
    private fun Sheet(content: @Composable () -> Unit) = XcsTheme(dark = false) {
        Box(Modifier.fillMaxWidth().background(XcsTheme.colors.sheet).padding(12.dp)) {
            content()
        }
    }

    @Test
    @Config(qualifiers = "w390dp-h420dp-xxhdpi")
    fun editOnTheMap() = captureRoboImage("src/test/screenshots/task_map_edit.png") {
        Sheet {
            TaskEditPanelContent(TaskState(
                TaskInfo(4, "Racing", editing = true, distance = 64_700.0, points = points),
                selected = 1))
        }
    }

    /** A saved route on the map, before flying it. */
    @Test
    @Config(qualifiers = "w390dp-h420dp-xxhdpi")
    fun previewOnTheMap() = captureRoboImage("src/test/screenshots/task_map_preview.png") {
        Sheet {
            RoutePreviewPanelContent(TaskState(
                TaskInfo(4, "Racing", name = "Le Versoud - Dent de Crolles", editing = true,
                         valid = true, distance = 64_700.0, points = points)))
        }
    }

    /** An active area task: its first area's target, upstream's numbers. */
    private fun activeArea() = TaskInfo(
        5, "AAT", valid = true, distance = 64_700.0, aatMinTime = 10_800.0, active = 1,
        points = listOf(
            points[0],
            TaskPointInfo(2, "Dent de Crolles", "turn", 10, "Area cylinder", 10_000.0,
                          leg = 14_200.0, zone = ZoneInfo("cylinder", 10_000.0),
                          target = TargetInfo(range = 0.35, radial = -20.0, locked = true,
                                              movable = true)),
            TaskPointInfo(3, "Mont Granier", "turn", 10, "Area cylinder", 10_000.0,
                          leg = 18_600.0, zone = ZoneInfo("cylinder", 10_000.0),
                          target = TargetInfo(movable = true)),
            points[3]),
        area = AreaStats(estimatedTime = 11_520.0, timeDelta = 720.0, speedRemaining = 24.5,
                         speedAchieved = 22.1))

    @Test
    @Config(qualifiers = "w390dp-h720dp-xxhdpi")
    fun target() = captureRoboImage("src/test/screenshots/task_map_target.png") {
        Sheet { TargetPanelContent(TaskState(activeArea()), 1) }
    }

    @Test
    fun loadWithWeGlide() = captureRoboImage("src/test/screenshots/task_load_weglide.png") {
        XcsTheme(dark = false) {
            TaskFilesContent(
                listOf(TaskFileInfo("Grenoble 300.tsk", "/d/tasks/Grenoble 300.tsk", 0)), {}, {},
                WeGlideTasks(WeGlideTaskList.TODAY, listOf(
                    WeGlideTask(1, "Cevennes 300", "Jane Doe", 301_200.0, "triangle",
                                turnpoints = listOf("Grenoble", "Mens", "Die", "Grenoble")),
                    WeGlideTask(2, "", "John Roe", 182_000.0, "free")), null))
        }
    }
}
