// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max

/** Metres a degree of latitude spans. */
private const val METRES_PER_DEGREE = 111_195.0

/**
 * Metres to [other], on a plane around the two: close enough over the
 * tens of kilometres a check looks at.
 */
fun GeoPosition.metresTo(other: GeoPosition): Double {
    val k = cos(Math.toRadians((latitude + other.latitude) / 2))
    return hypot((longitude - other.longitude) * k, latitude - other.latitude) * METRES_PER_DEGREE
}

/** The positions along [lines], every [step] metres or closer, ends included. */
internal fun sampleLines(lines: List<List<GeoPosition>>, step: Double): List<GeoPosition> {
    val samples = mutableListOf<GeoPosition>()
    for (line in lines) {
        line.firstOrNull()?.let { samples += it }
        for ((a, b) in line.zipWithNext()) {
            val n = max(1, ceil(a.metresTo(b) / step).toInt()).coerceAtMost(MAX_STEPS)
            for (i in 1..n) {
                val t = i.toDouble() / n
                samples += GeoPosition(a.latitude + (b.latitude - a.latitude) * t,
                                       a.longitude + (b.longitude - a.longitude) * t)
            }
        }
    }
    return samples
}

private const val MAX_STEPS = 10_000

/** What the data files hold along a flight (`xcs_data_coverage`). */
data class DataCoverage(
    /** The terrain's west, south, east and north; null without terrain. */
    val terrainBounds: List<Double>?,
    /** The first place along the lines that the terrain does not reach. */
    val terrainGap: GeoPosition? = null,
    /** Within the margin of the lines. */
    val airspace: Int = 0,
    val waypoints: Int = 0,
    val landables: Int = 0,
) {
    companion object {
        fun parse(text: String): DataCoverage {
            val o = Json.parseToJsonElement(text).jsonObject
            val terrain = o["terrain"] as? JsonObject
            fun count(key: String) = o[key]?.jsonPrimitive?.int ?: 0
            return DataCoverage(
                terrainBounds = terrain?.get("bounds")?.jsonArray?.map { it.jsonPrimitive.double },
                terrainGap = terrain?.get("gap")?.jsonArray?.let {
                    GeoPosition(it[1].jsonPrimitive.double, it[0].jsonPrimitive.double)
                },
                airspace = count("airspace"),
                waypoints = count("waypoints"),
                landables = count("landables"))
        }

        /** [[[lon, lat], ...], ...], as `xcs_data_coverage` takes them. */
        fun encode(lines: List<List<GeoPosition>>): String =
            lines.joinToString(",", "[", "]") { line ->
                line.joinToString(",", "[", "]") { "[${it.longitude},${it.latitude}]" }
            }
    }
}

/**
 * A region of the vector map on Buteo's server: one PMTiles file cut
 * from one build of the map ([OfflineMap]), listed in the server's
 * index (regions.json, made by mobile/tools/map_regions.py).
 */
@Serializable
data class MapRegion(
    /** The file's name without ".pmtiles", e.g. "rhone-alpes". */
    val name: String,
    /** As the pilot reads it, e.g. "Rhône-Alpes". */
    val title: String = name,
    /** Absolute once the index is parsed. */
    val url: String,
    /** Bytes. */
    val size: Long? = null,
    val sha256: String? = null,
    /** The day of the OpenStreetMap data, "2026-11-24". */
    val date: String,
    /** West, south, east, north. */
    val bbox: List<Double>,
    /** The region's outer rings (islands too), [[[lon, lat], ...], ...]; else its [bbox]. */
    val outlines: List<List<List<Double>>>? = null,
) {
    val fileName get() = "$name.pmtiles"

    /** Whether the region reaches within [margin] metres of [p]. */
    fun near(p: GeoPosition, margin: Double): Boolean {
        if (bbox.size != 4) return false
        val (west, south, east, north) = bbox
        val dLat = margin / METRES_PER_DEGREE
        val dLon = dLat / cos(Math.toRadians(p.latitude)).coerceAtLeast(0.01)
        if (p.latitude < south - dLat || p.latitude > north + dLat ||
            p.longitude < west - dLon || p.longitude > east + dLon)
            return false

        val rings = outlines.orEmpty()
            .map { ring -> ring.filter { it.size >= 2 }.map { GeoPosition(it[1], it[0]) } }
            .filter { it.size >= 3 }
        if (rings.isEmpty()) return true  // within the margin of the box
        if (rings.any { inside(it, p) }) return true
        // a plane in metres around p
        val k = cos(Math.toRadians(p.latitude)) * METRES_PER_DEGREE
        fun x(q: GeoPosition) = (q.longitude - p.longitude) * k
        fun y(q: GeoPosition) = (q.latitude - p.latitude) * METRES_PER_DEGREE
        return rings.any { ring ->
            (ring + ring.first()).zipWithNext().any { (a, b) ->
                distanceToSegment(x(a), y(a), x(b), y(b)) <= margin
            }
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        @Serializable
        private data class Index(val regions: List<MapRegion>)

        /** The regions of the index at [base], their URLs made absolute. */
        fun parseIndex(text: String, base: String): List<MapRegion> =
            json.decodeFromString(Index.serializer(), text).regions.map {
                it.copy(url = URI(base).resolve(it.url).toString())
            }

        /** Ray casting: whether [p] is inside [ring]. */
        private fun inside(ring: List<GeoPosition>, p: GeoPosition): Boolean {
            var c = false
            for ((a, b) in (ring + ring.first()).zipWithNext())
                if ((a.latitude > p.latitude) != (b.latitude > p.latitude) &&
                    p.longitude < a.longitude + (p.latitude - a.latitude) *
                    (b.longitude - a.longitude) / (b.latitude - a.latitude))
                    c = !c
            return c
        }

        /** From the origin to the segment (ax, ay)-(bx, by). */
        private fun distanceToSegment(ax: Double, ay: Double, bx: Double, by: Double): Double {
            val dx = bx - ax
            val dy = by - ay
            val l = dx * dx + dy * dy
            val t = if (l == 0.0) 0.0 else ((-ax * dx - ay * dy) / l).coerceIn(0.0, 1.0)
            return hypot(ax + t * dx, ay + t * dy)
        }
    }
}

/**
 * Where a flight goes, for the check before it: along the task, else
 * around the glider (or the phone's last position).
 */
data class FlightArea(
    /** The task's legs, zones and points; else one position. */
    val lines: List<List<GeoPosition>>,
    /** Metres around [lines] the flight may go. */
    val margin: Double,
    /** The task's points, to name where data is missing; empty around a position. */
    val points: List<MapPoint>,
    /**
     * Along a task, where the glider (or the phone) is: its region's map
     * downloads too, [AROUND_MARGIN] around, though the check is the task's.
     */
    val here: GeoPosition? = null,
) {
    val isTask get() = points.isNotEmpty()

    /** Along the lines, closely enough for the [margin] around them. */
    val samples: List<GeoPosition> by lazy { sampleLines(lines, max(margin / 2, 1000.0)) }

    /** The task's point nearest [p]; null around a position. */
    fun nearest(p: GeoPosition): String? = points.minByOrNull { it.position.metresTo(p) }?.name

    companion object {
        /** A detour from the task's lines. */
        const val TASK_MARGIN = 10_000.0
        /** A local flight without a task. */
        const val AROUND_MARGIN = 50_000.0

        /** Along [task] when it has points, else around [position]; null without either. */
        fun of(task: TaskOutline?, position: GeoPosition?): FlightArea? = when {
            task != null && task.points.isNotEmpty() ->
                FlightArea(task.lines + task.points.map { listOf(it.position) }, TASK_MARGIN,
                           task.points, here = position)
            position != null -> FlightArea(listOf(listOf(position)), AROUND_MARGIN, emptyList())
            else -> null
        }
    }
}

/** The kinds of data a flight needs. */
enum class DataKind { TERRAIN, VECTOR_MAP, AIRSPACE, WAYPOINTS }

/** A file to fetch for the flight. */
data class DataDownload(
    /** The file's name in XCSoarData. */
    val name: String,
    /** What the pilot reads: the region's title, else [name]. */
    val title: String,
    val url: String,
    val sha256: String?,
    /** Bytes, when the server says. */
    val size: Long?,
    /** The XCSoarData folder: "maps", "airspace"… */
    val folder: String,
    /** The data file it becomes; null for a vector map region (all are used together). */
    val use: DataFile?,
    /** The day of its data. */
    val date: String?,
)

/** One kind of data against the flight. */
data class DataCheck(
    val kind: DataKind,
    /** The files in use, by name; empty without. */
    val files: List<String>,
    /** All there for the flight. */
    val ok: Boolean,
    /** The data ends within the flight's area: near [gapNear] along a task. */
    val gap: Boolean = false,
    val gapNear: String? = null,
    /** Airspaces, or landables, within the area; null without an area. */
    val count: Int? = null,
    /** The day of the data, when known. */
    val date: String? = null,
    /** Newer or missing files, downloaded without asking on Wi-Fi. */
    val updates: List<DataDownload> = emptyList(),
    /** Another file that would cover the flight, for the pilot to choose. */
    val suggestion: DataDownload? = null,
)

/** The vector map's region files on the phone and what they leave out. */
data class StoredRegions(
    /** The day of each region's data, by name. */
    val dates: Map<String, String?>,
    /** The first place along the area the files do not reach. */
    val gap: GeoPosition?,
)

/** What the check reads besides the area. */
data class PreflightData(
    val status: DataStatus,
    /** Along the area; null without an area or the core. */
    val coverage: DataCoverage?,
    /** XCSoar's repository; null when its index could not be read. */
    val repository: List<RepositoryFile>?,
    /** The day each data file on the phone was written, by name. */
    val fileDates: Map<String, String>,
    /** Null when the vector map is not used. */
    val regions: StoredRegions?,
    /** The server's regions; null when its index could not be read. */
    val regionIndex: List<MapRegion>?,
)

/**
 * The data a flight needs, checked before it (the pre-flight screen):
 * the terrain, the vector map, airspace and waypoints against the
 * task (or the area around the glider), with what to download.
 */
data class PreflightCheck(
    /** Null: no task and no position, the files alone. */
    val area: FlightArea?,
    val checks: List<DataCheck>,
) {
    val ok get() = checks.all { it.ok }

    /** What the app downloads by itself on Wi-Fi: updates, the map's regions. */
    val automatic: List<DataDownload> get() = checks.flatMap { it.updates }.distinctBy { it.name }

    companion object {
        fun of(area: FlightArea?, data: PreflightData): PreflightCheck {
            val status = data.status
            val coverage = data.coverage.takeIf { area != null }
            val checks = mutableListOf<DataCheck>()

            val mapFiles = status.map.files
            val terrainGap = coverage?.terrainGap
            checks += DataCheck(
                DataKind.TERRAIN, mapFiles.map(::fileName),
                ok = status.map.terrain && terrainGap == null,
                gap = terrainGap != null, gapNear = terrainGap?.let { area?.nearest(it) },
                date = mapFiles.firstNotNullOfOrNull { data.repositoryDate(it) },
                updates = mapFiles.mapNotNull { data.update(it, DataFile.MAP) },
                suggestion = if (status.map.terrain && terrainGap == null) null
                             else data.suggest(area, mapFiles, "map"))

            data.regions?.let { stored ->
                val gap = stored.gap.takeIf { area != null }
                checks += DataCheck(
                    // as the server's index names them ("Bourgogne"), else by file
                    DataKind.VECTOR_MAP, stored.dates.keys.sorted().map { name ->
                        data.regionIndex?.firstOrNull { it.name == name }?.title ?: name
                    },
                    ok = stored.dates.isNotEmpty() && gap == null,
                    gap = gap != null, gapNear = gap?.let { area?.nearest(it) },
                    date = stored.dates.values.filterNotNull().minOrNull(),
                    updates = data.neededRegions(area))
            }

            val airspaceFiles = status.airspace.files
            val hasAirspace = airspaceFiles.isNotEmpty() || status.airspace.count > 0
            val airspaceNear = coverage?.airspace
            checks += DataCheck(
                DataKind.AIRSPACE, airspaceFiles.map(::fileName),
                ok = hasAirspace && airspaceNear != 0,
                count = airspaceNear,
                date = airspaceFiles.firstNotNullOfOrNull { data.repositoryDate(it) },
                updates = airspaceFiles.mapNotNull { data.update(it, DataFile.AIRSPACE) },
                suggestion = if (hasAirspace && airspaceNear != 0) null
                             else data.suggest(area, airspaceFiles, "airspace",
                                               "-ASP-National-OpenAIP.txt"))

            val waypointFiles = status.waypoints.files
            val hasWaypoints = waypointFiles.isNotEmpty() || status.waypoints.count > 0
            val landables = coverage?.landables
            checks += DataCheck(
                DataKind.WAYPOINTS, waypointFiles.map(::fileName),
                ok = hasWaypoints && landables != 0,
                count = landables,
                date = waypointFiles.firstNotNullOfOrNull { data.repositoryDate(it) },
                updates = waypointFiles.mapNotNull { data.update(it, DataFile.WAYPOINTS) },
                suggestion = if (hasWaypoints && landables != 0) null
                             else data.suggest(area, waypointFiles, "waypoint",
                                               "-WPT-National-XCSoar.cup",
                                               "-WPT-Airports-OpenAIP.cupx"))

            return PreflightCheck(area, checks)
        }

        private fun fileName(path: String) = path.substringAfterLast('/')

        private fun PreflightData.repositoryDate(path: String): String? =
            repository?.firstOrNull { it.name == fileName(path) }?.updated

        /** The repository's file of the same name, when newer than the phone's. */
        private fun PreflightData.update(path: String, use: DataFile): DataDownload? {
            val name = fileName(path)
            val file = repository?.firstOrNull { it.name == name } ?: return null
            val updated = file.updated ?: return null
            val local = fileDates[name] ?: return null
            if (updated <= local) return null
            return file.download(use)
        }

        /**
         * A repository file of [type] that covers the whole area (the
         * smallest; the ones named with a [preferred] ending first), not
         * one in use already.
         */
        private fun PreflightData.suggest(area: FlightArea?, inUse: List<String>, type: String,
                                          vararg preferred: String): DataDownload? {
            val samples = area?.samples ?: return null
            val used = inUse.map(::fileName).toSet()
            val covering = repository.orEmpty().filter { f ->
                f.type == type && f.name !in used && !f.name.contains("HighRes") &&
                    samples.all { f.covers(it) }
            }
            val best = preferred.firstNotNullOfOrNull { ending ->
                covering.filter { it.name.endsWith(ending, ignoreCase = true) }
                    .minByOrNull { it.bboxSize }
            } ?: covering.minByOrNull { it.bboxSize }
            return best?.download(best.dataFile ?: return null)
        }

        private fun RepositoryFile.download(use: DataFile): DataDownload? = DataDownload(
            name = name, title = name, url = uri, sha256 = sha256, size = null,
            folder = folder ?: return null, use = use, date = updated)

        /**
         * The server's regions the area reaches (and the glider's
         * surroundings), missing on the phone or older there.
         */
        private fun PreflightData.neededRegions(area: FlightArea?): List<DataDownload> {
            val samples = area?.samples ?: return emptyList()
            val stored = regions?.dates ?: return emptyList()
            return regionIndex.orEmpty()
                .filter { r ->
                    samples.any { r.near(it, area.margin) } ||
                        area.here?.let { r.near(it, FlightArea.AROUND_MARGIN) } == true
                }
                // an undated file (no Planetiler metadata) stays: it would come again and again
                .filter { r -> r.name !in stored || stored[r.name]?.let { it < r.date } == true }
                .map { r ->
                    DataDownload(r.fileName, r.title, r.url, r.sha256, r.size, "maps",
                                 use = null, date = r.date)
                }
        }
    }
}
