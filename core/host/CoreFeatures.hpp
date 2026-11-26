// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

#pragma once

#include <cstdint>
#include <string>
#include <string_view>

/**
 * What XCSoar's moving map draws over the terrain, as GeoJSON for a
 * map the app draws itself (xcs_map_features()): airspace, waypoints,
 * the task and the trail, from the same data the glide computer uses.
 * Runs on the core main thread.
 */
namespace CoreFeatures {

/** Every airspace of a class the pilot shows, as polygons. */
std::string
Airspaces() noexcept;

/** The airspaces with a warning now: [[id, state, acknowledged], ...]. */
std::string
AirspaceStates() noexcept;

/** Every waypoint, as points. */
std::string
Waypoints() noexcept;

/** The ordered task: its legs, zones and the active point. */
std::string
Task() noexcept;

/** The task being edited (XCS_TASK_BEGIN), as the map draws it; no
    features without an edit. */
std::string
EditedTask() noexcept;

/** The flight as long as the trail option says, in segments of like
    climb; none while the trail drifts (RecentTrail() has it then). */
std::string
Trail() noexcept;

/** As Trail(), the last two minutes only: small enough to send with
    every fix.  While the trail drifts with the wind, all of it (and
    Trail() none). */
std::string
RecentTrail() noexcept;

/** The flight's earlier thermals at the glider's altitude, as points. */
std::string
Thermals() noexcept;

/**
 * The terrain's heights (metres) over tile (zoom, x, y) of the Web
 * Mercator grid, @p size by @p size from the north-west, for the map's
 * relief (xcs_terrain_tile()).
 *
 * @return false without terrain or outside it
 */
bool
TerrainTile(unsigned zoom, unsigned x, unsigned y, unsigned size,
            int16_t *heights) noexcept;

/**
 * What the data files hold along @p lines, GeoJSON positions
 * [[[lon, lat], ...], ...], within @p margin metres: the terrain's
 * bounds and the first place where it ends, and how many airspaces,
 * waypoints and landables (xcs_data_coverage()).
 *
 * @return empty if @p lines or @p margin is not valid
 */
std::string
Coverage(std::string_view lines, double margin) noexcept;

} // namespace CoreFeatures
