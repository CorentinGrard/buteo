// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.waypoints

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.xcsoar.mobile.R
import org.xcsoar.mobile.core.Vac
import org.xcsoar.mobile.VacChart
import org.xcsoar.mobile.VacStore
import org.xcsoar.mobile.core.RadioSlot
import org.xcsoar.mobile.core.WaypointDetails
import org.xcsoar.mobile.core.WeatherStation
import org.xcsoar.mobile.core.XcsoarCore
import org.xcsoar.mobile.ui.ActionButton
import org.xcsoar.mobile.ui.Format
import org.xcsoar.mobile.ui.PageLayout
import org.xcsoar.mobile.ui.ScreenHeader
import org.xcsoar.mobile.ui.ValueList
import org.xcsoar.mobile.ui.flight.Caption
import org.xcsoar.mobile.ui.settings.Explanation
import org.xcsoar.mobile.ui.theme.XcsTheme
import org.xcsoar.mobile.ui.weather.utcTime
import org.xcsoar.mobile.ui.weather.weatherSummary
import kotlin.math.abs

/** Where a field's METAR and TAF are. */
sealed interface FieldWeather {
    data object Loading : FieldWeather
    data class Report(val station: WeatherStation) : FieldWeather
    /** NOAA has nothing for it, or could not be reached. */
    data object None : FieldWeather
}

/** Where a field's approach chart is. */
sealed interface FieldChart {
    data object Idle : FieldChart
    data object Loading : FieldChart
    data class Ready(val chart: VacChart) : FieldChart
    data object Failed : FieldChart
}

/** What a waypoint's page shows: [details] null until read or for none. */
data class WaypointDetailsState(
    val read: Boolean = false,
    val details: WaypointDetails? = null,
    /** Only for a field with an ICAO code. */
    val weather: FieldWeather? = null,
    val chart: FieldChart = FieldChart.Idle,
    /** The slot its frequency was just set in, for a confirmation. */
    val frequencySet: RadioSlot? = null,
)

class WaypointDetailsViewModel(
    private val core: XcsoarCore,
    private val vacStore: VacStore?,
) : ViewModel() {
    private val stateFlow = MutableStateFlow(WaypointDetailsState())
    val state: StateFlow<WaypointDetailsState> = stateFlow.asStateFlow()

    private var id: Int? = null
    private var weatherJob: Job? = null

    /** Show waypoint [id]; its weather comes once read. */
    fun open(id: Int) {
        this.id = id
        weatherJob?.cancel()
        stateFlow.value = WaypointDetailsState()
    }

    /** Reads it every few seconds while the page shows (the distance moves). */
    suspend fun refreshWhileShown() {
        while (true) {
            read()
            delay(REFRESH_MS)
        }
    }

    private suspend fun read() {
        val id = id ?: return
        val details = try {
            core.waypoint(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("XCSoar", "waypoint not read", e)
            null
        }
        stateFlow.update { it.copy(read = true, details = details) }
        if (stateFlow.value.weather == null)
            details?.icao?.let(::loadWeather)
    }

    private fun loadWeather(icao: String) {
        stateFlow.update { it.copy(weather = FieldWeather.Loading) }
        weatherJob = viewModelScope.launch {
            val station = try {
                core.weatherStation(icao)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("XCSoar", "weather of $icao", e)
                null
            }
            stateFlow.update {
                it.copy(weather = station?.let(FieldWeather::Report) ?: FieldWeather.None)
            }
        }
    }

    /** The field's chart: from the phone, else downloaded. */
    fun loadChart() {
        val icao = stateFlow.value.details?.icao ?: return
        val store = vacStore ?: return
        stateFlow.update { it.copy(chart = FieldChart.Loading) }
        viewModelScope.launch {
            val chart = try {
                FieldChart.Ready(store.chart(icao))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("XCSoar", "chart of $icao", e)
                FieldChart.Failed
            }
            stateFlow.update { it.copy(chart = chart) }
        }
    }

    /** Back from the chart: a tap opens it again. */
    fun closeChart() {
        stateFlow.update { it.copy(chart = FieldChart.Idle) }
    }

    fun goTo(onDone: () -> Unit) {
        val id = id ?: return
        viewModelScope.launch {
            try {
                core.gotoWaypoint(id)
            } catch (e: Exception) {
                Log.w("XCSoar", "go to $id", e)
            }
            onDone()
        }
    }

    /** "Set active" / "Set standby", named after the field, as upstream does. */
    fun setFrequency(slot: RadioSlot) {
        val details = stateFlow.value.details ?: return
        val frequency = details.frequency ?: return
        viewModelScope.launch {
            try {
                core.setRadioFrequency(slot, frequency, details.name)
                stateFlow.update { it.copy(frequencySet = slot) }
            } catch (e: Exception) {
                Log.w("XCSoar", "frequency not set", e)
            }
        }
    }

    companion object {
        const val REFRESH_MS = 2_000L
    }
}

@Composable
fun WaypointDetailsScreen(
    viewModel: WaypointDetailsViewModel,
    onGoneTo: () -> Unit,
    onOpenChart: () -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.refreshWhileShown() }
    BackHandler(onBack = onBack)
    // the chart opens once there
    LaunchedEffect(state.chart) {
        if (state.chart is FieldChart.Ready) onOpenChart()
    }
    WaypointDetailsContent(state, { viewModel.goTo(onGoneTo) }, viewModel::setFrequency,
                           viewModel::loadChart, onBack)
}

/**
 * A waypoint, a field above all, as the pilot tapped it on the map:
 * Go to first, then what to know before landing there (elevation,
 * runway, radio, reach), its weather and its approach chart.
 */
@Composable
fun WaypointDetailsContent(
    state: WaypointDetailsState,
    onGoTo: () -> Unit,
    onFrequency: (RadioSlot) -> Unit,
    onChart: () -> Unit,
    onBack: () -> Unit,
) {
    val details = state.details
    PageLayout(bottom = if (details == null) null else {
        { ActionButton(stringResource(R.string.go_to_name, details.name), primary = true,
                       modifier = Modifier.weight(1f), onClick = onGoTo) }
    }) {
        ScreenHeader(stringResource(when {
            details?.airport == true -> R.string.field_airport
            details?.landable == true -> R.string.landable
            else -> R.string.waypoint
        }), onBack)

        if (details == null) {
            Explanation(stringResource(if (state.read) R.string.field_none
                                       else R.string.status_reading))
            return@PageLayout
        }

        Text(details.name, color = XcsTheme.colors.text, fontSize = 22.sp,
             fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp))
        ValueList(rows(details))

        details.frequency?.let { frequency ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(stringResource(R.string.radio_set_active), outlined = true,
                             modifier = Modifier.weight(1f)) { onFrequency(RadioSlot.ACTIVE) }
                ActionButton(stringResource(R.string.radio_set_standby), outlined = true,
                             modifier = Modifier.weight(1f)) { onFrequency(RadioSlot.STANDBY) }
            }
            Explanation(state.frequencySet?.let {
                stringResource(if (it == RadioSlot.ACTIVE) R.string.airdetails_now_active
                               else R.string.airdetails_now_standby, frequency)
            } ?: stringResource(R.string.airdetails_sends, frequency))
        }

        details.comment?.takeIf { it.isNotBlank() }?.let { Explanation(it) }
        details.details?.takeIf { it.isNotBlank() }?.let { Explanation(it) }

        val icao = details.icao
        if (icao != null && Vac.covers(icao)) {
            Caption(stringResource(R.string.field_chart), Modifier.padding(start = 4.dp, top = 8.dp))
            ActionButton(stringResource(R.string.field_chart_open, icao), outlined = true,
                         enabled = state.chart != FieldChart.Loading,
                         modifier = Modifier.fillMaxWidth(), onClick = onChart)
            Explanation(when (val chart = state.chart) {
                FieldChart.Loading -> stringResource(R.string.field_chart_loading)
                FieldChart.Failed -> stringResource(R.string.field_chart_failed, icao)
                is FieldChart.Ready -> if (chart.chart.current)
                    stringResource(R.string.field_chart_hint, Format.day(chart.chart.cycle.toString()))
                    else stringResource(R.string.field_chart_old, Format.day(chart.chart.cycle.toString()))
                FieldChart.Idle -> stringResource(R.string.field_chart_hint,
                                                  Format.day(Vac.currentCycle().toString()))
            })
        }

        state.weather?.let { weather ->
            Caption(stringResource(R.string.menu_weather), Modifier.padding(start = 4.dp, top = 8.dp))
            when (weather) {
                FieldWeather.Loading -> Explanation(stringResource(R.string.field_weather_loading))
                FieldWeather.None -> Explanation(stringResource(R.string.field_weather_none,
                                                                icao.orEmpty()))
                is FieldWeather.Report -> WeatherReport(weather.station)
            }
        }
    }
}

/** Type, elevation, runway, radio, distance and reach, as known. */
@Composable
private fun rows(details: WaypointDetails): List<Pair<String, String>> = buildList {
    if (details.code.isNotEmpty() && details.code != details.name)
        add(stringResource(R.string.field_code) to details.code)
    details.elevation?.let {
        val v = Format.altitude(it)
        add(stringResource(R.string.field_elevation) to "${v.text} ${v.unit}")
    }
    details.runway?.let { add(stringResource(R.string.field_runway) to
                              runwayText(it, details.runwayLength)) }
    details.frequency?.let { add(stringResource(R.string.radio_title) to "$it MHz") }
    val distance = details.distance
    if (distance != null) {
        val d = Format.distance(distance)
        val b = Format.bearing(details.bearing)
        add(stringResource(R.string.field_from_here) to "${d.text} ${d.unit} · ${b.text}${b.unit}")
    }
    val arrival = details.arrival
    if (details.reachable != null && arrival != null) {
        val margin = Format.altitude(abs(arrival).toDouble())
        add(stringResource(R.string.field_reach) to stringResource(
            if (details.reachable == true) R.string.field_in_reach else R.string.field_out_of_reach,
            "${margin.text} ${margin.unit}"))
    }
}

/**
 * "02/20 · 1200 m": both ends by their magnetic-style number (tens of
 * degrees, 36 for north), and the length.
 */
@Composable
internal fun runwayText(direction: Int, length: Int?): String {
    fun end(degrees: Int): Int = ((degrees + 5) / 10).let { if (it % 36 == 0) 36 else it % 36 }
    val a = end(direction)
    val b = end(direction + 180)
    val ends = "%02d/%02d".format(minOf(a, b), maxOf(a, b))
    return length?.let {
        val v = Format.altitude(it.toDouble())
        "$ends · ${v.text} ${v.unit}"
    } ?: ends
}

/** The METAR's summary, its times, and the reports decoded. */
@Composable
private fun WeatherReport(station: WeatherStation) {
    val colors = XcsTheme.colors
    Column(Modifier
               .fillMaxWidth()
               .background(colors.panel, RoundedCornerShape(12.dp))
               .padding(horizontal = 14.dp, vertical = 10.dp),
           verticalArrangement = Arrangement.spacedBy(4.dp)) {
        weatherSummary(station)?.let {
            Text(it, color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(listOfNotNull(station.metarTime?.let { "METAR ${utcTime(it)}" },
                           station.tafTime?.let { "TAF ${utcTime(it)}" })
                 .joinToString(" · "),
             color = colors.textSecondary, fontSize = 14.sp)
        Text(station.text ?: station.metar.orEmpty(), color = colors.text,
             fontSize = 14.sp, fontFamily = FontFamily.Monospace,
             modifier = Modifier.padding(top = 4.dp))
    }
}
