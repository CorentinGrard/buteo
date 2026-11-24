// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

#include "CoreFeatures.hpp"
#include "Interface.hpp"
#include "Components.hpp"
#include "BackendComponents.hpp"
#include "DataComponents.hpp"
#include "MapSettings.hpp"
#include "Computer/GlideComputer.hpp"
#include "Computer/TraceComputer.hpp"
#include "Airspace/AirspaceVisibility.hpp"
#include "Airspace/ProtectedAirspaceWarningManager.hpp"
#include "Engine/Airspace/AbstractAirspace.hpp"
#include "Engine/Airspace/Airspaces.hpp"
#include "Engine/Airspace/AirspaceWarning.hpp"
#include "Engine/Airspace/AirspaceWarningManager.hpp"
#include "Engine/Waypoint/Waypoints.hpp"
#include "Engine/Waypoint/Waypoint.hpp"
#include "Engine/Task/ObservationZones/Boundary.hpp"
#include "Engine/Task/TaskManager.hpp"
#include "Engine/Task/Ordered/OrderedTask.hpp"
#include "Engine/Task/Ordered/Points/OrderedTaskPoint.hpp"
#include "Engine/Trace/Point.hpp"
#include "Engine/Trace/Vector.hpp"
#include "Task/ProtectedTaskManager.hpp"
#include "Formatter/AirspaceFormatter.hpp"
#include "Geo/GeoPoint.hpp"
#include "io/StringOutputStream.hxx"
#include "json/Serialize.hxx"

#include <boost/json.hpp>
#include <fmt/format.h>

#include <cmath>
#include <iterator>
#include <string_view>
#include <unordered_map>

namespace {

/**
 * GeoJSON written as text: coordinates with five decimals (about a
 * metre; boost::json would write every digit of a rounded double),
 * properties through boost::json for the escaping.  fmt ignores the
 * locale, which XCSoar sets for its language.
 */
class FeatureWriter {
  std::string text = R"({"type":"FeatureCollection","features":[)";
  bool first = true;

public:
  /** Start a feature; then a geometry, then Properties(). */
  void Begin(const char *geometry_type) noexcept {
    if (!first)
      text += ',';
    first = false;
    fmt::format_to(std::back_inserter(text),
                   R"({{"type":"Feature","geometry":{{"type":"{}","coordinates":)",
                   geometry_type);
  }

  void Position(const GeoPoint &p) noexcept {
    fmt::format_to(std::back_inserter(text), "[{:.5f},{:.5f}]",
                   p.longitude.Degrees(), p.latitude.Degrees());
  }

  /** Positions separated by commas; call between '[' and ']'. */
  template<typename R, typename F>
  void Positions(const R &range, F &&location) noexcept {
    bool first_point = true;
    for (const auto &i : range) {
      if (!first_point)
        text += ',';
      first_point = false;
      Position(location(i));
    }
  }

  void Raw(const char *s) noexcept {
    text += s;
  }

  void Properties(const boost::json::object &properties) noexcept {
    text += R"(},"properties":)";
    StringOutputStream os;
    Json::Serialize(os, properties);
    text += std::move(os).GetValue();
    text += '}';
  }

  std::string Finish() && noexcept {
    text += "]}";
    return std::move(text);
  }
};

/**
 * How the app colours an airspace: controlled (blue), restricted
 * (red), gliding or other, as XCSoar's default classes do.
 */
const char *
Kind(const AbstractAirspace &airspace) noexcept
{
  switch (airspace.GetTypeOrClass()) {
  case RESTRICTED:
  case PROHIBITED:
  case DANGER:
  case NOGLIDER:
  case TRA:
  case TSA:
  case TFR:
  case ALERT:
  case WARNING:
  case PROTECTED:
    return "restricted";

  case CLASSA:
  case CLASSB:
  case CLASSC:
  case CLASSD:
  case CLASSE:
  case CLASSF:
  case CLASSG:
  case CTR:
  case TMA:
  case CTA:
  case ATZ:
  case MATZ:
  case TMZ:
  case RMZ:
  case TIZ:
  case TIA:
    return "controlled";

  case GLIDING_SECTOR:
  case WAVE:
    return "gliding";

  default:
    return "other";
  }
}

/** The order of Airspaces::QueryAll(): an airspace's id. */
std::unordered_map<const AbstractAirspace *, unsigned>
AirspaceIds(const Airspaces &airspaces) noexcept
{
  std::unordered_map<const AbstractAirspace *, unsigned> ids;
  unsigned id = 0;
  for (const auto &i : airspaces.QueryAll())
    ids.emplace(&i.GetAirspace(), id++);
  return ids;
}

/**
 * A waypoint's name for a map label: without the code some files put
 * in front of it ("LFLU Valence Chabeuil" in XCSoar's French file).
 */
std::string_view
Label(const Waypoint &waypoint) noexcept
{
  std::string_view name = waypoint.name;
  const std::string_view code = waypoint.shortname;
  if (!code.empty() && name.size() > code.size() + 1 &&
      name.starts_with(code) && name[code.size()] == ' ')
    name.remove_prefix(code.size() + 1);
  return name;
}

/** A trail segment's colour class from the netto vario, m/s. */
int
ClimbClass(double vario) noexcept
{
  if (vario < -1.5) return -2;
  if (vario < -0.3) return -1;
  if (vario < 0.3) return 0;
  if (vario < 1.5) return 1;
  if (vario < 3) return 2;
  return 3;
}

} // namespace

namespace CoreFeatures {

std::string
Airspaces() noexcept
{
  FeatureWriter w;
  const auto *airspaces = data_components->airspaces.get();
  if (airspaces == nullptr)
    return std::move(w).Finish();

  const auto &renderer = CommonInterface::GetMapSettings().airspace;
  unsigned id = 0;
  for (const auto &i : airspaces->QueryAll()) {
    const AbstractAirspace &airspace = i.GetAirspace();
    const unsigned this_id = id++;
    const auto &points = airspace.GetPoints();
    if (points.size() < 3 || !IsAirspaceTypeOrClassVisible(airspace, renderer))
      continue;

    w.Begin("Polygon");
    w.Raw("[[");
    w.Positions(points, [](const auto &p){ return p.GetLocation(); });
    w.Raw(",");
    w.Position(points.front().GetLocation());
    w.Raw("]]");

    char base[64], top[64];
    AirspaceFormatter::FormatAltitudeShort(base, airspace.GetBase());
    AirspaceFormatter::FormatAltitudeShort(top, airspace.GetTop());
    w.Properties({
      {"id", this_id},
      {"name", airspace.GetName()},
      {"class", AirspaceFormatter::GetClassOrType(airspace)},
      {"kind", Kind(airspace)},
      {"base", base},
      {"top", top},
      /* above sea level; XCSoar's estimate for ground and flight
         level limits */
      {"base_m", std::lround(airspace.GetBase().altitude)},
      {"top_m", std::lround(airspace.GetTop().altitude)},
      {"ground", airspace.GetBase().IsTerrain()},
    });
  }
  return std::move(w).Finish();
}

std::string
AirspaceStates() noexcept
{
  boost::json::array states;
  const auto *airspaces = data_components->airspaces.get();
  if (airspaces != nullptr && backend_components->glide_computer) {
    const auto ids = AirspaceIds(*airspaces);
    const ProtectedAirspaceWarningManager::Lease lease{
      backend_components->glide_computer->GetAirspaceWarnings()};
    const AirspaceWarningManager &warnings = lease;
    for (const AirspaceWarning &warning : warnings) {
      if (warning.GetWarningState() == AirspaceWarning::WARNING_CLEAR)
        continue;
      const auto i = ids.find(&warning.GetAirspace());
      if (i == ids.end())
        continue;
      states.emplace_back(boost::json::array{
        i->second,
        warning.GetWarningState() == AirspaceWarning::WARNING_INSIDE
          ? "inside" : "near",
        !warning.IsAckExpired()});
    }
  }

  StringOutputStream os;
  Json::Serialize(os, states);
  return std::move(os).GetValue();
}

std::string
Waypoints() noexcept
{
  FeatureWriter w;
  const auto *waypoints = data_components->waypoints.get();
  if (waypoints == nullptr)
    return std::move(w).Finish();

  for (const auto &ptr : *waypoints) {
    const Waypoint &waypoint = *ptr;
    boost::json::object properties{
      {"id", waypoint.id},
      {"name", waypoint.name},
      {"code", waypoint.shortname.empty() ? waypoint.name : waypoint.shortname},
      {"label", Label(waypoint)},
      {"landable", waypoint.IsLandable()},
      {"airport", waypoint.IsAirport()},
    };
    if (waypoint.HasElevation())
      properties["elevation"] = std::lround(waypoint.elevation);
    if (waypoint.runway.IsDirectionDefined())
      properties["runway"] = waypoint.runway.GetDirectionDegrees();
    char frequency[32];
    if (waypoint.radio_frequency.Format(frequency, sizeof(frequency)) != nullptr)
      properties["frequency"] = frequency;

    w.Begin("Point");
    w.Position(waypoint.location);
    w.Properties(properties);
  }
  return std::move(w).Finish();
}

std::string
Task() noexcept
{
  FeatureWriter w;
  if (!backend_components->protected_task_manager)
    return std::move(w).Finish();

  const ProtectedTaskManager::Lease lease{
    *backend_components->protected_task_manager};
  const OrderedTask &task = lease->GetOrderedTask();
  const unsigned n = task.TaskSize();
  const unsigned active = task.GetActiveIndex();

  for (unsigned i = 0; i < n; ++i) {
    const OrderedTaskPoint &tp = task.GetTaskPoint(i);

    /* the zone's outline, as OZRenderer draws it (a line is two points) */
    const auto zone = tp.GetBoundary();
    const auto points = std::distance(zone.begin(), zone.end());
    if (points >= 2) {
      w.Begin("LineString");
      w.Raw("[");
      w.Positions(zone, [](const GeoPoint &p){ return p; });
      if (points >= 3) {
        w.Raw(",");
        w.Position(zone.front());
      }
      w.Raw("]");
      w.Properties({{"layer", "zone"}, {"index", i}, {"active", i == active}});
    }

    w.Begin("Point");
    w.Position(tp.GetLocationRemaining());
    w.Properties({{"layer", "point"}, {"index", i}, {"active", i == active},
                  {"name", tp.GetWaypoint().name}});

    if (i > 0) {
      w.Begin("LineString");
      w.Raw("[");
      w.Position(task.GetTaskPoint(i - 1).GetLocationRemaining());
      w.Raw(",");
      w.Position(tp.GetLocationRemaining());
      w.Raw("]");
      w.Properties({{"layer", "leg"}, {"index", i}, {"active", i == active},
                    {"done", i < active}});
    }
  }
  return std::move(w).Finish();
}

std::string
Trail() noexcept
{
  FeatureWriter w;
  if (!backend_components->glide_computer)
    return std::move(w).Finish();

  TracePointVector trace;
  backend_components->glide_computer->GetTraceComputer().LockedCopyTo(trace);
  if (trace.size() < 2)
    return std::move(w).Finish();

  constexpr TracePoint::Time span{20 * 60};
  const auto since = trace.back().GetTime() > span
    ? trace.back().GetTime() - span
    : TracePoint::Time{};

  /* consecutive points of one climb class make one line, which shares
     its last point with the next */
  std::vector<GeoPoint> line;
  int current = 0;
  const auto flush = [&]{
    if (line.size() >= 2) {
      w.Begin("LineString");
      w.Raw("[");
      w.Positions(line, [](const GeoPoint &p){ return p; });
      w.Raw("]");
      w.Properties({{"climb", current}});
    }
    line.clear();
  };

  for (const auto &p : trace) {
    if (p.GetTime() < since)
      continue;
    const int c = ClimbClass(p.GetVario());
    if (!line.empty() && c != current) {
      line.push_back(p.GetLocation());
      flush();
    }
    current = c;
    line.push_back(p.GetLocation());
  }
  flush();
  return std::move(w).Finish();
}

} // namespace CoreFeatures
