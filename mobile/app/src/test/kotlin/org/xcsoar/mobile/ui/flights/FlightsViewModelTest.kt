// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.flights

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xcsoar.mobile.core.Analysis
import org.xcsoar.mobile.core.FakeXcsoarCore
import org.xcsoar.mobile.core.WeGlideFlight
import org.xcsoar.mobile.core.XcsoarCore
import java.io.File
import kotlin.io.path.createTempDirectory

/** Uploads to WeGlide are remembered across sessions, by file name. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FlightsViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val old = FlightLog("/d/logs/2026-09-27-XCS-AAA-01.igc",
                                "2026-09-27-XCS-AAA-01.igc", 1, 1)
    private val new = FlightLog("/d/logs/2026-10-02-XCS-AAA-01.igc",
                                "2026-10-02-XCS-AAA-01.igc", 2, 1)
    private val oldFlight = WeGlideFlight(1, "https://www.weglide.org/flight/1")
    private val newFlight = WeGlideFlight(2, "https://www.weglide.org/flight/2")

    private class MemoryStore(val saved: MutableMap<String, WeGlideFlight>) : UploadStore {
        override suspend fun load() = saved.toMap()
        override suspend fun save(name: String, flight: WeGlideFlight) {
            saved[name] = flight
        }
        override suspend fun remove(name: String) {
            saved.remove(name)
        }
    }

    @Test
    fun rememberedAndSaved() = runTest {
        val store = MemoryStore(mutableMapOf(old.name to oldFlight))
        val core = object : XcsoarCore by FakeXcsoarCore(backgroundScope) {
            override suspend fun uploadToWeGlide(igcPath: String) = newFlight
        }
        val viewModel = FlightsViewModel({ listOf(old, new) }, core, store)

        viewModel.refresh()
        // an earlier session's upload, not one of this flight
        assertEquals(mapOf(old.path to Upload.Done(oldFlight)), viewModel.uploads.value)

        viewModel.upload(new)
        assertEquals(Upload.Done(newFlight), viewModel.uploads.value[new.path])
        assertEquals(mapOf(old.name to oldFlight, new.name to newFlight), store.saved)
    }

    /* a flight's page: its analysis read once; Delete removes the file
       and its remembered upload (a new file may get its name) */
    @Test
    fun openedAndDeleted() = runTest {
        val dir = createTempDirectory().toFile()
        val file = File(dir, new.name).apply { writeText("B\n") }
        val log = FlightLog(file.path, file.name, file.lastModified(), file.length())
        val store = MemoryStore(mutableMapOf(log.name to newFlight))
        var reads = 0
        val core = object : XcsoarCore by FakeXcsoarCore(backgroundScope) {
            override suspend fun analyseFlightFile(igcPath: String): Analysis {
                reads++
                return Analysis.parse("""{"date": "2026-10-02",
                                          "flight": {"takeoff": 41520, "duration": 15360}}""")
            }
        }
        val listed = { dir.listFiles().orEmpty().map {
            FlightLog(it.path, it.name, it.lastModified(), it.length())
        } }
        val viewModel = FlightsViewModel(listed, core, store, io = Dispatchers.Main)

        viewModel.open(log)
        val detail = viewModel.detail.value!!
        assertFalse(detail.reading)
        assertEquals("2026-10-02", detail.date)
        assertEquals(15360.0, detail.summary!!.duration, 0.0)
        viewModel.open(log)
        assertEquals(1, reads)

        var deleted = false
        viewModel.delete(log) { deleted = true }
        assertTrue(deleted)
        assertFalse(file.exists())
        assertEquals(emptyMap<String, WeGlideFlight>(), store.saved)
        assertEquals(emptyList<FlightLog>(), viewModel.flights.value)
        dir.deleteRecursively()
    }

    /* newest flight first by the file's own date: a 2010 flight copied
       in today goes after a flight of last month */
    @Test
    fun byTheFlightsDate() = runTest {
        val copied = FlightLog("/d/logs/2010-01-21-TEST-01.igc", "2010-01-21-TEST-01.igc",
                               modified = 1_795_000_000_000, size = 1,
                               started = 1_264_033_565_000)
        val flown = FlightLog("/d/logs/2026-10-02-XCS-AAA-01.igc", "2026-10-02-XCS-AAA-01.igc",
                              modified = 1_791_000_000_000, size = 1,
                              started = 1_790_990_000_000)
        // a file without a fix: by when it was written
        val empty = FlightLog("/d/logs/empty.igc", "empty.igc", modified = 1_700_000_000_000,
                              size = 1)
        val viewModel = FlightsViewModel({ listOf(copied, empty, flown) })
        viewModel.refresh()
        assertEquals(listOf(flown, empty, copied), viewModel.flights.value)
    }
}
