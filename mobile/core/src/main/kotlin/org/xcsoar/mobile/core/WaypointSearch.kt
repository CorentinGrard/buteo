// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Type filter of `xcs_waypoints_search`. */
enum class WaypointFilter(val code: Int) {
    ALL(0),
    /** Airfields and outlandings. */
    LANDABLE(1),
    AIRPORT(2),
}

/** One waypoint found by `xcs_waypoints_search`, nearest first. */
@Serializable
data class WaypointInfo(
    val id: Int,
    val name: String,
    val landable: Boolean = false,
    val airport: Boolean = false,
    /** Metres. */
    val elevation: Double? = null,
    /** Metres from the aircraft (else from home). */
    val distance: Double? = null,
    /** Degrees true. */
    val bearing: Double? = null,
    /** Landables with a GPS fix: can it be reached, and with what height (m). */
    val reachable: Boolean? = null,
    val arrival: Int? = null,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parseList(text: String): List<WaypointInfo> =
            json.decodeFromString(ListSerializer(serializer()), text)
    }
}

/**
 * One waypoint as XCSoar's waypoint details show it (`xcs_waypoint_get`):
 * SI units, null where the file does not say.
 */
@Serializable
data class WaypointDetails(
    val id: Int,
    val name: String,
    /** The file's short name, "LFLQ" or "LF3852"; may be empty. */
    val code: String = "",
    val landable: Boolean = false,
    val airport: Boolean = false,
    val latitude: Double,
    val longitude: Double,
    /** Metres. */
    val elevation: Double? = null,
    /** Degrees the runway points to (one of its two ends). */
    val runway: Int? = null,
    /** Metres. */
    @SerialName("runway_length") val runwayLength: Int? = null,
    /** MHz, formatted ("123.500"). */
    val frequency: String? = null,
    /** The file's description. */
    val comment: String? = null,
    /** The waypoint details file's text. */
    val details: String? = null,
    /** From the glider, with a GPS fix: metres and degrees true. */
    val distance: Double? = null,
    val bearing: Double? = null,
    /** Landables with a fix, on the safety polar (the glide cone). */
    val reachable: Boolean? = null,
    val arrival: Int? = null,
) {
    /**
     * Its ICAO code: a four letter short name ("LFLQ"), else none;
     * "LF3852", a field without one, has digits.
     */
    val icao: String? get() = code.takeIf { ICAO.matches(it) }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val ICAO = Regex("[A-Z]{4}")

        fun parse(text: String): WaypointDetails = json.decodeFromString(serializer(), text)
    }
}
