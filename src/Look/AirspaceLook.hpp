// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

#pragma once

#include "ui/canvas/Pen.hpp"
#include "ui/canvas/Brush.hpp"
#include "ui/canvas/Icon.hpp"
#include "Engine/Airspace/AirspaceClass.hpp"
#include "util/Serial.hpp"

static constexpr unsigned NUMAIRSPACECOLORS = 18;

struct AirspaceRendererSettings;
struct AirspaceClassRendererSettings;
class Font;

struct AirspaceClassLook {
  Color fill_color;

  /**
   * Solid fill used for transparent airspace rendering.
   */
  Brush solid_brush;

  Pen border_pen;

  void Initialise(const AirspaceClassRendererSettings &settings);
};

struct AirspaceLook {
  static const RGB8Color preset_colors[NUMAIRSPACECOLORS];

  AirspaceClassLook classes[AIRSPACECLASSCOUNT];

  Pen thick_pen;

  /**
   * Alpha of the fills (OpenGL, the default fill mode).  A light one
   * suits filling whole areas (#AirspaceClassRendererSettings::FillMode::ALL).
   */
  uint8_t fill_alpha = 90;

  /**
   * Draw an airspace with a warning in #warning_color and #warning_pen,
   * and one the aircraft is inside of in #inside_color and #inside_pen,
   * instead of its class colours, until acknowledged (OpenGL, the
   * default fill mode).  Off: they keep their class colours.
   */
  bool warning_colors = false;
  Color warning_color, inside_color;
  Pen warning_pen, inside_pen;

  MaskedIcon intercept_icon;

  /**
   * look for labels
   */
  Pen label_pen;
  Brush label_brush;
  Color label_text_color;

  /**
   * The font used to render the airspace name.
   */
  const Font *name_font;

  /** Incremented by Initialise(), including after an in-place font reload. */
  Serial name_font_serial;

  void Initialise(const AirspaceRendererSettings &settings,
                  const Font &_name_font);

  void Reinitialise(const AirspaceRendererSettings &settings);
};
