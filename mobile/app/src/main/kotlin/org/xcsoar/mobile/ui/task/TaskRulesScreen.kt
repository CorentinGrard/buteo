// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.task

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.xcsoar.mobile.R
import org.xcsoar.mobile.core.TaskRule
import org.xcsoar.mobile.core.TaskRules
import org.xcsoar.mobile.core.UnitGroup
import org.xcsoar.mobile.ui.Format
import org.xcsoar.mobile.ui.InputField
import org.xcsoar.mobile.ui.LinkRow
import org.xcsoar.mobile.ui.PageLayout
import org.xcsoar.mobile.ui.ScreenHeader
import org.xcsoar.mobile.ui.SettingsGroup
import org.xcsoar.mobile.ui.SwitchRow
import org.xcsoar.mobile.ui.flight.Caption
import org.xcsoar.mobile.ui.flight.Segmented
import org.xcsoar.mobile.ui.flight.Stepper
import org.xcsoar.mobile.ui.theme.XcsTheme
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun TaskRulesScreen(viewModel: TaskViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DisposableEffect(Unit) {
        viewModel.open()
        onDispose { viewModel.close() }
    }
    BackHandler(onBack = onBack)
    TaskRulesContent(state.task?.rules, onBack, viewModel::setRule)
}

/**
 * The edited task's rules, as upstream's task properties panel: the
 * start gate, the start and finish height limits, and how the start
 * counts.  Values in the pilot's units; 0 means no limit.
 */
@Composable
fun TaskRulesContent(rules: TaskRules?, onBack: () -> Unit,
                     onRule: (TaskRule, Double) -> Unit = { _, _ -> }) {
    // the rule whose dialog is open
    var asking by remember { mutableStateOf<TaskRule?>(null) }

    PageLayout {
        ScreenHeader(stringResource(R.string.task_rules), onBack)
        if (rules == null) return@PageLayout
        // FAI rules have no start limits but the gate (upstream hides them)
        val fai = rules.faiStartFinish

        Caption(stringResource(R.string.task_start), Modifier.padding(start = 4.dp))
        SettingsGroup {
            LinkRow(stringResource(R.string.task_start_open), timeText(rules.startOpen)) {
                asking = TaskRule.START_OPEN
            }
            LinkRow(stringResource(R.string.task_start_close), timeText(rules.startClose)) {
                asking = TaskRule.START_CLOSE
            }
            if (!fai) {
                LinkRow(stringResource(R.string.task_start_max_speed),
                        limitText(rules.startMaxSpeed, UnitGroup.HORIZONTAL_SPEED)) {
                    asking = TaskRule.START_MAX_SPEED
                }
                LinkRow(stringResource(R.string.task_start_max_height),
                        heightText(rules.startMaxHeight, rules.startHeightRef)) {
                    asking = TaskRule.START_MAX_HEIGHT
                }
            }
            SwitchRow(stringResource(R.string.task_arm_start),
                      stringResource(R.string.task_arm_start_detail), rules.startRequiresArm) {
                onRule(TaskRule.START_REQUIRES_ARM, if (it) 1.0 else 0.0)
            }
            SwitchRow(stringResource(R.string.task_score_exit),
                      stringResource(R.string.task_score_exit_detail), rules.startScoreExit) {
                onRule(TaskRule.START_SCORE_EXIT, if (it) 1.0 else 0.0)
            }
        }
        if (!fai) {
            // a pilot event opens the start after a wait, for a while
            MinutesStepper(stringResource(R.string.task_pev_wait), rules.pevStartWait) {
                onRule(TaskRule.PEV_START_WAIT, it)
            }
            MinutesStepper(stringResource(R.string.task_pev_window), rules.pevStartWindow) {
                onRule(TaskRule.PEV_START_WINDOW, it)
            }
        }

        Caption(stringResource(R.string.task_finish), Modifier.padding(start = 4.dp))
        SettingsGroup {
            if (!fai)
                LinkRow(stringResource(R.string.task_finish_min_height),
                        heightText(rules.finishMinHeight, rules.finishHeightRef)) {
                    asking = TaskRule.FINISH_MIN_HEIGHT
                }
            if (rules.faiAllowed)
                SwitchRow(stringResource(R.string.task_fai_rules),
                          stringResource(R.string.task_fai_rules_detail), fai) {
                    onRule(TaskRule.FAI_START_FINISH, if (it) 1.0 else 0.0)
                }
            SwitchRow(stringResource(R.string.task_navigate_nearest),
                      stringResource(R.string.task_navigate_nearest_detail),
                      rules.navigateNearest) {
                onRule(TaskRule.NAVIGATE_NEAREST, if (it) 1.0 else 0.0)
            }
        }
    }

    val close = { asking = null }
    when (val rule = asking) {
        TaskRule.START_OPEN, TaskRule.START_CLOSE -> {
            val open = rule == TaskRule.START_OPEN
            TimeDialog(stringResource(if (open) R.string.task_start_open else R.string.task_start_close),
                       if (open) rules?.startOpen ?: -1 else rules?.startClose ?: -1,
                       onSet = { onRule(rule, it.toDouble()); close() }, onDismiss = close)
        }
        TaskRule.START_MAX_SPEED ->
            LimitDialog(stringResource(R.string.task_start_max_speed), rules?.startMaxSpeed ?: 0.0,
                        UnitGroup.HORIZONTAL_SPEED, reference = null,
                        onSet = { value, _ -> onRule(rule, value); close() }, onDismiss = close)
        TaskRule.START_MAX_HEIGHT ->
            LimitDialog(stringResource(R.string.task_start_max_height), rules?.startMaxHeight ?: 0.0,
                        UnitGroup.ALTITUDE, reference = rules?.startHeightRef ?: "agl",
                        onSet = { value, reference ->
                            onRule(rule, value)
                            onRule(TaskRule.START_HEIGHT_REF, if (reference == "msl") 1.0 else 0.0)
                            close()
                        }, onDismiss = close)
        TaskRule.FINISH_MIN_HEIGHT ->
            LimitDialog(stringResource(R.string.task_finish_min_height), rules?.finishMinHeight ?: 0.0,
                        UnitGroup.ALTITUDE, reference = rules?.finishHeightRef ?: "agl",
                        onSet = { value, reference ->
                            onRule(rule, value)
                            onRule(TaskRule.FINISH_HEIGHT_REF, if (reference == "msl") 1.0 else 0.0)
                            close()
                        }, onDismiss = close)
        else -> {}
    }
}

/** "12:35", or "None" for no start gate time. */
@Composable
private fun timeText(minutes: Int) =
    if (minutes < 0) stringResource(R.string.task_rule_none)
    else String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60)

/** "170 km/h", or "Unlimited" for 0. */
@Composable
private fun limitText(si: Double, group: UnitGroup): String {
    if (si <= 0) return stringResource(R.string.task_no_limit)
    val unit = Format.unit(group)
    return "${unit.toUser(si).roundToInt()} ${unit.name}"
}

/** "1500 m MSL", or "Unlimited" for 0. */
@Composable
private fun heightText(m: Double, reference: String) =
    if (m <= 0) stringResource(R.string.task_no_limit)
    else "${limitText(m, UnitGroup.ALTITUDE)} ${referenceText(reference)}"

@Composable
private fun referenceText(reference: String) =
    stringResource(if (reference == "msl") R.string.task_msl else R.string.task_agl)

/**
 * The rules that apply, for the task page: "Start open time 12:35 ·
 * Start max. height 1500 m MSL"; empty without any.
 */
@Composable
internal fun rulesSummary(rules: TaskRules): String {
    val parts = mutableListOf<String>()
    if (rules.startOpen >= 0)
        parts += "${stringResource(R.string.task_start_open)} ${timeText(rules.startOpen)}"
    if (rules.startClose >= 0)
        parts += "${stringResource(R.string.task_start_close)} ${timeText(rules.startClose)}"
    if (rules.faiStartFinish)
        parts += stringResource(R.string.task_fai_rules)
    else {
        if (rules.startMaxSpeed > 0)
            parts += "${stringResource(R.string.task_start_max_speed)} " +
                limitText(rules.startMaxSpeed, UnitGroup.HORIZONTAL_SPEED)
        if (rules.startMaxHeight > 0)
            parts += "${stringResource(R.string.task_start_max_height)} " +
                heightText(rules.startMaxHeight, rules.startHeightRef)
        if (rules.finishMinHeight > 0)
            parts += "${stringResource(R.string.task_finish_min_height)} " +
                heightText(rules.finishMinHeight, rules.finishHeightRef)
    }
    return parts.joinToString(" · ")
}

/** 0..30 minutes in steps of one, as upstream's PEV rows. */
@Composable
private fun MinutesStepper(name: String, seconds: Double, onChange: (Double) -> Unit) {
    val minutes = (seconds / 60).roundToInt()
    val text = if (minutes == 0) stringResource(R.string.off) else "$minutes min"
    Stepper(name, name, text, text,
            canDecrease = minutes > 0, canIncrease = minutes < 30,
            onDecrease = { onChange((minutes - 1) * 60.0) },
            onIncrease = { onChange((minutes + 1) * 60.0) },
            modifier = Modifier.fillMaxWidth())
}

/** A start gate time, 24-hour local; "None" removes it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(title: String, minutes: Int, onSet: (Int) -> Unit, onDismiss: () -> Unit) {
    // without a time yet, noon: start gates open around then
    val initial = if (minutes >= 0) minutes else 12 * 60
    val state = rememberTimePickerState(initial / 60, initial % 60, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            val colors = XcsTheme.colors
            TimeInput(state, colors = TimePickerDefaults.colors(
                timeSelectorSelectedContainerColor = colors.selected,
                timeSelectorSelectedContentColor = colors.onSelected,
                timeSelectorUnselectedContainerColor = colors.control,
                timeSelectorUnselectedContentColor = colors.text))
        },
        confirmButton = {
            TextButton(onClick = { onSet(state.hour * 60 + state.minute) }) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onSet(-1) }) { Text(stringResource(R.string.task_rule_none)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

/**
 * A limit typed in the pilot's units (empty or 0: none), with its
 * height reference when it is a height.
 */
@Composable
private fun LimitDialog(title: String, si: Double, group: UnitGroup, reference: String?,
                        onSet: (Double, String?) -> Unit, onDismiss: () -> Unit) {
    val unit = Format.unit(group)
    var text by remember {
        mutableStateOf(if (si > 0) unit.toUser(si).roundToInt().toString() else "")
    }
    var ref by remember { mutableStateOf(reference) }
    val typed = text.trim().replace(',', '.').toDoubleOrNull()
    val valid = text.isBlank() || (typed != null && typed >= 0)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                InputField(text, { text = it }, unit.name, keyboardType = KeyboardType.Number)
                ref?.let { r ->
                    Segmented(listOf(stringResource(R.string.task_agl), stringResource(R.string.task_msl)),
                              if (r == "msl") 1 else 0, { ref = if (it == 1) "msl" else "agl" },
                              Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                // heights are whole metres in the task (upstream's unsigned)
                val value = typed?.let { unit.toSi(it).coerceAtLeast(0.0) } ?: 0.0
                onSet(if (group == UnitGroup.ALTITUDE) value.roundToInt().toDouble() else value, ref)
            }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onSet(0.0, ref) }) { Text(stringResource(R.string.task_no_limit)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}
