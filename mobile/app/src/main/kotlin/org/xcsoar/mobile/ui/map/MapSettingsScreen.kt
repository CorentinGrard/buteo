// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.map

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.xcsoar.mobile.R
import org.xcsoar.mobile.core.MapOption
import org.xcsoar.mobile.core.TerrainRamp
import org.xcsoar.mobile.ui.ScreenHeader
import org.xcsoar.mobile.ui.flight.Caption
import org.xcsoar.mobile.ui.LinkRow
import org.xcsoar.mobile.ui.SettingsGroup
import org.xcsoar.mobile.ui.SwitchRow
import org.xcsoar.mobile.ui.theme.XcsTheme

@Composable
fun MapSettingsScreen(viewModel: MapSettingsViewModel, onOpenUnits: () -> Unit,
                      onBack: () -> Unit) {
    val options by viewModel.options.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }
    BackHandler(onBack = onBack)
    MapSettingsContent(options, viewModel::set, onBack, onOpenUnits)
}

/**
 * Terrain, its colours, topography and the trail, saved in the profile;
 * and the units the map and the tiles show.
 */
@Composable
fun MapSettingsContent(
    options: Map<MapOption, Int>,
    onSet: (MapOption, Int) -> Unit,
    onBack: () -> Unit,
    /** null hides the Units row */
    onOpenUnits: (() -> Unit)? = null,
) {
    val colors = XcsTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.sheet)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader(stringResource(R.string.menu_map_units), onBack)

        SettingsGroup {
            SwitchRow(stringResource(R.string.terrain), stringResource(R.string.map_terrain_detail),
                      options[MapOption.TERRAIN]?.let { it != 0 }) {
                onSet(MapOption.TERRAIN, if (it) 1 else 0)
            }
            SwitchRow(stringResource(R.string.map_topography), stringResource(R.string.map_topography_detail),
                      options[MapOption.TOPOGRAPHY]?.let { it != 0 }) {
                onSet(MapOption.TOPOGRAPHY, if (it) 1 else 0)
            }
        }

        Caption(stringResource(R.string.circling), Modifier.padding(start = 4.dp))
        SettingsGroup {
            SwitchRow(stringResource(R.string.map_zoom), stringResource(R.string.map_zoom_detail),
                      options[MapOption.CIRCLING_ZOOM]?.let { it != 0 }) {
                onSet(MapOption.CIRCLING_ZOOM, if (it) 1 else 0)
            }
            SwitchRow(stringResource(R.string.map_thermal_assistant),
                      stringResource(R.string.map_thermal_assistant_detail),
                      options[MapOption.THERMAL_ASSISTANT]?.let { it != 0 }) {
                onSet(MapOption.THERMAL_ASSISTANT, if (it) 1 else 0)
            }
        }

        Caption(stringResource(R.string.map_terrain_colours), Modifier.padding(start = 4.dp))
        SettingsGroup(Modifier.selectableGroup()) {
            for (ramp in TerrainRamp.entries)
                ChoiceRow(stringResource(ramp.labelRes()), options[MapOption.TERRAIN_RAMP] == ramp.code) {
                    onSet(MapOption.TERRAIN_RAMP, ramp.code)
                }
        }

        Caption(stringResource(R.string.map_trail), Modifier.padding(start = 4.dp))
        Row(Modifier
                .fillMaxWidth()
                .height(56.dp)
                .background(colors.panel, RoundedCornerShape(12.dp))
                .selectableGroup()) {
            for ((label, value) in listOf(stringResource(R.string.off) to 0,
                                        stringResource(R.string.map_trail_short) to 2,
                                        stringResource(R.string.map_trail_long) to 1,
                                        stringResource(R.string.map_trail_full) to 3)) {
                val selected = options[MapOption.TRAIL] == value
                Box(Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(selected, role = Role.RadioButton) {
                            onSet(MapOption.TRAIL, value)
                        }
                        .padding(4.dp)
                        .background(if (selected) colors.selected else Color.Transparent,
                                    RoundedCornerShape(9.dp)),
                    contentAlignment = Alignment.Center) {
                    Text(label, color = if (selected) colors.onSelected else colors.text,
                         fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        if (onOpenUnits != null)
            SettingsGroup(Modifier.padding(top = 8.dp)) {
                LinkRow(stringResource(R.string.menu_units),
                        stringResource(R.string.menu_units_detail), onClick = onOpenUnits)
            }
    }
}

/** XCSoar's names of the colour ramps. */
@StringRes
private fun TerrainRamp.labelRes(): Int = when (this) {
    TerrainRamp.PASTEL -> R.string.ramp_pastel
    TerrainRamp.LOW_LANDS -> R.string.ramp_low_lands
    TerrainRamp.MOUNTAINOUS -> R.string.ramp_mountainous
    TerrainRamp.IMHOF_ATLAS -> R.string.ramp_imhof
    TerrainRamp.ICAO -> R.string.ramp_icao
    TerrainRamp.FRENCH_SIA -> R.string.ramp_french_sia
    TerrainRamp.GERMAN_DFS -> R.string.ramp_german_dfs
    TerrainRamp.SANDSTONE -> R.string.ramp_sandstone
    TerrainRamp.GREY -> R.string.ramp_grey
    TerrainRamp.HIGH_CONTRAST -> R.string.ramp_high_contrast
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = XcsTheme.colors
    Row(Modifier
            .fillMaxWidth()
            .height(56.dp)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier
                .size(22.dp)
                .border(2.dp, if (selected) colors.selected else colors.textSecondary, CircleShape)
                .padding(5.dp)
                .background(if (selected) colors.selected else Color.Transparent, CircleShape))
        Text(label, color = colors.text, fontSize = 17.sp)
    }
}

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun MapSettingsPreview() {
    XcsTheme(dark = false) {
        MapSettingsContent(mapOf(MapOption.TERRAIN to 1, MapOption.TERRAIN_RAMP to 11,
                                 MapOption.TOPOGRAPHY to 1, MapOption.TRAIL to 2), { _, _ -> }, {})
    }
}
