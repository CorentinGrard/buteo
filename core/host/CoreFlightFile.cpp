// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

#include "CoreFlightFile.hpp"
#include "CoreAnalysis.hpp"
#include "DebugReplayIGC.hpp"
#include "FlightPhaseDetector.hpp"
#include "Computer/CirclingComputer.hpp"
#include "Computer/Settings.hpp"
#include "Engine/Contest/ContestManager.hpp"
#include "Engine/Contest/Settings.hpp"
#include "Engine/Contest/Solvers/Contests.hpp"
#include "Engine/Trace/Trace.hpp"
#include "Formatter/LocalTimeFormatter.hpp"
#include "NMEA/Derived.hpp"
#include "NMEA/MoreData.hpp"
#include "io/StringOutputStream.hxx"
#include "json/Serialize.hxx"
#include "system/Path.hpp"
#include "time/RoughTime.hpp"

#include <boost/json.hpp>

#include <algorithm>
#include <cstdio>
#include <memory>
#include <vector>

using std::chrono::hours;

/** Altitude and trace points drawn at most. */
static constexpr std::size_t MAX_POINTS = 500;

namespace {

/** A fix of the flight, for the barograph and the trace. */
struct Sample {
  TimeStamp time;
  double altitude;
  GeoPoint location;
};

/** Every n-th of @p samples, at most #MAX_POINTS of them. */
template<typename F>
boost::json::array
Thin(const std::vector<Sample> &samples, F &&f) noexcept
{
  const std::size_t step =
    std::max<std::size_t>((samples.size() + MAX_POINTS - 1) / MAX_POINTS, 1);
  boost::json::array a;
  for (std::size_t i = 0; i < samples.size(); i += step)
    a.emplace_back(f(samples[i]));
  return a;
}

std::string
FormatDate(const BrokenDate &date) noexcept
{
  char buffer[16];
  snprintf(buffer, sizeof(buffer), "%04u-%02u-%02u",
           date.year, date.month, date.day);
  return buffer;
}

} // namespace

std::string
CoreFlightFile::Analyse(Path igc, const ContestSettings &contest,
                        RoughTimeDelta utc_offset)
{
  const std::unique_ptr<DebugReplay> replay{DebugReplayIGC::Create(igc)};

  CirclingSettings circling_settings;
  circling_settings.SetDefaults();
  CirclingComputer circling_computer;
  FlightPhaseDetector phases;

  /* AnalyseFlight's traces */
  Trace full_trace({}, Trace::null_time, 512);
  Trace triangle_trace({}, Trace::null_time, 1024);
  Trace sprint_trace({}, std::chrono::minutes{120}, 64);

  std::vector<Sample> samples;
  BrokenDate date = BrokenDate::Invalid();
  TimeStamp takeoff = TimeStamp::Undefined(), landing = TimeStamp::Undefined();
  bool released = false;
  GeoPoint last_location = GeoPoint::Invalid();

  /* AnalyseFlight's Run() */
  while (replay->Next()) {
    circling_computer.TurnRate(replay->SetCalculated(), replay->Basic(),
                               replay->Calculated().flight);
    circling_computer.Turning(replay->SetCalculated(), replay->Basic(),
                              replay->Calculated().flight, circling_settings);

    const MoreData &basic = replay->Basic();
    const FlyingState &flight = replay->Calculated().flight;
    phases.Update(basic, replay->Calculated());

    if (flight.flying && !takeoff.IsDefined()) {
      takeoff = flight.takeoff_time;
      if (basic.date_time_utc.IsDatePlausible())
        date = basic.GetDateTimeAt(takeoff);
    }

    if (!basic.time_available || !basic.location_available ||
        !basic.NavAltitudeAvailable())
      continue;

    /* an implausible jump: a broken logger, the rest is not trusted */
    if (last_location.IsValid() &&
        ((last_location.latitude - basic.location.latitude).Absolute() >
         Angle::Degrees(1) ||
         (last_location.longitude - basic.location.longitude).Absolute() >
         Angle::Degrees(30)))
      break;
    last_location = basic.location;

    /* the contest starts at the release (after an aerotow) */
    if (!released && flight.release_time.IsDefined()) {
      released = true;
      full_trace.EraseEarlierThan(flight.release_time);
      triangle_trace.EraseEarlierThan(flight.release_time);
      sprint_trace.EraseEarlierThan(flight.release_time);
    }

    /* the first flight only, as AnalyseFlight */
    if (takeoff.IsDefined() && !flight.flying) {
      landing = flight.landing_time;
      break;
    }

    const TracePoint point(basic);
    full_trace.push_back(point);
    triangle_trace.push_back(point);
    sprint_trace.push_back(point);

    if (takeoff.IsDefined())
      samples.push_back({basic.time, basic.nav_altitude, basic.location});
  }
  phases.Finish();

  boost::json::object o;
  if (date.IsPlausible())
    o["date"] = FormatDate(date);

  if (takeoff.IsDefined() && !samples.empty()) {
    /* still flying when the log ends: its last fix */
    if (!landing.IsDefined())
      landing = samples.back().time;

    o["flight"] = boost::json::object{
      {"takeoff", takeoff.ToDuration().count()},
      {"takeoff_local", FormatLocalTimeHHMM(takeoff, utc_offset).c_str()},
      {"landing", landing.ToDuration().count()},
      {"landing_local", FormatLocalTimeHHMM(landing, utc_offset).c_str()},
      {"duration", (landing - takeoff).count()},
    };

    /* "t": hours since the take-off, as the analysis' charts */
    const auto t = [takeoff](TimeStamp time){
      return (time - takeoff) / hours{1};
    };

    o["barograph"] = boost::json::object{
      {"altitude", Thin(samples, [&t](const Sample &s){
        return boost::json::array{t(s.time), s.altitude};
      })},
    };

    boost::json::array thermals;
    double gained = 0, circled = 0;
    for (const Phase &phase : phases.GetPhases()) {
      if (phase.phase_type != Phase::CIRCLING || phase.alt_diff <= 0 ||
          phase.duration.count() <= 0)
        continue;
      thermals.emplace_back(boost::json::array{
        t(phase.start_time), phase.GetVario(), phase.duration / hours{1},
      });
      gained += phase.alt_diff;
      circled += phase.duration.count();
    }
    boost::json::object climb{{"thermals", std::move(thermals)}};
    if (circled > 0)
      climb["average"] = gained / circled;
    o["climb"] = std::move(climb);

    ContestManager manager(contest.contest, full_trace, triangle_trace,
                           sprint_trace);
    manager.SetHandicap(contest.handicap);
    manager.SolveExhaustive();
    o["contest"] = boost::json::object{
      {"name", ContestToString(contest.contest) != nullptr
               ? ContestToString(contest.contest) : ""},
      {"results", CoreAnalysis::ContestResults(contest.contest,
                                               manager.GetStats())},
      {"trace", Thin(samples, [](const Sample &s){
        return boost::json::array{s.location.latitude.Degrees(),
                                  s.location.longitude.Degrees()};
      })},
    };
  }

  StringOutputStream os;
  Json::Serialize(os, o);
  return std::move(os).GetValue();
}
