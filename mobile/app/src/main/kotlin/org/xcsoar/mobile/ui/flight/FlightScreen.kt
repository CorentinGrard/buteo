// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.flight

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.xcsoar.mobile.core.AirspaceAlerts
import org.xcsoar.mobile.core.AirspaceWarningInfo
import org.xcsoar.mobile.core.FakeXcsoarCore
import org.xcsoar.mobile.core.FlightState
import org.xcsoar.mobile.core.MapCoverage
import org.xcsoar.mobile.core.MapItemInfo
import org.xcsoar.mobile.core.MapOrientation
import org.xcsoar.mobile.core.TileAction
import org.xcsoar.mobile.core.TileLayout
import org.xcsoar.mobile.core.TileGraphic
import kotlinx.coroutines.flow.StateFlow
import org.xcsoar.mobile.core.TileValue
import org.xcsoar.mobile.R
import org.xcsoar.mobile.ui.Format
import org.xcsoar.mobile.ui.UiText
import org.xcsoar.mobile.ui.theme.ThemeChoice
import org.xcsoar.mobile.ui.theme.XcsColors
import org.xcsoar.mobile.ui.theme.XcsTheme
import org.xcsoar.mobile.ui.upperStringResource
import java.io.File

@Composable
fun FlightScreen(
    viewModel: FlightViewModel,
    onOpenDataFiles: () -> Unit = {},
    onOpenWaypoints: () -> Unit = {},
    onOpenFlightSetup: () -> Unit = {},
    onOpenFlights: () -> Unit = {},
    onOpenTask: () -> Unit = {},
    onOpenAnalysis: () -> Unit = {},
    onOpenCrew: () -> Unit = {},
    onOpenAirspaceAlerts: () -> Unit = {},
    onOpenWeather: () -> Unit = {},
    onOpenPreflight: () -> Unit = {},
    /** units, when the core draws no map (else they are on the map's page) */
    onOpenUnits: () -> Unit = {},
    /** null when the core draws no map */
    onOpenMapSettings: (() -> Unit)? = null,
    onOpenSafety: () -> Unit = {},
    /** the wind chip */
    onOpenWind: () -> Unit = {},
    onOpenStatus: () -> Unit = {},
    onOpenSound: () -> Unit = {},
    onOpenTracking: () -> Unit = {},
    onOpenProfiles: () -> Unit = {},
    theme: ThemeChoice = ThemeChoice.SYSTEM,
    onTheme: (ThemeChoice) -> Unit = {},
    /** rows of three tiles ([TILE_ROWS]) */
    tileRows: Int = TILE_ROWS.first,
    onTileRows: (Int) -> Unit = {},
    /** where the map's region files are (XCSoarData/maps) */
    vectorMapDir: File? = null,
    /** changes with the region files: the vector map opens them again */
    vectorMapVersion: Int = 0,
    /** the vector map's zooms, cruising and circling, and where to keep them */
    vectorMapZooms: Pair<Double, Double> = VECTOR_CRUISE_ZOOM to VECTOR_CIRCLING_ZOOM,
    onVectorMapZooms: (cruise: Double, circling: Double) -> Unit = { _, _ -> },
    /** where the glider was last, for the vector map before a fix, and where to keep it */
    vectorMapPlace: Pair<Double, Double>? = null,
    onVectorMapPlace: (latitude: Double, longitude: Double) -> Unit = { _, _ -> },
    onEditTile: (TileLayout, Int) -> Unit = { _, _ -> },
    /** A tile was tapped: the page its InfoBox opens. */
    onTileAction: (TileAction) -> Unit = {},
    /** the menu is open (kept under the pages it opens) */
    menuOpen: Boolean = false,
    onMenuOpen: (Boolean) -> Unit = {},
    /** a page lies over the flight screen */
    covered: Boolean = false,
    /**
     * The route on the map: this panel instead of the tiles, and a tap on
     * the map goes to [onTaskMapTap] instead of a hold's card.
     */
    taskPanel: (@Composable () -> Unit)? = null,
    /** which mode [taskPanel] is, and its taps on the map */
    taskMapMode: TaskMapMode? = null,
    onTaskMapTap: (latitude: Double, longitude: Double, waypointId: Int?) -> Unit = { _, _, _ -> },
) {
    val state by viewModel.flightState.collectAsStateWithLifecycle()
    // read where it is shown, so only the vario bar recomposes with it
    val vario = viewModel.vario.collectAsStateWithLifecycle()
    val lastEvent by viewModel.lastEvent.collectAsStateWithLifecycle()
    val circling by viewModel.showCircling.collectAsStateWithLifecycle()
    val tileLayout by viewModel.tileLayout.collectAsStateWithLifecycle()
    val mapOrientation by viewModel.mapOrientation.collectAsStateWithLifecycle()
    val mapItems by viewModel.mapItems.collectAsStateWithLifecycle()
    val warnings by viewModel.airspaceWarnings.collectAsStateWithLifecycle()
    val varioSound by viewModel.varioSound.collectAsStateWithLifecycle()
    val macCready by viewModel.macCready.collectAsStateWithLifecycle()
    val tiles by viewModel.tiles.collectAsStateWithLifecycle()
    val hideTimer by viewModel.airspaceHideTimer.collectAsStateWithLifecycle()
    val airspaceAlerts by viewModel.airspaceAlerts.collectAsStateWithLifecycle()
    val replaySpeed by viewModel.replaySpeed.collectAsStateWithLifecycle()
    val thermalAssistant by viewModel.thermalAssistant.collectAsStateWithLifecycle()
    val circlingZoom by viewModel.circlingZoom.collectAsStateWithLifecycle()
    val relief by viewModel.relief.collectAsStateWithLifecycle()
    val vectorState = remember {
        VectorMapState(vectorMapZooms.first, vectorMapZooms.second, onVectorMapZooms,
                       vectorMapPlace, onVectorMapPlace)
    }
    // back from a page: the airspace alerts, the vario sound or the
    // map's settings may have changed
    LaunchedEffect(covered) {
        if (!covered) {
            viewModel.refreshAirspaceAlerts()
            viewModel.refreshVarioSound()
            viewModel.refreshMapSettings()
        }
    }
    BackHandler(enabled = menuOpen && !covered) { onMenuOpen(false) }

    FlightContent(
        state = state,
        lastEvent = lastEvent,
        circling = circling,
        tiles = tiles?.take(tileRows * TILE_COLUMNS),
        vario = { vario.value },
        macCready = macCready,
        onEditTile = { tile ->
            onEditTile(if (circling) TileLayout.CIRCLING else TileLayout.CRUISE, tile)
        },
        onTapTile = { tile ->
            viewModel.tapTile(if (circling) TileLayout.CIRCLING else TileLayout.CRUISE, tile,
                              onTileAction)
        },
        onMacCreadyChange = viewModel::setMacCready,
        onSetMacCready = viewModel::setMacCready,
        warnings = warnings,
        airspaceHideTimer = hideTimer,
        onAcknowledge = viewModel::acknowledgeAirspace,
        onNextWaypoint = onOpenWaypoints,
        onWind = onOpenWind,
        onNoPolar = onOpenCrew,
        replay = ReplayControls(replaySpeed, viewModel::pauseReplay, viewModel::setReplaySpeed,
                                viewModel::skipReplay, viewModel::stopReplay),
        menuOpen = menuOpen,
        onMenuOpen = onMenuOpen,
        menu = flightMenu(
            onOpenWaypoints, onOpenTask, onOpenFlightSetup, onOpenAnalysis, onOpenCrew,
            onOpenFlights, onOpenDataFiles, onOpenAirspaceAlerts,
            onOpenWeather = onOpenWeather,
            onOpenPreflight = onOpenPreflight,
            onOpenUnits = onOpenUnits,
            onOpenMapSettings = onOpenMapSettings,
            onOpenSafety = onOpenSafety,
            onOpenStatus = onOpenStatus,
            onOpenSound = onOpenSound,
            onOpenTracking = onOpenTracking,
            onOpenProfiles = onOpenProfiles,
            airspaceAlerts = airspaceAlerts,
            tileLayout = tileLayout,
            onTileLayout = viewModel::selectFlightMode,
            tileRows = tileRows,
            onTileRows = onTileRows,
            orientation = mapOrientation,
            onOrientation = viewModel::setMapOrientation,
            varioSound = varioSound,
            onVarioSound = viewModel::setVarioSound,
            theme = theme,
            onTheme = onTheme,
            mapCoverage = vectorState.coverage,
        ),
        map = MapSlot(
            content = { modifier ->
                // new regions: their bounds and zooms go into MapLibre's style
                key(vectorMapVersion) {
                    VectorMap(vectorState, vectorMapDir, viewModel::mapFeatures,
                              viewModel::terrainTile, viewModel::nearbyLandables,
                              viewModel.liveState, mapOrientation, viewModel::showFeatureItems,
                              modifier, circling = circling, circlingZoom = circlingZoom,
                              relief = relief,
                              taskMode = taskMapMode.takeIf { taskPanel != null },
                              onTaskTap = onTaskMapTap)
                }
            },
            onAircraftPosition = { _, y -> vectorState.setFocus(y) },
            onZoom = vectorState::zoom,
            follows = vectorState.follows,
            onFollow = vectorState::follow,
            orientation = mapOrientation,
            items = mapItems,
            onCloseItems = viewModel::hideMapItems,
            onGoto = viewModel::gotoWaypoint,
            thermalAssistant = thermalAssistant,
            liveState = viewModel.liveState,
        ),
        taskPanel = taskPanel,
    )
}

/** What the replay bar does (see [ReplayBar]). */
class ReplayControls(
    val speed: ReplaySpeed,
    val onPause: (Boolean) -> Unit = {},
    val onSpeed: (Double) -> Unit = {},
    val onSkip: () -> Unit = {},
    val onStop: () -> Unit = {},
)

/**
 * The moving map (MapLibre's, [VectorMap]): it pans, pinches and holds
 * itself.
 *
 * @param onAircraftPosition where to draw the aircraft, in pixels: the
 * middle of the part of the map the cards leave free
 * @param onZoom zoom steps (negative = in)
 * @param follows whether the map follows the aircraft
 * @param onFollow centre on the aircraft again
 * @param orientation which way is up, for the north mark; null hides it
 * @param items what is at the held point; null when not shown
 * @param onGoto fly directly to a waypoint of [items]
 * @param thermalAssistant drawn around the glider while circling
 * @param liveState the state at the vario's rate, which the thermal
 * assistant turns with
 */
class MapSlot(
    val content: @Composable (Modifier) -> Unit,
    val onAircraftPosition: (x: Int, y: Int) -> Unit,
    val onZoom: (steps: Int) -> Unit,
    val follows: Boolean = true,
    val onFollow: () -> Unit = {},
    val orientation: MapOrientation? = null,
    val items: List<MapItemInfo>? = null,
    val onCloseItems: () -> Unit = {},
    val onGoto: (waypointId: Int) -> Unit = {},
    val thermalAssistant: TileGraphic? = null,
    val liveState: StateFlow<FlightState?>? = null,
)

/**
 * The flight screen, map first: the map with the vario and final glide
 * bars on its edges and the menu and zoom buttons along its bottom, and
 * the tiles below it (portrait) or beside it (landscape, with the next
 * waypoint).
 * Without [map] (no native core) the map area only shows the glider
 * symbol.
 */
@Composable
fun FlightContent(
    state: FlightState?,
    lastEvent: UiText?,
    circling: Boolean,
    onMacCreadyChange: (Double) -> Unit,
    onSetMacCready: (Double) -> Unit = {},
    menu: FlightMenu = FlightMenu(),
    menuOpen: Boolean = false,
    onMenuOpen: (Boolean) -> Unit = {},
    map: MapSlot? = null,
    warnings: List<AirspaceWarningInfo> = emptyList(),
    /** the first warning hides itself then; null: it stays */
    airspaceHideTimer: HideTimer? = null,
    onAcknowledge: (AirspaceWarningInfo, day: Boolean) -> Unit = { _, _ -> },
    onNextWaypoint: () -> Unit = {},
    onWind: () -> Unit = {},
    /** the "NO POLAR" chip: set up a plane */
    onNoPolar: () -> Unit = {},
    tiles: List<TileValue>? = null,
    onEditTile: (Int) -> Unit = {},
    onTapTile: (Int) -> Unit = {},
    /** Faster than [state]; from [state] when null (previews). */
    vario: (() -> VarioValues?)? = null,
    /** As the pilot set it, before [state] has it. */
    macCready: Double? = state?.macCready,
    /** The replay's controls, shown while [state] is a replay. */
    replay: ReplayControls? = null,
    /** The task on the map: this instead of the tiles and the map's cards. */
    taskPanel: (@Composable () -> Unit)? = null,
) {
    val colors = XcsTheme.colors
    // the MacCready stepper, from its tile: each use counts, and it
    // closes by itself a while after the last
    var stepper by remember { mutableIntStateOf(0) }
    LaunchedEffect(stepper) {
        if (stepper > 0) {
            delay(STEPPER_MILLIS)
            stepper = 0
        }
    }
    val macCreadyStepper = if (stepper > 0) MacCreadyStepper(
        macCready, auto = state?.autoMacCready == true,
        onChange = {
            onMacCreadyChange(it)
            stepper++
        },
        onClose = { stepper = 0 }) else null
    BoxWithConstraints(Modifier.fillMaxSize().background(colors.background)) {
        val instruments = @Composable {
            if (taskPanel != null)
                taskPanel()
            else
                Instruments(state, circling, tiles, onEditTile, onTapTile,
                            onMacCready = { stepper = if (stepper > 0) 0 else 1 })
        }
        val mapArea = @Composable { showNext: Boolean, modifier: Modifier,
                                    insets: WindowInsets ->
            MapArea(state, lastEvent, circling, map, warnings, airspaceHideTimer,
                    onAcknowledge, onNextWaypoint, onWind, onNoPolar,
                    onSetMacCready, showNext,
                    vario, replay, modifier, insets, onMenu = { onMenuOpen(true) },
                    stepper = macCreadyStepper, taskOnMap = taskPanel != null)
        }

        if (maxWidth > maxHeight && maxWidth >= 600.dp) {
            Row(Modifier.fillMaxSize()) {
                mapArea(false, Modifier.weight(1f).fillMaxHeight(),
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical +
                                                      WindowInsetsSides.Start))
                Column(
                    Modifier
                        .width(400.dp)
                        .fillMaxHeight()
                        .background(colors.sheet)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Vertical + WindowInsetsSides.End)),
                ) {
                    // at the bottom, as in portrait: MacCready and the menu
                    // stay under the hand; short screens scroll
                    Spacer(Modifier.weight(1f))
                    Column(
                        Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (taskPanel == null)
                            NextWaypointCard(state?.next, Modifier.fillMaxWidth(),
                                             state?.nextTimeRemaining, onClick = onNextWaypoint,
                                             elevated = false)
                        instruments()
                    }
                }
            }
        } else {
            // the sheet may scroll on short screens; the map keeps most of
            // the height
            val sheetMax = maxHeight - 240.dp
            Column(Modifier.fillMaxSize()) {
                mapArea(true, Modifier.weight(1f).fillMaxWidth(),
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Top +
                                                      WindowInsetsSides.Horizontal))
                val sheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = sheetMax)
                        // flat: an outline where it meets the map
                        .background(colors.sheet, sheetShape)
                        .border(1.dp, colors.outline, sheetShape)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) { instruments() }
            }
        }

        FlightMenuSheet(menuOpen, menu, onClose = { onMenuOpen(false) })
    }
}

@OptIn(ExperimentalLayoutApi::class) // statusBarsIgnoringVisibility
@Composable
private fun MapArea(
    state: FlightState?,
    lastEvent: UiText?,
    circling: Boolean,
    map: MapSlot?,
    warnings: List<AirspaceWarningInfo>,
    hideTimer: HideTimer?,
    onAcknowledge: (AirspaceWarningInfo, day: Boolean) -> Unit,
    onNextWaypoint: () -> Unit,
    onWind: () -> Unit,
    onNoPolar: () -> Unit,
    onSetMacCready: (Double) -> Unit,
    /** false in landscape, where the side panel shows it */
    showNext: Boolean,
    vario: (() -> VarioValues?)?,
    replay: ReplayControls?,
    modifier: Modifier,
    insets: WindowInsets,
    onMenu: () -> Unit,
    /** open over the map buttons; null when closed */
    stepper: MacCreadyStepper?,
    /** the task on the map: only the airspace warnings and the zoom stay */
    taskOnMap: Boolean = false,
) {
    val colors = XcsTheme.colors
    // the glider goes in the middle of the map area the cards leave free
    var cardsBottom by remember { mutableIntStateOf(0) }
    var mapSize by remember { mutableStateOf(IntSize.Zero) }
    if (map != null)
        LaunchedEffect(cardsBottom, mapSize) {
            if (mapSize != IntSize.Zero)
                map.onAircraftPosition(mapSize.width / 2,
                                       (cardsBottom.coerceAtMost(mapSize.height) +
                                        mapSize.height) / 2)
        }

    Box(modifier.onSizeChanged { mapSize = it }) {
        if (map != null) {
            map.content(Modifier.fillMaxSize())
            val assistant = map.thermalAssistant
            if (assistant != null && circling && map.follows && mapSize != IntSize.Zero)
                ThermalAssistantSlot(assistant, map, state, cardsBottom, mapSize)
        } else
            MapPlaceholder(state?.track, cardsBottom, Modifier.fillMaxSize())

        // the status bar's icons follow the theme (MainActivity): keep
        // them on its colour, not on the map, where light icons on white
        // ground were invisible
        Box(Modifier
                .fillMaxWidth()
                .windowInsetsTopHeight(WindowInsets.statusBars)
                .background(colors.sheet))
        // hidden in flight (MainActivity), the bars come back with a swipe
        // from the top: the app gets that swipe too, and it must not pan
        // the map away from the glider
        Box(Modifier
                .fillMaxWidth()
                .windowInsetsTopHeight(WindowInsets.statusBarsIgnoringVisibility)
                .pointerInput(Unit) {
                    awaitPointerEventScope { while (true) awaitPointerEvent() }
                })

        Column(
            Modifier
                .onSizeChanged { cardsBottom = it.height }
                .windowInsetsPadding(insets)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            warnings.firstOrNull()?.let { top ->
                AirspaceWarningBanner(top, warnings.size - 1, { day -> onAcknowledge(top, day) },
                                      Modifier.fillMaxWidth(), hideTimer)
            }
            if (state?.replay == true && replay != null)
                ReplayBar(state.timeUtc, replay.speed, replay.onPause, replay.onSpeed,
                          replay.onSkip, replay.onStop)
            if (showNext && !taskOnMap)
                NextWaypointCard(state?.next, Modifier.fillMaxWidth(), state?.nextTimeRemaining,
                                 onClick = onNextWaypoint)
            if (!taskOnMap) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    state == null -> StatusChip(stringResource(R.string.flight_starting),
                                                 colors.textSecondary)
                    else -> {
                        WindChip(state.wind, onClick = onWind)
                        if (state.position == null)
                            StatusChip(upperStringResource(R.string.flight_no_gps), colors.caution)
                        // upstream's "No polar is set up, so final glide and speed to
                        // fly are off"
                        if (state.noPolar)
                            StatusChip(upperStringResource(R.string.flight_no_polar),
                                       colors.caution,
                                       onClick = onNoPolar,
                                       onClickLabel = stringResource(R.string.flight_set_up_plane))
                        if (state.replay && replay == null)
                            StatusChip(upperStringResource(R.string.replay_status),
                                       colors.neutralSafe)
                        if (!state.flying)
                            StatusChip(stringResource(R.string.flight_on_ground), colors.text)
                    }
                }
                if (lastEvent != null) StatusChip(lastEvent.text(), colors.textSecondary)
            }
        }

        // below the cards: the vario on the left edge, final glide on the
        // right, the map buttons along the bottom, the menu first, "Set
        // MC" beside it in lift
        val cardsHeight = with(LocalDensity.current) { cardsBottom.toDp() }
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(top = cardsHeight)
                .windowInsetsPadding(insets.only(WindowInsetsSides.Horizontal +
                                                 WindowInsetsSides.Bottom))
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        ) {
            // the task on the map: the zoom only, the panel does the rest
            if (taskOnMap) {
                if (map != null)
                    ZoomButtons(map.onZoom, Modifier.align(Alignment.BottomEnd))
                return@BoxWithConstraints
            }

            // what is left above the map buttons
            val barSpace = maxHeight - 56.dp - 12.dp
            // the vario stays, smaller, on short screens (EdgeBars.kt)
            if (barSpace >= VARIO_MIN_HEIGHT)
                VarioSlot(vario, state, Modifier.align(Alignment.TopStart)
                    .height(barSpace.coerceAtMost(300.dp)))
            if (barSpace >= FINAL_GLIDE_MIN_HEIGHT)
                FinalGlideBar(state?.finalGlide, Modifier.align(Alignment.TopEnd)
                    .height(barSpace.coerceAtMost(180.dp)))

            map?.items?.let {
                MapItemsCard(it, map.onCloseItems, map.onGoto, Modifier
                    .align(Alignment.BottomStart)
                    .padding(bottom = 64.dp))
            }

            if (stepper != null) {
                // over the map buttons, under the thumb
                MacCreadyControl(stepper.macCready, stepper.onChange,
                                 Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                     .border(1.dp, colors.outline, RoundedCornerShape(12.dp)),
                                 auto = stepper.auto)
                BackHandler(onBack = stepper.onClose)
                return@BoxWithConstraints
            }

            // where the map is free under the vario, within reach of the
            // thumb; the target card keeps the whole width (D25)
            MenuButton(onMenu, Modifier.align(Alignment.BottomStart))

            // the buttons' width, to keep "Set MC" clear of them
            var buttonsWidth by remember { mutableIntStateOf(0) }
            val buttonsDp = with(LocalDensity.current) { buttonsWidth.toDp() }
            val thermal = state?.currentThermal
            if (circling && thermal != null && thermal.lift > 0) {
                val beside = maxWidth - buttonsDp - MENU_ROOM >= SET_MC_ROOM
                SetMacCreadyButton(thermal.lift,
                                   { onSetMacCready(Format.stepVerticalSpeed(thermal.lift, 0)) },
                                   Modifier
                                       .align(Alignment.BottomStart)
                                       .padding(start = if (beside) MENU_ROOM else 0.dp,
                                                bottom = if (beside) 0.dp else 64.dp))
            }

            Row(
                Modifier.align(Alignment.BottomEnd).onSizeChanged { buttonsWidth = it.width },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (map != null) {
                    // a turned map shows where north is
                    map.orientation?.takeIf { it != MapOrientation.NORTH_UP }
                        ?.let { mapAngle(it, state) }
                        ?.let { NorthMark(it) }
                    if (!map.follows)
                        CentreButton(map.onFollow)
                    ZoomButtons(map.onZoom)
                }
            }
        }
    }
}

/**
 * The thermal assistant on the glider, where the core draws it (the
 * middle of the map the cards leave free), as large as that part
 * allows; it turns with the map at the vario's rate.
 */
@Composable
private fun ThermalAssistantSlot(graphic: TileGraphic, map: MapSlot, state: FlightState?,
                                 cardsBottom: Int, mapSize: IntSize) {
    val live = map.liveState?.collectAsStateWithLifecycle()?.value ?: state
    val up = map.orientation?.let { mapAngle(it, live) } ?: live?.track ?: 0.0
    val top = cardsBottom.coerceAtMost(mapSize.height)
    val centre = Offset(mapSize.width / 2f, (top + mapSize.height) / 2f)
    val radius = with(LocalDensity.current) {
        val free = minOf((mapSize.height - top) / 2f, mapSize.width / 2f) - 28.dp.toPx()
        free.coerceIn(36.dp.toPx(), 110.dp.toPx()).toDp()
    }
    ThermalAssistantOverlay(graphic, centre, radius, up, Modifier.fillMaxSize().graphicsLayer())
}

/**
 * The direction at the top of the map, for the north mark: the same
 * references the core turns the map with
 * (core/map/CoreMap.cpp ScreenAngle()); null when the core has none.
 */
private fun mapAngle(orientation: MapOrientation, s: FlightState?): Double? = when (orientation) {
    MapOrientation.NORTH_UP -> 0.0
    MapOrientation.TRACK_UP -> s?.track
    MapOrientation.TARGET_UP -> s?.next?.bearing ?: s?.track
    MapOrientation.WIND_UP -> s?.wind?.bearing ?: s?.track
    MapOrientation.HEADING_UP -> null
}

/** The tiles' columns. */
private const val TILE_COLUMNS = 3

/** The rows of tiles the pilot can choose: XCSoar's core keeps four
    (core/host/CoreInfoBoxes.hpp TILE_COUNT). */
val TILE_ROWS = 2..4

/** Room "Set MC" needs beside the map buttons, else it goes above them. */
private val SET_MC_ROOM = 96.dp

/** The menu button and the gap after it, on the left of the map buttons. */
private val MENU_ROOM = 64.dp

/** The MacCready stepper closes this long after its last use. */
private const val STEPPER_MILLIS = 5000L

/** The MacCready stepper over the map, opened from the MacCready tile. */
class MacCreadyStepper(
    val macCready: Double?,
    val auto: Boolean,
    val onChange: (Double) -> Unit,
    val onClose: () -> Unit,
)

/**
 * XCSoar's InfoBoxes from the core (a long press picks another), every
 * cell as high as the others; a tap on the MacCready tile opens its
 * stepper over the map, the others do what their InfoBox does.
 */
@Composable
private fun Instruments(
    state: FlightState?,
    circling: Boolean,
    tiles: List<TileValue>?,
    onEditTile: (Int) -> Unit,
    onTapTile: (Int) -> Unit,
    onMacCready: () -> Unit,
) {
    val colors = XcsTheme.colors
    val boxes = tiles?.map { it.toInfoBoxValue(colors) } ?: infoBoxes(state, circling)
    TileGrid(boxes.map { 1 }, Modifier.fillMaxWidth()) {
        boxes.forEachIndexed { i, box ->
            InfoBox(box.title, box.value, Modifier, box.color, box.comment, box.commentColor,
                    onLongClick = if (tiles != null) ({ onEditTile(i) }) else null,
                    onClick = when {
                        box.type == TileValue.MACCREADY -> onMacCready
                        tiles != null -> ({ onTapTile(i) })
                        else -> null
                    },
                    graphic = box.graphic)
        }
    }
}

/**
 * Lays its children out in rows of [TILE_COLUMNS], each over
 * [spans] columns (in the children's order), all as high as the
 * highest: a tile with a comment is no taller than one without.
 */
@Composable
private fun TileGrid(spans: List<Int>, modifier: Modifier, content: @Composable () -> Unit) {
    // the tiles' height at a width: every tile has a caption and a value
    // line, so it changes with the layout only; each child's intrinsic
    // height lays its text out once more, 7 % of the UI thread every second
    val heights = remember(spans) { HashMap<Int, Int>() }
    Layout(content, modifier) { measurables, constraints ->
        val gap = 8.dp.roundToPx()
        val cell = (constraints.maxWidth - gap * (TILE_COLUMNS - 1)) / TILE_COLUMNS
        fun width(span: Int) = cell * span + gap * (span - 1)

        val height = heights.getOrPut(constraints.maxWidth) {
            measurables.withIndex()
                .maxOfOrNull { (i, m) -> m.minIntrinsicHeight(width(spans[i])) } ?: 0
        }
        // the column and row of each child
        var column = 0
        var row = 0
        val cells = spans.map { span ->
            if (column + span > TILE_COLUMNS) {
                column = 0
                row++
            }
            (column to row).also { column += span }
        }
        val placeables = measurables.mapIndexed { i, m ->
            m.measure(Constraints.fixed(width(spans[i]), height))
        }
        val rows = if (spans.isEmpty()) 0 else row + 1
        layout(constraints.maxWidth, (rows * height + (rows - 1) * gap).coerceAtLeast(0)) {
            placeables.forEachIndexed { i, p ->
                val (c, r) = cells[i]
                p.place(c * (cell + gap), r * (height + gap))
            }
        }
    }
}

/**
 * The vario bar, reading [vario] in its own scope: it changes five
 * times a second, the rest of the screen once.  Its own layer, too, so
 * redrawing it leaves the rest alone.
 */
@Composable
private fun VarioSlot(vario: (() -> VarioValues?)?, state: FlightState?, modifier: Modifier) {
    val v = vario?.invoke()
        ?: state?.let { VarioValues(it.vario, it.averageVario, it.nettoVario) }
    VarioBar(v?.vario, v?.average, v?.netto, modifier.graphicsLayer())
}

private class InfoBoxValue(val title: String, val value: Format.Value,
                           val color: Color? = null, val comment: String = "",
                           val commentColor: Color? = null,
                           val graphic: TileGraphic? = null,
                           /** XCSoar's InfoBox type; -1 for the design's */
                           val type: Int = -1)

/** InfoBoxLook's colours by function (doc/architecture.rst). */
private fun tileColor(code: Int, colors: XcsColors): Color? = when (code) {
    TileValue.COLOR_RED -> colors.warning
    TileValue.COLOR_BLUE -> colors.neutralSafe
    TileValue.COLOR_GREEN -> colors.safe
    TileValue.COLOR_YELLOW -> colors.caution
    TileValue.COLOR_MAGENTA -> colors.task
    else -> null
}

private fun TileValue.toInfoBoxValue(colors: XcsColors) =
    InfoBoxValue(title, Format.Value(value, unit), tileColor(color, colors), comment,
                 tileColor(commentColor, colors), graphic, type)

/**
 * The design's six tiles from the snapshot, for previews and the fake
 * core; with the native core, the tiles are XCSoar's InfoBoxes.
 */
@Composable
private fun infoBoxes(s: FlightState?, circling: Boolean): List<InfoBoxValue> {
    val colors = XcsTheme.colors
    fun climbColor(v: Double?) = when {
        v == null -> null
        v >= 0.05 -> colors.lift
        v <= -0.05 -> colors.sink
        else -> null
    }
    val altitude = InfoBoxValue("Altitude", Format.altitude(s?.navAltitude))
    val agl = InfoBoxValue("AGL", Format.altitude(s?.altitudeAgl))
    // as the core's MacCready tile: manual green, automatic blue
    val macCready = InfoBoxValue(
        if (s?.autoMacCready == true) "MC auto" else "MC",
        s?.let { Format.macCready(it.macCready) } ?: Format.Value(Format.INVALID, ""),
        if (s?.autoMacCready == true) colors.neutralSafe else colors.safe,
        comment = s?.speedToFly?.let { Format.speed(it).let { v -> "${v.text} ${v.unit}" } }
            ?: "",
        type = TileValue.MACCREADY)
    // the design's boxes; their values and validity are XCSoar's InfoBoxes
    return if (circling) {
        val thermal = s?.currentThermal
        val wind = s?.wind
        listOf(
            InfoBoxValue("Thermal avg", Format.vario(thermal?.lift), climbColor(thermal?.lift)),
            InfoBoxValue("Gained", Format.altitudeDifference(thermal?.gain)),
            macCready,
            altitude,
            agl,
            InfoBoxValue("Wind", wind?.let {
                Format.Value(Format.bearing(it.bearing).text + "°",
                             Format.windSpeed(it.speed).let { v -> "${v.text} ${v.unit}" })
            } ?: Format.Value(Format.INVALID, "")),
        )
    } else {
        val last = s?.lastThermal
        listOf(
            altitude,
            agl,
            macCready,
            InfoBoxValue("L/D req", Format.requiredGlideRatio(s?.ldRequired).let {
                // the current L/D as the unit, like the design's "27 now 34"
                it.copy(unit = s?.ld?.let { ld -> "now ${Format.glideRatio(ld).text}" } ?: "")
            }),
            InfoBoxValue("Ground speed", Format.speed(s?.groundSpeed)),
            InfoBoxValue("Last thermal", Format.vario(last?.lift), climbColor(last?.lift)),
        )
    }
}

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun CruisePreview() {
    XcsTheme(dark = false) {
        FlightContent(FakeXcsoarCore.syntheticState(120), null, circling = false, {})
    }
}

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun CirclingPreview() {
    XcsTheme(dark = false) {
        FlightContent(FakeXcsoarCore.syntheticState(320), null, circling = true, {})
    }
}

@Preview(widthDp = 844, heightDp = 390)
@Composable
private fun LandscapePreview() {
    XcsTheme(dark = false) {
        FlightContent(FakeXcsoarCore.syntheticState(120), null, circling = false, {})
    }
}

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun NightNoDataPreview() {
    XcsTheme(dark = true) {
        FlightContent(null, null, circling = false, {})
    }
}

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun MenuPreview() {
    XcsTheme(dark = false) {
        FlightContent(FakeXcsoarCore.syntheticState(120), null, circling = false, {},
                      menu = flightMenu(), menuOpen = true)
    }
}

/**
 * The flight menu: in-flight actions, the flight screen's quick
 * settings, then what is done before and after the flight and the
 * settings.  Each page is listed once; related pages open from one
 * another (D25): the pilot from Aircraft & crew, the replay from
 * Flights, NOTAMs from Airspace, units from the map, the wind from
 * Flight setup.
 */
@Composable
fun flightMenu(
    onOpenWaypoints: () -> Unit = {},
    onOpenTask: () -> Unit = {},
    onOpenFlightSetup: () -> Unit = {},
    onOpenAnalysis: () -> Unit = {},
    onOpenCrew: () -> Unit = {},
    onOpenFlights: () -> Unit = {},
    onOpenDataFiles: () -> Unit = {},
    onOpenAirspaceAlerts: () -> Unit = {},
    onOpenWeather: () -> Unit = {},
    onOpenPreflight: () -> Unit = {},
    /** without a map: the units have no map page to be on */
    onOpenUnits: () -> Unit = {},
    /** null when the core draws no map */
    onOpenMapSettings: (() -> Unit)? = {},
    onOpenSafety: () -> Unit = {},
    onOpenStatus: () -> Unit = {},
    onOpenSound: () -> Unit = {},
    onOpenTracking: () -> Unit = {},
    onOpenProfiles: () -> Unit = {},
    airspaceAlerts: AirspaceAlerts = AirspaceAlerts(),
    tileLayout: Boolean? = null,
    onTileLayout: (circling: Boolean?) -> Unit = {},
    tileRows: Int = TILE_ROWS.first,
    onTileRows: (Int) -> Unit = {},
    orientation: MapOrientation? = null,
    onOrientation: (MapOrientation) -> Unit = {},
    varioSound: Boolean? = null,
    onVarioSound: (Boolean) -> Unit = {},
    theme: ThemeChoice = ThemeChoice.SYSTEM,
    onTheme: (ThemeChoice) -> Unit = {},
    mapCoverage: MapCoverage? = null,
) = FlightMenu(
    inFlight = listOf(
        menuAction(R.string.menu_go_to, R.string.menu_go_to_detail, onOpenWaypoints),
        menuAction(R.string.menu_task, R.string.menu_task_detail, onOpenTask),
        menuAction(R.string.menu_flight_setup, R.string.menu_flight_setup_detail,
                   onOpenFlightSetup),
        menuAction(R.string.menu_analysis, R.string.menu_analysis_detail, onOpenAnalysis),
    ),
    sections = listOf(
        MenuSection(stringResource(R.string.menu_ground), listOf(
            menuAction(R.string.menu_preflight, R.string.menu_preflight_detail, onOpenPreflight),
            menuAction(R.string.menu_crew, R.string.menu_crew_detail, onOpenCrew),
            // ICAO's abbreviations and names stay as they are
            MenuAction(stringResource(R.string.menu_weather), "METAR, TAF, RASP",
                       onClick = onOpenWeather),
            menuAction(R.string.menu_flights, R.string.menu_flights_detail, onOpenFlights),
        )),
        MenuSection(stringResource(R.string.menu_settings), listOf(
            // whether XCSoar warns: warnings off is shown nowhere else
            menuAction(R.string.airspace,
                       if (airspaceAlerts.warnings) R.string.menu_airspace_on
                       else R.string.menu_airspace_off, onOpenAirspaceAlerts),
            menuAction(R.string.menu_safety, R.string.menu_safety_detail, onOpenSafety),
            menuAction(R.string.menu_sound, R.string.menu_sound_detail, onOpenSound),
            onOpenMapSettings?.let {
                menuAction(R.string.menu_map_units, R.string.menu_map_units_detail, it)
            } ?: menuAction(R.string.menu_units, R.string.menu_units_detail, onOpenUnits),
            MenuAction(stringResource(R.string.menu_tracking), "Cloud, SkyLines, LiveTrack24",
                       onClick = onOpenTracking),
            menuAction(R.string.menu_data_files, R.string.menu_data_files_detail,
                       onOpenDataFiles),
            menuAction(R.string.menu_profiles, R.string.menu_profiles_detail, onOpenProfiles),
            // what XCSoar sees, for when something looks wrong
            menuAction(R.string.menu_status, R.string.menu_status_detail, onOpenStatus),
        )),
    ),
    tileLayout = tileLayout,
    onTileLayout = onTileLayout,
    tileRows = tileRows,
    onTileRows = onTileRows,
    orientation = orientation,
    onOrientation = onOrientation,
    varioSound = varioSound,
    onVarioSound = onVarioSound,
    theme = theme,
    onTheme = onTheme,
    mapCoverage = mapCoverage,
)

@Composable
private fun menuAction(@StringRes label: Int, @StringRes detail: Int, onClick: () -> Unit) =
    MenuAction(stringResource(label), stringResource(detail), onClick = onClick)
