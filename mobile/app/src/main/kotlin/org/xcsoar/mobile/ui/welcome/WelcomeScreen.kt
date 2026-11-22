// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.welcome

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.xcsoar.mobile.R
import org.xcsoar.mobile.core.DataFile
import org.xcsoar.mobile.core.RegionFiles
import org.xcsoar.mobile.core.UnitGroup
import org.xcsoar.mobile.core.UnitSettings
import org.xcsoar.mobile.ui.ActionButton
import org.xcsoar.mobile.ui.InputField
import org.xcsoar.mobile.ui.PageLayout
import org.xcsoar.mobile.ui.crew.PlaneRow
import org.xcsoar.mobile.ui.flight.Caption
import org.xcsoar.mobile.ui.menuPath
import org.xcsoar.mobile.ui.theme.XcsTheme
import java.util.Locale

/**
 * The first start: a few choices so the first flight has units, a
 * polar and a map.  [onDone] when finished or put off ("Set up later"):
 * the app does not ask again.
 */
@Composable
fun WelcomeScreen(viewModel: WelcomeViewModel, onAddPlane: () -> Unit, onDone: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.start() }
    BackHandler(enabled = state.step != WelcomeStep.UNITS || state.choosingCountry,
                onBack = viewModel::back)
    WelcomeContent(
        state,
        onPreset = viewModel::choosePreset,
        onPilot = viewModel::setPilot,
        onPlane = viewModel::choosePlane,
        onAddPlane = onAddPlane,
        onCountries = viewModel::openCountries,
        onCountry = viewModel::chooseCountry,
        onToggleFile = viewModel::toggleFile,
        onDownload = viewModel::downloadChosen,
        onRetry = viewModel::loadRepository,
        onNext = viewModel::next,
        onBack = viewModel::back,
        onDone = onDone,
    )
}

@Composable
fun WelcomeContent(
    state: WelcomeState,
    onPreset: (Int) -> Unit = {},
    onPilot: (String) -> Unit = {},
    onPlane: (String) -> Unit = {},
    onAddPlane: () -> Unit = {},
    onCountries: (Boolean) -> Unit = {},
    onCountry: (String) -> Unit = {},
    onToggleFile: (String) -> Unit = {},
    onDownload: () -> Unit = {},
    onRetry: () -> Unit = {},
    onNext: () -> Unit = {},
    onBack: () -> Unit = {},
    onDone: () -> Unit = {},
) {
    val colors = XcsTheme.colors
    PageLayout(bottom = {
        if (state.step != WelcomeStep.UNITS)
            ActionButton(stringResource(R.string.back), outlined = true, onClick = onBack)
        Spacer(Modifier.weight(1f))
        when {
            // Back closes the list
            state.choosingCountry -> {}
            state.step != WelcomeStep.DATA ->
                ActionButton(stringResource(R.string.next), primary = true, onClick = onNext)
            state.downloaded || state.files.none { it.chosen } ->
                ActionButton(stringResource(R.string.done), primary = true, enabled = !state.downloading,
                             onClick = onDone)
            else ->
                ActionButton(stringResource(if (state.downloading) R.string.welcome_downloading
                                         else R.string.download),
                             primary = true, enabled = !state.downloading,
                             onClick = onDownload)
        }
    }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Caption(stringResource(R.string.welcome_step, state.step.ordinal + 1,
                                   WelcomeStep.entries.size),
                    Modifier.padding(start = 4.dp).weight(1f))
            ActionButton(stringResource(R.string.welcome_later), onClick = onDone)
        }
        when (state.step) {
            WelcomeStep.UNITS -> UnitsStep(state, onPreset)
            WelcomeStep.CREW -> CrewStep(state, onPilot, onPlane, onAddPlane)
            WelcomeStep.DATA ->
                if (state.choosingCountry && state.repository != null)
                    CountryList(state, onCountry)
                else
                    DataStep(state, onCountries, onToggleFile, onRetry)
        }
    }
}

@Composable
private fun Title(title: String, text: String) {
    val colors = XcsTheme.colors
    Text(title, color = colors.text, fontSize = 26.sp, fontWeight = FontWeight.Bold,
         modifier = Modifier.padding(horizontal = 4.dp))
    Text(text, color = colors.textSecondary, fontSize = 16.sp,
         modifier = Modifier.padding(horizontal = 4.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, color = XcsTheme.colors.textSecondary, fontSize = 15.sp,
         modifier = Modifier.padding(horizontal = 4.dp))
}

/** XCSoar's presets, each with the units it gives. */
@Composable
private fun UnitsStep(state: WelcomeState, onPreset: (Int) -> Unit) {
    Title(stringResource(R.string.welcome_title),
          stringResource(R.string.welcome_intro))
    Caption(stringResource(R.string.units), Modifier.padding(start = 4.dp))
    val units = state.units
    if (units == null) {
        Loading(stringResource(R.string.flight_starting))
        return
    }
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        units.presets.forEachIndexed { i, preset ->
            Choice(preset.name, presetSample(units, i), state.preset == i) { onPreset(i) }
        }
    }
    Hint(stringResource(R.string.welcome_units_later,
                        menuPath(R.string.menu, R.string.menu_map_units, R.string.menu_units)))
}

/** "km · m · km/h · m/s": distance, altitude, speed and lift of a preset. */
internal fun presetSample(units: UnitSettings, index: Int): String =
    listOf(UnitGroup.DISTANCE, UnitGroup.ALTITUDE, UnitGroup.HORIZONTAL_SPEED,
           UnitGroup.VERTICAL_SPEED)
        .mapNotNull { units.presetUnit(index, it)?.name }
        .distinct()
        .joinToString(" · ")

@Composable
private fun Choice(title: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    val colors = XcsTheme.colors
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier
               .fillMaxWidth()
               .heightIn(min = 64.dp)
               .background(colors.panel, shape)
               .border(if (selected) 3.dp else 1.dp,
                       if (selected) colors.selected else colors.panelBorder, shape)
               .selectable(selected, role = Role.RadioButton, onClick = onClick)
               .padding(horizontal = 14.dp, vertical = 10.dp),
           verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = colors.text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        if (detail.isNotEmpty())
            Text(detail, color = colors.textSecondary, fontSize = 15.sp)
    }
}

/** The pilot's name for the flight log, and the glider. */
@Composable
private fun CrewStep(state: WelcomeState, onPilot: (String) -> Unit, onPlane: (String) -> Unit,
                     onAddPlane: () -> Unit) {
    Title(stringResource(R.string.welcome_crew_title),
          stringResource(R.string.welcome_crew_intro))
    Caption(stringResource(R.string.pilot), Modifier.padding(start = 4.dp))
    InputField(state.pilot, onPilot, stringResource(R.string.welcome_your_name), capitalization = KeyboardCapitalization.Words)

    Caption(stringResource(R.string.glider), Modifier.padding(start = 4.dp))
    val planes = state.planes
    if (planes == null) {
        Loading(stringResource(R.string.flight_starting))
        return
    }
    if (planes.isNotEmpty())
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (plane in planes)
                PlaneRow(plane, selected = plane.path == state.plane, lastFlown = false,
                         onSelect = { onPlane(plane.path) }, onEdit = null)
        }
    ActionButton(stringResource(if (planes.isEmpty()) R.string.welcome_add_glider
                                 else R.string.welcome_add_another_glider),
                 outlined = true, modifier = Modifier.fillMaxWidth(), onClick = onAddPlane)
    if (planes.isEmpty())
        Hint(stringResource(R.string.welcome_no_glider,
                            menuPath(R.string.menu, R.string.menu_crew)))
}

/** The map, airspace and waypoints for the region, to download. */
@Composable
private fun DataStep(state: WelcomeState, onCountries: (Boolean) -> Unit,
                     onToggleFile: (String) -> Unit, onRetry: () -> Unit) {
    val colors = XcsTheme.colors
    Title(stringResource(R.string.welcome_data_title),
          stringResource(R.string.welcome_data_intro))
    val error = state.repositoryError
    if (state.repository == null) {
        if (error != null) {
            Text(stringResource(R.string.welcome_repository_error, error.text()), color = colors.warning,
                 fontSize = 16.sp, modifier = Modifier.padding(horizontal = 4.dp))
            ActionButton(stringResource(R.string.welcome_try_again), outlined = true, onClick = onRetry)
            Hint(stringResource(R.string.welcome_files_later,
                                menuPath(R.string.menu, R.string.menu_data_files)))
        } else
            Loading(stringResource(R.string.welcome_looking))
        return
    }

    Caption(stringResource(R.string.welcome_region), Modifier.padding(start = 4.dp))
    Row(Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(colors.panel, RoundedCornerShape(14.dp))
            .clickable(enabled = !state.downloading, role = Role.Button,
                       onClickLabel = stringResource(R.string.welcome_choose_region)) { onCountries(true) }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(state.country?.let(::countryName) ?: stringResource(R.string.welcome_choose), color = colors.text,
             fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(stringResource(R.string.change), color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }

    if (state.files.isEmpty()) {
        Hint(stringResource(R.string.welcome_no_files))
        return
    }
    Caption(stringResource(R.string.files), Modifier.padding(start = 4.dp))
    Column(Modifier
               .fillMaxWidth()
               .background(colors.panel, RoundedCornerShape(14.dp))) {
        state.files.forEachIndexed { i, entry ->
            if (i > 0)
                HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = colors.panelBorder)
            FileChoice(entry, enabled = !state.downloading) { onToggleFile(entry.file.name) }
        }
    }
    if (state.files.any { it.file.dataFile == DataFile.MAP && it.chosen && !it.done })
        Hint(stringResource(R.string.welcome_maps_large,
                            menuPath(R.string.menu, R.string.menu_data_files)))
}

@Composable
private fun FileChoice(entry: RegionFileState, enabled: Boolean, onToggle: () -> Unit) {
    val colors = XcsTheme.colors
    val file = entry.file
    val kind = when (file.dataFile) {
        DataFile.MAP -> stringResource(R.string.welcome_file_map)
        DataFile.AIRSPACE -> stringResource(R.string.airspace)
        DataFile.WAYPOINTS -> stringResource(R.string.welcome_file_waypoints)
        else -> file.type
    }
    Column(Modifier
               .fillMaxWidth()
               .toggleable(entry.chosen, enabled = enabled && !entry.done, role = Role.Checkbox,
                           onValueChange = { onToggle() })
               .padding(start = 4.dp, end = 14.dp, top = 8.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(entry.chosen, onCheckedChange = null, enabled = !entry.done,
                     colors = CheckboxDefaults.colors(checkedColor = colors.selected,
                                                      checkmarkColor = colors.onSelected),
                     modifier = Modifier.padding(horizontal = 10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(kind, color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(file.description.ifEmpty { file.name }, color = colors.textSecondary,
                     fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                when {
                    entry.done -> Text(stringResource(R.string.welcome_downloaded), color = colors.safe,
                                       fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    entry.error != null -> Text(entry.error.text(), color = colors.warning,
                                                fontSize = 15.sp, maxLines = 2,
                                                overflow = TextOverflow.Ellipsis)
                }
            }
        }
        entry.progress?.let { progress ->
            val modifier = Modifier.fillMaxWidth().padding(start = 14.dp, top = 6.dp)
            if (progress >= 0)
                LinearProgressIndicator(progress = { progress }, color = colors.selected,
                                        trackColor = colors.panelBorder, modifier = modifier)
            else
                LinearProgressIndicator(color = colors.selected, trackColor = colors.panelBorder,
                                        modifier = modifier)
        }
    }
}

/** The countries the repository has files for, by name, with a search. */
@Composable
private fun CountryList(state: WelcomeState, onCountry: (String) -> Unit) {
    val colors = XcsTheme.colors
    var query by rememberSaveable { mutableStateOf("") }
    Title(stringResource(R.string.welcome_region), stringResource(R.string.welcome_where))
    InputField(query, { query = it }, stringResource(R.string.search), capitalization = KeyboardCapitalization.Words)
    val countries = RegionFiles.countries(state.repository.orEmpty())
        .map { it to countryName(it) }
        .filter { (code, name) ->
            query.isBlank() || name.contains(query.trim(), ignoreCase = true) ||
                code.equals(query.trim(), ignoreCase = true)
        }
        .sortedBy { it.second }
    Column(Modifier
               .fillMaxWidth()
               .background(colors.panel, RoundedCornerShape(14.dp))
               .selectableGroup()) {
        countries.forEachIndexed { i, (code, name) ->
            if (i > 0)
                HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = colors.panelBorder)
            Text(name, color = colors.text, fontSize = 17.sp,
                 fontWeight = if (code == state.country) FontWeight.Bold else FontWeight.Normal,
                 modifier = Modifier
                     .fillMaxWidth()
                     .selectable(code == state.country, role = Role.RadioButton) {
                         onCountry(code)
                     }
                     .heightIn(min = 56.dp)
                     .padding(horizontal = 14.dp, vertical = 16.dp))
        }
    }
}

@Composable
private fun Loading(text: String) {
    val colors = XcsTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(horizontal = 4.dp)) {
        CircularProgressIndicator(Modifier.size(28.dp), color = colors.text)
        Text(text, color = colors.textSecondary, fontSize = 16.sp)
    }
}

/** "France" for "fr", in the phone's language; the code if it is no country. */
internal fun countryName(code: String): String =
    Locale("", code.uppercase()).displayCountry.ifEmpty { code.uppercase() }
