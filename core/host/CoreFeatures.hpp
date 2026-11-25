// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

#pragma once

#include <cstdint>
#include <string>

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

/** The last 20 minutes of the flight, in segments of like climb. */
std::string
Trail() noexcept;

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

} // namespace CoreFeatures
