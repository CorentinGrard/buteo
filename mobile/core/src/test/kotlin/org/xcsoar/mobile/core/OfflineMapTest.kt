// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPOutputStream
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.sinh

class OfflineMapTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun tileIdsAsTheSpecification() {
        assertEquals(0L, PmTiles.tileId(0, 0, 0))
        assertEquals(1L, PmTiles.tileId(1, 0, 0))
        assertEquals(2L, PmTiles.tileId(1, 0, 1))
        assertEquals(3L, PmTiles.tileId(1, 1, 1))
        assertEquals(4L, PmTiles.tileId(1, 1, 0))
        assertEquals(5L, PmTiles.tileId(2, 0, 0))
        assertEquals(19078479L, PmTiles.tileId(12, 3423, 1763))
        // checked against a Planetiler file: Rhone-Alpes at zoom 11
        assertEquals(4959632L, PmTiles.tileId(11, 1055, 730))
    }

    @Test
    fun readsTilesRunsAndLeaves() {
        for (gzip in listOf(false, true))
            for (leaves in listOf(false, true)) {
                val tiles = mapOf(
                    Tile(0, 0, 0) to "world".toByteArray(),
                    Tile(1, 0, 0) to "sea".toByteArray(),
                    Tile(1, 0, 1) to "sea".toByteArray(),  // a run of two
                    Tile(1, 1, 1) to "land".toByteArray(),
                    Tile(2, 3, 1) to "east".toByteArray(),
                )
                val file = pmtiles("t-$gzip-$leaves", tiles, gzip = gzip, leaves = leaves,
                                   metadata = """{"name":"Test","planetiler:osm:osmosisreplicationtime":"2026-11-24T21:21:44Z"}""")
                PmTiles.open(file).use { p ->
                    for ((t, data) in tiles)
                        assertArrayEquals("$gzip $leaves $t", data, p.tile(t.z, t.x, t.y))
                    assertNull(p.tile(1, 1, 0))
                    assertNull(p.tile(2, 0, 0))
                    assertNull(p.tile(3, 0, 0))  // beyond its zooms
                    assertTrue(p.has(2, 3, 1))
                    assertFalse(p.has(2, 3, 2))
                    assertEquals(0, p.minZoom)
                    assertEquals(2, p.maxZoom)
                    assertEquals("Test", p.name)
                    assertEquals("2026-11-24", p.date)
                    assertEquals(4.5, p.bounds[0], 1e-7)
                    assertEquals(46.6, p.bounds[3], 1e-7)
                }
            }
    }

    @Test
    fun refusesWhatIsNotPmTiles() {
        val file = folder.newFile("other.pmtiles").apply { writeText("SQLite format 3") }
        assertTrue(runCatching { PmTiles.open(file) }.exceptionOrNull() is IOException)

        val dir = folder.newFolder("maps")
        file.copyTo(File(dir, "bad.pmtiles"))
        assertNull(OfflineMap.open(dir))
        pmtiles("good", mapOf(Tile(0, 0, 0) to byteArrayOf(1)), dir = dir)
        val errors = mutableListOf<String>()
        OfflineMap.open(dir) { f, _ -> errors += f.name }!!.use { map ->
            assertEquals(listOf("good"), map.regions.map { it.name })
        }
        assertEquals(listOf("bad.pmtiles"), errors)
        assertNull(OfflineMap.open(folder.newFolder("empty")))
        assertNull(OfflineMap.open(null))
    }

    @Test
    fun regionsTogether() {
        val dir = folder.newFolder("two")
        pmtiles("b-south", mapOf(Tile(1, 0, 1) to "south".toByteArray(),
                                 Tile(1, 1, 1) to "border".toByteArray()),
                dir = dir, maxZoom = 1, bounds = doubleArrayOf(4.0, 44.0, 7.0, 45.5),
                metadata = """{"planetiler:osm:osmosisreplicationtime":"2026-11-20T00:00:00Z"}""")
        pmtiles("a-north", mapOf(Tile(1, 0, 0) to "north".toByteArray(),
                                 Tile(1, 1, 1) to "border".toByteArray(),
                                 Tile(2, 0, 0) to "deeper".toByteArray()),
                dir = dir, maxZoom = 2, bounds = doubleArrayOf(3.5, 45.3, 7.3, 46.6),
                metadata = """{"planetiler:osm:osmosisreplicationtime":"2026-11-24T00:00:00Z"}""")
        OfflineMap.open(dir)!!.use { map ->
            assertEquals(listOf("a-north", "b-south"), map.regions.map { it.name })
            assertEquals("north", String(map.tile(1, 0, 0)!!))
            assertEquals("south", String(map.tile(1, 0, 1)!!))
            assertEquals("border", String(map.tile(1, 1, 1)!!))
            assertNull(map.tile(1, 1, 0))
            // as deep as both go
            assertEquals(1, map.maxZoom)
            assertArrayEquals(doubleArrayOf(3.5, 44.0, 7.3, 46.6), map.bounds, 1e-7)
            assertEquals("2026-11-20", map.date)
        }
    }

    /** Zoom 6, every tile of a block but one: x 28..42, y 18..28, without 35/23. */
    private fun holed(): OfflineMap {
        val tiles = mutableMapOf<Tile, ByteArray>()
        for (x in 28..42) for (y in 18..28)
            if (x != 35 || y != 23) tiles[Tile(6, x, y)] = byteArrayOf(x.toByte(), y.toByte())
        return OfflineMap(listOf(PmTiles.open(pmtiles("holed", tiles, minZoom = 6, maxZoom = 6))))
    }

    @Test
    fun findsTheFirstGapAlongTheTask() {
        holed().use { map ->
            // along row 23, west to east: the hole shows from tile 34 on (one tile around)
            val west = at(6, 31.5, 23.5)
            val east = at(6, 39.5, 23.5)
            val gap = map.firstGap(listOf(listOf(west, east)), margin = 0.0)
            assertNotNull(gap)
            assertEquals(34.0, OfflineMap.tileX(gap!!.longitude, 6), 0.26)
            // east to west: from 36
            val back = map.firstGap(listOf(listOf(east, west)), margin = 0.0)!!
            assertEquals(37.0, OfflineMap.tileX(back.longitude, 6), 0.26)
            // a row away from the hole: one tile around misses it
            assertNull(map.firstGap(listOf(listOf(at(6, 31.5, 21.5), at(6, 39.5, 21.5))),
                                    margin = 0.0))
            // ... but a margin wider than a tile there (about 400 km) reaches it
            val wide = map.firstGap(listOf(listOf(at(6, 31.5, 21.5), at(6, 39.5, 21.5))),
                                    margin = 600_000.0)!!
            assertEquals(33.0, OfflineMap.tileX(wide.longitude, 6), 0.26)
            // the edge of the block is a gap too
            assertNotNull(map.firstGap(listOf(listOf(at(6, 28.5, 21.5))), margin = 0.0))
            assertTrue(map.covers(at(6, 32.5, 21.5), margin = 0.0))
            assertFalse(map.covers(at(6, 34.5, 22.5), margin = 0.0))
        }
    }

    @Test
    fun coverageOfTheFlight() {
        holed().use { map ->
            val task = TaskOutline(
                listOf(MapPoint("Start", at(6, 31.5, 23.5)), MapPoint("Turn", at(6, 39.5, 23.5)),
                       MapPoint("Finish", at(6, 39.5, 21.5))),
                listOf(listOf(at(6, 31.5, 23.5), at(6, 39.5, 23.5)),
                       listOf(at(6, 39.5, 23.5), at(6, 39.5, 21.5))))
            val c = MapCoverage.of(map, task, glider = at(6, 32.5, 21.5))
            assertEquals(listOf("holed"), c.regions)
            // the hole is at x 35, nearer the start (31.5) than the turn (39.5)
            assertEquals("Start", c.gapNear)
            assertFalse(c.gliderOutside)
            assertFalse(c.complete)

            val north = TaskOutline(listOf(MapPoint("A", at(6, 32.5, 21.5))),
                                    listOf(listOf(at(6, 32.5, 21.5), at(6, 38.5, 21.5))))
            assertTrue(MapCoverage.of(map, north, null).complete)
            assertTrue(MapCoverage.of(map, null, at(6, 45.5, 21.5)).gliderOutside)
        }
        val none = MapCoverage.of(null, null, null)
        assertTrue(none.regions.isEmpty())
        assertFalse(none.complete)
    }

    @Test
    fun parsesTheTaskAsTheCoreWritesIt() {
        val geojson = """{"type":"FeatureCollection","features":[""" +
            """{"type":"Feature","geometry":{"type":"LineString","coordinates":[[4.1,44.2],[4.2,44.3],[4.1,44.2]]},"properties":{"layer":"zone","index":0,"active":false}},""" +
            """{"type":"Feature","geometry":{"type":"Point","coordinates":[4.15,44.25]},"properties":{"layer":"point","index":0,"active":false,"name":"Montélimar"}},""" +
            """{"type":"Feature","geometry":{"type":"Point","coordinates":[4.7,46.62]},"properties":{"layer":"point","index":1,"active":true,"name":"Sagy"}},""" +
            """{"type":"Feature","geometry":{"type":"LineString","coordinates":[[4.15,44.25],[4.7,46.62]]},"properties":{"layer":"leg","index":1,"active":true,"done":false}}""" +
            """]}"""
        val task = TaskOutline.parse(geojson)
        assertEquals(listOf("Montélimar", "Sagy"), task.points.map { it.name })
        assertEquals(GeoPosition(46.62, 4.7), task.points[1].position)
        assertEquals(2, task.lines.size)
        assertEquals(3, task.lines[0].size)
        assertEquals(GeoPosition(44.25, 4.15), task.lines[1][0])
        assertTrue(TaskOutline.parse("""{"type":"FeatureCollection","features":[]}""").points.isEmpty())
    }

    private data class Tile(val z: Int, val x: Int, val y: Int) {
        val id get() = PmTiles.tileId(z, x, y)
    }

    /** The position at tile coordinates [x], [y] of zoom [z]. */
    private fun at(z: Int, x: Double, y: Double): GeoPosition {
        val n = (1 shl z).toDouble()
        return GeoPosition(Math.toDegrees(atan(sinh(PI * (1 - 2 * y / n)))), x / n * 360 - 180)
    }

    /**
     * A PMTiles file as the specification lays it out: identical
     * neighbours as one run, offsets of 0 for data right after the
     * entry before; with [leaves], two entries per leaf directory.
     */
    private fun pmtiles(
        name: String, tiles: Map<Tile, ByteArray>, gzip: Boolean = true, leaves: Boolean = false,
        minZoom: Int = 0, maxZoom: Int = tiles.keys.maxOf { it.z },
        bounds: DoubleArray = doubleArrayOf(4.5, 44.0, 7.3, 46.6),
        metadata: String = "{}", dir: File = folder.root,
    ): File {
        class Entry(val id: Long, var run: Int, val offset: Long, val length: Int)
        val data = ByteArrayOutputStream()
        val entries = mutableListOf<Entry>()
        var last: ByteArray? = null
        for ((tile, bytes) in tiles.entries.sortedBy { it.key.id }) {
            val previous = entries.lastOrNull()
            if (previous != null && last.contentEquals(bytes) && previous.id + previous.run == tile.id) {
                previous.run++
                continue
            }
            val stored = if (gzip) gzip(bytes) else bytes
            entries += Entry(tile.id, 1, data.size().toLong(), stored.size)
            data.write(stored)
            last = bytes
        }
        fun directory(list: List<Entry>): ByteArray {
            val out = ByteArrayOutputStream()
            fun varint(value: Long) {
                var v = value
                while (v >= 0x80) { out.write(((v and 0x7F) or 0x80).toInt()); v = v ushr 7 }
                out.write(v.toInt())
            }
            varint(list.size.toLong())
            var id = 0L
            for (e in list) { varint(e.id - id); id = e.id }
            for (e in list) varint(e.run.toLong())
            for (e in list) varint(e.length.toLong())
            list.forEachIndexed { i, e ->
                val before = list.getOrNull(i - 1)
                varint(if (before != null && before.offset + before.length == e.offset) 0 else e.offset + 1)
            }
            val bytes = out.toByteArray()
            return if (gzip) gzip(bytes) else bytes
        }
        val leafBytes = ByteArrayOutputStream()
        val root = if (!leaves) directory(entries) else directory(entries.chunked(2).map { chunk ->
            val leaf = directory(chunk)
            Entry(chunk[0].id, 0, leafBytes.size().toLong(), leaf.size).also { leafBytes.write(leaf) }
        })
        val meta = metadata.toByteArray().let { if (gzip) gzip(it) else it }

        val header = ByteBuffer.allocate(127).order(ByteOrder.LITTLE_ENDIAN)
        header.put("PMTiles".toByteArray()).put(3)
        val rootOffset = 127L
        val metaOffset = rootOffset + root.size
        val leafOffset = metaOffset + meta.size
        val dataOffset = leafOffset + leafBytes.size()
        for (v in listOf(rootOffset, root.size.toLong(), metaOffset, meta.size.toLong(),
                         leafOffset, leafBytes.size().toLong(), dataOffset, data.size().toLong(),
                         tiles.size.toLong(), entries.size.toLong(), entries.size.toLong()))
            header.putLong(v)
        val compression: Byte = if (gzip) 2 else 1
        header.put(1).put(compression).put(compression).put(1)
        header.put(minZoom.toByte()).put(maxZoom.toByte())
        for (b in bounds) header.putInt(Math.round(b * 1e7).toInt())
        val file = File(dir, "$name.pmtiles")
        file.outputStream().use {
            it.write(header.array())
            it.write(root)
            it.write(meta)
            leafBytes.writeTo(it)
            data.writeTo(it)
        }
        return file
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }
}
