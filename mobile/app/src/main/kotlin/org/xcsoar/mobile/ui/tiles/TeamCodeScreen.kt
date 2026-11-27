// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.tiles

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.xcsoar.mobile.R
import org.xcsoar.mobile.core.TeamCodeInfo
import org.xcsoar.mobile.core.XcsoarCore
import org.xcsoar.mobile.ui.ActionButton
import org.xcsoar.mobile.ui.Format
import org.xcsoar.mobile.ui.InputField
import org.xcsoar.mobile.ui.PageLayout
import org.xcsoar.mobile.ui.ScreenHeader
import org.xcsoar.mobile.ui.ValueList
import org.xcsoar.mobile.ui.flight.Caption
import org.xcsoar.mobile.ui.settings.Explanation
import org.xcsoar.mobile.ui.theme.XcsTheme

class TeamCodeViewModel(private val core: XcsoarCore) : ViewModel() {
    private val infoFlow = MutableStateFlow<TeamCodeInfo?>(null)
    /** The team code dialog's values; null until read. */
    val info: StateFlow<TeamCodeInfo?> = infoFlow.asStateFlow()

    /** Reads it once a second while the page shows (the codes move). */
    suspend fun refreshWhileShown() {
        while (true) {
            read()
            delay(REFRESH_MS)
        }
    }

    private suspend fun read() {
        try {
            core.teamCode()?.let { infoFlow.value = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("XCSoar", "team code not read", e)
        }
    }

    /** The mate's code ("" forgets it); false if it is not a team code. */
    fun setMateCode(code: String): Boolean {
        val value = code.trim().uppercase()
        if (!isTeamCode(value)) return false
        infoFlow.value = infoFlow.value?.copy(mate = value)
        viewModelScope.launch {
            try {
                core.setTeamMateCode(value)
            } catch (e: Exception) {
                Log.w("XCSoar", "team code not set", e)
            }
            read()
        }
        return true
    }

    fun setReference(waypointId: Int?) {
        viewModelScope.launch {
            try {
                core.setTeamReference(waypointId)
            } catch (e: Exception) {
                Log.w("XCSoar", "team code reference not set", e)
            }
            read()
        }
    }

    companion object {
        const val REFRESH_MS = 1_000L

        /** Three to five letters or digits, as the core takes them; "" too. */
        fun isTeamCode(code: String) =
            (code.isEmpty() || code.length in 3..5) &&
                code.all { it in 'A'..'Z' || it in '0'..'9' }
    }
}

@Composable
fun TeamCodeScreen(viewModel: TeamCodeViewModel, onPickReference: () -> Unit,
                   onBack: () -> Unit) {
    val info by viewModel.info.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.refreshWhileShown() }
    BackHandler(onBack = onBack)
    TeamCodeContent(info, viewModel::setMateCode, onPickReference, onBack)
}

/**
 * XCSoar's team code dialog: this glider's code to read out on the
 * radio, and where the team mate is from the code they read out.  Both
 * count from the same reference waypoint.
 */
@Composable
fun TeamCodeContent(
    info: TeamCodeInfo?,
    onMateCode: (String) -> Boolean,
    onPickReference: () -> Unit,
    onBack: () -> Unit,
) {
    val mateCode = stringResource(R.string.team_mate_code)
    PageLayout {
        ScreenHeader(stringResource(R.string.team_title), onBack)

        OwnCode(info)

        Caption(stringResource(R.string.team_mate), Modifier.padding(start = 4.dp, top = 8.dp))
        ValueList(buildList {
            add(mateCode to info?.mate.orEmpty())
            add(stringResource(R.string.team_range) to info?.range.orEmpty())
            add(stringResource(R.string.team_bearing) to info?.bearing.orEmpty())
            add(stringResource(R.string.team_rel_bearing) to info?.relativeBearing.orEmpty())
            info?.flarmLock?.takeIf { it.isNotEmpty() }?.let { add(stringResource(R.string.team_flarm_lock) to it) }
        })
        var code by rememberSaveable { mutableStateOf("") }
        var invalid by rememberSaveable { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InputField(code, {
                code = it
                invalid = false
            }, label = if (invalid) stringResource(R.string.team_invalid) else mateCode,
                       capitalization = KeyboardCapitalization.Characters,
                       modifier = Modifier.weight(1f))
            ActionButton(stringResource(R.string.team_set_code), outlined = true, enabled = info != null) {
                if (onMateCode(code)) code = "" else invalid = true
            }
        }

        Caption(stringResource(R.string.team_reference), Modifier.padding(start = 4.dp, top = 8.dp))
        ValueList(listOf(stringResource(R.string.waypoint) to info?.reference?.name.orEmpty()))
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton(stringResource(R.string.team_set_wp), outlined = true, enabled = info != null,
                         onClick = onPickReference)
            Explanation(stringResource(R.string.team_same_reference))
        }
    }
}

/** This glider's code in large type, or why there is none. */
@Composable
private fun OwnCode(info: TeamCodeInfo?) {
    val colors = XcsTheme.colors
    val own = info?.own.orEmpty()
    val reference = info?.reference
    val detail = when {
        info == null -> stringResource(R.string.status_reading)
        reference == null -> stringResource(R.string.team_set_reference_first)
        own.isEmpty() -> stringResource(R.string.team_needs_fix)
        else -> stringResource(R.string.team_from, reference.name)
    }
    val spoken = if (own.isNotEmpty())
        stringResource(R.string.team_spoken_own, own.toList().joinToString(" "), detail)
    else stringResource(R.string.team_spoken_none, detail)
    Column(Modifier
               .fillMaxWidth()
               .background(colors.panel, RoundedCornerShape(14.dp))
               .padding(16.dp)
               .clearAndSetSemantics {
                   contentDescription = spoken
               }) {
        Caption(stringResource(R.string.team_own))
        Text(own.ifEmpty { Format.INVALID }, color = colors.text, fontSize = 34.sp,
             fontWeight = FontWeight.Bold, style = XcsTheme.numberStyle)
        Text(detail, color = colors.textSecondary, fontSize = 15.sp)
    }
}
