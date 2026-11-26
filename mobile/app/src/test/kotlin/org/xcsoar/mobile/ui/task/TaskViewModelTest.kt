// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.task

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.xcsoar.mobile.R
import org.xcsoar.mobile.core.FakeXcsoarCore
import org.xcsoar.mobile.core.TaskFileInfo
import org.xcsoar.mobile.core.TaskInfo
import org.xcsoar.mobile.core.TaskOp
import org.xcsoar.mobile.core.TaskPointInfo
import org.xcsoar.mobile.core.ZoneInfo
import org.xcsoar.mobile.core.XcsoarCore
import org.xcsoar.mobile.ui.asText
import org.xcsoar.mobile.ui.text

/** The task on the map: the refresh the screens share, taps on the map. */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** A core with an edited task; records the edits and target moves. */
    private class Core(scope: TestScope) : XcsoarCore by FakeXcsoarCore(scope.backgroundScope) {
        val edits = mutableListOf<Triple<TaskOp, Int, Double>>()
        val targets = mutableListOf<Triple<Int, Double, Double>>()
        var reads = 0

        override suspend fun task(edited: Boolean): TaskInfo {
            reads++
            return TaskInfo(4, "Racing", editing = edited)
        }

        override suspend fun editTask(op: TaskOp, index: Int, value: Double) {
            edits += Triple(op, index, value)
        }

        override suspend fun setTaskTarget(index: Int, latitude: Double, longitude: Double) {
            targets += Triple(index, latitude, longitude)
        }
    }

    @Test
    fun overlappingScreensKeepTheRefresh() = runTest(dispatcher) {
        val core = Core(this)
        val viewModel = TaskViewModel(core, dispatcher)
        try {
            // the task page opens before the panel under the map closes
            viewModel.open()
            viewModel.open()
            viewModel.close()
            runCurrent()
            assertNotNull(viewModel.state.value.task)
            val reads = core.reads
            advanceTimeBy(3500)
            runCurrent()
            assertEquals("still refreshing", reads + 1, core.reads)

            // the last one closes it; a cancelled read does not lose the task
            viewModel.close()
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(reads + 1, core.reads)
            assertNotNull(viewModel.state.value.task)
        } finally {
            // a failure above must not leave the loop running (extra
            // closes do nothing)
            repeat(2) { viewModel.close() }
        }
    }

    @Test
    fun tapAddsTheWaypointThere() = runTest(dispatcher) {
        val core = Core(this)
        val viewModel = TaskViewModel(core, dispatcher)
        viewModel.addWaypoint(42)
        runCurrent()
        assertEquals(listOf(Triple(TaskOp.APPEND, 0, 42.0)), core.edits)
        assertNull(viewModel.state.value.message)

        // beside the waypoints: nothing added, and why
        viewModel.addWaypoint(null)
        runCurrent()
        assertEquals(1, core.edits.size)
        assertEquals(text(R.string.task_map_no_waypoint), viewModel.state.value.message)
    }

    @Test
    fun leavingAppliesTheChangesOnly() = runTest(dispatcher) {
        val core = Core(this)
        val viewModel = TaskViewModel(core, dispatcher)
        var left = 0
        // nothing changed: the edit goes, the active route stays
        viewModel.beginEdit()
        runCurrent()
        viewModel.leave { left++ }
        runCurrent()
        assertEquals(listOf(TaskOp.BEGIN, TaskOp.CANCEL), core.edits.map { it.first })
        assertEquals(1, left)
        assertEquals(false, viewModel.state.value.task?.editing)

        // a waypoint added: applied on the way out, no "Done" needed
        core.edits.clear()
        viewModel.beginEdit()
        runCurrent()
        viewModel.addWaypoint(42)
        runCurrent()
        viewModel.leave { left++ }
        runCurrent()
        assertEquals(listOf(TaskOp.BEGIN, TaskOp.APPEND, TaskOp.COMMIT),
                     core.edits.map { it.first })
        assertEquals(2, left)
    }

    @Test
    fun anInvalidRouteKeepsThePilotOnThePage() = runTest(dispatcher) {
        val core = object : XcsoarCore by Core(this) {
            override suspend fun task(edited: Boolean) =
                TaskInfo(4, "Racing", editing = edited, errors = "No finish")
            override suspend fun editTask(op: TaskOp, index: Int, value: Double) {
                check(op != TaskOp.COMMIT) { "not valid" }
            }
        }
        val viewModel = TaskViewModel(core, dispatcher)
        var left = false
        viewModel.beginEdit()
        runCurrent()
        viewModel.addWaypoint(42)
        runCurrent()
        viewModel.leave { left = true }
        runCurrent()
        assertEquals(false, left)
        assertEquals("No finish".asText(), viewModel.state.value.message)
    }

    @Test
    fun routesAreNamedByTheirPoints() {
        fun point(id: Int, name: String) = TaskPointInfo(id, name, "turn", 0, "", null)
        // back home: the finish is the start, said once
        assertEquals("Montelimar Ancone - Sagy - Grenoble Isere",
                     routeName(listOf(point(1, "LFLQ Montelimar Ancone"), point(2, "LF7121 Sagy"),
                                      point(3, "LFLS Grenoble Isere"),
                                      point(1, "LFLQ Montelimar Ancone"))))
        assertEquals("Remoulins - Tarascon",
                     routeName(listOf(point(4, "110: Remoulins – ULM LF3025"),
                                      point(5, "111: Tarascon – ULM LF1351"))))
        // short names keep their capitals; nothing a file name cannot hold
        assertEquals("ST AUBAN - Gap-Tallard", routeName(listOf(point(6, "ST AUBAN"),
                                                                 point(7, "Gap-Tallard"))))
        assertEquals("A-B", routeName(listOf(point(8, "A/B"))))
    }

    @Test
    fun resumeFliesTheRouteAgain() = runTest(dispatcher) {
        val core = Core(this)
        val viewModel = TaskViewModel(core, dispatcher)
        viewModel.resume()
        runCurrent()
        assertEquals(listOf(Triple(TaskOp.RESUME, 0, 0.0)), core.edits)
    }

    @Test
    fun fastStepsBuildOnEachOther() = runTest(dispatcher) {
        val radii = mutableListOf<Double>()
        val core = object : XcsoarCore by Core(this) {
            override suspend fun task(edited: Boolean) =
                TaskInfo(5, "AAT", editing = true, points = listOf(
                    TaskPointInfo(1, "A", "start", 2, "Start cylinder", 1000.0),
                    TaskPointInfo(2, "B", "turn", 10, "Area cylinder", 500.0,
                                  zone = ZoneInfo("cylinder", 500.0))))
            override suspend fun editTask(op: TaskOp, index: Int, value: Double) {
                if (op == TaskOp.SET_RADIUS) radii += value
            }
        }
        val viewModel = TaskViewModel(core, dispatcher)
        viewModel.open()
        try {
            runCurrent()
            // three taps before the core answers, 100 m steps below 1 km
            repeat(3) { viewModel.changeRadius(1, +1) }
            runCurrent()
            assertEquals(listOf(600.0, 700.0, 800.0), radii)
        } finally {
            // else its refresh loop runs on in the test's virtual time
            viewModel.close()
        }
    }

    @Test
    fun noRouteOpensOnTheSavedOnes() = runTest(dispatcher) {
        var files = emptyList<TaskFileInfo>()
        val core = object : XcsoarCore by Core(this) {
            override suspend fun taskFiles() = files
        }
        val viewModel = TaskViewModel(core, dispatcher)
        try {
            // none saved: straight to building one
            viewModel.open()
            runCurrent()
            assertEquals(true, viewModel.state.value.task?.editing)
            viewModel.close()

            // some saved: the list, not the editor
            files = listOf(TaskFileInfo("Vercors 300", "/x/tasks/Vercors 300.tsk"))
            val other = TaskViewModel(core, dispatcher)
            other.open()
            runCurrent()
            assertEquals(false, other.state.value.task?.editing)
            assertEquals(files, other.state.value.files)
            other.close()
        } finally {
            viewModel.close()
        }
    }

    @Test
    fun aSavedRouteFliesOnlyFromItsPreview() = runTest(dispatcher) {
        val loaded = mutableListOf<TaskFileInfo>()
        val base = Core(this)
        val core = object : XcsoarCore by base {
            override suspend fun loadTask(file: TaskFileInfo) {
                loaded += file
            }
        }
        val viewModel = TaskViewModel(core, dispatcher)
        val file = TaskFileInfo("Vercors 300", "/x/tasks/Vercors 300.tsk")
        var shown = 0
        viewModel.preview(file) { shown++ }
        runCurrent()
        // in the editor, the route flown untouched
        assertEquals(listOf(file), loaded)
        assertEquals(1, shown)
        assertEquals(emptyList<TaskOp>(), base.edits.map { it.first })
        assertEquals(true, viewModel.state.value.task?.editing)

        // back to the list: dropped
        var listed = 0
        viewModel.cancel { listed++ }
        runCurrent()
        assertEquals(listOf(TaskOp.CANCEL), base.edits.map { it.first })
        assertEquals(1, listed)

        // flown
        base.edits.clear()
        viewModel.preview(file) { shown++ }
        runCurrent()
        viewModel.flyPreviewed()
        runCurrent()
        assertEquals(listOf(TaskOp.COMMIT), base.edits.map { it.first })
        assertEquals(false, viewModel.state.value.task?.editing)
        assertNull(viewModel.state.value.message)
    }

    @Test
    fun tapMovesTheTarget() = runTest(dispatcher) {
        val core = Core(this)
        val viewModel = TaskViewModel(core, dispatcher)
        viewModel.moveTarget(2, 44.5, 5.25)
        runCurrent()
        assertEquals(listOf(Triple(2, 44.5, 5.25)), core.targets)
    }
}
