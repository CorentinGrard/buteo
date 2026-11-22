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
import org.xcsoar.mobile.core.GeoPosition
import org.xcsoar.mobile.core.MapItemInfo
import org.xcsoar.mobile.core.TaskInfo
import org.xcsoar.mobile.core.TaskOp
import org.xcsoar.mobile.core.TaskPointInfo
import org.xcsoar.mobile.core.ZoneInfo
import org.xcsoar.mobile.core.XcsoarCore
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
        var items = listOf(MapItemInfo("location"),
                           MapItemInfo("waypoint", id = 42, name = "Gordes"))

        override suspend fun task(edited: Boolean): TaskInfo {
            reads++
            return TaskInfo(4, "Racing", editing = edited)
        }

        override suspend fun editTask(op: TaskOp, index: Int, value: Double) {
            edits += Triple(op, index, value)
        }

        override suspend fun mapItemsAt(x: Int, y: Int) = items

        override suspend fun mapLocationAt(x: Int, y: Int) = GeoPosition(44.5, 5.25)

        override suspend fun setTaskTarget(index: Int, latitude: Double, longitude: Double) {
            targets += Triple(index, latitude, longitude)
        }
    }

    @Test
    fun overlappingScreensKeepTheRefresh() = runTest(dispatcher) {
        val core = Core(this)
        val viewModel = TaskViewModel(core)
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
        val viewModel = TaskViewModel(core)
        viewModel.addAt(100, 200)
        runCurrent()
        assertEquals(listOf(Triple(TaskOp.APPEND, 0, 42.0)), core.edits)
        assertNull(viewModel.state.value.message)

        // beside the waypoints: nothing added, and why
        core.items = listOf(MapItemInfo("location"))
        viewModel.addAt(10, 20)
        runCurrent()
        assertEquals(1, core.edits.size)
        assertEquals(text(R.string.task_map_no_waypoint), viewModel.state.value.message)
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
        val viewModel = TaskViewModel(core)
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
    fun tapMovesTheTarget() = runTest(dispatcher) {
        val core = Core(this)
        val viewModel = TaskViewModel(core)
        viewModel.targetAt(2, 100, 200)
        runCurrent()
        assertEquals(listOf(Triple(2, 44.5, 5.25)), core.targets)
    }
}
