// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

#include "CoreFeatures.hpp"
#include "CoreTask.hpp"
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
#include "Engine/Task/Factory/AbstractTaskFactory.hpp"
#include "Engine/Task/Ordered/OrderedTask.hpp"
#include "Engine/Task/Ordered/Points/OrderedTaskPoint.hpp"
#include "Engine/Trace/Point.hpp"
#include "Engine/Trace/Vector.hpp"
#include "Task/ProtectedTaskManager.hpp"
#include "Formatter/AirspaceFormatter.hpp"
#include "Geo/GeoBounds.hpp"
#include "Geo/GeoPoint.hpp"
#include "Terrain/RasterTerrain.hpp"
#include "io/StringOutputStream.hxx"
#include "json/Serialize.hxx"

#include <boost/json.hpp>
#include <fmt/format.h>

#include <algorithm>
#include <cmath>
#include <iterator>
#include <limits>
#include <string_view>
#include <unordered_map>
#include <unordered_set>
#include <vector>

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

/** @p task's legs, zones and points; @p active: the point flown to,
    -1 for none.  The active task's points are where the glider goes
    (an area's target); the edited task's, its waypoints (it has no
    targets yet). */
static void
WriteTask(FeatureWriter &w, const OrderedTask &task, int active) noexcept
{
  const unsigned n = task.TaskSize();
  const auto where = [active](const OrderedTaskPoint &tp) -> const GeoPoint & {
    return active >= 0 ? tp.GetLocationRemaining() : tp.GetLocation();
  };

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
      w.Properties({{"layer", "zone"}, {"index", i}, {"active", int(i) == active}});
    }

    w.Begin("Point");
    w.Position(where(tp));
    w.Properties({{"layer", "point"}, {"index", i}, {"active", int(i) == active},
                  {"name", tp.GetWaypoint().name}});

    if (i > 0) {
      w.Begin("LineString");
      w.Raw("[");
      w.Position(where(task.GetTaskPoint(i - 1)));
      w.Raw(",");
      w.Position(where(tp));
      w.Raw("]");
      w.Properties({{"layer", "leg"}, {"index", i}, {"active", int(i) == active},
                    {"done", int(i) < active}});
    }
  }
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
  WriteTask(w, task, task.GetActiveIndex());
  return std::move(w).Finish();
}

std::string
EditedTask() noexcept
{
  FeatureWriter w;
  if (const OrderedTask *edited = CoreTask::GetEdited()) {
    /* as the map draws it: a copy, its last point a finish as on
       "Done" (a half-built task has no zones else) */
    auto copy = edited->Clone(CommonInterface::GetComputerSettings().task);
    copy->GetFactory().CheckAddFinish();
    copy->UpdateGeometry();
    WriteTask(w, *copy, -1);
  }
  return std::move(w).Finish();
}

/**
 * The trail's lines by climb class, at most @p max_age long (zero: as
 * long as the pilot's trail setting).
 */
static std::string
TrailSince(TracePoint::Time max_age) noexcept
{
  FeatureWriter w;
  if (!backend_components->glide_computer)
    return std::move(w).Finish();

  TracePointVector trace;
  backend_components->glide_computer->GetTraceComputer().LockedCopyTo(trace);
  if (trace.size() < 2)
    return std::move(w).Finish();

  /* as long as the pilot's trail setting, as XCSoar's map
     (GlueMapWindow::RenderTrail()) */
  TracePoint::Time span{};
  switch (CommonInterface::GetMapSettings().trail.length) {
  case TrailSettings::Length::OFF:
    return std::move(w).Finish();
  case TrailSettings::Length::LONG:
    span = TracePoint::Time{60 * 60};
    break;
  case TrailSettings::Length::SHORT:
    span = TracePoint::Time{10 * 60};
    break;
  case TrailSettings::Length::FULL:
    break;
  }
  if (max_age.count() > 0 && (span.count() == 0 || max_age < span))
    span = max_age;
  const auto since = span.count() > 0 && trace.back().GetTime() > span
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

std::string
Trail() noexcept
{
  return TrailSince({});
}

std::string
RecentTrail() noexcept
{
  /* the part of the trace XCSoar does not thin
     (TraceComputer's full_trace_no_thin_time) */
  return TrailSince(TracePoint::Time{2 * 60});
}

/** The latitude of row @p y (fractional) of a Web Mercator tile grid
    @p n tiles high. */
static Angle
MercatorLatitude(double y, double n) noexcept
{
  return Angle::Radians(std::atan(std::sinh(M_PI * (1 - 2 * y / n))));
}

static Angle
MercatorLongitude(double x, double n) noexcept
{
  return Angle::Degrees(x / n * 360 - 180);
}

/**
 * Water has no height: give it the one beside it on its row (else the
 * row above), so that lake shores cast no shadow.  The map draws the
 * water over the relief.  Beyond the terrain's edge the ground is at
 * sea level: carried along the rows, the edge's heights drew streaks
 * across the whole world when zoomed out.
 */
static constexpr int16_t NO_HEIGHT = std::numeric_limits<int16_t>::min();

static void
FillGaps(int16_t *heights, unsigned size) noexcept
{
  for (unsigned row = 0; row < size; ++row) {
    int16_t *const begin = heights + row * size, *const end = begin + size;
    const auto first = std::find_if(begin, end,
                                    [](int16_t h){ return h != NO_HEIGHT; });
    if (first == end) {
      if (row > 0)
        std::copy(begin - size, begin, begin);
      else
        std::fill(begin, end, int16_t(0));
      continue;
    }
    std::fill(begin, first, *first);
    for (int16_t *h = first + 1; h != end; ++h)
      if (*h == NO_HEIGHT)
        *h = h[-1];
  }
}

bool
TerrainTile(unsigned zoom, unsigned x, unsigned y, unsigned size,
            int16_t *heights) noexcept
{
  if (data_components == nullptr || data_components->terrain == nullptr)
    return false;

  RasterTerrain &terrain = *data_components->terrain;
  const double n = std::exp2(zoom);
  const GeoBounds bounds{
    GeoPoint{MercatorLongitude(x, n), MercatorLatitude(y, n)},
    GeoPoint{MercatorLongitude(x + 1, n), MercatorLatitude(y + 1, n)},
  };

  {
    RasterTerrain::Lease map{terrain};
    if (!map->IsDefined() || !map->GetBounds().Overlaps(bounds))
      return false;
  }

  /* zoomed in, the detailed terrain there; farther out, a tile spans
     many of the terrain's own and its overview (16 times coarser) is
     about as fine as the tile's pixels */
  constexpr unsigned DETAIL_ZOOM = 9;
  if (zoom >= DETAIL_ZOOM) {
    const GeoPoint center = bounds.GetCenter();
    const double radius = center.DistanceS(bounds.GetNorthWest());
    /* 16 tiles a call (RasterTileCache::PollTiles()); bounded, in
       case a tile fails to load */
    for (unsigned i = 0; i < 8 && terrain.UpdateTiles(center, radius); ++i) {}
  }

  RasterTerrain::Lease map{terrain};
  const GeoBounds inside = map->GetBounds();
  for (unsigned row = 0; row < size; ++row) {
    const Angle latitude = MercatorLatitude(y + (row + 0.5) / size, n);
    for (unsigned column = 0; column < size; ++column) {
      const GeoPoint p{MercatorLongitude(x + (column + 0.5) / size, n),
                       latitude};
      int16_t &height = heights[row * size + column];
      if (!inside.IsInside(p)) {
        height = 0;
        continue;
      }

      const TerrainHeight h = map->GetInterpolatedHeight(p);
      height = h.IsSpecial() ? NO_HEIGHT : h.GetValue();
    }
  }

  FillGaps(heights, size);
  return true;
}

/**
 * The points of @p lines ([[[lon, lat], ...], ...]) every @p step
 * metres or closer, each line's ends included.
 *
 * @return empty if @p lines is not such a list, or a line has a
 * position off the globe
 */
static std::vector<GeoPoint>
SampleLines(std::string_view lines, double step)
{
  /* a task of a few thousand kilometres, every kilometre */
  constexpr std::size_t MAX_SAMPLES = 100000;

  std::vector<GeoPoint> samples;
  boost::system::error_code ec;
  const auto value = boost::json::parse(lines, ec);
  if (ec || !value.is_array())
    return {};

  for (const auto &line : value.get_array()) {
    if (!line.is_array())
      return {};
    GeoPoint previous = GeoPoint::Invalid();
    for (const auto &position : line.get_array()) {
      const auto *p = position.if_array();
      if (p == nullptr || p->size() < 2 || !p->at(0).is_number() ||
          !p->at(1).is_number())
        return {};
      const GeoPoint point{Angle::Degrees(p->at(0).to_number<double>()),
                           Angle::Degrees(p->at(1).to_number<double>())};
      if (!point.Check())
        return {};

      if (previous.IsValid()) {
        const double n = std::ceil(previous.Distance(point) / step);
        if (n > MAX_SAMPLES)
          return {};
        for (unsigned i = 1; i <= unsigned(std::max(n, 1.)); ++i)
          samples.push_back(previous.Interpolate(point, i / std::max(n, 1.)));
      } else
        samples.push_back(point);
      if (samples.size() > MAX_SAMPLES)
        return {};
      previous = point;
    }
  }
  return samples;
}

/** Whether @p bounds holds everything within @p margin metres of @p p. */
static bool
HoldsAround(const GeoBounds &bounds, const GeoPoint &p, double margin) noexcept
{
  /* a degree of latitude, metres */
  constexpr double METRES_PER_DEGREE = 6371000 * M_PI / 180;
  const double cos_latitude = std::max(p.latitude.cos(), 0.01);
  const Angle dlat = Angle::Degrees(margin / METRES_PER_DEGREE);
  const Angle dlon = Angle::Degrees(margin / METRES_PER_DEGREE / cos_latitude);
  return bounds.IsInside(GeoPoint{p.longitude - dlon, p.latitude - dlat}) &&
    bounds.IsInside(GeoPoint{p.longitude + dlon, p.latitude + dlat});
}

std::string
Coverage(std::string_view lines, double margin) noexcept
try {
  if (!(margin >= 0 && margin <= 1e6))
    return {};
  const auto samples = SampleLines(lines, std::max(margin, 1000.));
  if (samples.empty())
    return {};

  boost::json::object result;

  boost::json::value terrain = nullptr;
  if (data_components->terrain != nullptr) {
    RasterTerrain::Lease map{*data_components->terrain};
    if (map->IsDefined()) {
      const GeoBounds &b = map->GetBounds();
      boost::json::object t{
        {"bounds", {b.GetWest().Degrees(), b.GetSouth().Degrees(),
                    b.GetEast().Degrees(), b.GetNorth().Degrees()}},
      };
      for (const GeoPoint &p : samples) {
        if (!HoldsAround(b, p, margin)) {
          t["gap"] = {p.longitude.Degrees(), p.latitude.Degrees()};
          break;
        }
      }
      terrain = std::move(t);
    }
  }
  result["terrain"] = std::move(terrain);

  std::unordered_set<const AbstractAirspace *> airspaces;
  if (const auto *a = data_components->airspaces.get())
    for (const GeoPoint &p : samples)
      for (const auto &i : a->QueryWithinRange(p, margin))
        airspaces.insert(&i.GetAirspace());
  result["airspace"] = airspaces.size();

  std::unordered_set<unsigned> waypoints;
  unsigned landables = 0;
  if (const auto *w = data_components->waypoints.get())
    for (const GeoPoint &p : samples)
      w->VisitWithinRange(p, margin, [&](const WaypointPtr &waypoint){
        /* a square around the point: only those within the margin */
        if (waypoint->location.Distance(p) <= margin &&
            waypoints.insert(waypoint->id).second && waypoint->IsLandable())
          ++landables;
      });
  result["waypoints"] = waypoints.size();
  result["landables"] = landables;

  StringOutputStream os;
  Json::Serialize(os, result);
  return std::move(os).GetValue();
} catch (...) {
  return {};
}

} // namespace CoreFeatures
