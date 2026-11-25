// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The layers of `xcs_map_features`, by their C value. */
enum class MapFeatures(val code: Int) {
    /** Airspace outlines with their limits (GeoJSON polygons). */
    AIRSPACE(0),
    /** Warned airspaces: `[[id, "near"|"inside", acknowledged], ...]`. */
    AIRSPACE_STATE(1),
    /** Every waypoint (GeoJSON points). */
    WAYPOINTS(2),
    /** The ordered task: legs, zones and points. */
    TASK(3),
    /** The last 20 minutes of the flight, by climb. */
    TRAIL(4),
    /** The task being edited, as the map draws it while building it. */
    TASK_EDITED(5),
}

/** An airspace with a warning (`XCS_FEATURES_AIRSPACE_STATE`). */
data class AirspaceState(val id: Int, val inside: Boolean, val acknowledged: Boolean) {
    companion object {
        /** `[[id, "near"|"inside", acknowledged], ...]` */
        fun parseList(text: String): List<AirspaceState> =
            Json.parseToJsonElement(text).jsonArray.mapNotNull { entry ->
                val a = entry as? JsonArray ?: return@mapNotNull null
                AirspaceState(a[0].jsonPrimitive.int,
                              a[1].jsonPrimitive.content == "inside",
                              a[2].jsonPrimitive.boolean)
            }
    }
}

/** A named place of the map. */
data class MapPoint(val name: String, val position: GeoPosition)

/**
 * The task as the map draws it (`XCS_FEATURES_TASK`): its points in
 * order, and its legs' and zones' outlines.
 */
data class TaskOutline(val points: List<MapPoint>, val lines: List<List<GeoPosition>>) {
    companion object {
        fun parse(text: String): TaskOutline {
            val points = mutableListOf<Pair<Int, MapPoint>>()
            val lines = mutableListOf<List<GeoPosition>>()
            for (feature in Json.parseToJsonElement(text).jsonObject["features"]?.jsonArray.orEmpty()) {
                val f = feature as? JsonObject ?: continue
                val properties = f["properties"] as? JsonObject ?: continue
                val coordinates = (f["geometry"] as? JsonObject)?.get("coordinates") ?: continue
                when (properties["layer"]?.jsonPrimitive?.contentOrNull) {
                    "point" -> points += (properties["index"]?.jsonPrimitive?.intOrNull ?: 0) to
                        MapPoint(properties["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                 position(coordinates.jsonArray))
                    "leg", "zone" -> lines += coordinates.jsonArray.map { position(it.jsonArray) }
                }
            }
            return TaskOutline(points.sortedBy { it.first }.map { it.second }, lines)
        }

        /** GeoJSON's [longitude, latitude]. */
        private fun position(a: JsonArray) =
            GeoPosition(a[1].jsonPrimitive.double, a[0].jsonPrimitive.double)
    }
}
