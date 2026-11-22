// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.task

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.xcsoar.mobile.R
import org.xcsoar.mobile.core.TaskInfo
import org.xcsoar.mobile.core.TaskPointInfo
import org.xcsoar.mobile.ui.Format
import org.xcsoar.mobile.ui.SettingsGroup
import org.xcsoar.mobile.ui.SwitchRow
import org.xcsoar.mobile.ui.ValueList
import org.xcsoar.mobile.ui.flight.Caption
import org.xcsoar.mobile.ui.flight.Stepper
import org.xcsoar.mobile.ui.theme.XcsTheme
import kotlin.math.roundToInt

/** What the panels under the map ask for; no-ops by default (tests). */
class TaskMapActions(
    val select: (Int) -> Unit = {},
    val changeRadius: (Int, Int) -> Unit = { _, _ -> },
    val remove: (Int) -> Unit = {},
    val done: () -> Unit = {},
    /** back to the task page, the edit going on */
    val list: () -> Unit = {},
    /** another area's target */
    val showTarget: (Int) -> Unit = {},
    val changeRange: (Int, Int) -> Unit = { _, _ -> },
    val changeRadial: (Int, Int) -> Unit = { _, _ -> },
    val setLocked: (Int, Boolean) -> Unit = { _, _ -> },
    val close: () -> Unit = {},
    val dismissMessage: () -> Unit = {},
)

/**
 * Building the edited task on the map: the map shows it and a tap adds
 * the waypoint under the finger (MainActivity); this is under the map.
 * Leaves when the edit ends ("Done").
 */
@Composable
fun TaskEditPanel(viewModel: TaskViewModel, onList: () -> Unit, onClose: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DisposableEffect(Unit) {
        viewModel.open()
        onDispose { viewModel.close() }
    }
    // "Done" made it the active task (or something else ended the edit)
    val editing = state.task?.editing
    LaunchedEffect(editing) { if (editing == false) onClose() }
    BackHandler(onBack = onList)
    TaskEditPanelContent(state, TaskMapActions(
        select = viewModel::select, changeRadius = viewModel::changeRadius,
        remove = viewModel::remove, done = viewModel::done, list = onList,
        dismissMessage = viewModel::dismissMessage))
}

@Composable
fun TaskEditPanelContent(state: TaskState, actions: TaskMapActions = TaskMapActions()) {
    val colors = XcsTheme.colors
    val task = state.task ?: return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Caption(stringResource(R.string.task_edit_title))
                Text(listOfNotNull(task.typeName,
                                   task.distance.takeIf { task.points.size >= 2 }?.let {
                                       Format.distance(it).let { d -> "${d.text} ${d.unit}" }
                                   }).joinToString(" · "),
                     color = colors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                     maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TaskButton(stringResource(R.string.task_list), outlined = true, onClick = actions.list)
        }

        // what went wrong (a tap beside the waypoints, "Done" on a task
        // that is not valid), else what to do; while building, the last
        // point is not the finish yet: "Done" makes it one
        val message = state.message
        Text(message?.text()
                 ?: stringResource(if (task.points.isEmpty()) R.string.task_map_hint_start
                                   else R.string.task_map_hint),
             color = if (message != null) colors.caution else colors.textSecondary,
             fontSize = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)

        if (task.points.isNotEmpty())
            PointChips(task, state.selected, actions.select)

        state.selected?.let { task.points.getOrNull(it) }?.let { point ->
            val index = state.selected
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                point.zone.radius?.let { radius ->
                    val size = Format.distance(radius)
                    Stepper(stringResource(R.string.task_zone_size_name),
                            "${stringResource(if (point.zone.shape == "line") R.string.task_half_width
                                              else R.string.task_radius)} · ${size.unit}",
                            size.text, "${size.text} ${size.unit}",
                            canDecrease = radius > 100, canIncrease = true,
                            onDecrease = { actions.changeRadius(index, -1) },
                            onIncrease = { actions.changeRadius(index, +1) },
                            modifier = Modifier.weight(1f))
                }
                TaskButton(stringResource(R.string.data_remove), outlined = true) {
                    actions.remove(index)
                }
            }
        }

        TaskButton(stringResource(R.string.done), primary = true, modifier = Modifier.fillMaxWidth(),
                   onClick = actions.done)
    }
}

/** "S", "1", "2", "F" with the names: tap one to change its zone. */
@Composable
private fun PointChips(task: TaskInfo, selected: Int?, onSelect: (Int) -> Unit) {
    val colors = XcsTheme.colors
    Row(Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        task.points.forEachIndexed { i, point ->
            val isSelected = selected == i
            Text("${pointMark(i, point)} ${point.name.trim()}",
                 color = if (isSelected) colors.onSelected else colors.text,
                 fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                 modifier = Modifier
                     .heightIn(min = 48.dp)
                     // on the sheet: the panel's grey, as the tiles
                     .background(if (isSelected) colors.selected else colors.panel,
                                 RoundedCornerShape(10.dp))
                     .selectable(isSelected, role = Role.RadioButton) { onSelect(i) }
                     .padding(horizontal = 14.dp, vertical = 13.dp))
        }
    }
}

private fun pointMark(index: Int, point: TaskPointInfo) = when (point.kind) {
    "start" -> "S"
    "finish" -> "F"
    else -> "$index"
}

/**
 * Moving the target of area [index] of the active task, as upstream's
 * target dialog: the map frames the area and a tap inside puts the
 * target there (MainActivity); this is under the map.
 */
@Composable
fun TargetPanel(viewModel: TaskViewModel, index: Int, onShowTarget: (Int) -> Unit,
                onClose: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DisposableEffect(Unit) {
        viewModel.open()
        onDispose { viewModel.close() }
    }
    BackHandler(onBack = onClose)
    TargetPanelContent(state, index, TaskMapActions(
        showTarget = onShowTarget, changeRange = viewModel::changeTargetRange,
        changeRadial = viewModel::changeTargetRadial, setLocked = viewModel::setTargetLocked,
        close = onClose, dismissMessage = viewModel::dismissMessage))
}

/** The areas whose target the pilot may still move. */
internal fun movableTargets(task: TaskInfo?): List<Int> =
    task?.points?.indices?.filter { task.points[it].target?.movable == true }.orEmpty()

@Composable
fun TargetPanelContent(state: TaskState, index: Int, actions: TaskMapActions = TaskMapActions()) {
    val colors = XcsTheme.colors
    val task = state.task ?: return
    val point = task.points.getOrNull(index) ?: return
    val target = point.target ?: return
    val areas = movableTargets(task)
    val at = areas.indexOf(index)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TaskButton("‹", outlined = true, enabled = at > 0,
                       modifier = Modifier.width(56.dp),
                       description = stringResource(R.string.task_previous_area)) {
                actions.showTarget(areas[at - 1])
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Caption(stringResource(R.string.task_target_of, stringResource(R.string.task_tp, index)))
                Text(point.name.trim(), color = colors.text, fontSize = 20.sp,
                     fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TaskButton("›", outlined = true, enabled = at >= 0 && at < areas.size - 1,
                       modifier = Modifier.width(56.dp),
                       description = stringResource(R.string.task_next_area)) {
                actions.showTarget(areas[at + 1])
            }
        }

        Text(state.message?.text() ?: stringResource(R.string.task_target_hint),
             color = if (state.message != null) colors.caution else colors.textSecondary,
             fontSize = 15.sp)

        // upstream's "Distance": -100 % the nearest point of the area,
        // 100 % the farthest; the radial turns it from the leg
        val range = "${(target.range * 100).roundToInt()} %"
        Stepper(stringResource(R.string.task_target_range), stringResource(R.string.task_target_range),
                range, range, canDecrease = target.range > -1, canIncrease = target.range < 1,
                onDecrease = { actions.changeRange(index, -1) },
                onIncrease = { actions.changeRange(index, +1) },
                modifier = Modifier.fillMaxWidth())
        val radial = "${target.radial.roundToInt()}°"
        Stepper(stringResource(R.string.task_target_radial), stringResource(R.string.task_target_radial),
                radial, radial, canDecrease = true, canIncrease = true,
                onDecrease = { actions.changeRadial(index, -1) },
                onIncrease = { actions.changeRadial(index, +1) },
                modifier = Modifier.fillMaxWidth())

        SettingsGroup {
            SwitchRow(stringResource(R.string.task_target_optimised),
                      stringResource(R.string.task_target_optimised_detail), !target.locked) {
                actions.setLocked(index, !it)
            }
        }

        // upstream's numbers: the task time against the minimum, the speeds
        task.area?.takeIf { it.estimatedTime > 0 }?.let { area ->
            val delta = area.timeDelta
            ValueList(listOfNotNull(
                stringResource(R.string.task_area_time) to Format.duration(area.estimatedTime).text,
                stringResource(R.string.task_area_delta) to
                    (if (delta < 0) "−" else "+") + Format.duration(kotlin.math.abs(delta)).text,
                area.speedRemaining?.let {
                    stringResource(R.string.task_speed_remaining) to
                        Format.taskSpeed(it).let { v -> "${v.text} ${v.unit}" }
                },
                area.speedAchieved?.let {
                    stringResource(R.string.task_speed_achieved) to
                        Format.taskSpeed(it).let { v -> "${v.text} ${v.unit}" }
                }))
        }

        TaskButton(stringResource(R.string.done), primary = true, modifier = Modifier.fillMaxWidth(),
                   onClick = actions.close)
    }
}
