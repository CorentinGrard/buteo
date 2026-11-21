// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

#pragma once

#include <string>

class Path;
class RoughTimeDelta;
struct ContestSettings;

/**
 * A flight recorded in an IGC file, analysed as XCSoar's AnalyseFlight
 * does (test/src/AnalyseFlight.cpp, which SkyLines runs on uploaded
 * flights): its own replay and computers, not the glide computer's.
 * Touches no global state, so it runs on any thread.
 */
namespace CoreFlightFile {

/**
 * The flight as JSON (UTF-8), in CoreAnalysis::Describe()'s format, so
 * that the app reads it as a flight's analysis:
 *
 *   {"date": "2026-11-21",
 *    "flight": {"takeoff": s, "takeoff_local": "11:42", "landing": s,
 *               "landing_local": "15:58", "duration": s},
 *    "barograph": {"altitude": [[t, m], ...]},
 *    "climb": {"thermals": [[t, m/s, hours], ...], "average": m/s},
 *    "contest": {"name": "OLC League", "results": [...],
 *                "trace": [[lat, lon], ...]}}
 *
 * "date" is the take-off's (UTC); local times use @p utc_offset.  The
 * climbs are the circling phases that gained height
 * (FlightPhaseDetector); the contest is the one in @p contest, with its
 * handicap.  At most 500 points of altitude and trace.  Only "date"
 * (if the file has one) without a take-off.
 *
 * Throws on I/O errors.
 */
std::string
Analyse(Path igc, const ContestSettings &contest, RoughTimeDelta utc_offset);

} // namespace CoreFlightFile
