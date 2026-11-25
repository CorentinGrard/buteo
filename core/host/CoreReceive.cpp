// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

/*
 * Headless counterpart of src/UIReceiveBlackboard.cpp.  Keeps the
 * backend duties (blackboard exchange, device settings, device
 * notification, task events) and leaves out InfoBoxes, overlays and
 * the OpenVario system clock.  Keep in sync with that file.
 */

#include "CoreReceive.hpp"
#include "CoreListener.hpp"
#include "Interface.hpp"
#include "ActionInterface.hpp"
#include "ApplyVegaSwitches.hpp"
#include "ApplyExternalSettings.hpp"
#include "Device/MultipleDevices.hpp"
#include "Input/TaskEventObserver.hpp"
#include "Monitor/WindMonitor.hpp"
#include "Task/ProtectedTaskManager.hpp"
#include "Components.hpp"
#include "BackendComponents.hpp"
#include "DataComponents.hpp"
#include "Terrain/RasterTerrain.hpp"
#include "Operation/PopupOperationEnvironment.hpp"
#include "ui/event/Notify.hpp"
#include "ui/event/PeriodicTimer.hpp"
#include "BackendProcessTimer.hpp"
#include "BatteryTimer.hpp"
#include "Blackboard/DeviceBlackboard.hpp"
#include "CalculationThread.hpp"

#include <memory>

static TaskEventObserver task_event_observer;
static WindMonitor wind_monitor;

void
CoreReceiveSensorData(OperationEnvironment &env) noexcept
{
  XCSoarInterface::ReceiveGPS();
  ApplyVegaSwitches();
  ApplyExternalSettings(env);
}

void
CoreReceiveCalculatedData() noexcept
{
  XCSoarInterface::ReceiveCalculated();

  if (backend_components->devices)
    backend_components->devices->NotifyCalculatedUpdate(CommonInterface::Basic(),
                                                        CommonInterface::Calculated());

  if (backend_components->protected_task_manager) {
    const ProtectedTaskManager::Lease lease{*backend_components->protected_task_manager};
    task_event_observer.Check(lease);
  }

  /* the one monitor without UI: a newer wind clears the manual one */
  wind_monitor.Check();
}

static void
OnGPSNotify() noexcept
{
  PopupOperationEnvironment env;
  CoreReceiveSensorData(env);

  if (auto *listener = GetCoreListener())
    listener->OnGPSUpdate();
}

static void
OnCalculatedNotify() noexcept
{
  CoreReceiveCalculatedData();

  if (auto *listener = GetCoreListener())
    listener->OnCalculatedUpdate();
}

static std::unique_ptr<UI::Notify> gps_notify, calculated_notify;

void
CoreInitNotify() noexcept
{
  gps_notify = std::make_unique<UI::Notify>(OnGPSNotify);
  calculated_notify = std::make_unique<UI::Notify>(OnCalculatedNotify);
}

void
CoreDeinitNotify() noexcept
{
  gps_notify.reset();
  calculated_notify.reset();
}

void
CoreSendGPSNotify() noexcept
{
  if (gps_notify)
    gps_notify->SendNotification();
}

void
CoreSendCalculatedNotify() noexcept
{
  if (calculated_notify)
    calculated_notify->SendNotification();
}

/* the backend half of BlackboardProcessTimer() in src/ProcessTimer.cpp
   (XCSoarInterface::ExchangeBlackboard() also pushes settings to the
   MainWindow) */
static void
ExchangeBlackboard() noexcept
{
  backend_components->device_blackboard->ExpireWallClock();

  XCSoarInterface::ExchangeDeviceBlackboard();
  if (backend_components->calculation_thread)
    backend_components->calculation_thread->SetComputerSettings(
      CommonInterface::GetComputerSettings());
}

/* MainWindow::LateInitialise(): open the devices once the event loop
   runs (on Android, opening may ask the user for a permission) */
static bool late_initialised, open_devices;

static void
LateInitialise() noexcept
{
  if (late_initialised)
    return;
  late_initialised = true;

  if (open_devices && backend_components->devices != nullptr) {
    /* must be persistent: DeviceDescriptor::Open() is asynchronous */
    static PopupOperationEnvironment env;
    backend_components->devices->Open(env);
  }
}

/* "Battery low" messages, as MainWindow's timer runs it */
static BatteryTimer battery_timer;

/**
 * Load the detailed terrain around the glider.  XCSoar loads it only
 * while painting the map (MapWindow::UpdateTerrain()), around the
 * map's centre; the app may show no map (in the background) or one it
 * draws itself, and the glide computer (reach, final glide over
 * terrain) would then use the 16 times coarser overview once the
 * glider has flown out of the tiles loaded last.  Tiles already
 * loaded stay while they fit (RasterTileCache::PollTiles()), so this
 * and the map's own loading keep each other's.
 */
static void
UpdateTerrain() noexcept
{
  /* about a glide's range; the loader adds a tile (256 pixels) */
  constexpr double radius = 50000;

  const auto &basic = CommonInterface::Basic();
  if (data_components != nullptr && data_components->terrain != nullptr &&
      basic.location_available)
    data_components->terrain->UpdateTiles(basic.location, radius);
}

static void
OnTimer() noexcept
{
  LateInitialise();
  ExchangeBlackboard();
  BackendSettingsTimer();
  BackendDeviceTimer();
  UpdateTerrain();
  battery_timer.Process();
}

static std::unique_ptr<UI::PeriodicTimer> timer;

void
CoreStartTimer(bool _open_devices) noexcept
{
  late_initialised = false;
  open_devices = _open_devices;
  timer = std::make_unique<UI::PeriodicTimer>(OnTimer);
  timer->Schedule(std::chrono::milliseconds(500));
}

void
CoreStopTimer() noexcept
{
  timer.reset();
}
