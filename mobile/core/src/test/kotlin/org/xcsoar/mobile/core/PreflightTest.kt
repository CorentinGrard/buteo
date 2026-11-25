// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreflightTest {
    private val montelimar = MapPoint("Montélimar", GeoPosition(44.58, 4.74))
    private val vienne = MapPoint("Vienne", GeoPosition(45.52, 4.87))
    private val sagy = MapPoint("Sagy", GeoPosition(46.62, 4.94))
    private val task = TaskOutline(listOf(montelimar, vienne, sagy),
                                   listOf(listOf(montelimar.position, vienne.position),
                                          listOf(vienne.position, sagy.position)))
    private val area = FlightArea.of(task, null)!!

    private fun file(name: String, type: String, updated: String, vararg bbox: Double,
                     folder: String = if (type == "map") "maps" else type) =
        RepositoryFile(name, "https://example.org/$name", type, "fr", updated = updated,
                       bbox = bbox.toList(), folder = folder)

    private val repository = listOf(
        file("FRA_ALPS.xcm", "map", "2019-05-01", 3.5, 43.5, 9.0, 47.5),
        file("FRA_FULL.xcm", "map", "2019-05-01", -5.0, 42.0, 9.0, 51.5),
        file("ALPS.xcm", "map", "2019-05-01", 4.5, 43.4, 16.5, 49.0),
        file("FR-ASP-National-OpenAIP.txt", "airspace", "2026-11-20", -8.0, 39.0, 10.7, 51.18),
        file("FR-WPT-National-XCSoar.cup", "waypoint", "2026-03-01", -61.5, -0.35, 55.7, 51.2,
             folder = "waypoints"),
    )

    private val status = DataStatus(
        MapStatus(listOf("/x/XCSoarData/maps/FRA_ALPS.xcm"), terrain = true),
        FileStatus(listOf("/x/XCSoarData/airspace/FR-ASP-National-OpenAIP.txt"), 812),
        FileStatus(listOf("/x/XCSoarData/waypoints/FR-WPT-National-XCSoar.cup"), 400))

    private val dates = mapOf("FRA_ALPS.xcm" to "2026-11-01",
                              "FR-ASP-National-OpenAIP.txt" to "2026-11-20",
                              "FR-WPT-National-XCSoar.cup" to "2026-10-01")

    private fun data(coverage: DataCoverage? = DataCoverage(listOf(3.5, 43.5, 9.0, 47.5),
                                                            null, 40, 120, 25),
                     status: DataStatus = this.status, fileDates: Map<String, String> = dates,
                     regions: StoredRegions? = null, index: List<MapRegion>? = null) =
        PreflightData(status, coverage, repository, fileDates, regions, index)

    @Test
    fun coverageAsTheCoreWritesIt() {
        val c = DataCoverage.parse(
            """{"terrain":{"bounds":[1.43E2,-3.73E1,1.47E2,-3.36E1],"gap":[1.468E2,-3.727E1]},""" +
                """"airspace":18,"waypoints":10,"landables":1}""")
        assertEquals(listOf(143.0, -37.3, 147.0, -33.6), c.terrainBounds)
        assertEquals(GeoPosition(-37.27, 146.8), c.terrainGap)
        assertEquals(18, c.airspace)
        assertEquals(1, c.landables)
        val none = DataCoverage.parse("""{"terrain":null,"airspace":0,"waypoints":0,"landables":0}""")
        assertNull(none.terrainBounds)
        assertNull(none.terrainGap)
        assertEquals("[[[4.74,44.58],[4.87,45.52]],[[4.94,46.62]]]",
                     DataCoverage.encode(listOf(listOf(montelimar.position, vienne.position),
                                                listOf(sagy.position))))
    }

    @Test
    fun regionsOfTheIndex() {
        val regions = MapRegion.parseIndex(
            """{"regions":[{"name":"rhone-alpes","title":"Rhône-Alpes","url":"rhone-alpes.pmtiles",""" +
                """"size":17283615,"date":"2026-11-24","bbox":[4.0,44.0,7.2,46.5],""" +
                """"outline":[[4.0,44.0],[7.2,44.0],[7.2,46.5],[4.0,44.0]],"extra":1}]}""",
            "https://maps.example.org/fr/regions.json")
        val r = regions.single()
        assertEquals("https://maps.example.org/fr/rhone-alpes.pmtiles", r.url)
        assertEquals("rhone-alpes.pmtiles", r.fileName)
        // the outline is a triangle: south-east of its diagonal
        assertTrue(r.near(GeoPosition(44.5, 6.5), 0.0))
        assertFalse(r.near(GeoPosition(45.0, 4.8), 0.0))       // in the box, beyond the diagonal
        assertTrue(r.near(GeoPosition(45.0, 4.8), 40_000.0))   // ... 28 km from it
        assertFalse(r.near(GeoPosition(45.0, 4.8), 20_000.0))
        assertFalse(r.near(GeoPosition(48.85, 2.35), 50_000.0))  // Paris
        // without an outline, its box
        assertTrue(r.copy(outline = null).near(GeoPosition(45.0, 4.8), 0.0))
    }

    @Test
    fun theAreaOfTheFlight() {
        assertTrue(area.isTask)
        assertEquals(FlightArea.TASK_MARGIN, area.margin, 0.0)
        assertEquals("Vienne", area.nearest(GeoPosition(45.4, 4.8)))
        // legs every 5 km: Montélimar to Sagy is about 230 km
        assertTrue(area.samples.size in 45..60)

        val around = FlightArea.of(TaskOutline(emptyList(), emptyList()), montelimar.position)!!
        assertFalse(around.isTask)
        assertEquals(FlightArea.AROUND_MARGIN, around.margin, 0.0)
        assertNull(around.nearest(vienne.position))
        assertNull(FlightArea.of(null, null))
    }

    @Test
    fun allThereForTheFlight() {
        val check = PreflightCheck.of(area, data())
        assertTrue(check.ok)
        assertEquals(listOf(DataKind.TERRAIN, DataKind.AIRSPACE, DataKind.WAYPOINTS),
                     check.checks.map { it.kind })
        val airspace = check.checks[1]
        assertEquals(listOf("FR-ASP-National-OpenAIP.txt"), airspace.files)
        assertEquals(40, airspace.count)
        assertEquals("2026-11-20", airspace.date)
        assertEquals(25, check.checks[2].count)
        assertTrue(check.automatic.isEmpty())
        assertTrue(check.checks.all { it.suggestion == null })
    }

    @Test
    fun theTerrainEndsBeforeTheTask() {
        val coverage = DataCoverage(listOf(3.5, 43.5, 9.0, 46.5), GeoPosition(46.4, 4.92), 40, 120, 25)
        val terrain = PreflightCheck.of(area, data(coverage)).checks.first()
        assertFalse(terrain.ok)
        assertEquals("Sagy", terrain.gapNear)
        // the smallest map covering the whole task, not the one in use
        assertEquals("ALPS.xcm", terrain.suggestion?.name)
        assertEquals(DataFile.MAP, terrain.suggestion?.use)
        assertEquals("maps", terrain.suggestion?.folder)
        assertTrue(terrain.updates.isEmpty())
    }

    @Test
    fun nothingNearTheTask() {
        val check = PreflightCheck.of(area, data(DataCoverage(listOf(3.5, 43.5, 9.0, 47.5),
                                                              null, 0, 3, 0)))
        assertFalse(check.ok)
        assertFalse(check.checks[1].ok)
        assertFalse(check.checks[2].ok)
        // the national files are those in use: nothing else to offer
        assertNull(check.checks[1].suggestion)
    }

    @Test
    fun noFilesAtAll() {
        val empty = DataStatus(MapStatus(emptyList(), false), FileStatus(emptyList(), 0),
                               FileStatus(emptyList(), 0))
        val check = PreflightCheck.of(area, data(DataCoverage(null), status = empty))
        assertFalse(check.ok)
        // the smallest map covering the task: the French Alps' reaches Sagy
        assertEquals(listOf("FRA_ALPS.xcm", "FR-ASP-National-OpenAIP.txt",
                            "FR-WPT-National-XCSoar.cup"),
                     check.checks.map { it.suggestion?.name })
        assertEquals(DataFile.WAYPOINTS, check.checks[2].suggestion?.use)
        // the map's own airspace and waypoints count as files
        val fromMap = empty.copy(airspace = FileStatus(emptyList(), 30))
        assertTrue(PreflightCheck.of(area, data(status = fromMap)).checks[1].ok)
        // without a task or a position: the files alone
        val alone = PreflightCheck.of(null, data(DataCoverage(null), status = empty))
        assertNull(alone.area)
        assertTrue(alone.checks.all { it.suggestion == null && it.count == null })
    }

    @Test
    fun newerFilesDownloadOnTheirOwn() {
        val older = dates + ("FR-ASP-National-OpenAIP.txt" to "2026-11-02")
        val check = PreflightCheck.of(area, data(fileDates = older))
        val airspace = check.checks[1]
        assertTrue(airspace.ok)  // an update is not a problem
        assertEquals(listOf("FR-ASP-National-OpenAIP.txt"), airspace.updates.map { it.name })
        assertEquals(DataFile.AIRSPACE, airspace.updates.single().use)
        assertEquals("2026-11-20", airspace.updates.single().date)
        assertEquals(listOf("FR-ASP-National-OpenAIP.txt"), check.automatic.map { it.name })
        // a file the phone has not dated (not in the repository's names): left alone
        assertTrue(PreflightCheck.of(area, data(fileDates = emptyMap())).automatic.isEmpty())
    }

    private fun region(name: String, date: String, vararg bbox: Double) =
        MapRegion(name, name.uppercase(), "https://maps.example.org/$name.pmtiles",
                  size = 10_000_000, date = date, bbox = bbox.toList())

    private val index = listOf(
        region("ra-nord", "2026-11-24", 3.6, 45.2, 7.3, 46.6),
        region("ra-sud", "2026-11-24", 3.6, 44.0, 7.3, 45.5),
        region("bourgogne", "2026-11-24", 2.8, 46.15, 5.5, 48.4),
        region("bretagne", "2026-11-24", -5.2, 47.2, -1.0, 48.9),
    )

    @Test
    fun theVectorMapsRegions() {
        val stored = StoredRegions(mapOf("ra-nord" to "2026-11-24"), gap = sagy.position)
        val check = PreflightCheck.of(area, data(regions = stored, index = index))
        val map = check.checks.single { it.kind == DataKind.VECTOR_MAP }
        assertFalse(map.ok)
        assertEquals("Sagy", map.gapNear)
        assertEquals(listOf("ra-nord"), map.files)
        // the task reaches the south and Burgundy, not Brittany
        assertEquals(listOf("ra-sud.pmtiles", "bourgogne.pmtiles"), map.updates.map { it.name })
        assertEquals(listOf("RA-SUD", "BOURGOGNE"), map.updates.map { it.title })
        assertTrue(map.updates.all { it.use == null && it.folder == "maps" && it.size == 10_000_000L })
        assertEquals(map.updates, check.automatic)

        // a newer build of a stored region; an undated one is left alone
        val newer = index.map { if (it.name == "ra-nord") it.copy(date = "2026-12-20") else it }
        val all = StoredRegions(mapOf("ra-nord" to "2026-11-24", "ra-sud" to null,
                                      "bourgogne" to "2026-11-24"), gap = null)
        val updated = PreflightCheck.of(area, data(regions = all, index = newer))
            .checks.single { it.kind == DataKind.VECTOR_MAP }
        assertTrue(updated.ok)
        assertEquals("2026-11-24", updated.date)
        assertEquals(listOf("ra-nord.pmtiles"), updated.updates.map { it.name })

        // none on the phone: not ok, all the task's regions to download
        val none = PreflightCheck.of(area, data(regions = StoredRegions(emptyMap(), null),
                                                index = index))
            .checks.single { it.kind == DataKind.VECTOR_MAP }
        assertFalse(none.ok)
        assertEquals(3, none.updates.size)
        // the vector map not used: no check, no download
        assertTrue(PreflightCheck.of(area, data(index = index)).checks.none {
            it.kind == DataKind.VECTOR_MAP
        })
    }
}
