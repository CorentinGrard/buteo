// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

/** A task or task point type the core allows, with XCSoar's name. */
@Serializable
data class TaskTypeChoice(val type: Int, val name: String)

/**
 * A point's observation zone: its shape and the sizes the pilot may
 * change there (null: fixed by the task's rules, or not this shape),
 * as upstream's zone editors.
 */
@Serializable
data class ZoneInfo(
    /** "cylinder", "line", "sector", "annular_sector", "symmetric_sector", "keyhole", … */
    val shape: String = "",
    /** m; lines: half their length */
    val radius: Double? = null,
    /** m */
    @SerialName("inner_radius") val innerRadius: Double? = null,
    /** Degrees true. */
    @SerialName("start_radial") val startRadial: Double? = null,
    @SerialName("end_radial") val endRadial: Double? = null,
    /** Degrees. */
    val angle: Double? = null,
)

/** An area's target in the active task (upstream's target dialog). */
@Serializable
data class TargetInfo(
    /** -1 (nearest) .. 1 (farthest point of the area). */
    val range: Double = 0.0,
    /** Degrees from the leg, -180..180. */
    val radial: Double = 0.0,
    /** false: XCSoar's optimiser moves it. */
    val locked: Boolean = false,
    /** Not passed yet. */
    val movable: Boolean = false,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
)

/** One point of a task (`xcs_task_get`). */
@Serializable
data class TaskPointInfo(
    @SerialName("waypoint_id") val waypointId: Int,
    val name: String,
    /** "start", "turn" or "finish". */
    val kind: String,
    /** XCSoar's TaskPointFactoryType. */
    val type: Int,
    @SerialName("type_name") val typeName: String,
    /** Zone radius (lines: half their length), m; null if it has none. */
    val radius: Double? = null,
    /** Distance from the previous point, m. */
    val leg: Double = 0.0,
    /** The point types allowed here. */
    val types: List<TaskTypeChoice> = emptyList(),
    val zone: ZoneInfo = ZoneInfo(),
    /** Areas of the active task only. */
    val target: TargetInfo? = null,
)

/** Another start the pilot may take (upstream's optional starts). */
@Serializable
data class OptionalStart(
    @SerialName("waypoint_id") val waypointId: Int,
    val name: String,
)

/**
 * The task's rules, as upstream's task properties panel; 0 means no
 * limit.  [startOpen] and [startClose] are local minutes of the day,
 * -1 for none.
 */
@Serializable
data class TaskRules(
    @SerialName("start_open") val startOpen: Int = -1,
    @SerialName("start_close") val startClose: Int = -1,
    /** m/s */
    @SerialName("start_max_speed") val startMaxSpeed: Double = 0.0,
    /** m */
    @SerialName("start_max_height") val startMaxHeight: Double = 0.0,
    /** "agl" or "msl" */
    @SerialName("start_height_ref") val startHeightRef: String = "agl",
    @SerialName("finish_min_height") val finishMinHeight: Double = 0.0,
    @SerialName("finish_height_ref") val finishHeightRef: String = "agl",
    @SerialName("fai_start_finish") val faiStartFinish: Boolean = false,
    /** The task type has FAI start and finish rules to switch on. */
    @SerialName("fai_allowed") val faiAllowed: Boolean = false,
    @SerialName("start_requires_arm") val startRequiresArm: Boolean = false,
    @SerialName("start_score_exit") val startScoreExit: Boolean = false,
    @SerialName("navigate_nearest") val navigateNearest: Boolean = false,
    /** s */
    @SerialName("pev_start_wait") val pevStartWait: Double = 0.0,
    @SerialName("pev_start_window") val pevStartWindow: Double = 0.0,
)

/** Upstream's target dialog numbers for the active area task. */
@Serializable
data class AreaStats(
    /** s */
    @SerialName("estimated_time") val estimatedTime: Double = 0.0,
    /** Estimated time less the minimum time, s. */
    @SerialName("time_delta") val timeDelta: Double = 0.0,
    /** m/s */
    @SerialName("speed_remaining") val speedRemaining: Double? = null,
    @SerialName("speed_achieved") val speedAchieved: Double? = null,
)

/** The active or the edited task (`xcs_task_get`). */
@Serializable
data class TaskInfo(
    /** XCSoar's TaskFactoryType. */
    val type: Int,
    @SerialName("type_name") val typeName: String,
    val name: String = "",
    val editing: Boolean = false,
    val valid: Boolean = false,
    /** XCSoar's validation message when not valid. */
    val errors: String = "",
    /** Nominal distance, m. */
    val distance: Double = 0.0,
    /** Area tasks only. */
    @SerialName("distance_min") val distanceMin: Double? = null,
    @SerialName("distance_max") val distanceMax: Double? = null,
    /** Area tasks only, s. */
    @SerialName("aat_min_time") val aatMinTime: Double? = null,
    /** The point flown to; active task only. */
    val active: Int? = null,
    val types: List<TaskTypeChoice> = emptyList(),
    val points: List<TaskPointInfo> = emptyList(),
    @SerialName("optional_starts") val optionalStarts: List<OptionalStart> = emptyList(),
    val rules: TaskRules = TaskRules(),
    /** Active area task only. */
    val area: AreaStats? = null,
) {
    val isArea get() = aatMinTime != null

    companion object {
        fun parse(text: String): TaskInfo = json.decodeFromString(serializer(), text)
    }
}

/** A task XCSoar finds in a file (`xcs_task_list_files`). */
@Serializable
data class TaskFileInfo(
    val name: String,
    val path: String,
    /** The task's position in its file. */
    val index: Int = 0,
) {
    companion object {
        fun parseList(text: String): List<TaskFileInfo> =
            json.decodeFromString(ListSerializer(serializer()), text)
    }
}

/** Values of `xcs_task_op` (core/api/xcsoar_core.h). */
enum class TaskOp(val code: Int) {
    BEGIN(1),
    CANCEL(2),
    COMMIT(3),
    /** value: waypoint id */
    APPEND(4),
    REMOVE(5),
    /** Swap index and index + 1. */
    SWAP(6),
    CLEAR(7),
    /** value: TaskFactoryType */
    SET_TYPE(8),
    /** value: TaskPointFactoryType */
    SET_POINT_TYPE(9),
    /** value: metres */
    SET_RADIUS(10),
    /** value: seconds */
    SET_AAT_MIN_TIME(11),
    /** value: 1 next, -1 previous */
    ADVANCE(12),
    RESTART(13),
    /** value: metres */
    SET_INNER_RADIUS(14),
    /** value: degrees true */
    SET_START_RADIAL(15),
    SET_END_RADIAL(16),
    /** value: degrees */
    SET_SECTOR_ANGLE(17),
    /** index: [TaskRule] */
    SET_RULE(18),
    /** value: waypoint id */
    ADD_OPTIONAL_START(19),
    REMOVE_OPTIONAL_START(20),
    /** Active area task; value -1..1 */
    SET_TARGET_RANGE(21),
    /** Active area task; value: degrees from the leg */
    SET_TARGET_RADIAL(22),
    /** Active area task; value 1 locked, 0 optimised */
    LOCK_TARGET(23),
}

/** Values of `xcs_task_rule`: [TaskOp.SET_RULE]'s index. */
enum class TaskRule(val code: Int) {
    /** local minutes of the day, -1 none */
    START_OPEN(0),
    START_CLOSE(1),
    /** m/s, 0 none */
    START_MAX_SPEED(2),
    /** m, 0 none */
    START_MAX_HEIGHT(3),
    /** 0 AGL, 1 MSL */
    START_HEIGHT_REF(4),
    FINISH_MIN_HEIGHT(5),
    FINISH_HEIGHT_REF(6),
    /** 0/1 */
    FAI_START_FINISH(7),
    START_REQUIRES_ARM(8),
    START_SCORE_EXIT(9),
    NAVIGATE_NEAREST(10),
    /** s, 0..1800 */
    PEV_START_WAIT(11),
    PEV_START_WINDOW(12),
}
