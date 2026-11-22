// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.xcsoar.mobile.R
import org.xcsoar.mobile.core.NotamInfo
import org.xcsoar.mobile.core.NotamList
import org.xcsoar.mobile.core.NotamSettings
import org.xcsoar.mobile.core.XcsoarCore
import org.xcsoar.mobile.ui.ActionButton
import org.xcsoar.mobile.ui.Format
import org.xcsoar.mobile.ui.InputField
import org.xcsoar.mobile.ui.PageLayout
import org.xcsoar.mobile.ui.ScreenHeader
import org.xcsoar.mobile.ui.SettingsGroup
import org.xcsoar.mobile.ui.SwitchRow
import org.xcsoar.mobile.ui.UiText
import org.xcsoar.mobile.ui.flight.Caption
import org.xcsoar.mobile.ui.flight.Stepper
import org.xcsoar.mobile.ui.text
import org.xcsoar.mobile.ui.theme.XcsTheme
import org.xcsoar.mobile.ui.weather.utcTime

data class NotamState(
    val loaded: Boolean = false,
    val settings: NotamSettings = NotamSettings(),
    /** The settings differ from the saved ones. */
    val changed: Boolean = false,
    val list: NotamList = NotamList(),
    val message: UiText? = null,
)

/** NOTAMConfigPanel and NOTAMList in one page. */
class NotamViewModel(private val core: XcsoarCore) : ViewModel() {
    private val stateFlow = MutableStateFlow(NotamState())
    val state: StateFlow<NotamState> = stateFlow.asStateFlow()

    fun load() {
        viewModelScope.launch {
            val settings = try { core.notamSettings() } catch (_: Exception) { null }
            stateFlow.update {
                it.copy(loaded = settings != null, settings = settings ?: NotamSettings(),
                        changed = false, message = null)
            }
            loadList()
        }
    }

    private suspend fun loadList() {
        val list = try { core.notams() } catch (_: Exception) { NotamList() }
        stateFlow.update { it.copy(list = list) }
    }

    fun change(edit: (NotamSettings) -> NotamSettings) =
        stateFlow.update { it.copy(settings = edit(it.settings), changed = true, message = null) }

    fun save() {
        val settings = stateFlow.value.settings
        viewModelScope.launch {
            try {
                core.setNotamSettings(settings)
                stateFlow.update { it.copy(changed = false) }
                followDownload()
            } catch (_: Exception) {
                stateFlow.update { it.copy(message = text(R.string.crew_could_not_save)) }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val started = try { core.refreshNotams() } catch (_: Exception) { false }
            if (!started) {
                stateFlow.update { it.copy(message = text(R.string.notam_needs_fix)) }
                return@launch
            }
            stateFlow.update { it.copy(message = null) }
            followDownload()
        }
    }

    /** The download runs in the core: show the list until it is done. */
    private suspend fun followDownload() {
        loadList()
        repeat(FOLLOW_SECONDS) {
            delay(1000)
            loadList()
            if (!stateFlow.value.list.loading && it > 1) return
        }
    }

    private companion object {
        const val FOLLOW_SECONDS = 30
    }
}

@Composable
fun NotamScreen(viewModel: NotamViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.load() }
    BackHandler(onBack = onBack)
    NotamContent(state, viewModel::change, viewModel::save, viewModel::refresh, onBack)
}

/** Steps of the download radius, km. */
private val RADII = listOf(10, 20, 30, 50, 75, 100, 150, NotamSettings.MAX_RADIUS_KM)

/** Steps of the refresh interval, min; 0 only when asked. */
private val INTERVALS = listOf(0, 15, 30, 60, 120, NotamSettings.MAX_REFRESH_MIN)

@Composable
fun NotamContent(
    state: NotamState,
    onChange: ((NotamSettings) -> NotamSettings) -> Unit,
    onSave: () -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = XcsTheme.colors
    val s = state.settings
    val whenAsked = stringResource(R.string.notam_when_asked)
    PageLayout(bottom = if (state.changed) ({
        ActionButton(stringResource(R.string.save), primary = true, modifier = Modifier.weight(1f), onClick = onSave)
    }) else null) {
        ScreenHeader(stringResource(R.string.notam_title), onBack)

        SettingsGroup {
            SwitchRow(stringResource(R.string.notam_title), stringResource(R.string.notam_detail),
                      s.enabled, enabled = state.loaded) { on ->
                onChange { it.copy(enabled = on) }
            }
        }

        if (s.enabled) {
            ListStepper(stringResource(R.string.notam_radius_name),
                        stringResource(R.string.notam_radius), RADII, s.radiusKm,
                        { "${it}" }) { r -> onChange { it.copy(radiusKm = r) } }
            ListStepper(stringResource(R.string.notam_interval_name),
                        stringResource(R.string.notam_refresh), INTERVALS, s.refreshMinutes,
                        { if (it == 0) whenAsked else "${it} min" }) { m ->
                onChange { it.copy(refreshMinutes = m) }
            }

            Caption(stringResource(R.string.notam_show), Modifier.padding(start = 4.dp, top = 8.dp))
            SettingsGroup {
                SwitchRow(stringResource(R.string.notam_effective),
                          stringResource(R.string.notam_effective_detail),
                          s.onlyEffective) { on -> onChange { it.copy(onlyEffective = on) } }
                SwitchRow(stringResource(R.string.notam_ifr),
                          stringResource(R.string.notam_ifr_detail),
                          s.showIfr) { on -> onChange { it.copy(showIfr = on) } }
            }
            InputField(s.hiddenQCodes,
                       { v -> onChange { it.copy(hiddenQCodes = v.uppercase().take(255)) } },
                       stringResource(R.string.notam_qcodes), capitalization = KeyboardCapitalization.Characters)
            Explanation(stringResource(R.string.notam_qcodes_hint))

            NotamList(state, onRefresh)
        }

        state.message?.let { Text(it.text(), color = colors.caution, fontSize = 15.sp) }
    }
}

@Composable
private fun NotamList(state: NotamState, onRefresh: () -> Unit) {
    val colors = XcsTheme.colors
    val list = state.list
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Caption(stringResource(R.string.notam_shown, list.notams.size, list.total))
            val updated = list.updated
            Text(when {
                     list.loading -> stringResource(R.string.welcome_downloading)
                     updated != null -> stringResource(R.string.notam_updated, utcTime(updated))
                     else -> stringResource(R.string.notam_not_downloaded)
                 },
                 color = colors.textSecondary, fontSize = 14.sp)
        }
        ActionButton(stringResource(R.string.notam_refresh), outlined = true, enabled = !list.loading && !state.changed,
                     onClick = onRefresh)
    }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    for (notam in list.notams) {
        val key = notam.number + notam.location
        NotamCard(notam, expanded == key) { expanded = if (expanded == key) null else key }
    }
}

@Composable
private fun NotamCard(notam: NotamInfo, expanded: Boolean, onToggle: () -> Unit) {
    val colors = XcsTheme.colors
    Column(Modifier
               .fillMaxWidth()
               .background(colors.panel, RoundedCornerShape(12.dp))
               .clickable(role = Role.Button, onClick = onToggle)
               .padding(horizontal = 14.dp, vertical = 10.dp),
           verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(listOf(notam.location, notam.number).filter(String::isNotEmpty)
                     .joinToString(" · "),
                 color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                 modifier = Modifier.weight(1f))
            notam.distance?.let {
                val d = Format.distance(it)
                Text("${d.text} ${d.unit}", color = colors.textSecondary, fontSize = 15.sp)
            }
        }
        val band = listOf(notam.lower, notam.upper).filter(String::isNotEmpty)
        Text(listOfNotNull(band.joinToString(" – ").takeIf { band.isNotEmpty() },
                           validity(notam)).joinToString(" · "),
             color = if (notam.active) colors.caution else colors.textSecondary,
             fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(notam.text, color = colors.text, fontSize = 15.sp,
             maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
    }
}

/** "until 03 18:00 UTC", "from 05 07:00 UTC", "permanent" */
@Composable
private fun validity(notam: NotamInfo): String = when {
    !notam.active && notam.start.isNotEmpty() ->
        stringResource(R.string.notam_from, dayTime(notam.start))
    notam.permanent -> stringResource(R.string.notam_permanent)
    else -> notam.end?.let { stringResource(R.string.notam_until, dayTime(it)) } ?: ""
}

/** "2026-10-03T18:00:00Z" → "3 Oct 18:00 UTC" */
private fun dayTime(iso: String): String {
    if (iso.length < 16) return iso
    val month = iso.substring(5, 7).toIntOrNull()?.takeIf { it in 1..12 }?.let {
        Month.of(it).getDisplayName(TextStyle.SHORT, Locale.getDefault())
    } ?: return iso
    return "${iso.substring(8, 10).trimStart('0')} $month ${utcTime(iso)}"
}

/** One of [values]; a value between them steps to the next. */
@Composable
private fun ListStepper(name: String, caption: String, values: List<Int>, value: Int,
                        text: (Int) -> String, onChange: (Int) -> Unit) {
    val below = values.indexOfLast { it < value }
    val above = values.indexOfFirst { it > value }
    val shown = text(value)
    Stepper(name, caption, shown, shown,
            canDecrease = below >= 0, canIncrease = above >= 0,
            onDecrease = { if (below >= 0) onChange(values[below]) },
            onIncrease = { if (above >= 0) onChange(values[above]) },
            modifier = Modifier.fillMaxWidth())
}
