// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.flight

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.xcsoar.mobile.R
import org.xcsoar.mobile.core.AirspaceAlerts
import org.xcsoar.mobile.core.Analysis
import org.xcsoar.mobile.core.AirspaceWarningInfo
import org.xcsoar.mobile.core.airspaceAlerts
import org.xcsoar.mobile.core.CoreEvent
import org.xcsoar.mobile.core.FlightState
import org.xcsoar.mobile.core.FlightTimes
import org.xcsoar.mobile.core.GlideComputerEvent
import org.xcsoar.mobile.core.SafetyOption
import org.xcsoar.mobile.core.MapItemInfo
import org.xcsoar.mobile.core.WaypointFilter
import org.xcsoar.mobile.core.WaypointInfo
import org.xcsoar.mobile.core.MapFeatures
import org.xcsoar.mobile.core.MapOption
import org.xcsoar.mobile.core.MapOrientation
import org.xcsoar.mobile.core.SoundOption
import org.xcsoar.mobile.core.Thermal
import org.xcsoar.mobile.ui.Format
import org.xcsoar.mobile.ui.UiText
import org.xcsoar.mobile.ui.asText
import org.xcsoar.mobile.ui.summary.FlightEnd
import org.xcsoar.mobile.core.TileAction
import org.xcsoar.mobile.core.TileGraphic
import org.xcsoar.mobile.core.TileLayout
import org.xcsoar.mobile.core.TileValue
import org.xcsoar.mobile.core.XcsoarCore
import kotlin.math.abs
import org.xcsoar.mobile.ui.text
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/**
 * State and actions of the flight screen; knows only [XcsoarCore].
 *
 * @param core the process's core (shared with the other screens)
 * @param calloutSettings the callouts the pilot wants, read at each one
 */
class FlightViewModel(
    private val core: XcsoarCore,
    private val calloutSettings: () -> CalloutSettings = { CalloutSettings() },
) : ViewModel() {
    /**
     * The latest state, at most [UI_INTERVAL_MS] apart: what the screen
     * shows besides the vario changes with the GPS (once a second), and
     * recomposing the whole screen more often costs battery.
     */
    val flightState: StateFlow<FlightState?> = core.flightState  // conflated
        .transform { emit(it); delay(UI_INTERVAL_MS) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, core.flightState.value)

    /**
     * The state as fast as the vario: what turns with the map (the
     * thermal assistant) follows it; only while something collects it.
     */
    val liveState: StateFlow<FlightState?> = core.flightState
        .transform { emit(it); delay(VARIO_INTERVAL_MS) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * The thermal assistant to draw around the glider: while circling,
     * once a second, unless the pilot turned it off (Map settings).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val thermalAssistant: StateFlow<TileGraphic?> = core.flightState
        .map { it?.circling == true }
        .distinctUntilChanged()
        .transformLatest { circling ->
            emit(null)
            if (!circling) return@transformLatest
            val wanted = try {
                core.mapOption(MapOption.THERMAL_ASSISTANT)
            } catch (_: Exception) {
                null
            }
            if (wanted == 0) return@transformLatest
            while (true) {
                emit(try {
                    core.thermalAssistant()?.takeIf { it.circling }
                } catch (_: Exception) {
                    null
                })
                delay(TILES_INTERVAL_MS)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The vario, faster ([VARIO_INTERVAL_MS]): only its panel follows it. */
    val vario: StateFlow<VarioValues?> = core.flightState
        .map { s -> s?.let { VarioValues(it.vario, it.averageVario, it.nettoVario) } }
        .distinctUntilChanged()
        .transform { emit(it); delay(VARIO_INTERVAL_MS) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val lastEventFlow = MutableStateFlow<UiText?>(null)
    /** A short text for the latest glide computer event, for the status line. */
    val lastEvent: StateFlow<UiText?> = lastEventFlow.asStateFlow()

    /* the pilot's choice of layout, until the next flight mode change */
    private val modeOverride = MutableStateFlow<Boolean?>(null)

    /** The pilot's choice of tiles: null follows the flight mode. */
    val tileLayout: StateFlow<Boolean?> = modeOverride.asStateFlow()

    /** Show the circling layout: the glide computer's mode, or the pilot's pick. */
    val showCircling: StateFlow<Boolean> =
        combine(flightState, modeOverride) { state, override ->
            override ?: (state?.circling == true)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /* bumped when the pilot changes a tile */
    private val tilesChanged = MutableStateFlow(0)

    /*
     * once per second, like XCSoar's InfoBoxes (the GPS rate): with each
     * state the screen shows, so that both are drawn in one frame (apart,
     * the whole screen was laid out and drawn twice a second), and every
     * few seconds when no state comes (the clock tiles)
     */
    private val tilesTicker = merge(
        flightState.map { },
        flow {
            while (true) {
                delay(TILES_IDLE_MS)
                emit(Unit)
            }
        })

    /**
     * The tiles of the layout shown, XCSoar's InfoBoxes computed by the
     * core, every second and when the layout changes; null without the
     * core's InfoBoxes.
     */
    val tiles: StateFlow<List<TileValue>?> =
        combine(tilesTicker, showCircling, tilesChanged) { _, circling, _ -> circling }
            .conflate()
            .map { circling ->
                try {
                    core.tiles(if (circling) TileLayout.CIRCLING else TileLayout.CRUISE)
                } catch (_: Exception) {
                    null
                }
            }
            // only while the flight screen shows them
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Tap a tile: [onAction] gets what XCSoar's InfoBox opens. */
    fun tapTile(layout: TileLayout, tile: Int, onAction: (TileAction) -> Unit) {
        viewModelScope.launch {
            try {
                core.tapTile(layout, tile)?.let(onAction)
            } catch (_: Exception) {
                // the core stopped: nothing to open
            }
        }
    }

    /** Read the tiles again now (after the pilot changed one). */
    fun refreshTiles() {
        tilesChanged.value++
    }

    private val warningsFlow = MutableStateFlow<List<AirspaceWarningInfo>>(emptyList())
    /** Airspace warnings to show, most severe first (hidden ones left out). */
    val airspaceWarnings: StateFlow<List<AirspaceWarningInfo>> = warningsFlow.asStateFlow()

    private val hideTimerFlow = MutableStateFlow<HideTimer?>(null)
    /** When the first shown warning hides itself; null if it stays. */
    val airspaceHideTimer: StateFlow<HideTimer?> = hideTimerFlow.asStateFlow()

    private val airspaceAlertsFlow = MutableStateFlow(AirspaceAlerts())
    /** How warnings reach the pilot (Menu → Airspace). */
    val airspaceAlerts: StateFlow<AirspaceAlerts> = airspaceAlertsFlow.asStateFlow()

    /** Read the airspace alert options again (after the pilot changed them). */
    fun refreshAirspaceAlerts() {
        viewModelScope.launch {
            airspaceAlertsFlow.value = try {
                core.airspaceAlerts()
            } catch (_: Exception) {
                AirspaceAlerts()
            }
            showWarnings()
        }
    }

    private val varioSoundFlow = MutableStateFlow<Boolean?>(null)
    /** The vario sound is on; null if this device has none (yet). */
    val varioSound: StateFlow<Boolean?> = varioSoundFlow.asStateFlow()

    fun setVarioSound(on: Boolean) {
        varioSoundFlow.value = on
        viewModelScope.launch {
            try {
                core.setSoundOption(SoundOption.VARIO, if (on) 1 else 0)
            } catch (_: Exception) {
                varioSoundFlow.value = readVarioSound()
            }
        }
    }

    /** Read the vario sound again (after the pilot changed its settings). */
    fun refreshVarioSound() {
        viewModelScope.launch { varioSoundFlow.value = readVarioSound() }
    }

    private suspend fun readVarioSound(): Boolean? = try {
        core.soundOption(SoundOption.VARIO)?.let { it != 0 }
    } catch (_: Exception) {
        null
    }

    private val alertFlow = MutableSharedFlow<AirspaceAlert>(extraBufferCapacity = 4)
    /** Something new the pilot must notice: sound and/or vibrate. */
    val alerts: SharedFlow<AirspaceAlert> = alertFlow.asSharedFlow()

    private val flightEndFlow = MutableSharedFlow<FlightEnd>(extraBufferCapacity = 2)
    /** A flight ended: landed (not in a replay), or a replay reached its end. */
    val flightEnded: SharedFlow<FlightEnd> = flightEndFlow.asSharedFlow()

    private val calloutFlow = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** What the voice says (Callouts), as the pilot chose. */
    val callouts: SharedFlow<String> = calloutFlow.asSharedFlow()

    /* when the last callout but airspace was said (SystemClock.uptimeMillis) */
    private var lastCallout = Long.MIN_VALUE / 2

    /**
     * A replay runs: not the pilot's flight, so nothing in it alerts, no
     * tone, vibration or voice (the pilot, 2026-11-27).  The core warns
     * of no airspace in it either.
     */
    private val replaying get() = core.flightState.value?.replay == true

    /**
     * Say [text]: airspace always, the others at most every
     * [CALLOUT_GAP_MS]; nothing in a replay.
     */
    private fun say(text: String?, airspace: Boolean = false) {
        if (text == null || replaying) return
        val now = SystemClock.uptimeMillis()
        if (!airspace) {
            if (now - lastCallout < CALLOUT_GAP_MS) return
            lastCallout = now
        }
        calloutFlow.tryEmit(text)
    }

    /** The callouts of a glide computer event: final glide, task points. */
    private fun callout(event: GlideComputerEvent) {
        // not later either, should the replay stop meanwhile
        if (replaying) return
        val settings = calloutSettings()
        when (event) {
            GlideComputerEvent.FINALGLIDE_ABOVE, GlideComputerEvent.FINALGLIDE_BELOW ->
                if (settings.finalGlide)
                    say(Callouts.finalGlide(event == GlideComputerEvent.FINALGLIDE_ABOVE,
                                            core.flightState.value?.finalGlide
                                                ?.altitudeDifference))
            GlideComputerEvent.TASK_START, GlideComputerEvent.TASK_NEXTWAYPOINT,
            GlideComputerEvent.TASK_FINISH ->
                if (settings.taskPoints)
                    viewModelScope.launch {
                        // the next point is in the snapshot after the event
                        delay(NEXT_POINT_WAIT_MS)
                        say(Callouts.taskPoint(event, core.flightState.value?.next))
                    }
            // the core says it once, then at most every 5 minutes
            GlideComputerEvent.LANDABLE_UNREACHABLE -> viewModelScope.launch {
                if (outOfCone()) {
                    alertFlow.tryEmit(AirspaceAlert(Alert.WARNING, sound = true,
                                                    vibration = true))
                    // a safety alert: not dropped for a recent callout
                    say(Callouts.OUT_OF_CONE, airspace = true)
                }
            }
            else -> {}
        }
    }

    /**
     * No field left in the pilot's safety glide cone: the cone is on
     * (Safety page), so "no landable in reach" means out of it.
     */
    private suspend fun outOfCone(): Boolean {
        val ratio = try {
            core.safetyOption(SafetyOption.GLIDE_RATIO)
        } catch (_: Exception) {
            null
        }
        return ratio != null && ratio > 0 && !replaying
    }

    /* warnings already announced, by airspace and state */
    private var announced = emptySet<String>()

    /* the core's warnings, and when each (airspace and state) appeared */
    private var activeWarnings = emptyList<AirspaceWarningInfo>()
    private var shownSince = emptyMap<String, Long>()
    private var hideJob: Job? = null

    private val AirspaceWarningInfo.key get() = "$id/$state"

    private suspend fun refreshWarnings() {
        val warnings = try {
            core.airspaceWarnings()
        } catch (_: Exception) {
            emptyList()
        }
        val now = SystemClock.uptimeMillis()
        activeWarnings = warnings
        shownSince = warnings.associate { it.key to (shownSince[it.key] ?: now) }
        showWarnings()

        // like XCSoar: alert once per new warning, again when it gets worse
        val keys = warnings.map { it.key }.toSet()
        val fresh = warnings.filter { it.key !in announced }
        announced = keys
        val options = airspaceAlertsFlow.value
        if (fresh.isNotEmpty() && (options.sound || options.vibration) && !replaying)
            alertFlow.tryEmit(AirspaceAlert(
                if (fresh.any { it.inside }) Alert.WARNING else Alert.CAUTION,
                options.sound, options.vibration))
        // the most severe first, as the banner
        if (options.warnings && calloutSettings().airspace)
            fresh.mapNotNull(Callouts::airspace).take(2).forEach { say(it, airspace = true) }
    }

    /**
     * Publish the warnings still shown: with auto hide, each one goes
     * after its time, until it gets worse (a new state shows again).
     */
    private fun showWarnings() {
        val now = SystemClock.uptimeMillis()
        val hideMillis = airspaceAlertsFlow.value.autoHideSeconds * 1000L
        fun hidesAt(w: AirspaceWarningInfo) = (shownSince[w.key] ?: now) + hideMillis

        val visible = if (hideMillis > 0) activeWarnings.filter { hidesAt(it) > now }
                      else activeWarnings
        warningsFlow.value = visible
        hideTimerFlow.value = visible.firstOrNull()
            ?.takeIf { hideMillis > 0 }
            ?.let { HideTimer(hidesAt(it) - hideMillis, hideMillis) }

        hideJob?.cancel()
        val next = visible.takeIf { hideMillis > 0 }?.minOfOrNull { hidesAt(it) }
        if (next != null)
            hideJob = viewModelScope.launch {
                delay(next - now)
                showWarnings()
            }
    }

    fun acknowledgeAirspace(warning: AirspaceWarningInfo, day: Boolean) {
        // gone at once; the core confirms on the refresh below
        activeWarnings = activeWarnings.filter { it.id != warning.id }
        showWarnings()
        viewModelScope.launch {
            try {
                core.acknowledgeAirspace(warning.id, day)
            } catch (_: Exception) {
                // gone meanwhile
            }
            refreshWarnings()
        }
    }

    init {
        // warnings change slowly; the airspace events below speed this up
        viewModelScope.launch {
            while (true) {
                if (flightState.value != null)
                    refreshWarnings()
                delay(WARNING_POLL_MS)
            }
        }
        viewModelScope.launch {
            try {
                core.start()
                varioSoundFlow.value = readVarioSound()
                readMapSettings()
                airspaceAlertsFlow.value = core.airspaceAlerts()
                core.units()?.let { Format.units = it }
            } catch (e: Exception) {
                // never crash the app: show why and keep the UI usable
                lastEventFlow.value = text(R.string.event_core_failed, e.message.orEmpty())
            }
        }
        // leaving a climb: its average and height gained
        viewModelScope.launch {
            var wasCircling: Boolean? = null
            var lastClimb: Thermal? = null
            core.flightState.map { it?.circling }.distinctUntilChanged().collect { circling ->
                if (wasCircling == true && circling == false && calloutSettings().climbs &&
                    !replaying)
                    launch {
                        // XCSoar sets the last thermal as the climb ends; a
                        // turn or two in and out of it leaves it as it was
                        delay(NEXT_POINT_WAIT_MS)
                        val thermal = core.flightState.value?.lastThermal
                        if (thermal != null && thermal != lastClimb) {
                            lastClimb = thermal
                            say(Callouts.climb(thermal))
                        }
                    }
                wasCircling = circling
            }
        }
        viewModelScope.launch {
            core.events.collect { event ->
                if (event is CoreEvent.GlideComputer)
                    callout(event.event)
                if (event is CoreEvent.GlideComputer &&
                    event.event == GlideComputerEvent.LANDING &&
                    core.flightState.value?.replay != true)
                    flightEndFlow.tryEmit(FlightEnd.Landed)
                if (event is CoreEvent.ReplayFinished)
                    (replayAnalysis?.let(FlightEnd::ReplayedFile)
                        ?: event.analysis?.let(FlightEnd::Replayed))
                        ?.let(flightEndFlow::tryEmit)
                if (event is CoreEvent.GlideComputer &&
                    event.event in AIRSPACE_EVENTS)
                    launch { refreshWarnings() }
                if (event is CoreEvent.GlideComputer &&
                    (event.event == GlideComputerEvent.FLIGHTMODE_CLIMB ||
                     event.event == GlideComputerEvent.FLIGHTMODE_CRUISE))
                    modeOverride.value = null
                lastEventFlow.value = when (event) {
                    is CoreEvent.GlideComputer -> describe(event.event)
                    is CoreEvent.Message -> event.text.asText()
                    is CoreEvent.ReplayFinished -> text(R.string.summary_replay_finished)
                }
            }
        }
    }

    private val replaySpeedFlow = MutableStateFlow(ReplaySpeed())
    /** The speed the next or current replay runs at, and whether it is paused. */
    val replaySpeed: StateFlow<ReplaySpeed> = replaySpeedFlow.asStateFlow()

    private val replayWindowFlow = MutableStateFlow<ReplayWindow?>(null)
    /** What the current replay spans, for its slider; null: unknown (the whole file). */
    val replayWindow: StateFlow<ReplayWindow?> = replayWindowFlow.asStateFlow()

    private val replaySeekFlow = MutableStateFlow<Double?>(null)
    /** Where the replay is going while the core computes its way there (s of [ReplayWindow]). */
    val replaySeek: StateFlow<Double?> = replaySeekFlow.asStateFlow()

    /* the seek to make after the one under way: the slider's last place */
    private var nextSeek: Double? = null

    /* the replayed file's analysis (its page's), for the summary at its end */
    private var replayAnalysis: Analysis? = null

    /**
     * Replay an IGC or NMEA file at [replaySpeed]: from the take-off to
     * the landing when its [analysis] (the flight's page) gives them,
     * else the whole file.  At its end, the summary is that analysis's.
     */
    fun startReplay(path: String, analysis: Analysis? = null) {
        val window = ReplayWindow.of(analysis?.date, analysis?.flight)
        replaySpeedFlow.value = replaySpeedFlow.value.copy(paused = false)
        replayWindowFlow.value = window
        replayAnalysis = analysis
        nextSeek = null
        viewModelScope.launch {
            try {
                core.startReplay(path, timeScale = replaySpeedFlow.value.timeScale)
                if (window != null) {
                    core.setReplayEnd(window.end)
                    seekReplay(window.start)
                }
            } catch (e: Exception) {
                lastEventFlow.value = text(R.string.event_replay_failed, e.message.orEmpty())
            }
        }
    }

    fun stopReplay() {
        viewModelScope.launch {
            try {
                core.stopReplay()
            } catch (e: Exception) {
                lastEventFlow.value = text(R.string.event_replay_not_stopped, e.message.orEmpty())
            }
        }
    }

    fun pauseReplay(paused: Boolean) {
        replaySpeedFlow.value = replaySpeedFlow.value.copy(paused = paused)
        val speed = replaySpeedFlow.value
        // only a running replay: the speed is also the next one's
        if (flightState.value?.replay != true) return
        viewModelScope.launch {
            try {
                core.setReplayTimeScale(if (speed.paused) 0.0 else speed.timeScale)
            } catch (_: Exception) {
                // the replay just ended
            }
        }
    }

    /**
     * Go to [time] of the replay (s since its day's midnight, as
     * [ReplayWindow]): the slider let go.  The core computes every fix
     * up to there, seconds for hours of flight; a slider let go again
     * meanwhile goes on from there to its last place.
     */
    fun seekReplay(time: Double) {
        if (replaySeekFlow.value != null) {
            nextSeek = time
            replaySeekFlow.value = time
            return
        }
        replaySeekFlow.value = time
        viewModelScope.launch {
            var target: Double? = time
            while (target != null) {
                try {
                    core.seekReplay(target)
                } catch (_: Exception) {
                    // past the end, or the replay just ended
                }
                target = nextSeek
                nextSeek = null
            }
            replaySeekFlow.value = null
        }
    }

    /**
     * Show the cruise or circling layout until the next flight mode
     * change; null follows the flight mode again.
     */
    fun selectFlightMode(circling: Boolean?) {
        modeOverride.value = circling
    }

    private val mapOrientationFlow = MutableStateFlow<MapOrientation?>(null)
    /** The map orientation; null until the core answered. */
    val mapOrientation: StateFlow<MapOrientation?> = mapOrientationFlow.asStateFlow()

    private val circlingZoomFlow = MutableStateFlow(true)
    /** A zoom of its own while circling (Map & units). */
    val circlingZoom: StateFlow<Boolean> = circlingZoomFlow.asStateFlow()

    private val reliefFlow = MutableStateFlow(true)
    /** The shaded relief on the map (Map & units, XCSoar's terrain switch). */
    val relief: StateFlow<Boolean> = reliefFlow.asStateFlow()

    /**
     * The map's orientation and circling zoom from the profile: at the
     * start and back from a page (Map & units).
     */
    fun refreshMapSettings() {
        viewModelScope.launch { readMapSettings() }
    }

    private suspend fun readMapSettings() {
        try {
            core.mapOrientation()?.let { mapOrientationFlow.value = it }
            core.mapOption(MapOption.CIRCLING_ZOOM)?.let { circlingZoomFlow.value = it != 0 }
            core.mapOption(MapOption.TERRAIN)?.let { reliefFlow.value = it != 0 }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    fun setMapOrientation(next: MapOrientation) {
        mapOrientationFlow.value = next
        viewModelScope.launch {
            try {
                core.setMapOrientation(next)
            } catch (_: Exception) {
            }
        }
    }

    private val mapItemsFlow = MutableStateFlow<List<MapItemInfo>?>(null)
    /** The items at the last held point of the map; null when none is shown. */
    val mapItems: StateFlow<List<MapItemInfo>?> = mapItemsFlow.asStateFlow()

    /** "Go to" from the map items; closes them. */
    fun gotoWaypoint(id: Int) {
        mapItemsFlow.value = null
        viewModelScope.launch {
            try {
                core.gotoWaypoint(id)
            } catch (_: Exception) {
                lastEventFlow.value = text(R.string.event_goto_landable)
            }
        }
    }

    fun hideMapItems() {
        mapItemsFlow.value = null
    }

    /** The map's layers: the core's data as GeoJSON. */
    suspend fun mapFeatures(which: MapFeatures): String? = core.mapFeatures(which)

    /** The map's relief: the core's terrain heights. */
    suspend fun terrainTile(zoom: Int, x: Int, y: Int, size: Int): ShortArray? =
        core.terrainTile(zoom, x, y, size)

    /** The landables the map colours by reach. */
    suspend fun nearbyLandables(): List<WaypointInfo> =
        core.searchWaypoints("", WaypointFilter.LANDABLE, 150)

    /** What the pilot held on the map. */
    fun showFeatureItems(items: List<MapItemInfo>) {
        mapItemsFlow.value = items.ifEmpty { null }
    }

    /* the pilot's MacCready until a snapshot has it */
    private val pendingMacCready = MutableStateFlow<Double?>(null)

    /**
     * MacCready as the pilot set it, at once; [flightState] only follows
     * once a second, and steps from its value would get lost.
     */
    val macCready: StateFlow<Double?> =
        combine(core.flightState, pendingMacCready) { state, pending ->
            pending ?: state?.macCready
        }.stateIn(viewModelScope, SharingStarted.Eagerly, core.flightState.value?.macCready)

    /** Set MacCready to [value] (m/s, already on the user unit's grid). */
    fun setMacCready(value: Double) {
        val mc = value.coerceIn(0.0, 5.0)
        pendingMacCready.value = mc
        viewModelScope.launch {
            try {
                core.setMacCready(mc)
                withTimeoutOrNull(MAC_CREADY_WAIT_MS) {
                    core.flightState.first { it == null || abs(it.macCready - mc) < 1e-6 }
                }
                // speed to fly and the like follow MacCready
                refreshTiles()
            } catch (e: Exception) {
                lastEventFlow.value = text(R.string.event_mc_not_set, e.message.orEmpty())
            }
            // unless the pilot stepped again meanwhile
            pendingMacCready.compareAndSet(mc, null)
        }
    }

    /* climb and cruise are left out: the tiles already show the mode */
    private fun describe(e: GlideComputerEvent): UiText? = when (e) {
        GlideComputerEvent.TAKEOFF -> text(R.string.event_takeoff)
        GlideComputerEvent.LANDING -> text(R.string.event_landing)
        GlideComputerEvent.FLIGHTMODE_FINALGLIDE -> text(R.string.voice_final_glide_title)
        GlideComputerEvent.TASK_START -> text(R.string.event_task_started)
        GlideComputerEvent.TASK_NEXTWAYPOINT -> text(R.string.event_next_turnpoint)
        GlideComputerEvent.TASK_FINISH -> text(R.string.event_task_finished)
        GlideComputerEvent.LANDABLE_UNREACHABLE -> text(R.string.event_no_landable)
        else -> null
    }
}

/** 5 screen updates per second: smooth enough for the vario. */
private const val UI_INTERVAL_MS = 1000L

/**
 * How fast a replay runs: [timeScale] times real time, unless [paused].
 * Its slider goes anywhere in the flight: no other speed.
 */
data class ReplaySpeed(val timeScale: Double = 10.0, val paused: Boolean = false)

/** What a replay shows after the landing, s: the roll to a stop. */
private const val REPLAY_AFTER_LANDING = 60.0

/**
 * What a replay spans, for its slider: the flight's day ([dayStartUtc],
 * its midnight UTC, s since 1970), and its take-off ([start]) and
 * landing ([end], with the roll after it), s since that midnight, as
 * the flight's analysis gives them ([FlightTimes]).
 */
data class ReplayWindow(val dayStartUtc: Double, val start: Double, val end: Double) {
    /** Where [timeUtc] (s since 1970) is in it, s since the day's midnight. */
    fun at(timeUtc: Double) = timeUtc - dayStartUtc

    companion object {
        /**
         * From a flight's analysis: its [date] ("YYYY-MM-DD", UTC) and
         * [times]; null without them.  Not landed (the file ends in the
         * air): to the end of the flight's time.
         */
        fun of(date: String?, times: FlightTimes?): ReplayWindow? {
            times ?: return null
            val day = try {
                LocalDate.parse(date ?: return null).atStartOfDay(ZoneOffset.UTC).toEpochSecond()
            } catch (_: DateTimeParseException) {
                return null
            }
            val end = times.landing?.let { it + REPLAY_AFTER_LANDING }
                ?: (times.takeoff + times.duration)
            return ReplayWindow(day.toDouble(), times.takeoff, end).takeIf { end > times.takeoff }
        }
    }
}
private const val VARIO_INTERVAL_MS = 200L
private const val TILES_INTERVAL_MS = 1000L
private const val TILES_IDLE_MS = 3000L

private const val WARNING_POLL_MS = 1000L

/** Callouts but airspace come at least this far apart. */
private const val CALLOUT_GAP_MS = 4000L

/** What an event's callout waits for the snapshot it changes. */
private const val NEXT_POINT_WAIT_MS = 1500L

/** How long a MacCready set waits for a snapshot with it; ~0.1 s on a phone. */
private const val MAC_CREADY_WAIT_MS = 2000L

private val AIRSPACE_EVENTS = setOf(
    GlideComputerEvent.AIRSPACE_NEAR, GlideComputerEvent.AIRSPACE_ENTER,
    GlideComputerEvent.AIRSPACE_LEAVE)

/** How urgent an alert is (doc/architecture.rst colours: red, orange). */
enum class Alert { WARNING, CAUTION }

/** An alert to play, with the pilot's choice of sound and vibration:
    airspace, or leaving the safety glide cone. */
data class AirspaceAlert(val level: Alert, val sound: Boolean, val vibration: Boolean)

/**
 * A banner that hides itself: shown at [start] (SystemClock.uptimeMillis)
 * for [duration] ms.
 */
data class HideTimer(val start: Long, val duration: Long)

/** Total energy vario, its 30 s average and netto (m/s), as the snapshot has them. */
data class VarioValues(val vario: Double?, val average: Double?, val netto: Double?)
