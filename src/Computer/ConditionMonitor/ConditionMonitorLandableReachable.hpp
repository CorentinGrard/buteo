// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

#pragma once

#include "ConditionMonitor.hpp"

class ConditionMonitorLandableReachable final : public ConditionMonitor {
  /** How long a landable must stay in reach to warn again. */
  static constexpr FloatDuration REARM_TIME = std::chrono::minutes{1};

  bool last_reachable = false;
  bool now_reachable = false;

  /** Since when a landable is in reach. */
  TimeStamp reachable_since = TimeStamp::Undefined();

public:
  constexpr ConditionMonitorLandableReachable() noexcept
    :ConditionMonitor(std::chrono::minutes{5}, std::chrono::seconds{1}) {}

protected:
  bool CheckCondition(const NMEAInfo &basic,
                      const DerivedInfo &calculated,
                      const ComputerSettings &settings) noexcept override;
  void Notify() noexcept override;
  void SaveLast() noexcept override;
};
