// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

#include "CoreTask.hpp"
#include "Interface.hpp"
#include "Components.hpp"
#include "BackendComponents.hpp"
#include "DataComponents.hpp"
#include "Protection.hpp"
#include "LocalPath.hpp"
#include "LogFile.hpp"
#include "Task/ProtectedTaskManager.hpp"
#include "Task/TaskStore.hpp"
#include "Task/LoadFile.hpp"
#include "Task/SaveFile.hpp"
#include "Task/TaskFile.hpp"
#include "Task/TypeStrings.hpp"
#include "Task/ValidationErrorStrings.hpp"
#include "Engine/Task/TaskManager.hpp"
#include "Engine/Task/Ordered/OrderedTask.hpp"
#include "Engine/Task/Ordered/Points/OrderedTaskPoint.hpp"
#include "Engine/Task/Ordered/Points/StartPoint.hpp"
#include "Engine/Task/Ordered/Points/IntermediatePoint.hpp"
#include "Engine/Task/Factory/AbstractTaskFactory.hpp"
#include "Engine/Task/ObservationZones/CylinderZone.hpp"
#include "Engine/Task/ObservationZones/LineSectorZone.hpp"
#include "Engine/Task/ObservationZones/AnnularSectorZone.hpp"
#include "Engine/Task/ObservationZones/KeyholeZone.hpp"
#include "Engine/Task/Ordered/Points/AATPoint.hpp"
#include "Engine/Task/Factory/TaskFactoryType.hpp"
#include "xcsoar_core.h"
#include "NMEA/Derived.hpp"
#include "Engine/Waypoint/Waypoints.hpp"
#include "json/Serialize.hxx"
#include "io/StringOutputStream.hxx"
#include "system/FileUtil.hpp"
#include "util/StringAPI.hxx"

#include <boost/json.hpp>

#include <cmath>
#include <memory>

/* the copy the pilot edits (main thread only) */
static std::unique_ptr<OrderedTask> edit_task;

static ProtectedTaskManager *
GetTaskManager() noexcept
{
  return backend_components != nullptr
    ? backend_components->protected_task_manager.get()
    : nullptr;
}

static const TaskBehaviour &
GetTaskBehaviour() noexcept
{
  return CommonInterface::GetComputerSettings().task;
}

/**
 * Show a change of the active task at once, like trigger_redraw() in
 * InputEventsTask.cpp: without a GPS fix nothing else recalculates.
 */
static void
Recalculate() noexcept
{
  if (!CommonInterface::Basic().location_available)
    ForceCalculation();
}

static bool
IsAreaTask(TaskFactoryType type) noexcept
{
  return type == TaskFactoryType::AAT || type == TaskFactoryType::MAT;
}

/** Lines report half their length, like XCSoar's line width setting. */
static const CylinderZone *
GetSizedZone(const OrderedTaskPoint &tp) noexcept
{
  return dynamic_cast<const CylinderZone *>(&tp.GetObservationZone());
}

/** Angles and ranges come back from upstream's geometry with rounding
    errors (45° as 45.000000000014): to the precision the pilot sets
    them with. */
static double
Round(double value, double step) noexcept
{
  return std::round(value / step) * step;
}

/** What the pilot may change of a zone, as upstream's zone editors
    (CreateObservationZoneEditWidget()); the others are fixed by the
    rules they belong to (FAI sectors, BGA zones, the MAT cylinder). */
struct ZoneEdit {
  bool radius = false, radials = false, angle = false, inner_radius = false;
};

static ZoneEdit
GetZoneEdit(const ObservationZonePoint &oz, TaskFactoryType task_type) noexcept
{
  using Shape = ObservationZone::Shape;
  const bool fai_general = task_type == TaskFactoryType::FAI_GENERAL;
  switch (oz.GetShape()) {
  case Shape::SECTOR:
    return {.radius = true, .radials = true};
  case Shape::ANNULAR_SECTOR:
    return {.radius = true, .radials = true, .inner_radius = true};
  case Shape::SYMMETRIC_SECTOR:
    return {.radius = true, .angle = true};
  case Shape::CUSTOM_KEYHOLE:
    return {.radius = true, .angle = true, .inner_radius = true};
  case Shape::LINE:
  case Shape::CYLINDER:
    return {.radius = !fai_general};
  default:
    return {};
  }
}

static const char *
GetShapeName(ObservationZone::Shape shape) noexcept
{
  using Shape = ObservationZone::Shape;
  switch (shape) {
  case Shape::LINE: return "line";
  case Shape::CYLINDER: return "cylinder";
  case Shape::MAT_CYLINDER: return "mat_cylinder";
  case Shape::SECTOR: return "sector";
  case Shape::FAI_SECTOR: return "fai_sector";
  case Shape::DAEC_KEYHOLE: return "daec_keyhole";
  case Shape::BGAFIXEDCOURSE: return "bga_fixed_course";
  case Shape::BGAENHANCEDOPTION: return "bga_enhanced_option";
  case Shape::BGA_START: return "bga_start";
  case Shape::ANNULAR_SECTOR: return "annular_sector";
  case Shape::SYMMETRIC_SECTOR: return "symmetric_sector";
  case Shape::CUSTOM_KEYHOLE: return "keyhole";
  }
  return "other";
}

/** The zone's shape and the sizes the pilot may change (degrees for
    angles). */
static boost::json::object
ZoneJson(const ObservationZonePoint &oz, TaskFactoryType task_type) noexcept
{
  boost::json::object o{{"shape", GetShapeName(oz.GetShape())}};
  const auto edit = GetZoneEdit(oz, task_type);
  if (edit.radius)
    if (const auto *c = dynamic_cast<const CylinderZone *>(&oz))
      o["radius"] = c->GetRadius();
  if (edit.radials)
    if (const auto *z = dynamic_cast<const SectorZone *>(&oz)) {
      o["start_radial"] = Round(z->GetStartRadial().AsBearing().Degrees(), 0.01);
      o["end_radial"] = Round(z->GetEndRadial().AsBearing().Degrees(), 0.01);
    }
  if (edit.angle)
    if (const auto *z = dynamic_cast<const SymmetricSectorZone *>(&oz))
      o["angle"] = Round(z->GetSectorAngle().Degrees(), 0.01);
  if (edit.inner_radius) {
    if (const auto *k = dynamic_cast<const KeyholeZone *>(&oz))
      o["inner_radius"] = k->GetInnerRadius();
    else if (const auto *a = dynamic_cast<const AnnularSectorZone *>(&oz))
      o["inner_radius"] = a->GetInnerRadius();
  }
  return o;
}

/** "agl" or "msl" (TaskPropertiesPanel's altitude_reference_list). */
static const char *
ReferenceName(AltitudeReference reference) noexcept
{
  return reference == AltitudeReference::MSL ? "msl" : "agl";
}

/** A start gate time in local minutes of the day; -1 for none. */
static int
LocalMinutes(RoughTime t) noexcept
{
  if (!t.IsValid())
    return -1;
  return int((t + CommonInterface::GetComputerSettings().utc_offset)
             .GetMinuteOfDay());
}

/** TaskPropertiesPanel's rows, in SI units (see xcs_task_rule). */
static boost::json::object
RulesJson(const OrderedTask &task) noexcept
{
  const auto &p = task.GetOrderedTaskSettings();
  const auto &start = p.start_constraints;
  const auto &finish = p.finish_constraints;
  return {
    {"start_open", LocalMinutes(start.open_time_span.GetRoughStart())},
    {"start_close", LocalMinutes(start.open_time_span.GetRoughEnd())},
    {"start_max_speed", start.max_speed},
    {"start_max_height", start.max_height},
    {"start_height_ref", ReferenceName(start.max_height_ref)},
    {"finish_min_height", finish.min_height},
    {"finish_height_ref", ReferenceName(finish.min_height_ref)},
    {"fai_start_finish", finish.fai_finish},
    {"fai_allowed", IsFai(task.GetFactoryType())},
    {"start_requires_arm", start.require_arm},
    {"start_score_exit", start.score_exit},
    {"navigate_nearest", p.navigate_nearest},
    {"pev_start_wait", start.pev_start_wait_time.count()},
    {"pev_start_window", start.pev_start_window.count()},
  };
}

static const char *
GetKind(const OrderedTaskPoint &tp) noexcept
{
  switch (tp.GetType()) {
  case TaskPointType::START:
    return "start";
  case TaskPointType::FINISH:
    return "finish";
  default:
    return "turn";
  }
}

static boost::json::array
ToJson(const LegalPointSet &types) noexcept
{
  boost::json::array a;
  for (unsigned i = 0; i < unsigned(TaskPointFactoryType::COUNT); ++i)
    if (const auto type = TaskPointFactoryType(i); types.Contains(type))
      a.emplace_back(boost::json::object{
        {"type", i},
        {"name", OrderedTaskPointName(type)},
      });
  return a;
}

static boost::json::object
ToJson(const OrderedTask &task) noexcept
{
  const auto &factory = task.GetFactory();
  const auto type = task.GetFactoryType();
  const auto errors = task.CheckTask();

  boost::json::array types;
  for (const auto t : task.GetFactoryTypes())
    types.emplace_back(boost::json::object{
      {"type", unsigned(t)},
      {"name", OrderedTaskFactoryName(t)},
    });

  boost::json::array points;
  for (unsigned i = 0; i < task.TaskSize(); ++i) {
    const OrderedTaskPoint &tp = task.GetPoint(i);
    const auto point_type = factory.GetType(tp);
    boost::json::object p{
      {"waypoint_id", tp.GetWaypoint().id},
      {"name", tp.GetWaypoint().name.c_str()},
      {"kind", GetKind(tp)},
      {"type", unsigned(point_type)},
      {"type_name", OrderedTaskPointName(point_type)},
      {"leg", i > 0 ? tp.GetNominalLegDistance() : 0.},
      {"types", ToJson(factory.GetValidTypes(i))},
    };
    if (const auto *zone = GetSizedZone(tp))
      p["radius"] = zone->GetRadius();
    p["zone"] = ZoneJson(tp.GetObservationZone(), type);
    points.emplace_back(std::move(p));
  }

  /* OptionalStartsDialog: other starts the pilot may take */
  boost::json::array optional_starts;
  for (const auto &tp : task.GetOptionalStartPoints())
    optional_starts.emplace_back(boost::json::object{
      {"waypoint_id", tp.GetWaypoint().id},
      {"name", tp.GetWaypoint().name.c_str()},
    });

  const auto &stats = task.GetStats();
  boost::json::object o{
    {"type", unsigned(type)},
    {"type_name", OrderedTaskFactoryName(type)},
    {"name", task.GetName().c_str()},
    {"valid", task.TaskSize() > 0 && !IsError(errors)},
    {"errors", task.TaskSize() > 0 ? getTaskValidationErrors(errors) : ""},
    {"distance", stats.distance_nominal},
    {"types", std::move(types)},
    {"points", std::move(points)},
    {"optional_starts", std::move(optional_starts)},
    {"rules", RulesJson(task)},
  };
  if (IsAreaTask(type)) {
    o["distance_min"] = stats.distance_min;
    o["distance_max"] = stats.distance_max;
    o["aat_min_time"] =
      task.GetOrderedTaskSettings().aat_min_time.count();
  }
  return o;
}

static std::string
Serialize(const boost::json::value &value) noexcept
{
  StringOutputStream os;
  Json::Serialize(os, value);
  return std::move(os).GetValue();
}

/**
 * TargetDialog's numbers: each area's target (range -1..1 from the
 * nearest to the farthest point of the area, radial in degrees from
 * the leg, upstream's "Distance" and "Radial"), and the estimated task
 * time against the minimum time.
 */
static void
AddTargets(boost::json::object &o, const OrderedTask &task,
           unsigned active) noexcept
{
  auto &points = o["points"].as_array();
  for (unsigned i = 0; i < task.TaskSize() && i < points.size(); ++i) {
    const AATPoint *ap = task.GetAATTaskPoint(i);
    if (ap == nullptr)
      continue;

    const auto rar = ap->GetTargetRangeRadial();
    const GeoPoint &target = ap->GetTargetLocation();
    points[i].as_object()["target"] = boost::json::object{
      /* TargetDialog shows whole percent and degrees; the way back
         through the task projection is not exact (50 % comes back as
         49.9 %) */
      {"range", Round(rar.range, 0.01)},
      {"radial", Round(rar.radial.Degrees(), 1)},
      {"locked", ap->IsTargetLocked()},
      /* upstream lets the pilot move only the targets ahead */
      {"movable", i >= active},
      {"latitude", target.latitude.Degrees()},
      {"longitude", target.longitude.Degrees()},
    };
  }

  const TaskStats &stats = CommonInterface::Calculated().ordered_task_stats;
  if (!stats.task_valid)
    return;

  const auto estimated = stats.GetEstimatedTotalTime();
  boost::json::object area{
    {"estimated_time", estimated.count()},
    {"time_delta", (estimated - FloatDuration{task.GetOrderedTaskSettings()
                                              .aat_min_time}).count()},
  };
  if (stats.total.remaining_effective.IsDefined())
    area["speed_remaining"] = stats.total.remaining_effective.GetSpeed();
  if (stats.total.travelled.IsDefined())
    area["speed_achieved"] = stats.total.travelled.GetSpeed();
  o["area"] = std::move(area);
}

std::string
CoreTask::Describe(bool edited) noexcept
{
  if (edited) {
    if (edit_task == nullptr)
      return {};

    auto o = ToJson(*edit_task);
    o["editing"] = true;
    return Serialize(o);
  }

  auto *task_manager = GetTaskManager();
  if (task_manager == nullptr)
    return {};

  const ProtectedTaskManager::Lease lease{*task_manager};
  const OrderedTask &task = lease->GetOrderedTask();
  auto o = ToJson(task);
  o["editing"] = false;
  const unsigned active = lease->GetActiveTaskPointIndex();
  o["active"] = active;
  if (IsAreaTask(task.GetFactoryType()))
    AddTargets(o, task, active);
  return Serialize(o);
}

const OrderedTask *
CoreTask::GetEdited() noexcept
{
  return edit_task.get();
}

void
CoreTask::BeginEdit() noexcept
{
  if (auto *task_manager = GetTaskManager())
    edit_task = task_manager->TaskClone();
}

void
CoreTask::CancelEdit() noexcept
{
  edit_task.reset();
}

bool
CoreTask::Commit() noexcept
{
  auto *task_manager = GetTaskManager();
  if (edit_task == nullptr || task_manager == nullptr)
    return false;

  /* TaskManagerDialog::Commit() */
  edit_task->GetFactory().CheckAddFinish();
  edit_task->UpdateStatsGeometry();

  if (edit_task->TaskSize() > 0 && IsError(edit_task->CheckTask()))
    return false;

  {
    /* this may change the waypoint database */
    const ScopeSuspendAllThreads suspend;
    edit_task->CheckDuplicateWaypoints(*data_components->waypoints);
    data_components->waypoints->Optimise();
  }

  task_manager->TaskCommit(*edit_task);
  edit_task.reset();
  Recalculate();

  try {
    task_manager->TaskSaveDefault();
  } catch (...) {
    LogError(std::current_exception(), "Failed to save the default task");
  }
  return true;
}

/** After a change of the points, like TaskEditPanel. */
static bool
Changed(bool ok) noexcept
{
  if (ok) {
    edit_task->ClearName();
    edit_task->UpdateGeometry();
  }
  return ok;
}

bool
CoreTask::Append(unsigned waypoint_id) noexcept
{
  if (edit_task == nullptr || edit_task->IsFull())
    return false;

  auto waypoint = data_components->waypoints->LookupId(waypoint_id);
  if (waypoint == nullptr)
    return false;

  /* TaskEditPanel::EditTaskPoint() for a new point */
  AbstractTaskFactory &factory = edit_task->GetFactory();
  std::unique_ptr<OrderedTaskPoint> point;
  if (edit_task->TaskSize() == 0)
    point = factory.CreateStart(std::move(waypoint));
  else
    point = factory.CreateIntermediate(std::move(waypoint));
  return point != nullptr && Changed(factory.Append(*point, true));
}

bool
CoreTask::Remove(unsigned index) noexcept
{
  return edit_task != nullptr && index < edit_task->TaskSize() &&
    Changed(edit_task->GetFactory().Remove(index));
}

bool
CoreTask::Swap(unsigned index) noexcept
{
  return edit_task != nullptr && index + 1 < edit_task->TaskSize() &&
    Changed(edit_task->GetFactory().Swap(index, true));
}

bool
CoreTask::Clear() noexcept
{
  if (edit_task == nullptr)
    return false;

  edit_task->RemoveAllPoints();
  return Changed(true);
}

bool
CoreTask::SetType(unsigned type) noexcept
{
  if (edit_task == nullptr || type >= unsigned(TaskFactoryType::COUNT))
    return false;

  /* TaskPropertiesPanel::OnTaskTypeChange() and Leave() */
  const auto new_type = TaskFactoryType(type);
  if (new_type != edit_task->GetFactoryType()) {
    edit_task->SetFactory(new_type);
    edit_task->GetFactory().MutateTPsToTaskType();
    edit_task->UpdateGeometry();
  }
  return true;
}

bool
CoreTask::SetPointType(unsigned index, unsigned type) noexcept
{
  if (edit_task == nullptr || index >= edit_task->TaskSize() ||
      type >= unsigned(TaskPointFactoryType::COUNT))
    return false;

  /* MutateTaskPointDialog */
  AbstractTaskFactory &factory = edit_task->GetFactory();
  const auto new_type = TaskPointFactoryType(type);
  if (!factory.GetValidTypes(index).Contains(new_type))
    return false;

  const OrderedTaskPoint &old_point = edit_task->GetPoint(index);
  if (factory.GetType(old_point) == new_type)
    return true;

  auto point = factory.CreateMutatedPoint(old_point, new_type);
  return point != nullptr && Changed(factory.Replace(*point, index, true));
}

bool
CoreTask::SetRadius(unsigned index, double radius) noexcept
{
  if (edit_task == nullptr || index >= edit_task->TaskSize() ||
      !(radius > 0 && radius <= 1e6))
    return false;

  auto *zone = dynamic_cast<CylinderZone *>(
    &edit_task->GetPoint(index).GetObservationZone());
  if (zone == nullptr)
    return false;

  /* the observation zone editors change the zone in place */
  if (auto *line = dynamic_cast<LineSectorZone *>(zone))
    line->SetLength(2 * radius);
  else
    zone->SetRadius(radius);
  return Changed(true);
}

/** The zone of an edited point, if the pilot may change `what` of it. */
template<typename Zone>
static Zone *
GetEditableZone(unsigned index, bool ZoneEdit::*what) noexcept
{
  if (edit_task == nullptr || index >= edit_task->TaskSize())
    return nullptr;

  auto &oz = edit_task->GetPoint(index).GetObservationZone();
  if (!(GetZoneEdit(oz, edit_task->GetFactoryType()).*what))
    return nullptr;
  return dynamic_cast<Zone *>(&oz);
}

bool
CoreTask::SetInnerRadius(unsigned index, double radius) noexcept
{
  if (!(radius > 0 && radius <= 1e6))
    return false;

  auto *zone = GetEditableZone<ObservationZonePoint>(index,
                                                     &ZoneEdit::inner_radius);
  if (auto *keyhole = dynamic_cast<KeyholeZone *>(zone))
    keyhole->SetInnerRadius(radius);
  else if (auto *annulus = dynamic_cast<AnnularSectorZone *>(zone))
    annulus->SetInnerRadius(radius);
  else
    return false;
  return Changed(true);
}

bool
CoreTask::SetRadial(unsigned index, bool end, double degrees) noexcept
{
  if (!(degrees >= -360 && degrees <= 720))
    return false;

  auto *zone = GetEditableZone<SectorZone>(index, &ZoneEdit::radials);
  if (zone == nullptr)
    return false;

  const auto radial = Angle::Degrees(degrees).AsBearing();
  if (end)
    zone->SetEndRadial(radial);
  else
    zone->SetStartRadial(radial);
  return Changed(true);
}

bool
CoreTask::SetSectorAngle(unsigned index, double degrees) noexcept
{
  if (!(degrees > 0 && degrees <= 360))
    return false;

  auto *zone = GetEditableZone<SymmetricSectorZone>(index, &ZoneEdit::angle);
  if (zone == nullptr)
    return false;

  zone->SetSectorAngle(Angle::Degrees(degrees));
  return Changed(true);
}

static bool
ToReference(double value, AltitudeReference &reference) noexcept
{
  if (value == 0)
    reference = AltitudeReference::AGL;
  else if (value == 1)
    reference = AltitudeReference::MSL;
  else
    return false;
  return true;
}

/** Local minutes of the day (-1: none) as upstream's RoughTime (UTC). */
static bool
ToRoughTime(double minutes, RoughTime &t) noexcept
{
  if (minutes == -1) {
    t = RoughTime::Invalid();
    return true;
  }
  if (!(minutes >= 0 && minutes < 24 * 60) || minutes != int(minutes))
    return false;
  t = RoughTime::FromMinuteOfDayChecked(int(minutes)) -
    CommonInterface::GetComputerSettings().utc_offset;
  return true;
}

bool
CoreTask::SetRule(unsigned rule, double value) noexcept
{
  if (edit_task == nullptr)
    return false;

  /* TaskPropertiesPanel::ReadValues() */
  auto p = edit_task->GetOrderedTaskSettings();
  auto &start = p.start_constraints;
  auto &finish = p.finish_constraints;
  const bool flag = value != 0;
  const bool whole = value >= 0 && value <= 100000 && value == int(value);

  switch (rule) {
  case XCS_TASK_RULE_START_OPEN:
  case XCS_TASK_RULE_START_CLOSE: {
    RoughTime open = start.open_time_span.GetRoughStart();
    RoughTime close = start.open_time_span.GetRoughEnd();
    if (!ToRoughTime(value, rule == XCS_TASK_RULE_START_OPEN ? open : close))
      return false;
    start.open_time_span = TimeSpan::FromRoughTimes(open, close);
    break;
  }

  case XCS_TASK_RULE_START_MAX_SPEED:
    if (!(value >= 0 && value <= 300))
      return false;
    start.max_speed = value;
    break;

  case XCS_TASK_RULE_START_MAX_HEIGHT:
    if (!whole)
      return false;
    start.max_height = unsigned(value);
    break;

  case XCS_TASK_RULE_START_HEIGHT_REF:
    if (!ToReference(value, start.max_height_ref))
      return false;
    break;

  case XCS_TASK_RULE_FINISH_MIN_HEIGHT:
    if (!whole)
      return false;
    finish.min_height = unsigned(value);
    break;

  case XCS_TASK_RULE_FINISH_HEIGHT_REF:
    if (!ToReference(value, finish.min_height_ref))
      return false;
    break;

  case XCS_TASK_RULE_FAI_START_FINISH:
    /* OnFAIFinishHeightChange(): both copies */
    if (!IsFai(edit_task->GetFactoryType()))
      return false;
    finish.fai_finish = start.fai_finish = flag;
    break;

  case XCS_TASK_RULE_START_REQUIRES_ARM:
    start.require_arm = flag;
    break;

  case XCS_TASK_RULE_START_SCORE_EXIT:
    start.score_exit = flag;
    break;

  case XCS_TASK_RULE_NAVIGATE_NEAREST:
    p.navigate_nearest = flag;
    break;

  case XCS_TASK_RULE_PEV_START_WAIT:
  case XCS_TASK_RULE_PEV_START_WINDOW:
    /* the panel's range: 0..30 minutes */
    if (!(whole && value <= 30 * 60))
      return false;
    (rule == XCS_TASK_RULE_PEV_START_WAIT
     ? start.pev_start_wait_time
     : start.pev_start_window) = std::chrono::duration<unsigned>(unsigned(value));
    break;

  default:
    return false;
  }

  edit_task->SetOrderedTaskSettings(p);
  return true;
}

bool
CoreTask::AddOptionalStart(unsigned waypoint_id) noexcept
{
  /* OptionStartsWidget::Relocate() for a new row */
  if (edit_task == nullptr || edit_task->TaskSize() == 0)
    return false;

  auto waypoint = data_components->waypoints->LookupId(waypoint_id);
  return waypoint != nullptr &&
    Changed(edit_task->GetFactory().AppendOptionalStart(std::move(waypoint)));
}

bool
CoreTask::RemoveOptionalStart(unsigned index) noexcept
{
  return edit_task != nullptr &&
    index < edit_task->GetOptionalStartPointCount() &&
    Changed(edit_task->RemoveOptionalStart(index));
}

/**
 * Change an area's target in the active task, as TargetWidget does;
 * a target the pilot placed is locked, so the optimiser leaves it.
 */
template<typename F>
static bool
WithTarget(unsigned index, F &&f) noexcept
{
  auto *task_manager = GetTaskManager();
  if (task_manager == nullptr)
    return false;

  {
    ProtectedTaskManager::ExclusiveLease lease{*task_manager};
    if (index < lease->GetActiveTaskPointIndex())
      return false;

    const OrderedTask &task = lease->GetOrderedTask();
    AATPoint *ap = task.GetAATTaskPoint(index);
    if (ap == nullptr || !f(*ap, task))
      return false;
  }

  Recalculate();
  return true;
}

bool
CoreTask::SetTargetRange(unsigned index, double range) noexcept
{
  if (!(range >= -1 && range <= 1))
    return false;

  return WithTarget(index, [range](AATPoint &ap, const OrderedTask &task){
    /* TargetWidget::OnRangeModified(): flipping the side flips the
       radial too */
    auto rar = ap.GetTargetRangeRadial();
    if ((range < 0) != (rar.range < 0))
      rar.radial += rar.radial.IsNegative()
        ? Angle::HalfCircle() : -Angle::HalfCircle();
    rar.range = range;
    ap.LockTarget(true);
    ap.SetTarget(rar, task.GetTaskProjection());
    return true;
  });
}

bool
CoreTask::SetTargetRadial(unsigned index, double degrees) noexcept
{
  if (!(degrees >= -360 && degrees <= 360))
    return false;

  return WithTarget(index, [degrees](AATPoint &ap, const OrderedTask &task){
    /* TargetWidget::OnRadialModified(): within ±180°, and past ±90° the
       range turns round */
    auto rar = ap.GetTargetRangeRadial();
    Angle radial = Angle::Degrees(degrees);
    if (radial >= Angle::HalfCircle())
      radial -= Angle::FullCircle();
    else if (radial <= -Angle::HalfCircle())
      radial += Angle::FullCircle();
    if ((radial.Absolute() > Angle::QuarterCircle()) !=
        (rar.radial.Absolute() > Angle::QuarterCircle()))
      rar.range = -rar.range;
    rar.radial = radial;
    ap.LockTarget(true);
    ap.SetTarget(rar, task.GetTaskProjection());
    return true;
  });
}

bool
CoreTask::SetTargetLocation(unsigned index, GeoPoint location) noexcept
{
  if (!location.IsValid())
    return false;

  /* TargetMapWindow::TargetDragged(), inside the area only */
  return WithTarget(index, [location](AATPoint &ap, const OrderedTask &){
    if (!ap.GetObservationZone().IsInSector(location))
      return false;
    ap.LockTarget(true);
    ap.SetTarget(location, true);
    return true;
  });
}

bool
CoreTask::LockTarget(unsigned index, bool locked) noexcept
{
  /* TargetWidget::OnOptimized(): unlocked, the optimiser moves it */
  return WithTarget(index, [locked](AATPoint &ap, const OrderedTask &){
    ap.LockTarget(locked);
    return true;
  });
}

bool
CoreTask::SetAATMinTime(double seconds) noexcept
{
  if (edit_task == nullptr || !(seconds >= 0 && seconds <= 24 * 3600))
    return false;

  auto settings = edit_task->GetOrderedTaskSettings();
  settings.aat_min_time = std::chrono::duration<unsigned>(unsigned(seconds));
  edit_task->SetOrderedTaskSettings(settings);
  return true;
}

std::string
CoreTask::ListFiles() noexcept
{
  TaskStore store;
  try {
    store.Scan(true);
  } catch (...) {
    LogError(std::current_exception(), "Task file scan failed");
  }

  boost::json::array files;
  for (unsigned i = 0; i < store.Size(); ++i)
    files.emplace_back(boost::json::object{
      {"name", store.GetName(i)},
      {"path", store.GetPath(i).c_str()},
      {"index", store.GetTaskIndex(i)},
    });
  return Serialize(files);
}

bool
CoreTask::Load(const char *path, unsigned index) noexcept
{
  if (GetTaskManager() == nullptr || path == nullptr || *path == '\0')
    return false;

  std::unique_ptr<OrderedTask> task;
  try {
    task = TaskFile::GetTask(Path{path}, GetTaskBehaviour(),
                             data_components->waypoints.get(), index);
  } catch (...) {
    LogError(std::current_exception(), "Task file load failed");
  }
  if (task == nullptr)
    return false;

  task->UpdateGeometry();
  edit_task = std::move(task);
  return true;
}

bool
CoreTask::Save(const char *name) noexcept
{
  if (edit_task == nullptr || name == nullptr || *name == '\0' ||
      StringFind(name, '/') != nullptr || StringFind(name, '\\') != nullptr)
    return false;

  /* TaskActionsPanel::SaveTask() */
  try {
    const auto folder = MakeLocalPath("tasks");
    const std::string file = std::string{name} + ".tsk";
    SaveTask(AllocatedPath::Build(folder, file.c_str()), *edit_task);
    edit_task->SetName(name);
    return true;
  } catch (...) {
    LogError(std::current_exception(), "Task file save failed");
    return false;
  }
}

bool
CoreTask::Advance(int offset) noexcept
{
  auto *task_manager = GetTaskManager();
  if (task_manager == nullptr || (offset != 1 && offset != -1))
    return false;

  task_manager->IncrementActiveTaskPoint(offset);
  Recalculate();
  return true;
}

void
CoreTask::Restart() noexcept
{
  if (auto *task_manager = GetTaskManager()) {
    task_manager->ResetTask();
    Recalculate();
  }
}

void
CoreTask::Deinitialise() noexcept
{
  edit_task.reset();
}

bool
CoreTask::Edit(std::unique_ptr<OrderedTask> task) noexcept
{
  if (GetTaskManager() == nullptr || task == nullptr)
    return false;

  task->SetTaskBehaviour(GetTaskBehaviour());
  task->UpdateGeometry();
  edit_task = std::move(task);
  return true;
}

const TaskBehaviour &
CoreTask::GetBehaviour() noexcept
{
  return GetTaskBehaviour();
}
