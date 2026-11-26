// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.task

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.xcsoar.mobile.R
import org.xcsoar.mobile.core.TaskFileInfo
import org.xcsoar.mobile.core.TaskInfo
import org.xcsoar.mobile.core.TaskPointInfo
import org.xcsoar.mobile.ui.Format
import org.xcsoar.mobile.ui.LinkRow
import org.xcsoar.mobile.ui.PageLayout
import org.xcsoar.mobile.ui.flight.Caption
import org.xcsoar.mobile.ui.flight.Stepper
import org.xcsoar.mobile.ui.theme.XcsTheme

/** What the task screen can ask for; no-ops by default (previews, tests). */
class TaskActions(
    val beginEdit: () -> Unit = {},
    val cancel: () -> Unit = {},
    val done: () -> Unit = {},
    val next: () -> Unit = {},
    val previous: () -> Unit = {},
    val restart: () -> Unit = {},
    val addPoint: () -> Unit = {},
    val select: (Int) -> Unit = {},
    val remove: (Int) -> Unit = {},
    val moveUp: (Int) -> Unit = {},
    val moveDown: (Int) -> Unit = {},
    val clear: () -> Unit = {},
    val setType: (Int) -> Unit = {},
    val setPointType: (Int, Int) -> Unit = { _, _ -> },
    val changeRadius: (Int, Int) -> Unit = { _, _ -> },
    val changeInnerRadius: (Int, Int) -> Unit = { _, _ -> },
    /** point, end radial (else start), direction */
    val changeRadial: (Int, Boolean, Int) -> Unit = { _, _, _ -> },
    val changeAngle: (Int, Int) -> Unit = { _, _ -> },
    val changeMinTime: (Int) -> Unit = {},
    val addOptionalStart: () -> Unit = {},
    val removeOptionalStart: (Int) -> Unit = {},
    /** fly the route again after "Go to" */
    val resume: () -> Unit = {},
    /** the start again as the last point: back home */
    val finishAtStart: () -> Unit = {},
    val openRules: () -> Unit = {},
    /** build the edited task on the map */
    val openMap: () -> Unit = {},
    /** move the targets of the active area task, from this area */
    val openTargets: (Int) -> Unit = {},
    val save: (String) -> Unit = {},
    val openFiles: () -> Unit = {},
    val dismissMessage: () -> Unit = {},
    /** a saved route on the map, to fly it or not */
    val preview: (TaskFileInfo) -> Unit = {},
    /** fly without a route */
    val stop: () -> Unit = {},
    /** build a new one */
    val newRoute: () -> Unit = {},
    val delete: (TaskFileInfo) -> Unit = {},
)

/** @param onPreview a saved route is loaded: show it on the map ([RoutePreviewPanel]) */
@Composable
fun TaskScreen(viewModel: TaskViewModel, onAddPoint: () -> Unit, onAddStart: () -> Unit,
               onOpenRules: () -> Unit, onOpenFiles: () -> Unit, onOpenMap: () -> Unit,
               onOpenTargets: (Int) -> Unit, onPreview: () -> Unit, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DisposableEffect(Unit) {
        viewModel.open()
        onDispose { viewModel.close() }
    }
    // leaving applies what was changed (or stays to show what is wrong)
    val leave = { viewModel.leave(onBack) }
    BackHandler(onBack = leave)
    TaskContent(state, leave, TaskActions(
        beginEdit = viewModel::beginEdit, cancel = viewModel::cancel, done = viewModel::done,
        next = viewModel::next, previous = viewModel::previous, restart = viewModel::restart,
        addPoint = onAddPoint, select = viewModel::select, remove = viewModel::remove,
        moveUp = viewModel::moveUp, moveDown = viewModel::moveDown, clear = viewModel::clear,
        setType = viewModel::setType, setPointType = viewModel::setPointType,
        changeRadius = viewModel::changeRadius, changeInnerRadius = viewModel::changeInnerRadius,
        changeRadial = viewModel::changeRadial, changeAngle = viewModel::changeAngle,
        changeMinTime = viewModel::changeMinTime,
        addOptionalStart = onAddStart, removeOptionalStart = viewModel::removeOptionalStart,
        resume = viewModel::resume, finishAtStart = viewModel::finishAtStart,
        openRules = onOpenRules, openMap = onOpenMap, openTargets = onOpenTargets,
        save = viewModel::save, openFiles = onOpenFiles,
        dismissMessage = viewModel::dismissMessage,
        preview = { viewModel.preview(it, onPreview) }, stop = viewModel::stop,
        newRoute = viewModel::newRoute, delete = viewModel::delete,
    ))
}

/**
 * The route: the one flown (next or previous point, restart, edit,
 * stop), alone, so that it does not change by a slip of the finger;
 * without one the pilot's saved routes, a tap shows one on the map to
 * fly it; or the one being built, which replaces the flown one on
 * "Done" or Back.
 */
@Composable
fun TaskContent(state: TaskState, onBack: () -> Unit, actions: TaskActions = TaskActions()) {
    val colors = XcsTheme.colors
    val task = state.task
    val editing = task?.editing == true
    val flying = task != null && task.points.isNotEmpty()

    PageLayout(bottom = when {
        // the edit replaces the flown route on "Done"
        editing -> ({
            TaskButton(stringResource(R.string.done), primary = true, modifier = Modifier.weight(1f),
                       onClick = actions.done)
        })
        task != null && !flying -> ({
            TaskButton(stringResource(R.string.route_new), primary = true,
                       modifier = Modifier.weight(1f), onClick = actions.newRoute)
        })
        else -> null
    }) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (editing) {
                TaskButton(stringResource(R.string.cancel), onClick = actions.cancel)
                Text(stringResource(R.string.task_edit_title), color = colors.text, fontSize = 24.sp,
                     fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            } else {
                TaskButton(stringResource(R.string.back), onClick = onBack)
                Text(stringResource(R.string.menu_task), color = colors.text, fontSize = 24.sp,
                     fontWeight = FontWeight.Bold)
            }
        }

        state.message?.let { message ->
            Text(message.text(), color = colors.onAlert, fontSize = 16.sp,
                 modifier = Modifier
                     .fillMaxWidth()
                     .background(colors.cautionContainer, RoundedCornerShape(12.dp))
                     .clickable(onClickLabel = stringResource(R.string.task_dismiss), onClick = actions.dismissMessage)
                     .padding(14.dp))
        }

        if (task == null) {
            Text(stringResource(R.string.flight_starting), color = colors.textSecondary, fontSize = 16.sp)
            return@PageLayout
        }

        if (editing) {
            Editor(task, state.selected, actions)
            return@PageLayout
        }

        if (flying) {
            // "Go to" flies elsewhere: the route waits, and says so
            if (task.paused) Paused(task.goto?.name.orEmpty(), actions.resume)
            Current(task, actions)
            return@PageLayout
        }

        Text(stringResource(R.string.route_none), color = colors.textSecondary, fontSize = 16.sp,
             modifier = Modifier.padding(horizontal = 4.dp))
        Routes(state.files, actions)

        // WeGlide's lists and the files of other names: loaded to review
        TaskButton(stringResource(R.string.route_more), outlined = true,
                   modifier = Modifier.fillMaxWidth()) {
            actions.beginEdit()
            actions.openFiles()
        }
    }
}

/** The route being built: its kind, points and zones, and how to add more. */
@Composable
private fun Editor(task: TaskInfo, selected: Int?, actions: TaskActions) {
    val colors = XcsTheme.colors
    Summary(task, editing = true, actions)

    if (task.points.isEmpty())
        Text(stringResource(R.string.task_empty_editing), color = colors.textSecondary, fontSize = 16.sp,
             modifier = Modifier.padding(horizontal = 4.dp))

    task.points.forEachIndexed { index, point ->
        PointRow(index, point, task, editing = true, selected == index, actions)
    }

    // back home: the start again, which "Done" makes the finish
    val start = task.points.firstOrNull()
    if (start != null && task.points.size >= 2 &&
        task.points.last().waypointId != start.waypointId)
        TaskButton(stringResource(R.string.route_finish_at, start.name.trim()), outlined = true,
                   modifier = Modifier.fillMaxWidth(), onClick = actions.finishAtStart)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TaskButton(stringResource(R.string.waypoints_add_point), primary = true, modifier = Modifier.weight(1f),
                   onClick = actions.addPoint)
        // tap the waypoints on the map instead
        TaskButton(stringResource(R.string.task_on_map), outlined = true,
                   modifier = Modifier.weight(1f), onClick = actions.openMap)
    }
}

/**
 * The route flown: its name and length, the points (the one flown to
 * in the task's colour), the moves along it, and Edit / Stop.
 */
@Composable
private fun Current(task: TaskInfo, actions: TaskActions) {
    val colors = XcsTheme.colors
    Column(Modifier
               .fillMaxWidth()
               .background(colors.panel, RoundedCornerShape(14.dp))
               .padding(14.dp),
           verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Caption(stringResource(R.string.route_current))
        RouteHeading(task)
        RoutePoints(task, flown = task.active.takeIf { !task.paused })

        // an area task: its targets, from the area flown to
        val areas = movableTargets(task)
        if (areas.isNotEmpty())
            TaskButton(stringResource(R.string.task_targets), outlined = true,
                       modifier = Modifier.fillMaxWidth()) {
                actions.openTargets(areas.firstOrNull { it >= (task.active ?: 0) } ?: areas[0])
            }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TaskButton(stringResource(R.string.task_previous), outlined = true, enabled = (task.active ?: 0) > 0,
                       modifier = Modifier.weight(1f), onClick = actions.previous)
            TaskButton(stringResource(R.string.next), outlined = true,
                       enabled = (task.active ?: 0) < task.points.size - 1,
                       modifier = Modifier.weight(1f), onClick = actions.next)
            TaskButton(stringResource(R.string.task_restart), outlined = true, modifier = Modifier.weight(1f),
                       onClick = actions.restart)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TaskButton(stringResource(R.string.edit), outlined = true, modifier = Modifier.weight(1f),
                       onClick = actions.beginEdit)
            // two taps: the route's progress goes with it
            ConfirmButton(stringResource(R.string.route_stop), stringResource(R.string.route_stop_confirm),
                          Modifier.weight(1f), onConfirm = actions.stop)
        }
        // the saved routes come with Stop, not by a slip of the finger
        Text(stringResource(R.string.route_switch_hint), color = colors.textSecondary,
             fontSize = 14.sp)
    }
}

/** A route's name, and its length and kind. */
@Composable
internal fun RouteHeading(task: TaskInfo) {
    val colors = XcsTheme.colors
    Text(task.name.ifBlank { routeName(task.points) }, color = colors.text, fontSize = 20.sp,
         fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
    val length = if (task.points.size >= 2)
        Format.distance(task.distance).let { "${it.text} ${it.unit} · " } else ""
    Text(length + task.typeName, color = colors.textSecondary, fontSize = 15.sp)
}

/** A route's points with their legs; the one [flown] to in the task's colour. */
@Composable
internal fun RoutePoints(task: TaskInfo, flown: Int? = null) {
    val colors = XcsTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        task.points.forEachIndexed { index, point ->
            val now = flown == index
            Row(Modifier.fillMaxWidth().heightIn(min = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(pointMark(index, point), color = if (now) colors.task else colors.textSecondary,
                     fontSize = 15.sp, fontWeight = FontWeight.Bold,
                     modifier = Modifier.widthIn(min = 18.dp))
                Text(point.name.trim(), color = if (now) colors.task else colors.text,
                     fontSize = 16.sp, fontWeight = if (now) FontWeight.Bold else FontWeight.Normal,
                     maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (index > 0)
                    Format.distance(point.leg).let {
                        Text("${it.text} ${it.unit}", color = colors.textSecondary,
                             style = XcsTheme.numberStyle, fontSize = 15.sp)
                    }
            }
        }
    }
}

/**
 * The pilot's saved routes, the last first: a tap shows one on the
 * map, to fly it.  Each route applied is kept (TaskViewModel.commit).
 */
@Composable
private fun Routes(files: List<TaskFileInfo>?, actions: TaskActions) {
    val colors = XcsTheme.colors
    Caption(stringResource(R.string.route_yours), Modifier.padding(start = 4.dp, top = 8.dp))
    if (files == null) return
    if (files.isEmpty()) {
        Text(stringResource(R.string.route_yours_empty), color = colors.textSecondary, fontSize = 15.sp,
             modifier = Modifier.padding(horizontal = 4.dp))
        return
    }
    for (file in files) {
        val name = file.routeName
        Row(Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .background(colors.panel, RoundedCornerShape(14.dp))
                .clickable(onClickLabel = stringResource(R.string.route_show, name)) {
                    actions.preview(file)
                }
                .padding(start = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(name, color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                 maxLines = 2, overflow = TextOverflow.Ellipsis,
                 modifier = Modifier.weight(1f).padding(vertical = 12.dp))
            if (file.path.endsWith(".tsk", ignoreCase = true))
                ConfirmButton(stringResource(R.string.route_delete),
                              stringResource(R.string.route_delete_confirm),
                              plain = true) { actions.delete(file) }
        }
    }
}

/**
 * A button that asks for a second tap within a few seconds: what it
 * does cannot be undone.  No dialog over the map in flight.
 */
@Composable
private fun ConfirmButton(label: String, confirmLabel: String, modifier: Modifier = Modifier,
                          plain: Boolean = false, onConfirm: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(CONFIRM_MILLIS)
            armed = false
        }
    }
    TaskButton(if (armed) confirmLabel else label, outlined = !plain || armed, modifier = modifier) {
        if (armed) {
            armed = false
            onConfirm()
        } else armed = true
    }
}

private const val CONFIRM_MILLIS = 3000L

/**
 * "Go to" flies to [name] instead: the route waits until the pilot
 * resumes it (XCSoar keeps it meanwhile).
 */
@Composable
private fun Paused(name: String, onResume: () -> Unit) {
    val colors = XcsTheme.colors
    Column(Modifier
               .fillMaxWidth()
               .background(colors.panel, RoundedCornerShape(14.dp))
               .border(2.dp, colors.neutralSafe, RoundedCornerShape(14.dp))
               .padding(14.dp),
           verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Caption(stringResource(R.string.route_paused))
        Text(stringResource(R.string.route_going_to, name.trim()), color = colors.text,
             fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2,
             overflow = TextOverflow.Ellipsis)
        TaskButton(stringResource(R.string.route_resume), primary = true,
                   modifier = Modifier.fillMaxWidth(), onClick = onResume)
    }
}

/** Task type, distance, area minimum time and what makes it invalid. */
@Composable
private fun Summary(task: TaskInfo, editing: Boolean, actions: TaskActions) {
    val colors = XcsTheme.colors
    var choosing by remember { mutableStateOf(false) }
    Column(Modifier
               .fillMaxWidth()
               .background(colors.panel, RoundedCornerShape(14.dp))
               .padding(14.dp),
           verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Caption(task.name.ifEmpty { stringResource(R.string.task_type) })
                Text(task.typeName, color = colors.text, fontSize = 20.sp,
                     fontWeight = FontWeight.Bold)
            }
            if (editing)
                TaskButton(stringResource(if (choosing) R.string.close else R.string.change), outlined = true) {
                    choosing = !choosing
                }
        }
        if (editing && choosing)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (choice in task.types)
                    Choice(choice.name, choice.type == task.type) {
                        actions.setType(choice.type)
                        choosing = false
                    }
            }

        if (task.points.size >= 2) {
            val distance = Format.distance(task.distance)
            val text = if (task.distanceMin != null && task.distanceMax != null)
                "${Format.distance(task.distanceMin).text}–" +
                    "${Format.distance(task.distanceMax).text} ${distance.unit} " +
                    "(${stringResource(R.string.task_nominal, distance.text)})"
            else
                "${distance.text} ${distance.unit}"
            Text(text, color = colors.text, style = XcsTheme.numberStyle, fontSize = 22.sp)
        }

        task.aatMinTime?.let { time ->
            val value = Format.duration(time).text
            if (editing)
                Stepper(stringResource(R.string.task_min_time_name),
                        "${stringResource(R.string.task_min_time)} · h:mm", value, value,
                        canDecrease = time > 0, canIncrease = true,
                        onDecrease = { actions.changeMinTime(-1) },
                        onIncrease = { actions.changeMinTime(+1) },
                        modifier = Modifier.fillMaxWidth())
            else
                Text("${stringResource(R.string.task_min_time)} $value", color = colors.textSecondary, fontSize = 15.sp)
        }

        if (editing && task.points.isNotEmpty() && !task.valid && task.errors.isNotBlank())
            Text(task.errors.trim(), color = colors.caution, fontSize = 15.sp)

        // the start gate and height limits: on the task page, as on a
        // briefing; changed on their own page
        val summary = rulesSummary(task.rules)
        if (editing)
            Box(Modifier.background(colors.sheet, RoundedCornerShape(12.dp))) {
                LinkRow(stringResource(R.string.task_rules),
                        summary.ifEmpty { stringResource(R.string.task_rule_none) },
                        onClick = actions.openRules)
            }
        else if (summary.isNotEmpty() && task.points.isNotEmpty())
            Text("${stringResource(R.string.task_rules)} · $summary",
                 color = colors.textSecondary, fontSize = 15.sp)
    }
}

@Composable
private fun pointLabel(index: Int, point: TaskPointInfo) = when (point.kind) {
    "start" -> stringResource(R.string.task_start)
    "finish" -> stringResource(R.string.task_finish)
    else -> stringResource(R.string.task_tp, index)
}

@Composable
private fun PointRow(index: Int, point: TaskPointInfo, task: TaskInfo, editing: Boolean,
                     selected: Boolean, actions: TaskActions) {
    val colors = XcsTheme.colors
    val flying = !editing && task.active == index
    Column(Modifier
               .fillMaxWidth()
               .background(colors.panel, RoundedCornerShape(14.dp))
               .then(if (flying) Modifier.border(2.dp, colors.task, RoundedCornerShape(14.dp))
                     else Modifier)) {
        Row(Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .then(if (editing) Modifier.clickable(onClickLabel = stringResource(R.string.task_change_point, point.name)) {
                    actions.select(index)
                } else Modifier)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier
                    .size(36.dp)
                    .background(if (flying) colors.task else colors.control, CircleShape),
                contentAlignment = Alignment.Center) {
                Text(when (point.kind) { "start" -> "S"; "finish" -> "F"; else -> "$index" },
                     color = if (flying) colors.onSelected else colors.text,
                     fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(point.name, color = colors.text, fontSize = 18.sp,
                     fontWeight = FontWeight.SemiBold, maxLines = 1,
                     overflow = TextOverflow.Ellipsis)
                val size = point.radius?.let { r ->
                    Format.distance(r).let { " · ${it.text} ${it.unit}" }
                }.orEmpty()
                Text("${pointLabel(index, point)} · ${point.typeName}$size",
                     color = colors.textSecondary, fontSize = 14.sp, maxLines = 1,
                     overflow = TextOverflow.Ellipsis)
                if (point.kind == "start" && task.optionalStarts.isNotEmpty())
                    Text(stringResource(R.string.task_or_starts,
                                        task.optionalStarts.joinToString { it.name.trim() }),
                         color = colors.textSecondary, fontSize = 14.sp, maxLines = 2,
                         overflow = TextOverflow.Ellipsis)
            }
            if (index > 0) {
                val leg = Format.distance(point.leg)
                Text("${leg.text} ${leg.unit}", color = colors.textSecondary,
                     style = XcsTheme.numberStyle, fontSize = 16.sp)
            }
        }

        if (editing && selected)
            PointEditor(index, point, task, actions)
    }
}

/** Zone type and size, order, removal of one point. */
@Composable
private fun PointEditor(index: Int, point: TaskPointInfo, task: TaskInfo, actions: TaskActions) {
    val count = task.points.size
    Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
           verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (point.types.size > 1)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (choice in point.types)
                    Choice(choice.name, choice.type == point.type) {
                        actions.setPointType(index, choice.type)
                    }
            }
        val zone = point.zone
        zone.radius?.let { radius ->
            val size = Format.distance(radius)
            val value = size.text
            Stepper(stringResource(R.string.task_zone_size_name),
                    if (zone.shape == "line")
                        "${stringResource(R.string.task_half_width)} · ${size.unit}"
                    else "${stringResource(R.string.task_radius)} · ${size.unit}",
                    value, "$value ${size.unit}",
                    canDecrease = radius > 100, canIncrease = true,
                    onDecrease = { actions.changeRadius(index, -1) },
                    onIncrease = { actions.changeRadius(index, +1) },
                    modifier = Modifier.fillMaxWidth())
        }
        zone.innerRadius?.let { radius ->
            val size = Format.distance(radius)
            val name = stringResource(R.string.task_inner_radius)
            Stepper(name, "$name · ${size.unit}", size.text, "${size.text} ${size.unit}",
                    canDecrease = radius > 100, canIncrease = true,
                    onDecrease = { actions.changeInnerRadius(index, -1) },
                    onIncrease = { actions.changeInnerRadius(index, +1) },
                    modifier = Modifier.fillMaxWidth())
        }
        // upstream's sector editor: the radials, or the angle of a sector
        // centred on the legs' bisector
        zone.startRadial?.let {
            AngleStepper(stringResource(R.string.task_start_radial), it, bearing = true,
                         onChange = { d -> actions.changeRadial(index, false, d) })
        }
        zone.endRadial?.let {
            AngleStepper(stringResource(R.string.task_end_radial), it, bearing = true,
                         onChange = { d -> actions.changeRadial(index, true, d) })
        }
        zone.angle?.let {
            AngleStepper(stringResource(R.string.task_sector_angle), it, bearing = false,
                         canDecrease = it > TaskViewModel.ANGLE_STEP, canIncrease = it < 360,
                         onChange = { d -> actions.changeAngle(index, d) })
        }
        if (point.kind == "start")
            OptionalStarts(task, actions)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TaskButton(stringResource(R.string.task_up), outlined = true, enabled = index > 0,
                       modifier = Modifier.weight(1f)) { actions.moveUp(index) }
            TaskButton(stringResource(R.string.task_down), outlined = true, enabled = index < count - 1,
                       modifier = Modifier.weight(1f)) { actions.moveDown(index) }
            TaskButton(stringResource(R.string.data_remove), outlined = true, modifier = Modifier.weight(1f)) {
                actions.remove(index)
            }
        }
    }
}

/** Degrees: a bearing ("045°") or an angle ("90°"), in [TaskViewModel.ANGLE_STEP]s. */
@Composable
private fun AngleStepper(name: String, degrees: Double, bearing: Boolean,
                         canDecrease: Boolean = true, canIncrease: Boolean = true,
                         onChange: (Int) -> Unit) {
    val text = if (bearing) Format.bearing(degrees).text else Math.round(degrees).toString()
    Stepper(name, "$name · °", "$text°", "$text°",
            canDecrease = canDecrease, canIncrease = canIncrease,
            onDecrease = { onChange(-1) }, onIncrease = { onChange(+1) },
            modifier = Modifier.fillMaxWidth())
}

/**
 * The other starts the pilot may take (upstream's optional starts):
 * XCSoar takes the one flown through.
 */
@Composable
private fun OptionalStarts(task: TaskInfo, actions: TaskActions) {
    val colors = XcsTheme.colors
    Caption(stringResource(R.string.task_other_starts), Modifier.padding(start = 4.dp))
    task.optionalStarts.forEachIndexed { i, start ->
        Row(Modifier
                .fillMaxWidth()
                .background(colors.sheet, RoundedCornerShape(12.dp))
                .padding(start = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(start.name.trim(), color = colors.text, fontSize = 17.sp, maxLines = 1,
                 overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            TaskButton(stringResource(R.string.data_remove)) { actions.removeOptionalStart(i) }
        }
    }
    TaskButton(stringResource(R.string.task_add_start), outlined = true,
               modifier = Modifier.fillMaxWidth(), onClick = actions.addOptionalStart)
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = XcsTheme.colors
    Box(Modifier
            .heightIn(min = 48.dp)
            .background(if (selected) colors.selected else colors.control,
                        RoundedCornerShape(10.dp))
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center) {
        Text(label, color = if (selected) colors.onSelected else colors.text,
             fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** A 56 dp button: filled, outlined or plain text. */
@Composable
internal fun TaskButton(
    label: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    outlined: Boolean = false,
    enabled: Boolean = true,
    /** what a screen reader says, when [label] is a symbol */
    description: String = label,
    onClick: () -> Unit,
) {
    val colors = XcsTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Box(modifier
            .height(56.dp)
            .widthIn(min = 88.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .background(when {
                primary -> colors.selected
                outlined -> colors.control
                else -> Color.Transparent
            }, shape)
            .then(if (outlined) Modifier.border(1.dp, colors.panelBorder, shape) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center) {
        Text(label, color = if (primary) colors.onSelected else colors.text,
             fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}
