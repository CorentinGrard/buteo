// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.task

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.xcsoar.mobile.R
import org.xcsoar.mobile.core.TaskFileInfo
import org.xcsoar.mobile.core.WeGlideTask
import org.xcsoar.mobile.core.WeGlideTaskList
import org.xcsoar.mobile.ui.Format
import org.xcsoar.mobile.ui.ScreenHeader
import org.xcsoar.mobile.ui.UiText
import org.xcsoar.mobile.ui.flight.Caption
import org.xcsoar.mobile.ui.flight.Segmented
import org.xcsoar.mobile.ui.theme.XcsTheme

@Composable
fun TaskFilesScreen(viewModel: TaskViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        viewModel.listFiles()
        viewModel.listWeGlide(state.weGlideList)
    }
    BackHandler(onBack = onBack)
    TaskFilesContent(state.files, { viewModel.load(it); onBack() }, onBack,
                     WeGlideTasks(state.weGlideList, state.weGlideTasks, state.weGlideError),
                     onWeGlideList = viewModel::listWeGlide,
                     onWeGlideTask = { viewModel.loadWeGlide(it); onBack() })
}

/** A WeGlide list being shown: [tasks] null while downloading. */
data class WeGlideTasks(val list: WeGlideTaskList, val tasks: List<WeGlideTask>?,
                        val error: UiText?)

/** The tasks XCSoar finds in XCSoarData; a tap loads one for editing. */
@Composable
fun TaskFilesContent(files: List<TaskFileInfo>?, onLoad: (TaskFileInfo) -> Unit,
                     onBack: () -> Unit,
                     weGlide: WeGlideTasks? = null,
                     onWeGlideList: (WeGlideTaskList) -> Unit = {},
                     onWeGlideTask: (WeGlideTask) -> Unit = {}) {
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
        ScreenHeader(stringResource(R.string.task_load_title), onBack)

        when {
            files == null -> Text(stringResource(R.string.task_looking), color = colors.textSecondary,
                                  fontSize = 16.sp)
            files.isEmpty() -> Text(
                stringResource(R.string.task_no_files),
                color = colors.textSecondary, fontSize = 16.sp)
        }

        for (file in files.orEmpty())
            Text(file.name, color = colors.text, fontSize = 18.sp,
                 fontWeight = FontWeight.SemiBold, maxLines = 2,
                 overflow = TextOverflow.Ellipsis,
                 modifier = Modifier
                     .fillMaxWidth()
                     .heightIn(min = 56.dp)
                     .background(colors.panel, RoundedCornerShape(14.dp))
                     .clickable(onClickLabel = stringResource(R.string.task_load_name, file.name)) { onLoad(file) }
                     .padding(horizontal = 14.dp, vertical = 16.dp))

        if (weGlide != null) WeGlideSection(weGlide, onWeGlideList, onWeGlideTask)
    }
}

@StringRes
private fun WeGlideTaskList.labelRes() = when (this) {
    WeGlideTaskList.MINE -> R.string.task_list_mine
    WeGlideTaskList.DECLARED -> R.string.task_list_declared
    WeGlideTaskList.TODAY -> R.string.task_list_today
    WeGlideTaskList.SCORES -> R.string.task_list_scores
}

/** WeGlide's tasks, as upstream's WeGlide tasks panel: a tap loads one. */
@Composable
private fun WeGlideSection(weGlide: WeGlideTasks, onList: (WeGlideTaskList) -> Unit,
                           onTask: (WeGlideTask) -> Unit) {
    val colors = XcsTheme.colors
    Caption("WeGlide", Modifier.padding(start = 4.dp, top = 8.dp))
    val lists = WeGlideTaskList.entries
    Segmented(lists.map { stringResource(it.labelRes()) }, lists.indexOf(weGlide.list),
              { onList(lists[it]) }, Modifier.fillMaxWidth())
    when {
        weGlide.error != null -> Text(weGlide.error.text(), color = colors.caution, fontSize = 16.sp)
        weGlide.tasks == null -> Text(stringResource(R.string.welcome_downloading), color = colors.textSecondary,
                                      fontSize = 16.sp)
        weGlide.tasks.isEmpty() -> Text(stringResource(R.string.task_no_tasks), color = colors.textSecondary,
                                        fontSize = 16.sp)
    }
    for (task in weGlide.tasks.orEmpty()) {
        val name = task.name.ifEmpty { stringResource(R.string.task_numbered, task.id) }
        val distance = Format.distance(task.distance)
        val detail = listOfNotNull(
            "${distance.text} ${distance.unit}",
            task.kind.takeIf { it.isNotEmpty() && it != "unknown" },
            stringResource(R.string.task_turnpoints, task.turnpoints.size)
                .takeIf { task.turnpoints.isNotEmpty() },
            task.userName.ifEmpty { null },
            task.scoringDate.ifEmpty { null },
        ).joinToString(" · ")
        Column(Modifier
                   .fillMaxWidth()
                   .heightIn(min = 56.dp)
                   .background(colors.panel, RoundedCornerShape(14.dp))
                   .clickable(onClickLabel = stringResource(R.string.task_load_name, name)) { onTask(task) }
                   .padding(horizontal = 14.dp, vertical = 10.dp),
               verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                 maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(detail, color = colors.textSecondary, fontSize = 14.sp, maxLines = 2,
                 overflow = TextOverflow.Ellipsis)
        }
    }
}
