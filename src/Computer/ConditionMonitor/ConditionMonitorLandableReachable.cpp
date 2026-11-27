// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

#include "ConditionMonitorLandableReachable.hpp"
#include "NMEA/Info.hpp"
#include "NMEA/Derived.hpp"
#include "Input/InputQueue.hpp"

bool
ConditionMonitorLandableReachable::CheckCondition(const NMEAInfo &basic,
                                                  const DerivedInfo &calculated,
                                                  [[maybe_unused]] const ComputerSettings &settings) noexcept
{
  if (!calculated.flight.flying)
    return false;

  now_reachable = calculated.common_stats.landable_reachable;

  /* SaveLast() runs only after a warning: a landable back in reach
     for a while arms the next one, not one gained in a turn at the
     edge of the reach */
  if (!now_reachable)
    reachable_since = TimeStamp::Undefined();
  else if (!reachable_since.IsDefined() || basic.time < reachable_since)
    reachable_since = basic.time;
  else if (basic.time >= reachable_since + REARM_TIME)
    last_reachable = true;

  // warn when becoming unreachable
  return (!now_reachable && last_reachable);
}

void
ConditionMonitorLandableReachable::Notify() noexcept
{
  InputEvents::processGlideComputer(GCE_LANDABLE_UNREACHABLE);
}

void
ConditionMonitorLandableReachable::SaveLast() noexcept
{
  last_reachable = now_reachable;
}
