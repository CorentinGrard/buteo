// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.crew

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.xcsoar.mobile.R
import org.xcsoar.mobile.core.PlaneInfo
import org.xcsoar.mobile.ui.ActionButton
import org.xcsoar.mobile.ui.PageLayout
import org.xcsoar.mobile.ui.InputField
import org.xcsoar.mobile.ui.LinkRow
import org.xcsoar.mobile.ui.SettingsGroup
import org.xcsoar.mobile.ui.ScreenHeader
import org.xcsoar.mobile.ui.flight.Caption
import org.xcsoar.mobile.ui.theme.XcsTheme

/**
 * Choose the plane (and the co-pilot of a two-seater) before flying.
 * Shown when the app opens; [onBack] is null there, so the pilot has
 * to choose (or skip, when there is no plane yet).
 */
@Composable
fun CrewScreen(
    viewModel: CrewViewModel,
    onEditPlane: (PlaneInfo?) -> Unit,
    onEditPilot: () -> Unit,
    onDone: () -> Unit,
    onBack: (() -> Unit)?,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.load() }
    if (onBack != null)
        BackHandler(onBack = onBack)
    CrewContent(state, viewModel::select, viewModel::selectCopilot, viewModel::addCopilot,
                onEditPlane, onEditPilot, onConfirm = { viewModel.confirm(onDone) },
                onSkip = onDone, onBack = onBack)
}

@Composable
fun CrewContent(
    state: CrewState,
    onSelect: (String) -> Unit,
    onSelectCopilot: (String) -> Unit,
    onAddCopilot: (String) -> Unit,
    onEditPlane: (PlaneInfo?) -> Unit,
    onEditPilot: () -> Unit,
    onConfirm: () -> Unit,
    onSkip: () -> Unit,
    onBack: (() -> Unit)?,
) {
    val colors = XcsTheme.colors
    val plane = state.selectedPlane
    val planes = state.planes
    // nothing to choose yet: the pilot may still fly ("Not now")
    val canSkip = onBack == null && planes != null
    PageLayout(bottom = if (plane == null && !canSkip) null else ({
        if (plane != null) {
            ActionButton(if (plane.doubleSeater && state.copilot.isNotEmpty())
                             stringResource(R.string.crew_fly_with, plane.registration,
                                            state.copilot)
                         else stringResource(R.string.crew_fly, plane.registration),
                         primary = true,
                         modifier = Modifier.weight(1f), onClick = onConfirm)
        } else
            ActionButton(stringResource(R.string.crew_not_now), outlined = true, modifier = Modifier.weight(1f),
                         onClick = onSkip)
    })) {
        ScreenHeader(stringResource(R.string.menu_crew), onBack)

        Caption(stringResource(R.string.crew_aircraft), Modifier.padding(start = 4.dp))
        if (planes != null && planes.isEmpty())
            Text(stringResource(R.string.crew_add_hint), color = colors.textSecondary, fontSize = 16.sp,
                 modifier = Modifier.padding(horizontal = 4.dp))

        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (plane in planes.orEmpty())
                PlaneRow(plane, selected = plane.path == state.selected,
                         lastFlown = plane.path == state.lastFlown,
                         onSelect = { onSelect(plane.path) },
                         onEdit = { onEditPlane(plane) })
        }
        ActionButton(stringResource(R.string.crew_add_aircraft), outlined = true, modifier = Modifier.fillMaxWidth()) {
            onEditPlane(null)
        }

        if (plane != null && plane.doubleSeater)
            CopilotChoice(state, onSelectCopilot, onAddCopilot)

        Caption(stringResource(R.string.pilot), Modifier.padding(start = 4.dp, top = 8.dp))
        SettingsGroup {
            LinkRow(state.pilot.ifEmpty { stringResource(R.string.crew_set_name) },
                    stringResource(R.string.crew_name_detail), onClick = onEditPilot)
        }

        state.error?.let { Text(it.text(), color = colors.warning, fontSize = 16.sp) }
    }
}

@Composable
internal fun PlaneRow(
    plane: PlaneInfo,
    selected: Boolean,
    lastFlown: Boolean,
    onSelect: () -> Unit,
    /** null: no "Edit" button */
    onEdit: (() -> Unit)?,
) {
    val colors = XcsTheme.colors
    val shape = RoundedCornerShape(14.dp)
    Row(Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .background(colors.panel, shape)
            .border(if (selected) 3.dp else 1.dp,
                    if (selected) colors.selected else colors.panelBorder, shape)
            .selectable(selected, role = Role.RadioButton, onClick = onSelect)
            .padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(listOf(plane.registration, plane.competitionId)
                     .filter { it.isNotEmpty() }.joinToString(" · "),
                 color = colors.text, fontSize = 19.sp, fontWeight = FontWeight.Bold,
                 maxLines = 1, overflow = TextOverflow.Ellipsis)
            val details = listOfNotNull(
                plane.type.ifEmpty { plane.polarName }.ifEmpty { null },
                if (plane.doubleSeater) stringResource(R.string.crew_two_seats) else null,
                if (lastFlown) stringResource(R.string.crew_last_flown) else null)
            Text(details.joinToString(" · "), color = colors.textSecondary, fontSize = 15.sp,
                 maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onEdit != null)
            ActionButton(stringResource(R.string.edit), onClick = onEdit)
    }
}

/** Solo, a co-pilot flown with before, or a new one. */
@Composable
private fun CopilotChoice(
    state: CrewState,
    onSelect: (String) -> Unit,
    onAdd: (String) -> Unit,
) {
    val colors = XcsTheme.colors
    Caption(stringResource(R.string.crew_copilot), Modifier.padding(start = 4.dp))
    Column(Modifier
               .fillMaxWidth()
               .background(colors.panel, RoundedCornerShape(14.dp))
               .selectableGroup()) {
        CopilotRow(stringResource(R.string.crew_solo), state.copilot.isEmpty()) { onSelect("") }
        for (name in state.copilots)
            CopilotRow(name, state.copilot == name) { onSelect(name) }
    }

    var name by rememberSaveable { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        InputField(name, { name = it }, stringResource(R.string.crew_new_copilot), Modifier.weight(1f),
                   capitalization = KeyboardCapitalization.Words)
        ActionButton(stringResource(R.string.add), outlined = true, enabled = name.isNotBlank()) {
            onAdd(name)
            name = ""
        }
    }
}

@Composable
private fun CopilotRow(name: String, selected: Boolean, onClick: () -> Unit) {
    val colors = XcsTheme.colors
    Row(Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(4.dp)
            .background(if (selected) colors.selected else Color.Transparent,
                        RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(name, color = if (selected) colors.onSelected else colors.text, fontSize = 17.sp,
             fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
