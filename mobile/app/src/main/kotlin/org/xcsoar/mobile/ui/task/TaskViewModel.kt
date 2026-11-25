// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.task

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.xcsoar.mobile.R
import org.xcsoar.mobile.core.TaskFileInfo
import org.xcsoar.mobile.core.TaskInfo
import org.xcsoar.mobile.core.TaskOp
import org.xcsoar.mobile.core.TaskPointInfo
import org.xcsoar.mobile.core.TaskRule
import org.xcsoar.mobile.core.WaypointInfo
import org.xcsoar.mobile.core.WeGlideTask
import org.xcsoar.mobile.core.WeGlideTaskList
import org.xcsoar.mobile.core.XcsoarCore
import org.xcsoar.mobile.ui.UiText
import org.xcsoar.mobile.ui.asText
import org.xcsoar.mobile.ui.text
import java.io.File

data class TaskState(
    /** The active task, or the edited copy while [TaskInfo.editing]. */
    val task: TaskInfo? = null,
    /** The point whose zone is being changed (editing only). */
    val selected: Int? = null,
    /** The task files, once listed. */
    val files: List<TaskFileInfo>? = null,
    /** The WeGlide list shown, its tasks once downloaded, or why not. */
    val weGlideList: WeGlideTaskList = WeGlideTaskList.MINE,
    val weGlideTasks: List<WeGlideTask>? = null,
    val weGlideError: UiText? = null,
    /** Something the pilot asked for did not work. */
    val message: UiText? = null,
)

/**
 * XCSoar's task manager: the pilot edits a copy of the task, which
 * becomes the active one on [done].  Without an edit, the active task
 * is shown and can be advanced or restarted.
 */
class TaskViewModel(private val core: XcsoarCore) : ViewModel() {
    private val stateFlow = MutableStateFlow(TaskState())
    val state: StateFlow<TaskState> = stateFlow.asStateFlow()

    private val editing get() = stateFlow.value.task?.editing == true

    /** The edit changed something: leaving the page applies it ([leave]). */
    private var changed = false

    private var job: Job? = null

    /* the screens showing the task: the panel under the map and the
       task page overlap while one replaces the other */
    private var openCount = 0

    /** Shown: read the task, and the active one again while flying it. */
    fun open() {
        if (openCount++ > 0) return
        listFiles()
        job = viewModelScope.launch {
            refresh()
            // no route yet: straight to building one
            val task = stateFlow.value.task
            if (task != null && !task.editing && task.points.isEmpty()) beginEdit()
            while (isActive) {
                delay(REFRESH_MS)
                refresh()
            }
        }
    }

    fun close() {
        if (openCount == 0 || --openCount > 0) return
        job?.cancel()
        job = null
    }

    private suspend fun refresh() {
        val task = try {
            core.task(editing) ?: if (editing) core.task(false) else null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        stateFlow.update { it.copy(task = task) }
    }

    /**
     * Run one change, then show the result: [op]'s message (null for
     * none), or [refused] if it failed.
     */
    private fun run(refused: UiText?, op: suspend () -> UiText?) {
        viewModelScope.launch {
            val message = try {
                op()
            } catch (_: Exception) {
                refused
            }
            stateFlow.update { it.copy(message = message) }
            refresh()
        }
    }

    private fun edit(op: TaskOp, index: Int = 0, value: Double = 0.0,
                     refused: UiText = text(R.string.task_not_possible)) {
        if (editing) changed = true
        run(refused) { core.editTask(op, index, value); null }
    }

    fun dismissMessage() = stateFlow.update { it.copy(message = null) }

    /* the active task */

    fun beginEdit() = run(text(R.string.task_cannot_edit)) {
        changed = false
        core.editTask(TaskOp.BEGIN)
        stateFlow.update { it.copy(task = core.task(true), selected = null) }
        null
    }

    fun next() = edit(TaskOp.ADVANCE, value = 1.0, refused = text(R.string.task_no_next))
    fun previous() = edit(TaskOp.ADVANCE, value = -1.0, refused = text(R.string.task_no_previous))
    fun restart() = edit(TaskOp.RESTART)

    /** Fly the route again after "Go to" flew elsewhere. */
    fun resume() = edit(TaskOp.RESUME)

    /* editing */

    fun cancel() = run(null) {
        changed = false
        core.editTask(TaskOp.CANCEL)
        stateFlow.update { it.copy(task = core.task(false), selected = null) }
        null
    }

    /** Make the edited task the active one, unless it is not valid. */
    fun done() {
        viewModelScope.launch { commit() }
    }

    /**
     * [done]; false, with what is wrong, when the task is not valid.
     * Every route applied is kept in XCSoarData/tasks, by its name or
     * one made of its points, so that the pilot switches back to it in
     * one tap ([fly]).
     */
    private suspend fun commit(): Boolean = try {
        val edited = core.task(true)
        if (edited != null && edited.points.size >= 2)
            try {
                core.saveTask(edited.name.ifBlank { routeName(edited.points) })
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // flown all the same; only not listed
            }
        core.editTask(TaskOp.COMMIT)
        changed = false
        stateFlow.update { it.copy(task = core.task(false), selected = null, message = null) }
        listFiles()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        val errors = core.task(true)?.errors?.trim().orEmpty()
        stateFlow.update {
            it.copy(message = errors.takeIf { e -> e.isNotEmpty() }?.asText()
                ?: text(R.string.task_invalid))
        }
        refresh()
        false
    }

    /**
     * The pilot leaves the page (Back): what they changed is applied,
     * as on "Done", rather than left waiting unseen while the old route
     * guides; a route that is not valid keeps them on the page with what
     * is wrong ("Cancel" drops it).  Nothing changed: the edit goes.
     */
    fun leave(onLeft: () -> Unit) {
        if (!editing) {
            onLeft()
            return
        }
        viewModelScope.launch {
            if (changed) {
                if (!commit()) return@launch
            } else {
                try {
                    core.editTask(TaskOp.CANCEL)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // the core stopped: nothing to keep
                }
                val active = try {
                    core.task(false)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                stateFlow.update { it.copy(task = active, selected = null) }
            }
            onLeft()
        }
    }

    /**
     * Fly one of the saved routes ([TaskState.files]) now, instead of
     * the current one: no editing, no "Done".
     */
    fun fly(file: TaskFileInfo) = run(text(R.string.task_cannot_load, file.name)) {
        if (editing) core.editTask(TaskOp.CANCEL)
        core.loadTask(file)
        core.editTask(TaskOp.COMMIT)
        changed = false
        stateFlow.update { it.copy(task = core.task(false), selected = null) }
        null
    }

    /** Fly without a route; it stays in the list. */
    fun stop() = run(text(R.string.task_cannot_edit)) {
        core.editTask(TaskOp.BEGIN)
        core.editTask(TaskOp.CLEAR)
        core.editTask(TaskOp.COMMIT)
        changed = false
        stateFlow.update { it.copy(task = core.task(false), selected = null) }
        null
    }

    /** An empty route to build; the current one flies until it is applied. */
    fun newRoute() = run(text(R.string.task_cannot_edit)) {
        core.editTask(TaskOp.BEGIN)
        core.editTask(TaskOp.CLEAR)
        changed = false
        stateFlow.update { it.copy(task = core.task(true), selected = null) }
        null
    }

    /** Forget a saved route (only Buteo's and XCSoar's own .tsk files). */
    fun delete(file: TaskFileInfo) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                File(file.path).takeIf { it.name.endsWith(".tsk", ignoreCase = true) }?.delete()
            }
            listFiles()
        }
    }

    /**
     * An out-and-return or a triangle back home: the start again, as the
     * last point (the finish once applied).
     */
    fun finishAtStart() {
        val start = stateFlow.value.task?.points?.firstOrNull() ?: return
        edit(TaskOp.APPEND, value = start.waypointId.toDouble(), refused = text(R.string.task_full))
    }

    fun add(waypoint: WaypointInfo) =
        edit(TaskOp.APPEND, value = waypoint.id.toDouble(), refused = text(R.string.task_full))

    fun select(index: Int?) = stateFlow.update {
        it.copy(selected = if (it.selected == index) null else index)
    }

    fun remove(index: Int) {
        stateFlow.update { it.copy(selected = null) }
        edit(TaskOp.REMOVE, index)
    }

    fun moveUp(index: Int) {
        if (index == 0) return
        stateFlow.update { it.copy(selected = index - 1) }
        edit(TaskOp.SWAP, index - 1)
    }

    fun moveDown(index: Int) {
        stateFlow.update { it.copy(selected = index + 1) }
        edit(TaskOp.SWAP, index)
    }

    fun clear() {
        stateFlow.update { it.copy(selected = null) }
        edit(TaskOp.CLEAR)
    }

    fun setType(type: Int) = edit(TaskOp.SET_TYPE, value = type.toDouble())

    fun setPointType(index: Int, type: Int) =
        edit(TaskOp.SET_POINT_TYPE, index, type.toDouble())

    /** One step bigger (+1) or smaller (-1), see [radiusStep]. */
    fun changeRadius(index: Int, direction: Int) {
        val radius = stateFlow.value.task?.points?.getOrNull(index)?.radius ?: return
        val step = radiusStep(if (direction > 0) radius else radius - 1)
        val value = (radius + direction * step).coerceAtLeast(MIN_RADIUS)
        showPoint(index) { it.copy(radius = value, zone = it.zone.copy(radius = value)) }
        edit(TaskOp.SET_RADIUS, index, value)
    }

    /**
     * Show a stepper's new value at once: taps come faster than the
     * core's answer, and each must step from the one before.
     */
    private fun showPoint(index: Int, change: (TaskPointInfo) -> TaskPointInfo) =
        stateFlow.update { state ->
            state.copy(task = state.task?.let { task ->
                task.copy(points = task.points.mapIndexed { i, p -> if (i == index) change(p) else p })
            })
        }

    private fun point(index: Int): TaskPointInfo? = stateFlow.value.task?.points?.getOrNull(index)

    /** The inner radius of an annular sector or keyhole, as [changeRadius]. */
    fun changeInnerRadius(index: Int, direction: Int) {
        val radius = point(index)?.zone?.innerRadius ?: return
        val step = radiusStep(if (direction > 0) radius else radius - 1)
        val value = (radius + direction * step).coerceAtLeast(MIN_RADIUS)
        showPoint(index) { it.copy(zone = it.zone.copy(innerRadius = value)) }
        edit(TaskOp.SET_INNER_RADIUS, index, value)
    }

    /** A sector's start (or [end]) radial, ± [ANGLE_STEP] degrees. */
    fun changeRadial(index: Int, end: Boolean, direction: Int) {
        val zone = point(index)?.zone ?: return
        val radial = (if (end) zone.endRadial else zone.startRadial) ?: return
        val value = ((radial + direction * ANGLE_STEP) % 360 + 360) % 360
        showPoint(index) {
            it.copy(zone = if (end) it.zone.copy(endRadial = value)
                           else it.zone.copy(startRadial = value))
        }
        edit(if (end) TaskOp.SET_END_RADIAL else TaskOp.SET_START_RADIAL, index, value)
    }

    /** A symmetric sector's or keyhole's angle, ± [ANGLE_STEP] degrees. */
    fun changeAngle(index: Int, direction: Int) {
        val angle = point(index)?.zone?.angle ?: return
        val value = (angle + direction * ANGLE_STEP).coerceIn(ANGLE_STEP, 360.0)
        showPoint(index) { it.copy(zone = it.zone.copy(angle = value)) }
        edit(TaskOp.SET_SECTOR_ANGLE, index, value)
    }

    /** One of the task's rules, in the core's units ([TaskRule]). */
    fun setRule(rule: TaskRule, value: Double) = edit(TaskOp.SET_RULE, rule.code, value)

    fun addOptionalStart(waypoint: WaypointInfo) =
        edit(TaskOp.ADD_OPTIONAL_START, value = waypoint.id.toDouble())

    fun removeOptionalStart(index: Int) = edit(TaskOp.REMOVE_OPTIONAL_START, index)

    /* the targets of the active area task, as upstream's target dialog */

    /** ± [TARGET_RANGE_STEP], from the nearest (-1) to the farthest (1) point. */
    fun changeTargetRange(index: Int, direction: Int) {
        val target = point(index)?.target ?: return
        val value = (target.range + direction * TARGET_RANGE_STEP).coerceIn(-1.0, 1.0)
        // moved by the pilot: locked, as the core does
        showPoint(index) { it.copy(target = target.copy(range = value, locked = true)) }
        edit(TaskOp.SET_TARGET_RANGE, index, value)
    }

    /** ± [TARGET_RADIAL_STEP] degrees from the leg, -180..180. */
    fun changeTargetRadial(index: Int, direction: Int) {
        val target = point(index)?.target ?: return
        var radial = target.radial + direction * TARGET_RADIAL_STEP
        if (radial > 180) radial -= 360
        if (radial < -180) radial += 360
        showPoint(index) { it.copy(target = target.copy(radial = radial, locked = true)) }
        edit(TaskOp.SET_TARGET_RADIAL, index, radial)
    }

    /** Locked, or moved by XCSoar's optimiser. */
    fun setTargetLocked(index: Int, locked: Boolean) =
        edit(TaskOp.LOCK_TARGET, index, if (locked) 1.0 else 0.0)

    /** A tap on the map while building the route: [waypointId] under the finger. */
    fun addWaypoint(waypointId: Int?) {
        viewModelScope.launch { append(waypointId) }
    }

    private suspend fun append(waypointId: Int?) {
        if (editing) changed = true
        val message = if (waypointId == null) text(R.string.task_map_no_waypoint)
        else try {
            core.editTask(TaskOp.APPEND, value = waypointId.toDouble())
            null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            text(R.string.task_full)
        }
        stateFlow.update { it.copy(message = message) }
        refresh()
    }

    /** The target there, if inside its area. */
    fun moveTarget(index: Int, latitude: Double, longitude: Double) =
        run(text(R.string.task_target_outside)) {
            core.setTaskTarget(index, latitude, longitude)
            null
        }

    /** ± [AAT_TIME_STEP] seconds. */
    fun changeMinTime(direction: Int) {
        val time = stateFlow.value.task?.aatMinTime ?: return
        val value = (time + direction * AAT_TIME_STEP).coerceIn(0.0, MAX_AAT_TIME)
        stateFlow.update { it.copy(task = it.task?.copy(aatMinTime = value)) }
        edit(TaskOp.SET_AAT_MIN_TIME, value = value)
    }

    /* files */

    /**
     * The saved routes, the last saved first, then those of other files
     * (SeeYou's); not XCSoar's copy of the current one (Default.tsk).
     */
    fun listFiles() {
        viewModelScope.launch {
            val files = try {
                val all = core.taskFiles()
                withContext(Dispatchers.IO) {
                    all.filterNot { File(it.path).name.equals("Default.tsk", ignoreCase = true) }
                        .sortedWith(compareBy<TaskFileInfo> { !it.path.endsWith(".tsk", true) }
                                        .thenByDescending { File(it.path).lastModified() })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            stateFlow.update { it.copy(files = files) }
        }
    }

    /* WeGlide, as upstream's WeGlide tasks panel */

    fun listWeGlide(list: WeGlideTaskList) {
        stateFlow.update { it.copy(weGlideList = list, weGlideTasks = null, weGlideError = null) }
        viewModelScope.launch {
            try {
                val tasks = core.weGlideTasks(list)
                stateFlow.update { if (it.weGlideList == list) it.copy(weGlideTasks = tasks) else it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                stateFlow.update {
                    if (it.weGlideList != list) it
                    else it.copy(weGlideTasks = emptyList(),
                                 weGlideError = e.message?.asText()
                                     ?: text(R.string.task_weglide_silent))
                }
            }
        }
    }

    /** Download it into the editor; the pilot reviews it and taps Done. */
    fun loadWeGlide(task: WeGlideTask) {
        viewModelScope.launch {
            changed = true
            val message = try {
                core.loadWeGlideTask(task.id)
                stateFlow.update { it.copy(selected = null) }
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val name = task.name.ifEmpty { "WeGlide" }
                e.message?.let { "$name: $it".asText() }
                    ?: text(R.string.task_weglide_not_loaded, name)
            }
            stateFlow.update { it.copy(message = message) }
            refresh()
        }
    }

    fun load(file: TaskFileInfo) = run(text(R.string.task_cannot_load, file.name)) {
        changed = true
        core.loadTask(file)
        stateFlow.update { it.copy(task = core.task(true), selected = null) }
        null
    }

    /** Save the edited task as XCSoarData/tasks/<name>.tsk. */
    fun save(name: String) {
        val clean = name.trim()
        if (clean.isEmpty() || clean.any { it == '/' || it == '\\' }) {
            stateFlow.update { it.copy(message = text(R.string.task_not_file_name, name)) }
            return
        }
        run(text(R.string.task_cannot_save, clean)) {
            core.saveTask(clean)
            text(R.string.task_saved, clean)
        }
    }

    companion object {
        private const val REFRESH_MS = 3000L
        private const val MIN_RADIUS = 100.0
        const val AAT_TIME_STEP = 15 * 60.0
        /** Zone radials and angles, as upstream's zone editors. */
        const val ANGLE_STEP = 10.0
        const val TARGET_RANGE_STEP = 0.05
        const val TARGET_RADIAL_STEP = 5.0
        private const val MAX_AAT_TIME = 12 * 3600.0

        /** Zone size steps: 100 m below 1 km, 500 m below 5 km, else 1 km. */
        fun radiusStep(radius: Double) = when {
            radius < 1000 -> 100.0
            radius < 5000 -> 500.0
            else -> 1000.0
        }
    }
}

/**
 * A route's name from its points: "Montelimar Ancone - Sagy - Grenoble
 * Isere", without the leading codes ("LFLQ", "LF7121", "110:"), what
 * follows a dash ("Remoulins – ULM LF3025"), and the finish where it is
 * the start (back home); at most 60 characters.
 */
internal fun routeName(points: List<TaskPointInfo>): String {
    val code = Regex("^([A-Z0-9]{4,7}|[0-9]+:)\\s+")
    fun short(name: String): String {
        val plain = name.trim().replace(Regex("\\s+"), " ")
        val named = code.replace(plain, "").ifEmpty { plain }
        return named.substringBefore(" – ").substringBefore(" - ").trim()
    }
    val names = points.map { short(it.name) }.toMutableList()
    if (names.size >= 2 && points.last().waypointId == points.first().waypointId)
        names.removeAt(names.size - 1)
    val name = names.joinToString(" - ").map { if (it in "/\\:*?\"<>|") '-' else it }
        .joinToString("")
    return if (name.length <= 60) name
    else name.take(60).substringBeforeLast(' ').trimEnd(' ', '-')
}

/** What the list shows for a route file: a .tsk by its file name. */
val TaskFileInfo.routeName: String
    get() = if (path.endsWith(".tsk", ignoreCase = true)) File(path).nameWithoutExtension else name
