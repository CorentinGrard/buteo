// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.tiles

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.xcsoar.mobile.R
import org.xcsoar.mobile.core.AtcInfo
import org.xcsoar.mobile.core.XcsoarCore
import org.xcsoar.mobile.ui.ActionButton
import org.xcsoar.mobile.ui.PageLayout
import org.xcsoar.mobile.ui.ScreenHeader
import org.xcsoar.mobile.ui.ValueList
import org.xcsoar.mobile.ui.flight.Caption
import org.xcsoar.mobile.ui.flight.Stepper
import org.xcsoar.mobile.ui.settings.Explanation
import kotlin.math.abs
import kotlin.math.roundToInt

class AtcViewModel(private val core: XcsoarCore) : ViewModel() {
    private val infoFlow = MutableStateFlow<AtcInfo?>(null)
    /** The reference and declination; null until read. */
    val info: StateFlow<AtcInfo?> = infoFlow.asStateFlow()

    suspend fun read() {
        try {
            core.atc()?.let { infoFlow.value = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("XCSoar", "ATC reference not read", e)
        }
    }

    /** A waypoint picked by "Relocate", or null for "Clear". */
    fun setReference(waypointId: Int?) = change { core.setAtcReference(waypointId) }

    fun setDeclination(degrees: Int) {
        infoFlow.value = infoFlow.value?.copy(declination = degrees.toDouble())
        change { core.setMagneticDeclination(degrees.toDouble()) }
    }

    private fun change(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                Log.w("XCSoar", "ATC reference not set", e)
            }
            read()
        }
    }

    companion object {
        /** upstream's limits of the declination, degrees */
        const val MAX_DECLINATION = 30
    }
}

@Composable
fun AtcScreen(viewModel: AtcViewModel, onRelocate: () -> Unit, onBack: () -> Unit) {
    val info by viewModel.info.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.read() }
    BackHandler(onBack = onBack)
    AtcContent(info, onRelocate, { viewModel.setReference(null) },
               viewModel::setDeclination, onBack)
}

/**
 * XCSoar's ATC radial panels: the point the ATC radial tile measures
 * from (a waypoint) and the magnetic declination it turns true bearings
 * into magnetic ones with.
 */
@Composable
fun AtcContent(
    info: AtcInfo?,
    onRelocate: () -> Unit,
    onClear: () -> Unit,
    onDeclination: (Int) -> Unit,
    onBack: () -> Unit,
) {
    PageLayout {
        ScreenHeader(stringResource(R.string.atc_title), onBack)

        Caption(stringResource(R.string.atc_reference), Modifier.padding(start = 4.dp, top = 8.dp))
        ValueList(listOf(stringResource(R.string.waypoint) to info?.waypoint.orEmpty(),
                         stringResource(R.string.atc_location) to info?.location.orEmpty()))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(stringResource(R.string.waypoints_relocate), outlined = true, enabled = info != null,
                         onClick = onRelocate)
            ActionButton(stringResource(R.string.wind_clear), outlined = true, enabled = info?.location != null,
                         onClick = onClear)
        }
        Explanation(stringResource(R.string.atc_hint))

        Caption(stringResource(R.string.atc_setup), Modifier.padding(start = 4.dp, top = 8.dp))
        val degrees = info?.declination?.roundToInt() ?: 0
        val text = declination(degrees)
        Stepper(stringResource(R.string.atc_declination_name),
                stringResource(R.string.atc_declination), text,
                stringResource(if (degrees < 0) R.string.atc_spoken_west
                               else R.string.atc_spoken_east, abs(degrees)),
                canDecrease = info != null && degrees > -AtcViewModel.MAX_DECLINATION,
                canIncrease = info != null && degrees < AtcViewModel.MAX_DECLINATION,
                onDecrease = { onDeclination(degrees - 1) },
                onIncrease = { onDeclination(degrees + 1) },
                modifier = Modifier.fillMaxWidth())
        Explanation(stringResource(R.string.atc_declination_hint))
    }
}

/** "2° E", "3° W", "0°". */
@Composable
private fun declination(degrees: Int) = when {
    degrees > 0 -> stringResource(R.string.atc_east, degrees)
    degrees < 0 -> stringResource(R.string.atc_west, -degrees)
    else -> "0°"
}
