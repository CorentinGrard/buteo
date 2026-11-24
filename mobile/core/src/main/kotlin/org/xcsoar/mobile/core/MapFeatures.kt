// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
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
