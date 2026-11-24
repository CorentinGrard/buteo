// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

/*
 * The moving map of the headless core: what MainWindow and
 * GlueMapWindow do for the map in XCSoar's OpenGL builds, without the
 * window system.  The app owns the surface; the core main thread owns
 * the EGL context and draws (D6).
 */

#include "CoreMap.hpp"
#include "xcsoar_core.h"
#include "AndroidGraphics.hpp"
#include "Interface.hpp"
#include "Components.hpp"
#include "BackendComponents.hpp"
#include "DataComponents.hpp"
#include "NetComponents.hpp"
#include "Tracking/TrackingGlue.hpp"
#include "Observation/Database.hpp"
#include "Weather/NOAAGlue.hpp"
#include "CoreRasp.hpp"
#include "MapWindow/MapWindow.hpp"
#include "Look/MapLook.hpp"
#include "Look/TrafficLook.hpp"
#include "Look/GlobalFonts.hpp"
#include "Look/DefaultFonts.hpp"
#include "Screen/Layout.hpp"
#include "Screen/Debug.hpp"
#include "Hardware/DisplayDPI.hpp"
#include "ui/display/Display.hpp"
#include "ui/canvas/Canvas.hpp"
#include "ui/canvas/opengl/Init.hpp"
#include "ui/canvas/opengl/Globals.hpp"
#include "Topography/TopographyStore.hpp"
#include "Topography/TopographyFile.hpp"
#include "Topography/TopographyGlue.hpp"
#include "Computer/GlideComputer.hpp"
#include "Task/ProtectedTaskManager.hpp"
#include "Engine/Task/TaskManager.hpp"
#include "Engine/Task/Ordered/OrderedTask.hpp"
#include "Engine/Task/Ordered/Points/OrderedTaskPoint.hpp"
#include "Engine/Task/Ordered/Points/AATPoint.hpp"
#include "Engine/Task/Factory/AbstractTaskFactory.hpp"
#include "Engine/Task/ObservationZones/Boundary.hpp"
#include "Geo/GeoBounds.hpp"
#include "Projection/WindowProjection.hpp"
#include "Renderer/BackgroundRenderer.hpp"
#include "ui/canvas/BufferCanvas.hpp"
#include "ui/opengl/System.hpp"
#include "CoreTask.hpp"
#include "Terrain/RasterTerrain.hpp"
#include "UISettings.hpp"
#include "MapSettings.hpp"
#include "ui/canvas/PortableColor.hpp"
#include "Terrain/TerrainSettings.hpp"
#include "Profile/Profile.hpp"
#include "Profile/Current.hpp"
#include "Profile/Map.hpp"
#include "Profile/Keys.hpp"
#include "NMEA/Derived.hpp"
#include "MapWindow/Items/MapItem.hpp"
#include "MapWindow/Items/List.hpp"
#include "MapWindow/Items/Builder.hpp"
#include "Formatter/AirspaceFormatter.hpp"
#include "Engine/Airspace/AbstractAirspace.hpp"
#include "Engine/Waypoint/Waypoint.hpp"
#include "Engine/Airspace/Airspaces.hpp"
#include "Engine/Waypoint/Waypoints.hpp"
#include "Airspace/ProtectedAirspaceWarningManager.hpp"
#include "json/Serialize.hxx"
#include "io/StringOutputStream.hxx"

#include <boost/json.hpp>
#include "LogFile.hpp"
#include "thread/Debug.hpp"
#include "ui/event/Timer.hpp"
#include "ui/event/PeriodicTimer.hpp"

#include <android/native_window.h>
#include <EGL/egl.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <memory>
#include <optional>
#include <vector>

namespace {

/** MapWindow without a parent window: the core paints it itself. */
class CoreMapWindow final : public MapWindow {
public:
  CoreMapWindow(const MapLook &look, const TrafficLook &traffic_look) noexcept
    :MapWindow(look, traffic_look) {
    /* the app's orientation button shows where north is; XCSoar's
       compass would sit under the status bar */
    compass_visible = false;
  }

  void Paint(Canvas &canvas) noexcept {
    /* like GlueMapWindow::OnPaintBuffer(): with OpenGL, the main
       thread is the draw thread while painting */
    EnterDrawThread();
    /* nothing to load before the map has a position */
    if (visible_projection.IsValid())
      UpdateData();
    OnPaintBuffer(canvas);
    LeaveDrawThread();
  }

  MapWindowProjection &Projection() noexcept {
    return visible_projection;
  }

  /** Not following the glider: upstream then draws every area's
      target, not only the next one's (GlueMapWindow pans likewise). */
  void SetFollowing(bool following) noexcept {
    follow_mode = following ? FOLLOW_SELF : FOLLOW_PAN;
  }

  using MapWindowBlackboard::ReadUIState;

private:
  /** Load the terrain tiles and topography of the visible area. */
  void UpdateData() noexcept {
    UpdateTerrain();
    UpdateTopography(256);
  }
};

void ZoomFrame() noexcept;

/*
 * Buteo's map look, the "Design rework" canvas's (D19): airspace as a
 * thin outline with a light band inside, filled orange with a warning
 * ahead and red inside; landables green when reachable, orange when
 * not; the task in the app's magenta.  The app's own
 * colours (ui/theme), over XCSoar's looks; colours from the pilot's
 * profile stay, XCSoar's pure primaries take the design's tones.
 *
 * The canvas fills each airspace lightly; over France's stacked
 * airspace the fills added up to a grey wash over the whole map, so the
 * light tint is a band along the inside of the outline (XCSoar's
 * padding, narrower) and only a warned airspace is filled.
 */
constexpr RGB8Color LOOK_BLUE{0x1f, 0x5b, 0xd6};
constexpr RGB8Color LOOK_RED{0xb4, 0x2a, 0x30};
constexpr RGB8Color LOOK_ORANGE{0xc2, 0x41, 0x0c};
constexpr RGB8Color LOOK_GREEN{0x0b, 0x7a, 0x5c};
constexpr RGB8Color LOOK_TASK{0xb5, 0x17, 0x9e};
/* the task's colour halfway to white: legs and zones not flown to */
constexpr RGB8Color LOOK_TASK_LIGHT{0xda, 0x8b, 0xce};
/* XCSoar's magenta classes ("other", unclassified), apart from the task */
constexpr RGB8Color LOOK_VIOLET{0x6d, 0x3f, 0xa0};

RGB8Color
ToneAirspaceColor(RGB8Color color) noexcept
{
  if (color == RGB8_RED)
    return LOOK_RED;
  if (color == RGB8_BLUE)
    return LOOK_BLUE;
  if (color == RGB8_MAGENTA)
    return LOOK_VIOLET;
  return color;
}

/** The settings behind the look, which have no page in the app; before
    the looks are created from them.  Not saved in the profile. */
void
ApplyButeoSettings(MapSettings &settings) noexcept
{
  auto &airspace = settings.airspace;
  /* the renderer that colours warned airspaces (XCSoar's "fill all"
     draws them all alike) */
  if (airspace.fill_mode == AirspaceRendererSettings::FillMode::ALL)
    airspace.fill_mode = AirspaceRendererSettings::FillMode::DEFAULT;
  for (auto &c : airspace.classes) {
    c.border_color = ToneAirspaceColor(c.border_color);
    c.fill_color = ToneAirspaceColor(c.fill_color);
  }
  /* AAT areas: yellow in XCSoar, the task's colour here */
  airspace.classes[AATASK].SetColors(LOOK_TASK);

  settings.waypoint.landable_style =
    WaypointRendererSettings::LandableStyle::TRAFFIC_LIGHTS;
  settings.waypoint.vector_landable_rendering = true;

  /* labels where they help: the task and the places to land (XCSoar
     labels every turn point, each with its file's short code) */
  if (settings.waypoint.label_selection ==
      WaypointRendererSettings::LabelSelection::ALL)
    settings.waypoint.label_selection =
      WaypointRendererSettings::LabelSelection::TASK_AND_LANDABLE;

  /* the glider is the first thing to find on the map: XCSoar's simple
     symbol is drawn with pens as thin as the roads' */
  if (settings.aircraft_symbol == AircraftSymbol::SIMPLE)
    settings.aircraft_symbol = AircraftSymbol::SIMPLE_LARGE;
}

/*
 * XCSoar's map files give every topography layer the map generator's
 * colours (topology.tpl): red roads, which read as airspace, and
 * yellow towns.  Buteo draws them in warm greys, thinner, under the
 * airspace, the task and the glider; water stays blue.
 */
constexpr BGRA8Color LOOK_ROAD_MAJOR{0x7d, 0x76, 0x6c, 0xd0};
constexpr BGRA8Color LOOK_ROAD{0x9a, 0x93, 0x88, 0xb0};
constexpr BGRA8Color LOOK_RAILWAY{0x6b, 0x67, 0x60, 0xa0};
constexpr BGRA8Color LOOK_TOWN{0xc9, 0xc0, 0xae, 0x90};

constexpr bool
IsRGB(BGRA8Color c, uint8_t r, uint8_t g, uint8_t b) noexcept
{
  return c.Red() == r && c.Green() == g && c.Blue() == b;
}

void
ToneTopography(TopographyStore &store) noexcept
{
  for (auto &file : store) {
    const auto color = file.GetColor();
    const unsigned width = file.GetPenWidth();
    if (IsRGB(color, 240, 64, 64))
      file.SetStyle(width >= 3 ? LOOK_ROAD_MAJOR : LOOK_ROAD,
                    std::max(width, 2u) - 1);
    else if (IsRGB(color, 223, 223, 0))
      file.SetStyle(LOOK_TOWN, width);
    else if (IsRGB(color, 64, 64, 64))
      file.SetStyle(LOOK_RAILWAY, width);
  }
}

void
LoadButeoTopography(TopographyStore &store) noexcept
{
  LoadConfiguredTopography(store);
  ToneTopography(store);
}

/**
 * The trail in the app's vario colours: sink neutral grey, lift green,
 * darker the stronger (XCSoar's default goes from blue in sink to
 * yellow and red in lift, and red means a warning here).
 */
void
ApplyButeoTrail(TrailLook &trail) noexcept
{
  constexpr unsigned n = TrailLook::NUMSNAILCOLORS;
  constexpr RGB8Color sink_strong{0x6b, 0x70, 0x6d}, sink_weak{0xb5, 0xb9, 0xb6};
  constexpr RGB8Color lift_weak{0x8f, 0xd6, 0xb3}, lift_strong{0x05, 0x5c, 0x44};

  const auto mix = [](RGB8Color a, RGB8Color b, double t) noexcept {
    const auto c = [t](uint8_t x, uint8_t y) noexcept {
      return uint8_t(x + (y - x) * t + 0.5);
    };
    return Color(c(a.Red(), b.Red()), c(a.Green(), b.Green()),
                 c(a.Blue(), b.Blue()));
  };

  const unsigned width = Layout::ScalePenWidth(2);
  for (unsigned i = 0; i < n; ++i) {
    const Color color = i < n / 2
      ? mix(sink_strong, sink_weak, double(i) / (n / 2 - 1))
      : mix(lift_weak, lift_strong, double(i - n / 2) / (n - 1 - n / 2));
    trail.trail_brushes[i].Create(color);
    trail.trail_pens[i].Create(width, color);
    trail.scaled_trail_pens[i].Create(trail.trail_widths[i], color);
  }
}

void
ApplyButeoLook(MapLook &look) noexcept
{
  auto &airspace = look.airspace;
  /* a band a third of XCSoar's, lighter */
  airspace.thick_pen.Create(Layout::ScalePenWidth(4), COLOR_BLACK);
  airspace.fill_alpha = 0x30;
  airspace.warning_colors = true;
  airspace.warning_color = Color(LOOK_ORANGE);
  airspace.inside_color = Color(LOOK_RED);
  airspace.warning_pen.Create(Layout::ScalePenWidth(3), airspace.warning_color);
  airspace.inside_pen.Create(Layout::ScalePenWidth(3), airspace.inside_color);

  /* reachable or not: orange is caution, as everywhere in the app */
  auto &waypoint = look.waypoint;
  waypoint.reachable_brush.Create(Color(LOOK_GREEN));
  waypoint.orange_brush.Create(Color(LOOK_ORANGE));
  waypoint.terrain_unreachable_brush.Create(Color(LOOK_ORANGE));
  waypoint.unreachable_brush.Create(Color(LOOK_ORANGE));
  /* the canvas rings them in white, but XCSoar draws the runway with
     the same pen: ring and runway ran into one white blot */
  waypoint.landable_pen.Create(Layout::ScalePenWidth(1), Color(0x0e, 0x12, 0x10));

  /* the line to the target solid, the legs dashed, as the canvas */
  auto &task = look.task;
  const Color task_color{LOOK_TASK}, light{LOOK_TASK_LIGHT};
  task.oz_current_pen.Create(Pen::SOLID, Layout::ScalePenWidth(3), task_color);
  task.oz_active_pen.Create(Pen::SOLID, Layout::ScalePenWidth(2), task_color);
  task.oz_inactive_pen.Create(Pen::SOLID, Layout::ScalePenWidth(1), light);
  task.leg_active_pen.Create(Pen::DASH2, Layout::ScalePenWidth(2), task_color);
  task.leg_inactive_pen.Create(Pen::DASH2, Layout::ScalePenWidth(2), light);
  task.arrow_active_pen.Create(Layout::ScalePenWidth(2), task_color);
  task.arrow_inactive_pen.Create(Layout::ScalePenWidth(1), light);
  task.bearing_pen.Create(Layout::ScalePenWidth(3), task_color);
  task.isoline_pen.Create(Pen::DASH2, Layout::ScalePenWidth(1), task_color);

  ApplyButeoTrail(look.trail);
}

/**
 * The edited task as the map draws it (XCS_MAP_MODE_TASK): in a task
 * manager of its own, so the active task keeps guiding the glider.
 */
struct TaskPreview {
  TaskManager manager;
  ProtectedTaskManager protected_manager;

  TaskPreview(const TaskBehaviour &behaviour, const Waypoints &waypoints) noexcept
    :manager(behaviour, waypoints), protected_manager(manager, behaviour) {}

  /** A copy of the edited task, its last point a finish as on "Done"
      (else a half-built task is not valid and nothing is drawn). */
  void Update(const OrderedTask &edited) noexcept {
    auto copy = edited.Clone(CommonInterface::GetComputerSettings().task);
    copy->GetFactory().CheckAddFinish();
    copy->UpdateStatsGeometry();
    ProtectedTaskManager::ExclusiveLease lease{protected_manager};
    lease->Commit(*copy);
  }
};

/** Created on the first Attach(), kept until the process exits. */
struct Graphics {
  UI::Display display{EGL_DEFAULT_DISPLAY};

  /* new data is drawn at most this often */
  static constexpr auto REDRAW_INTERVAL = std::chrono::milliseconds{250};
  UI::Timer redraw_timer{[]{ CoreMap::Render(); }};

  /* a zoom button's step is drawn over a few frames */
  static constexpr auto ZOOM_FRAME = std::chrono::milliseconds{16};
  UI::PeriodicTimer zoom_timer{ZoomFrame};

  MapLook map_look;
  TrafficLook traffic_look;
  std::unique_ptr<CoreMapWindow> map;

  /* xcs_map_mode, and the edited task in XCS_MAP_MODE_TASK */
  unsigned mode = XCS_MAP_MODE_FLIGHT;
  std::unique_ptr<TaskPreview> task_preview;

  Graphics(PixelSize size) {
    ScreenInitialized();

    /* before the looks load their icons into textures */
    CoreGraphics::SetTextureNonPowerOfTwo(OpenGL::texture_non_power_of_two);

    const auto &ui = CommonInterface::GetUISettings();
    Layout::Initialise(display, size, ui.GetPercentScale(), ui.custom_dpi);
    Fonts::Initialize();
    ApplyButeoSettings(CommonInterface::SetMapSettings());
    map_look.Initialise(ui.map, Fonts::map, Fonts::map_bold);
    ApplyButeoLook(map_look);
    traffic_look.Initialise(Fonts::map);

    map = std::make_unique<CoreMapWindow>(map_look, traffic_look);
    WindowStyle style;
    style.Hide();
    map->Create(nullptr, PixelRect{size}, style);
    ConnectData();
  }

  ~Graphics() noexcept {
    map.reset();
    task_preview.reset();
    Fonts::Deinitialize();
    ScreenDeinitialized();
  }

  /** Like Startup() does for its GlueMapWindow. */
  void ConnectData() noexcept {
    auto &data = *data_components;
    auto &backend = *backend_components;

    if (data.topography == nullptr) {
      data.topography = std::make_unique<TopographyStore>();
      LoadButeoTopography(*data.topography);
    }

    map->SetWaypoints(data.waypoints.get());
    map->SetTask(mode == XCS_MAP_MODE_TASK && task_preview
                 ? &task_preview->protected_manager
                 : backend.protected_task_manager.get());
    map->SetRoutePlanner(&backend.glide_computer->GetProtectedRoutePlanner());
    map->SetGlideComputer(backend.glide_computer.get());
    map->SetAirspaces(data.airspaces.get());
    map->SetTopography(data.topography.get());
    map->SetTerrain(data.terrain.get());

    map->SetNOAAStore(noaa_store);
    map->SetRasp(CoreRasp::Get());

    /* thermals and waves: our own, the cloud's, SkyLines' and the
       thermal info map's, in one list (the cloud's traffic comes with
       the FLARM traffic, merged by the merge thread) */
    map->SetObservations(data.observations.get());

    if (!CommonInterface::Basic().location_available)
      CentreWithoutFix();
  }

  /**
   * Until the GPS has a fix: the home waypoint, else the middle of the
   * map file (Startup() only knows home).
   */
  void CentreWithoutFix() noexcept {
    const auto &settings = CommonInterface::GetComputerSettings();
    if (settings.poi.home_location_available)
      map->SetLocation(settings.poi.home_location);
    else if (data_components->terrain != nullptr)
      map->SetLocation(data_components->terrain->GetTerrainCenter());
  }
};

Graphics *graphics;
ANativeWindow *window;
EGLSurface surface = EGL_NO_SURFACE;
PixelSize size;
PixelPoint aircraft_position;

/* the map follows the aircraft until the pilot pans it */
bool follow = true;

/* what Frame() shows, again when the free part of the map changes
   (the task panel replaces the tiles), until the pilot moves the map */
struct Framed {
  GeoPoint center;
  double radius;
};
std::optional<Framed> framed;

/** A zoom button's step in progress: map scales, logarithmic. */
struct ZoomAnimation {
  double from, to;
  std::chrono::steady_clock::time_point start;
};

std::optional<ZoomAnimation> zoom;

constexpr auto ZOOM_DURATION = std::chrono::milliseconds{200};

/* for the circling zoom: the flight mode of the last frame */
bool was_circling = false;

/* what the last frame showed, so new data redraws only when it
   changes the map (sensors publish many times per second) */
Validity drawn_fix;
Angle drawn_angle = Angle::Zero();
std::chrono::steady_clock::time_point drawn_time;

/* a frame of this surface could not be shown (logged once per surface:
   the map then stays on its last frame) */
bool swap_failed = false;

/* clock-driven things (the trail fading, warnings) stay current */
constexpr auto IDLE_REDRAW_INTERVAL = std::chrono::seconds{5};

/**
 * The screen angle, like GlueMapWindow::UpdateScreenAngle() without
 * its pages and two-finger twist.
 */
Angle
ScreenAngle(MapOrientation orientation, const NMEAInfo &basic,
            const DerivedInfo &calculated) noexcept
{
  switch (orientation) {
  case MapOrientation::NORTH_UP:
    return Angle::Zero();

  case MapOrientation::TARGET_UP:
    if (calculated.task_stats.current_leg.vector_remaining.IsValid())
      return calculated.task_stats.current_leg.vector_remaining.bearing;
    break;

  case MapOrientation::HEADING_UP:
    return basic.attitude.heading_available
      ? basic.attitude.heading
      : Angle::Zero();

  case MapOrientation::WIND_UP:
    if (calculated.wind_available && calculated.wind.norm >= 0.5)
      return calculated.wind.bearing;
    break;

  case MapOrientation::TRACK_UP:
    break;
  }

  return basic.track_available ? basic.track : Angle::Zero();
}

/**
 * Keep separate scales for cruise and circling when the profile asks
 * for it (GlueMapWindow::SwitchZoomClimb()).
 */
void
SwitchZoomClimb(MapWindowProjection &projection, bool circling) noexcept
{
  auto &settings = CommonInterface::SetMapSettings();
  if (!settings.circle_zoom_enabled)
    return;

  if (circling) {
    settings.cruise_scale = projection.GetScale();
    projection.SetScale(settings.circling_scale);
  } else {
    settings.circling_scale = projection.GetScale();
    projection.SetScale(settings.cruise_scale);
  }
}

void
ReleaseSurface() noexcept
{
  if (surface != EGL_NO_SURFACE) {
    graphics->display.MakeCurrent(EGL_NO_SURFACE);
    graphics->display.DestroySurface(surface);
    surface = EGL_NO_SURFACE;
  }

  if (window != nullptr) {
    ANativeWindow_release(window);
    window = nullptr;
  }
}

/** Draw the next frame of the zoom animation (eased out). */
void
ZoomFrame() noexcept
{
  if (graphics == nullptr)
    return;

  if (!zoom) {
    graphics->zoom_timer.Cancel();
    return;
  }

  auto &projection = graphics->map->Projection();
  const double t = std::min(
    std::chrono::duration<double>(std::chrono::steady_clock::now() -
                                  zoom->start) / ZOOM_DURATION, 1.);
  if (t >= 1) {
    /* end exactly on the step of the scale list */
    projection.SetMapScale(zoom->to);
    zoom.reset();
    graphics->zoom_timer.Cancel();
  } else {
    const double eased = 1 - std::pow(1 - t, 3);
    projection.SetFreeMapScale(
      std::exp(std::log(zoom->from) +
               (std::log(zoom->to) - std::log(zoom->from)) * eased));
  }

  CoreMap::Render();
}

/**
 * Show a circle around @p center in the part of the map the cards
 * leave free (its middle is aircraft_position), north up; the map
 * stops following the glider.
 */
void
Frame(GeoPoint center, double radius) noexcept
{
  auto &projection = graphics->map->Projection();
  const int free_height = 2 * (int(size.height) - aircraft_position.y);
  const int side = std::max(std::min(int(size.width), free_height), 1);
  zoom.reset();
  projection.SetScale(side / (2.2 * std::max(radius, 1000.)));
  projection.SetGeoLocation(center);
  follow = false;
  framed = Framed{center, radius};
}

/** Frame() again for a new size or glider position. */
void
Reframe() noexcept
{
  if (framed && graphics != nullptr && graphics->mode != XCS_MAP_MODE_FLIGHT)
    Frame(framed->center, framed->radius);
}

/** The circle around a task point and its zone. */
double
ZoneRadius(const OrderedTaskPoint &tp) noexcept
{
  double radius = 0;
  for (const GeoPoint &p : tp.GetBoundary())
    radius = std::max(radius, tp.GetLocation().Distance(p));
  return radius;
}

/** Frame the whole task with its zones; false if it has no point. */
bool
FrameTask(const OrderedTask &task) noexcept
{
  if (task.TaskSize() == 0)
    return false;

  GeoBounds bounds{task.GetPoint(0).GetLocation()};
  for (unsigned i = 0; i < task.TaskSize(); ++i) {
    const auto &tp = task.GetPoint(i);
    bounds.Extend(tp.GetLocation());
    for (const GeoPoint &p : tp.GetBoundary())
      bounds.Extend(p);
  }

  const GeoPoint center = bounds.GetCenter();
  Frame(center, std::max(center.Distance(bounds.GetNorthWest()),
                         center.Distance(bounds.GetSouthEast())));
  return true;
}

} // namespace

bool
CoreMap::Attach(ANativeWindow *_window, unsigned width, unsigned height,
                unsigned dpi) noexcept
try {
  size = {width, height};
  if (aircraft_position == PixelPoint{})
    aircraft_position = PixelRect{size}.GetCenter();

  if (graphics == nullptr) {
    Display::ProvideDPI(dpi, dpi);
    graphics = new Graphics(size);
  }

  ReleaseSurface();
  ANativeWindow_acquire(_window);
  window = _window;
  surface = graphics->display.CreateWindowSurface(window);
  graphics->display.MakeCurrent(surface);
  OpenGL::SetupViewport({width, height});

  graphics->map->Resize(size);
  Reframe();
  swap_failed = false;
  LogFormat("Map attached %ux%u", width, height);
  Render();
  return true;
} catch (...) {
  LogError(std::current_exception(), "Map attach failed");
  return false;
}

void
CoreMap::Detach() noexcept
{
  if (graphics == nullptr)
    return;

  if (IsAttached())
    LogString("Map detached");
  ReleaseSurface();
}

void
CoreMap::Deinitialise() noexcept
{
  if (graphics == nullptr)
    return;

  ReleaseSurface();
  delete graphics;
  graphics = nullptr;
  aircraft_position = {};
  follow = true;
  framed.reset();
  zoom.reset();
  was_circling = false;
}

bool
CoreMap::IsAttached() noexcept
{
  return surface != EGL_NO_SURFACE;
}

void
CoreMap::SetAircraftPosition(int x, int y) noexcept
{
  aircraft_position = {x, y};
  Reframe();
  Render();
}

void
CoreMap::Zoom(int steps) noexcept
{
  if (graphics == nullptr)
    return;

  auto &projection = graphics->map->Projection();
  const double current = projection.GetMapScale();
  /* a tap during the animation steps on from where it is going */
  const double target = projection.StepMapScale(zoom ? zoom->to : current,
                                                steps);
  if (!(current > 0) || !(target > 0))
    return;

  framed.reset();
  zoom = ZoomAnimation{current, target, std::chrono::steady_clock::now()};
  graphics->zoom_timer.Schedule(Graphics::ZOOM_FRAME);
  ZoomFrame();
}

void
CoreMap::Pan(double dx, double dy) noexcept
{
  if (graphics == nullptr)
    return;

  auto &projection = graphics->map->Projection();
  if (!projection.IsValid())
    return;

  /* the point that was under the screen origin moves with the finger */
  const PixelPoint origin = projection.GetScreenOrigin();
  projection.SetGeoLocation(
    projection.ScreenToGeo({origin.x - int(dx), origin.y - int(dy)}));
  follow = false;
  framed.reset();
  Render();
}

void
CoreMap::Scale(double factor) noexcept
{
  if (graphics == nullptr || !(factor > 0))
    return;

  /* the fingers take over from a zoom button */
  zoom.reset();
  framed.reset();
  auto &projection = graphics->map->Projection();
  projection.SetFreeMapScale(projection.GetMapScale() / factor);
  Render();
}

void
CoreMap::Follow() noexcept
{
  follow = true;
  framed.reset();
  /* without a fix there is no aircraft to follow: back to where the
     map started */
  if (graphics != nullptr && !CommonInterface::Basic().location_available)
    graphics->CentreWithoutFix();
  Render();
}

void
CoreMap::SetOrientation(unsigned orientation) noexcept
{
  if (orientation > unsigned(MapOrientation::WIND_UP))
    return;

  auto &settings = CommonInterface::SetMapSettings();
  settings.cruise_orientation = settings.circling_orientation =
    MapOrientation(orientation);
  Profile::map.Set(ProfileKeys::OrientationCruise, orientation);
  Profile::map.Set(ProfileKeys::OrientationCircling, orientation);
  Profile::Save();
  Render();
}

unsigned
CoreMap::GetOrientation() noexcept
{
  return unsigned(CommonInterface::GetMapSettings().cruise_orientation);
}

static boost::json::object
Describe(const MapItem &item) noexcept
{
  boost::json::object o;
  switch (item.type) {
  case MapItem::Type::LOCATION: {
    const auto &i = static_cast<const LocationMapItem &>(item);
    o["type"] = "location";
    if (i.HasElevation())
      o["elevation"] = i.elevation;
    break;
  }

  case MapItem::Type::SELF:
    o["type"] = "self";
    break;

  case MapItem::Type::TASK_OZ: {
    const auto &i = static_cast<const TaskOZMapItem &>(item);
    o["type"] = "task";
    o["name"] = i.waypoint->name.c_str();
    break;
  }

  case MapItem::Type::AIRSPACE: {
    const auto &airspace =
      *static_cast<const AirspaceMapItem &>(item).airspace;
    o["type"] = "airspace";
    o["name"] = airspace.GetName();
    o["class"] = AirspaceFormatter::GetClassOrType(airspace);
    char buffer[64];
    AirspaceFormatter::FormatAltitudeShort(buffer, airspace.GetTop());
    o["top"] = buffer;
    AirspaceFormatter::FormatAltitudeShort(buffer, airspace.GetBase());
    o["base"] = buffer;
    break;
  }

  case MapItem::Type::THERMAL:
    o["type"] = "thermal";
    break;

  case MapItem::Type::WAYPOINT: {
    const auto &waypoint =
      *static_cast<const WaypointMapItem &>(item).waypoint;
    o["type"] = "waypoint";
    o["id"] = waypoint.id;
    o["name"] = waypoint.name.c_str();
    o["landable"] = waypoint.IsLandable();
    if (waypoint.HasElevation())
      o["elevation"] = waypoint.elevation;
    char buffer[32];
    if (waypoint.radio_frequency.Format(buffer, sizeof(buffer)) != nullptr)
      o["frequency"] = buffer;
    if (!waypoint.comment.empty())
      o["detail"] = waypoint.comment.c_str();
    break;
  }

  case MapItem::Type::TRAFFIC:
    o["type"] = "traffic";
    break;

  default:
    o["type"] = "other";
    break;
  }
  return o;
}

std::string
CoreMap::ItemsAt(int x, int y) noexcept
{
  boost::json::array items;

  if (graphics != nullptr && graphics->map->Projection().IsValid()) {
    const auto &projection = graphics->map->Projection();
    const auto location = projection.ScreenToGeo({x, y});
    const auto range =
      projection.DistancePixelsToMeters(Layout::GetHitRadius());

    const auto &basic = CommonInterface::Basic();
    const auto &calculated = CommonInterface::Calculated();
    const auto &computer_settings = CommonInterface::GetComputerSettings();
    const auto &settings = CommonInterface::GetMapSettings();
    auto &data = *data_components;
    auto &backend = *backend_components;

    /* the same sources as GlueMapWindow::ShowMapItems() */
    MapItemList list;
    MapItemListBuilder builder(list, location, range);
    if (settings.item_list.add_location)
      builder.AddLocation(basic, data.terrain.get());
    if (basic.location_available)
      builder.AddSelfIfNear(basic.location, basic.attitude.heading);
    builder.AddTaskOZs(*backend.protected_task_manager);
    builder.AddVisibleAirspace(*data.airspaces,
                               &backend.glide_computer->GetAirspaceWarnings(),
                               computer_settings.airspace, settings.airspace,
                               basic, calculated);
    if (projection.GetMapScale() <= 4000 && data.observations != nullptr)
      builder.AddThermals(*data.observations, basic, calculated,
                          computer_settings);
    builder.AddWaypoints(*data.waypoints,
                         &backend.glide_computer->GetProtectedRoutePlanner(),
                         basic, calculated, computer_settings);
    builder.AddTraffic(basic.flarm.traffic);
    list.Sort();

    for (const auto *item : list)
      items.emplace_back(Describe(*item));
  }

  StringOutputStream os;
  Json::Serialize(os, items);
  return std::move(os).GetValue();
}

bool
CoreMap::SetOption(unsigned option, int value) noexcept
{
  auto &settings = CommonInterface::SetMapSettings();
  auto &profile = Profile::map;

  switch (option) {
  case XCS_MAP_TERRAIN:
    settings.terrain.enable = value != 0;
    profile.Set(ProfileKeys::DrawTerrain, settings.terrain.enable);
    break;

  case XCS_MAP_TERRAIN_RAMP:
    if (value < 0 || unsigned(value) >= TerrainRendererSettings::NUM_RAMPS)
      return false;
    settings.terrain.ramp = value;
    profile.Set(ProfileKeys::TerrainRamp, unsigned(value));
    break;

  case XCS_MAP_TOPOGRAPHY:
    settings.topography_enabled = value != 0;
    profile.Set(ProfileKeys::DrawTopography, settings.topography_enabled);
    break;

  case XCS_MAP_TRAIL:
    if (value < 0 || value > int(TrailSettings::Length::FULL))
      return false;
    settings.trail.length = TrailSettings::Length(value);
    profile.Set(ProfileKeys::SnailTrail, unsigned(value));
    break;

  case XCS_MAP_CIRCLING_ZOOM:
    settings.circle_zoom_enabled = value != 0;
    profile.Set(ProfileKeys::CircleZoom, settings.circle_zoom_enabled);
    break;

  case XCS_MAP_THERMAL_ASSISTANT: {
    /* the app draws it around the glider; upstream's default place
       when on */
    using Position = UISettings::ThermalAssistantPosition;
    auto &position = CommonInterface::SetUISettings().thermal_assistant_position;
    if ((value != 0) != (position != Position::OFF))
      position = value != 0 ? Position::BOTTOM_LEFT_AVOID_IB : Position::OFF;
    profile.SetEnum(ProfileKeys::TAPosition, position);
    break;
  }

  default:
    return false;
  }

  Profile::Save();
  if (graphics != nullptr)
    graphics->map->FlushCaches();
  Render();
  return true;
}

bool
CoreMap::GetOption(unsigned option, int &value) noexcept
{
  const auto &settings = CommonInterface::GetMapSettings();

  switch (option) {
  case XCS_MAP_TERRAIN:
    value = settings.terrain.enable;
    return true;

  case XCS_MAP_TERRAIN_RAMP:
    value = settings.terrain.ramp;
    return true;

  case XCS_MAP_TOPOGRAPHY:
    value = settings.topography_enabled;
    return true;

  case XCS_MAP_TRAIL:
    value = int(settings.trail.length);
    return true;

  case XCS_MAP_CIRCLING_ZOOM:
    value = settings.circle_zoom_enabled;
    return true;

  case XCS_MAP_THERMAL_ASSISTANT:
    value = CommonInterface::GetUISettings().thermal_assistant_position !=
      UISettings::ThermalAssistantPosition::OFF;
    return true;
  }

  return false;
}

bool
CoreMap::SetMode(unsigned mode, unsigned index) noexcept
{
  if (graphics == nullptr || backend_components == nullptr)
    return false;

  auto &map = *graphics->map;
  auto &active = *backend_components->protected_task_manager;

  switch (mode) {
  case XCS_MAP_MODE_FLIGHT:
    map.SetTask(&active);
    graphics->task_preview.reset();
    follow = true;
    framed.reset();
    break;

  case XCS_MAP_MODE_TASK: {
    const OrderedTask *edited = CoreTask::GetEdited();
    if (edited == nullptr)
      return false;

    auto &preview = graphics->task_preview;
    if (!preview)
      preview = std::make_unique<TaskPreview>(
        CommonInterface::GetComputerSettings().task,
        *data_components->waypoints);
    preview->Update(*edited);
    map.SetTask(&preview->protected_manager);
    /* an empty task: where the map is, for the first points */
    if (!FrameTask(*edited))
      follow = false;
    break;
  }

  case XCS_MAP_MODE_TARGET: {
    GeoPoint location;
    double radius;
    {
      const ProtectedTaskManager::Lease lease{active};
      const OrderedTask &task = lease->GetOrderedTask();
      if (task.GetAATTaskPoint(index) == nullptr)
        return false;

      const OrderedTaskPoint &tp = task.GetPoint(index);
      location = tp.GetLocation();
      radius = ZoneRadius(tp);
    }

    map.SetTask(&active);
    graphics->task_preview.reset();
    Frame(location, radius);
    break;
  }

  default:
    return false;
  }

  graphics->mode = mode;
  Render();
  return true;
}

GeoPoint
CoreMap::LocationAt(int x, int y) noexcept
{
  if (graphics == nullptr || !graphics->map->Projection().IsValid())
    return GeoPoint::Invalid();

  return graphics->map->Projection().ScreenToGeo({x, y});
}

bool
CoreMap::TerrainImage(GeoPoint north_west, GeoPoint south_east,
                      unsigned width, unsigned height, uint8_t *rgba) noexcept
{
  /* the EGL context, current with the map's surface or without one
     (ReleaseSurface()) */
  if (graphics == nullptr || data_components == nullptr ||
      data_components->terrain == nullptr ||
      eglGetCurrentContext() == EGL_NO_CONTEXT)
    return false;

  const auto &terrain_settings = CommonInterface::GetMapSettings().terrain;
  if (!terrain_settings.enable)
    return false;

  /* the app's flat projection: the box's middle, as many pixels per
     metre north-south as the image is high */
  const PixelSize size{width, height};
  const GeoPoint center = north_west.Middle(south_east);
  const double height_m =
    GeoPoint(center.longitude, north_west.latitude)
    .DistanceS(GeoPoint(center.longitude, south_east.latitude));
  if (!(height_m > 0))
    return false;

  WindowProjection projection;
  projection.SetScreenSize(size);
  projection.SetScreenOrigin(PixelRect{size}.GetCenter());
  projection.SetGeoLocation(center);
  projection.SetScreenAngle(Angle::Zero());
  projection.SetScale(height / height_m);
  projection.UpdateScreenBounds();

  BufferCanvas canvas;
  canvas.Create(size);
  canvas.Begin();
  canvas.Clear(COLOR_WHITE);

  BackgroundRenderer background;
  background.SetTerrain(data_components->terrain.get());
  background.SetShadingAngle(projection, terrain_settings,
                             CommonInterface::Calculated());
  background.Draw(canvas, projection, terrain_settings);

  /* OpenGL reads from the bottom row up */
  std::vector<uint8_t> pixels(std::size_t(width) * height * 4);
  glPixelStorei(GL_PACK_ALIGNMENT, 1);
  glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels.data());
  canvas.End();

  const std::size_t row = std::size_t(width) * 4;
  for (unsigned y = 0; y < height; ++y)
    std::copy_n(pixels.data() + (height - 1 - y) * row, row, rgba + y * row);

  /* the map draws its next frame into its own surface again */
  Render();
  return true;
}

void
CoreMap::OnTaskChanged() noexcept
{
  if (graphics == nullptr)
    return;

  if (graphics->mode == XCS_MAP_MODE_TASK && graphics->task_preview)
    if (const OrderedTask *edited = CoreTask::GetEdited())
      graphics->task_preview->Update(*edited);

  Render();
}

/** The map's rotation for this data, as Render() sets it. */
static Angle
CurrentScreenAngle() noexcept
{
  if (graphics != nullptr && graphics->mode != XCS_MAP_MODE_FLIGHT)
    return Angle::Zero();

  const auto &calculated = CommonInterface::Calculated();
  const auto &settings = CommonInterface::GetMapSettings();
  return ScreenAngle(calculated.circling
                     ? settings.circling_orientation
                     : settings.cruise_orientation,
                     CommonInterface::Basic(), calculated);
}

void
CoreMap::Invalidate() noexcept
{
  if (!IsAttached())
    return;

  /* a new GPS fix, a turn of the map or a flight mode change; else
     only every few seconds */
  const auto &basic = CommonInterface::Basic();
  const bool changed =
    basic.location_available.Modified(drawn_fix) ||
    CommonInterface::Calculated().circling != was_circling ||
    (CurrentScreenAngle() - drawn_angle).AsDelta().Absolute() >= Angle::Degrees(1) ||
    std::chrono::steady_clock::now() - drawn_time >= IDLE_REDRAW_INTERVAL;

  if (changed)
    graphics->redraw_timer.SchedulePreserve(Graphics::REDRAW_INTERVAL);
}

void
CoreMap::OnDataChanged() noexcept
{
  if (graphics == nullptr)
    return;

  /* a new map file may bring new topography */
  graphics->map->SetTopography(nullptr);
  auto &topography = *data_components->topography;
  topography.Reset();
  LoadButeoTopography(topography);

  graphics->ConnectData();
  graphics->map->FlushCaches();
  Render();
}

void
CoreMap::Render() noexcept
{
  if (!IsAttached())
    return;

  graphics->redraw_timer.Cancel();

  auto &map = *graphics->map;
  const auto &basic = CommonInterface::Basic();

  map.ReadBlackboard(basic, CommonInterface::Calculated(),
                     CommonInterface::GetComputerSettings(),
                     CommonInterface::GetMapSettings());
  map.ReadUIState(CommonInterface::GetUIState());

  /* what GlueMapWindow's display modes do, minus its overlays: the
     app shows its own cards */
  const auto &calculated = CommonInterface::Calculated();
  const auto &settings = CommonInterface::GetMapSettings();
  auto &projection = map.Projection();

  if (calculated.circling != was_circling) {
    was_circling = calculated.circling;
    if (follow)
      SwitchZoomClimb(projection, was_circling);
  }

  /* a task or an area is laid out north up, as upstream's target map */
  const auto orientation = graphics->mode != XCS_MAP_MODE_FLIGHT
    ? MapOrientation::NORTH_UP
    : was_circling
    ? settings.circling_orientation
    : settings.cruise_orientation;
  projection.SetScreenAngle(ScreenAngle(orientation, basic, calculated));
  drawn_angle = projection.GetScreenAngle();
  drawn_fix = basic.location_available;
  drawn_time = std::chrono::steady_clock::now();

  /* looking ahead in cruise when the map turns with the glider: the
     aircraft sits glider_screen_position percent above the bottom of
     the free area (whose middle is aircraft_position) */
  PixelPoint origin = aircraft_position;
  if (follow && !was_circling && orientation != MapOrientation::NORTH_UP) {
    const int free_height = 2 * (int(size.height) - aircraft_position.y);
    origin.y = int(size.height) -
      free_height * settings.glider_screen_position / 100;
  }
  projection.SetScreenOrigin(origin);
  map.SetFollowing(follow);
  if (follow && basic.location_available)
    projection.SetGeoLocation(basic.location);
  map.UpdateScreenBounds();

  Canvas canvas{size};
  map.Paint(canvas);
  if (!graphics->display.SwapBuffers(surface) && !swap_failed) {
    swap_failed = true;
    LogFormat("Map frame not shown: EGL error 0x%x", unsigned(eglGetError()));
  }
}
