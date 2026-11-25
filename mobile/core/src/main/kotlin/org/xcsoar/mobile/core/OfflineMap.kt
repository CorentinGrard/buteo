// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.zip.GZIPInputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.tan

/**
 * One region's vector map in a PMTiles (version 3) file: OpenStreetMap
 * vector tiles at every zoom from [minZoom] to [maxZoom], so nothing of
 * the region is missing without a network.  Safe from several threads:
 * MapLibre asks for tiles from its own.
 */
class PmTiles private constructor(
    val file: File,
    private val channel: FileChannel,
    private val header: ByteBuffer,
) : Closeable {
    val minZoom = header.get(100).toInt() and 0xFF
    val maxZoom = header.get(101).toInt() and 0xFF

    /** West, south, east, north, degrees. */
    val bounds = DoubleArray(4) { header.getInt(102 + 4 * it) / 1e7 }

    private val internalGzip = when (header.get(97).toInt()) {
        NONE, UNKNOWN -> false
        GZIP -> true
        else -> throw IOException("$file: directories compressed other than by gzip")
    }
    private val tileGzip = when (header.get(98).toInt()) {
        NONE, UNKNOWN -> false
        GZIP -> true
        else -> throw IOException("$file: tiles compressed other than by gzip")
    }
    private val leafOffset = header.getLong(40)
    private val tileOffset = header.getLong(56)
    private val root = directory(header.getLong(8), header.getLong(16).toInt())

    private val metadata by lazy {
        try {
            val bytes = inflate(read(header.getLong(24), header.getLong(32).toInt()), internalGzip)
            Json.parseToJsonElement(String(bytes)).jsonObject
        } catch (e: Exception) {
            null
        }
    }

    /** The metadata's name, else the file's. */
    val name: String get() =
        metadata?.get("name")?.jsonPrimitive?.contentOrNull?.ifBlank { null }
            ?: file.nameWithoutExtension

    /** The day of the OpenStreetMap data ("2026-11-24"), as Planetiler writes it. */
    val date: String? get() =
        metadata?.get("planetiler:osm:osmosisreplicationtime")?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.length >= 10 }?.substring(0, 10)

    /** A tile as MapLibre reads it (uncompressed), null if the file has none there. */
    fun tile(z: Int, x: Int, y: Int): ByteArray? {
        val (offset, length) = find(z, x, y) ?: return null
        return inflate(read(tileOffset + offset, length), tileGzip)
    }

    fun has(z: Int, x: Int, y: Int) = find(z, x, y) != null

    override fun close() = channel.close()

    /** Where the tile is in the tile data, and its length. */
    private fun find(z: Int, x: Int, y: Int): Pair<Long, Int>? {
        if (z !in minZoom..maxZoom) return null
        val id = tileId(z, x, y)
        var dir = root
        repeat(4) {  // the specification's deepest
            val i = dir.find(id)
            if (i < 0) return null
            if (dir.runLengths[i] > 0)
                return dir.offsets[i] to dir.lengths[i]
            dir = leaf(dir.offsets[i], dir.lengths[i])
        }
        return null
    }

    /** The leaf directories read last, by their offset. */
    private val leaves = object : LinkedHashMap<Long, Directory>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Directory>) =
            size > LEAVES_KEPT
    }

    private fun leaf(offset: Long, length: Int): Directory {
        synchronized(leaves) { leaves[offset]?.let { return it } }
        val dir = directory(leafOffset + offset, length)
        synchronized(leaves) { leaves[offset] = dir }
        return dir
    }

    private fun directory(offset: Long, length: Int) =
        Directory.decode(inflate(read(offset, length), internalGzip))

    private fun read(offset: Long, length: Int): ByteArray {
        if (length < 0 || length > MAX_READ) throw IOException("$file: $length bytes at $offset")
        val buffer = ByteBuffer.allocate(length)
        while (buffer.hasRemaining())
            if (channel.read(buffer, offset + buffer.position()) < 0)
                throw EOFException("$file: ends before ${offset + length}")
        return buffer.array()
    }

    /** The tiles a directory lists, by tile ID (the specification's Entry). */
    internal class Directory(
        val ids: LongArray,
        /** 0: a leaf directory; else this many tiles, from the ID on, with the same data. */
        val runLengths: IntArray,
        val offsets: LongArray,
        val lengths: IntArray,
    ) {
        /** The entry for [id], or -1. */
        fun find(id: Long): Int {
            var low = 0
            var high = ids.size - 1
            while (low <= high) {
                val k = (low + high) ushr 1
                when {
                    ids[k] < id -> low = k + 1
                    ids[k] > id -> high = k - 1
                    else -> return k
                }
            }
            // the entry before: a leaf from its ID on, or a run that reaches [id]
            if (high >= 0 && (runLengths[high] == 0 || id - ids[high] < runLengths[high]))
                return high
            return -1
        }

        companion object {
            fun decode(bytes: ByteArray): Directory {
                var position = 0
                fun varint(): Long {
                    var value = 0L
                    var shift = 0
                    while (true) {
                        if (position >= bytes.size || shift > 63)
                            throw IOException("directory cut short")
                        val b = bytes[position++].toInt()
                        value = value or ((b and 0x7F).toLong() shl shift)
                        if (b and 0x80 == 0) return value
                        shift += 7
                    }
                }
                val n = varint().toInt()
                if (n < 0 || n > bytes.size) throw IOException("$n entries in ${bytes.size} bytes")
                val ids = LongArray(n)
                var last = 0L
                for (i in 0 until n) { last += varint(); ids[i] = last }
                val runs = IntArray(n) { varint().toInt() }
                val lengths = IntArray(n) { varint().toInt() }
                val offsets = LongArray(n)
                for (i in 0 until n) {
                    val v = varint()
                    // 0: right after the entry before
                    offsets[i] = if (v == 0L && i > 0) offsets[i - 1] + lengths[i - 1] else v - 1
                }
                return Directory(ids, runs, offsets, lengths)
            }
        }
    }

    companion object {
        private const val UNKNOWN = 0
        private const val NONE = 1
        private const val GZIP = 2
        private const val LEAVES_KEPT = 64
        /** No tile or directory comes near this. */
        private const val MAX_READ = 64 shl 20

        /** The file's tiles, or an exception when it is not PMTiles version 3. */
        fun open(file: File): PmTiles {
            val channel = FileChannel.open(file.toPath(), StandardOpenOption.READ)
            try {
                val header = ByteBuffer.allocate(127).order(ByteOrder.LITTLE_ENDIAN)
                while (header.hasRemaining())
                    if (channel.read(header, header.position().toLong()) < 0)
                        throw EOFException("$file: no PMTiles header")
                val magic = String(header.array(), 0, 7, Charsets.US_ASCII)
                if (magic != "PMTiles" || header.get(7).toInt() != 3)
                    throw IOException("$file: not PMTiles version 3")
                return PmTiles(file, channel, header)
            } catch (e: Exception) {
                channel.close()
                throw e
            }
        }

        /** The specification's tile ID: the zooms before, then along a Hilbert curve. */
        fun tileId(z: Int, x: Int, y: Int): Long {
            require(z in 0..26 && x in 0 until (1 shl z) && y in 0 until (1 shl z)) {
                "no tile $z/$x/$y"
            }
            var id = ((1L shl (2 * z)) - 1) / 3
            val n = 1 shl z
            var tx = x
            var ty = y
            var s = n shr 1
            while (s > 0) {
                val rx = if (tx and s != 0) 1 else 0
                val ry = if (ty and s != 0) 1 else 0
                id += s.toLong() * s * ((3 * rx) xor ry)
                if (ry == 0) {
                    if (rx == 1) {
                        tx = n - 1 - tx
                        ty = n - 1 - ty
                    }
                    val t = tx; tx = ty; ty = t
                }
                s = s shr 1
            }
            return id
        }

        private fun inflate(bytes: ByteArray, gzip: Boolean): ByteArray =
            if (gzip) GZIPInputStream(bytes.inputStream()).use { it.readBytes() } else bytes
    }
}

/**
 * The vector map's region files on the phone (the .pmtiles files in
 * XCSoarData/maps), used together: a tile comes from the first that has it.  The regions
 * are to be cut from one build of the map (`pmtiles extract`), so that a
 * tile on their border is the same in each and the map has no seam.
 */
class OfflineMap(val regions: List<PmTiles>) : Closeable {
    init {
        require(regions.isNotEmpty())
    }

    /** The deepest zoom of every region: MapLibre enlarges its tiles beyond. */
    val maxZoom = regions.minOf { it.maxZoom }
    val minZoom = regions.maxOf { it.minZoom }.coerceAtMost(maxZoom)

    /** West, south, east, north of all the regions, degrees. */
    val bounds = doubleArrayOf(regions.minOf { it.bounds[0] }, regions.minOf { it.bounds[1] },
                               regions.maxOf { it.bounds[2] }, regions.maxOf { it.bounds[3] })

    /** The oldest region's data, the date the map is as up to date as. */
    val date: String? get() = regions.mapNotNull { it.date }.minOrNull()

    fun tile(z: Int, x: Int, y: Int): ByteArray? =
        regions.firstNotNullOfOrNull { it.tile(z, x, y) }

    /**
     * The first place along [lines] near which the map has a hole: a
     * tile at [maxZoom] missing within [margin] metres of them (at least
     * the tiles around).  Each line is walked in steps of a quarter of
     * a tile; a line of one point is that point.  Null if none.
     */
    fun firstGap(lines: List<List<GeoPosition>>, margin: Double = MARGIN): GeoPosition? {
        val z = maxZoom
        val size = 1 shl z
        val checked = HashSet<Long>()
        fun gapAround(p: GeoPosition): Boolean {
            val x = tileX(p.longitude, z)
            val y = tileY(p.latitude, z)
            val tileMetres = EARTH_CIRCUMFERENCE * cos(Math.toRadians(p.latitude)) / size
            val r = max(1, ceil(margin / tileMetres).toInt()).coerceAtMost(MAX_RING)
            for (dy in -r..r) {
                val ty = floor(y).toInt() + dy
                if (ty !in 0 until size) continue
                for (dx in -r..r) {
                    val tx = Math.floorMod(floor(x).toInt() + dx, size)
                    if (checked.add(ty.toLong() * size + tx) && regions.none { it.has(z, tx, ty) })
                        return true
                }
            }
            return false
        }
        for (line in lines) {
            if (line.size == 1 && gapAround(line[0])) return line[0]
            for ((a, b) in line.zipWithNext()) {
                val tiles = max(abs(tileX(b.longitude, z) - tileX(a.longitude, z)),
                                abs(tileY(b.latitude, z) - tileY(a.latitude, z)))
                val steps = max(1, ceil(tiles * 4).toInt()).coerceAtMost(MAX_STEPS)
                for (i in 0..steps) {
                    val t = i.toDouble() / steps
                    val p = GeoPosition(a.latitude + (b.latitude - a.latitude) * t,
                                        a.longitude + (b.longitude - a.longitude) * t)
                    if (gapAround(p)) return p
                }
            }
        }
        return null
    }

    fun covers(position: GeoPosition, margin: Double = MARGIN) =
        firstGap(listOf(listOf(position)), margin) == null

    override fun close() = regions.forEach { it.close() }

    companion object {
        /** Around the task and the glider: a detour from the line. */
        const val MARGIN = 10_000.0
        private const val EARTH_CIRCUMFERENCE = 40_075_016.7
        private const val MAX_RING = 8
        private const val MAX_STEPS = 100_000
        private const val MAX_LATITUDE = 85.05

        /**
         * The region files in [dir] (by name, so the order stays), null
         * without any.  A file that is not PMTiles is left out and told
         * to [onError].
         */
        fun open(dir: File?, onError: (File, Exception) -> Unit = { _, _ -> }): OfflineMap? {
            val regions = dir?.listFiles { f -> f.isFile && f.extension == "pmtiles" }
                ?.sortedBy { it.name }
                ?.mapNotNull { file ->
                    try {
                        PmTiles.open(file)
                    } catch (e: Exception) {
                        onError(file, e)
                        null
                    }
                }
            return if (regions.isNullOrEmpty()) null else OfflineMap(regions)
        }

        /** Web Mercator, in tiles of zoom [z] (fractional). */
        internal fun tileX(longitude: Double, z: Int) =
            (longitude + 180) / 360 * (1 shl z)

        internal fun tileY(latitude: Double, z: Int): Double {
            val phi = Math.toRadians(latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE))
            return (1 - ln(tan(phi) + 1 / cos(phi)) / PI) / 2 * (1 shl z)
        }
    }
}

/**
 * What the phone has of the vector map for this flight: its region
 * files, and where the task or the glider goes beyond them.
 */
data class MapCoverage(
    /** The region files' names, empty without any (the map is online only). */
    val regions: List<String>,
    /** The oldest region's data, "2026-11-24". */
    val date: String? = null,
    /** The point of the task nearest to where the map has a hole. */
    val gapNear: String? = null,
    /** The glider is beyond the stored map. */
    val gliderOutside: Boolean = false,
) {
    val complete get() = regions.isNotEmpty() && gapNear == null && !gliderOutside

    companion object {
        fun of(map: OfflineMap?, task: TaskOutline?, glider: GeoPosition?): MapCoverage {
            if (map == null) return MapCoverage(emptyList())
            val gap = task?.let { t -> map.firstGap(t.lines + t.points.map { listOf(it.position) }) }
            return MapCoverage(
                regions = map.regions.map { it.name },
                date = map.date,
                gapNear = gap?.let { g -> task.points.minByOrNull { distance(it.position, g) }?.name },
                gliderOutside = glider != null && !map.covers(glider))
        }

        /** Enough to tell the nearest point: degrees, longitude by its parallel. */
        private fun distance(a: GeoPosition, b: GeoPosition): Double {
            val dx = (a.longitude - b.longitude) * cos(Math.toRadians((a.latitude + b.latitude) / 2))
            val dy = a.latitude - b.latitude
            return dx * dx + dy * dy
        }
    }
}
