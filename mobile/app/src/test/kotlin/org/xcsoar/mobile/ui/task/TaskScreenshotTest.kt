// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.task

import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.xcsoar.mobile.core.TaskFileInfo
import org.xcsoar.mobile.core.TaskInfo
import org.xcsoar.mobile.core.WeGlideTask
import org.xcsoar.mobile.core.WeGlideTaskList
import org.xcsoar.mobile.core.OptionalStart
import org.xcsoar.mobile.core.TaskPointInfo
import org.xcsoar.mobile.core.TaskRules
import org.xcsoar.mobile.core.TaskTypeChoice
import org.xcsoar.mobile.core.ZoneInfo
import org.xcsoar.mobile.ui.theme.XcsTheme

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

    @Test
    fun activeRacingTask() = captureRoboImage("src/test/screenshots/task_active.png") {
        XcsTheme(dark = false) {
            TaskContent(TaskState(TaskInfo(4, "Racing", valid = true, distance = 64_700.0,
                                           active = 2, points = points)), {})
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
