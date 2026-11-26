// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.nio.ByteBuffer
import kotlinx.coroutines.CancellationException
import org.xcsoar.AppPermissionManager
import org.xcsoar.mobile.core.DataFile
import org.xcsoar.mobile.core.REPOSITORY_URI
import org.xcsoar.mobile.ui.analysis.LocalTerrain
import org.xcsoar.mobile.ui.analysis.TerrainLoader
import org.xcsoar.mobile.ui.data.DataFilesScreen
import org.xcsoar.mobile.ui.data.DownloadScreen
import org.xcsoar.mobile.ui.data.DownloadViewModel
import org.xcsoar.mobile.ui.data.DataFilesViewModel
import org.xcsoar.mobile.ui.data.PreflightScreen
import org.xcsoar.mobile.ui.data.PreflightViewModel
import org.xcsoar.mobile.ui.analysis.AnalysisPage
import org.xcsoar.mobile.ui.analysis.AnalysisScreen
import org.xcsoar.mobile.ui.crew.CrewScreen
import org.xcsoar.mobile.ui.crew.CrewViewModel
import org.xcsoar.mobile.ui.crew.PilotScreen
import org.xcsoar.mobile.ui.crew.PilotViewModel
import org.xcsoar.mobile.ui.crew.PlaneEditScreen
import org.xcsoar.mobile.ui.crew.PlaneEditViewModel
import org.xcsoar.mobile.ui.analysis.AnalysisViewModel
import org.xcsoar.mobile.ui.flight.FlightScreen
import org.xcsoar.mobile.ui.tiles.AirspaceDetailsScreen
import org.xcsoar.mobile.ui.tiles.AirspaceDetailsViewModel
import org.xcsoar.mobile.ui.tiles.AtcScreen
import org.xcsoar.mobile.ui.tiles.AtcViewModel
import org.xcsoar.mobile.ui.tiles.RadioScreen
import org.xcsoar.mobile.ui.tiles.RadioViewModel
import org.xcsoar.mobile.ui.tiles.TeamCodeScreen
import org.xcsoar.mobile.ui.tiles.TeamCodeViewModel
import org.xcsoar.mobile.ui.tiles.TilePickerScreen
import org.xcsoar.mobile.ui.tiles.TilePickerViewModel
import org.xcsoar.mobile.ui.map.MapSettingsScreen
import org.xcsoar.mobile.ui.waypoints.WaypointsScreen
import org.xcsoar.mobile.ui.waypoints.WaypointsViewModel
import org.xcsoar.mobile.ui.map.MapSettingsViewModel
import org.xcsoar.mobile.ui.flights.FlightLog
import org.xcsoar.mobile.ui.flights.FlightsScreen
import org.xcsoar.mobile.ui.flights.FlightsViewModel
import org.xcsoar.mobile.ui.flights.FlightLogScreen
import org.xcsoar.mobile.ui.profiles.ProfilesScreen
import org.xcsoar.mobile.ui.profiles.ProfilesViewModel
import org.xcsoar.mobile.ui.settings.AirspaceAlertsScreen
import org.xcsoar.mobile.ui.settings.AirspaceAlertsViewModel
import org.xcsoar.mobile.ui.settings.SafetyScreen
import org.xcsoar.mobile.ui.settings.SafetyViewModel
import org.xcsoar.mobile.ui.settings.WindScreen
import org.xcsoar.mobile.ui.status.StatusScreen
import org.xcsoar.mobile.ui.status.StatusViewModel
import org.xcsoar.mobile.ui.settings.WindViewModel
import org.xcsoar.mobile.ui.settings.SoundScreen
import org.xcsoar.mobile.ui.settings.NotamScreen
import org.xcsoar.mobile.ui.settings.NotamViewModel
import org.xcsoar.mobile.ui.settings.TrackingScreen
import org.xcsoar.mobile.ui.settings.TrackingViewModel
import org.xcsoar.mobile.ui.settings.VarioSoundViewModel
import org.xcsoar.mobile.ui.flight.Callouts
import org.xcsoar.mobile.core.Thermal
import org.xcsoar.mobile.ui.weather.WeatherScreen
import org.xcsoar.mobile.ui.weather.WeatherViewModel
import org.xcsoar.mobile.ui.setup.FlightSetupScreen
import org.xcsoar.mobile.ui.task.TaskFilesScreen
import org.xcsoar.mobile.ui.task.TaskEditPanel
import org.xcsoar.mobile.ui.task.TaskRulesScreen
import org.xcsoar.mobile.ui.task.TaskScreen
import org.xcsoar.mobile.ui.task.TaskViewModel
import org.xcsoar.mobile.ui.task.RoutePreviewPanel
import org.xcsoar.mobile.ui.task.TargetPanel
import org.xcsoar.mobile.ui.units.UnitsScreen
import org.xcsoar.mobile.ui.units.UnitsViewModel
import org.xcsoar.mobile.ui.summary.SummaryScreen
import org.xcsoar.mobile.ui.summary.SummaryViewModel
import org.xcsoar.mobile.ui.welcome.WelcomeScreen
import org.xcsoar.mobile.ui.welcome.WelcomeViewModel
import org.xcsoar.mobile.core.WaypointFilter
import org.xcsoar.mobile.ui.setup.FlightSetupViewModel
import org.xcsoar.mobile.ui.flight.FlightViewModel
import org.xcsoar.mobile.ui.flight.TaskMapMode
import org.xcsoar.mobile.ui.theme.ThemeChoice
import org.xcsoar.mobile.ui.theme.XcsTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private enum class Screen { FLIGHT, PREFLIGHT, DATA_FILES, DOWNLOAD, MAP_SETTINGS, WAYPOINTS, FLIGHT_SETUP, FLIGHTS,
                            TASK, TASK_FILES, TASK_ADD_POINT, TASK_ADD_START, TASK_RULES,
                            UNITS, ANALYSIS,
                            CREW, PLANE_EDIT, PILOT, TILE_PICKER,
                            AIRSPACE_ALERTS, SAFETY, WIND, STATUS, SOUND, TRACKING, WEATHER,
                            NOTAMS, FLIGHT_LOG, PROFILES, TEAM_CODE, TEAM_REFERENCE, RADIO,
                            ATC, ATC_REFERENCE, AIRSPACE_DETAILS, WELCOME, SUMMARY }

/**
 * The map's terrain for the analysis maps, from the native core; null
 * without one (the fake core draws no map).
 */
private fun MainActivity.terrainLoader(): TerrainLoader? {
    val core = (application as XcsoarApp).core ?: return null
    return { north, west, south, east, width, height ->
        try {
            core.terrainImage(north, west, south, east, width, height)?.let { rgba ->
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    .apply { copyPixelsFromBuffer(ByteBuffer.wrap(rgba)) }
                    .asImageBitmap()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}

/** How deep a page is: pages further in slide in from the right. */
private val Screen.depth: Int
    get() = when (this) {
        Screen.FLIGHT -> 0
        Screen.DOWNLOAD, Screen.PLANE_EDIT, Screen.TASK_FILES, Screen.TASK_ADD_POINT,
        Screen.TASK_ADD_START, Screen.TASK_RULES, Screen.TEAM_REFERENCE, Screen.ATC_REFERENCE,
        // open from the page that holds them (D25)
        Screen.PILOT, Screen.FLIGHT_LOG, Screen.NOTAMS, Screen.UNITS, Screen.WIND -> 2
        else -> 1
    }

private const val PAGE_MILLIS = 250

/**
 * Deeper pages slide in from the right over the one they leave; going
 * back, the page slides out to the right and uncovers the one below.
 */
private fun AnimatedContentTransitionScope<Screen>.pageTransition(): ContentTransform {
    val forward = targetState.depth > initialState.depth
    val spec = tween<IntOffset>(PAGE_MILLIS, easing = FastOutSlowInEasing)
    // opaque pages: no fade, so two pages never show through each other
    return if (forward)
        slideInHorizontally(spec) { it } togetherWith
            slideOutHorizontally(spec) { -it / 4 } using null
    else
        (slideInHorizontally(spec) { -it / 4 } togetherWith
            slideOutHorizontally(spec) { it } using null)
            .apply { targetContentZIndex = -1f }
}

/**
 * A page over the flight screen: it takes every touch, so none reaches
 * the map under it.
 */
@Composable
private fun Page(terrain: TerrainLoader?, content: @Composable () -> Unit) {
    // the analysis maps draw the map's terrain behind them
    CompositionLocalProvider(LocalTerrain provides terrain) {
        Box(Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope { while (true) awaitPointerEvent() }
            }) { content() }
    }
}

class MainActivity : ComponentActivity() {
    private val app get() = application as XcsoarApp

    private val alerts by lazy { Alerts(this) }

    /* one system permission dialog at a time, for AppPermissionManager */
    private var permissionResult: ((Boolean) -> Unit)? = null
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permissionResult?.invoke(granted)
            permissionResult = null
            // the location permission may just have been granted
            if (granted)
                FlightService.start(this)
        }

    /* the data file kind waiting for the system file picker */
    private var pickingKind: DataFile? = null
    private var onPicked: ((DataFile, String) -> Unit)? = null
    private val filePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val kind = pickingKind
            pickingKind = null
            if (uri != null && kind != null)
                onPicked?.invoke(kind, uri.toString())
        }

    private var onProfilePicked: ((Uri) -> Unit)? = null
    private val profilePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onProfilePicked?.invoke(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // in flight the screen must stay on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // the volume keys set the vario sound's volume (OpenSL plays on
        // the media stream), even while it is silent
        volumeControlStream = AudioManager.STREAM_MUSIC

        // the notification of FlightService (it runs without it, unseen)
        if (savedInstanceState == null)
            app.permissionManager.requestNotificationPermissionDirect()

        setContent {
            var theme by remember { mutableStateOf(app.theme) }
            val terrain = remember { terrainLoader() }
            var tileRows by remember { mutableStateOf(app.tileRows) }
            val dark = when (theme) {
                ThemeChoice.WHITE -> false
                ThemeChoice.DARK -> true
                ThemeChoice.SYSTEM -> isSystemInDarkTheme()
            }
            // the status bar icons follow the theme, not the phone's
            DisposableEffect(dark) {
                val bars = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                           else SystemBarStyle.light(android.graphics.Color.TRANSPARENT,
                                                     android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(bars, bars)
                onDispose {}
            }
            XcsTheme(dark) {
                val flightViewModel: FlightViewModel = viewModel(factory = viewModelFactory {
                    initializer {
                        FlightViewModel(
                            core = app.anyCore,
                            calloutSettings = { app.calloutSettings },
                        )
                    }
                })
                val dataViewModel: DataFilesViewModel = viewModel(factory = viewModelFactory {
                    initializer {
                        DataFilesViewModel(app.anyCore) { uri, kind ->
                            app.importDataFile(Uri.parse(uri), kind)
                        }
                    }
                })
                onPicked = dataViewModel::choose

                val mapViewModel: MapSettingsViewModel = viewModel(factory = viewModelFactory {
                    initializer { MapSettingsViewModel(app.anyCore) }
                })

                val downloadViewModel: DownloadViewModel = viewModel(factory = viewModelFactory {
                    initializer {
                        DownloadViewModel(
                            app.anyCore,
                            index = { app.downloader.index(REPOSITORY_URI).path },
                            download = app::downloadDataFile,
                        )
                    }
                })

                val waypointsViewModel: WaypointsViewModel = viewModel(factory = viewModelFactory {
                    initializer { WaypointsViewModel(app.anyCore) }
                })

                val setupViewModel: FlightSetupViewModel = viewModel(factory = viewModelFactory {
                    initializer { FlightSetupViewModel(app.anyCore) }
                })

                val flightsViewModel: FlightsViewModel = viewModel(factory = viewModelFactory {
                    initializer { FlightsViewModel(app::flightLogs, app.core, app.weGlideUploads) }
                })

                val taskViewModel: TaskViewModel = viewModel(factory = viewModelFactory {
                    initializer { TaskViewModel(app.anyCore) }
                })
                // a second waypoint list, to pick task points from all waypoints
                val pickViewModel: WaypointsViewModel = viewModel(
                    key = "pick", factory = viewModelFactory {
                        initializer { WaypointsViewModel(app.anyCore, WaypointFilter.ALL) }
                    })

                val unitsViewModel: UnitsViewModel = viewModel(factory = viewModelFactory {
                    initializer { UnitsViewModel(app.anyCore) }
                })

                val analysisViewModel: AnalysisViewModel = viewModel(factory = viewModelFactory {
                    initializer { AnalysisViewModel(app.anyCore) }
                })

                val crewViewModel: CrewViewModel = viewModel(factory = viewModelFactory {
                    initializer { CrewViewModel(app.anyCore) }
                })
                val planeEditViewModel: PlaneEditViewModel = viewModel(factory = viewModelFactory {
                    initializer { PlaneEditViewModel(app.anyCore) }
                })
                val pilotViewModel: PilotViewModel = viewModel(factory = viewModelFactory {
                    initializer { PilotViewModel(app.anyCore) }
                })
                val varioSoundViewModel: VarioSoundViewModel = viewModel(
                    factory = viewModelFactory { initializer { VarioSoundViewModel(app.anyCore) } })
                val trackingViewModel: TrackingViewModel = viewModel(
                    factory = viewModelFactory { initializer { TrackingViewModel(app.anyCore) } })
                val notamViewModel: NotamViewModel = viewModel(
                    factory = viewModelFactory { initializer { NotamViewModel(app.anyCore) } })
                val preflightViewModel: PreflightViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            PreflightViewModel(app.flightData::check,
                                               app.flightData::downloadLater,
                                               app.flightData::automaticNow,
                                               app.flightData.state)
                        }
                    })
                val flightDataState by app.flightData.state.collectAsState()
                val weatherViewModel: WeatherViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            WeatherViewModel(app.anyCore) { name ->
                                app.updateDataFile(DataFile.RASP, name)
                            }
                        }
                    })
                val safetyViewModel: SafetyViewModel = viewModel(
                    factory = viewModelFactory { initializer { SafetyViewModel(app.anyCore) } })
                val windViewModel: WindViewModel = viewModel(
                    factory = viewModelFactory { initializer { WindViewModel(app.anyCore) } })
                val teamCodeViewModel: TeamCodeViewModel = viewModel(
                    factory = viewModelFactory { initializer { TeamCodeViewModel(app.anyCore) } })
                val radioViewModel: RadioViewModel = viewModel(
                    factory = viewModelFactory { initializer { RadioViewModel(app.anyCore) } })
                val atcViewModel: AtcViewModel = viewModel(
                    factory = viewModelFactory { initializer { AtcViewModel(app.anyCore) } })
                val airspaceDetailsViewModel: AirspaceDetailsViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { AirspaceDetailsViewModel(app.anyCore) }
                    })
                val statusViewModel: StatusViewModel = viewModel(
                    factory = viewModelFactory { initializer { StatusViewModel(app.anyCore) } })
                val airspaceAlertsViewModel: AirspaceAlertsViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { AirspaceAlertsViewModel(app.anyCore) }
                    })
                val profilesViewModel: ProfilesViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            ProfilesViewModel(app.anyCore,
                                              importFile = { app.importProfile(Uri.parse(it)) },
                                              switch = ::switchProfile)
                        }
                    })
                onProfilePicked = { profilesViewModel.import(it.toString()) }
                val tilePickerViewModel: TilePickerViewModel = viewModel(
                    factory = viewModelFactory { initializer { TilePickerViewModel(app.anyCore) } })
                val summaryViewModel: SummaryViewModel = viewModel(factory = viewModelFactory {
                    initializer { SummaryViewModel(app.anyCore, app::flightLogs) }
                })
                val welcomeViewModel: WelcomeViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            WelcomeViewModel(
                                app.anyCore,
                                index = { app.downloader.index(REPOSITORY_URI).path },
                                download = app::downloadDataFile,
                                // the GPS fix, else the phone's last position
                                position = {
                                    app.anyCore.flightState.value?.position
                                        ?: withContext(Dispatchers.IO) { app.lastKnownPosition() }
                                },
                                regionHints = app::regionHints,
                            )
                        }
                    })

                LaunchedEffect(flightViewModel) {
                    flightViewModel.alerts.collect { alerts.play(it) }
                }
                LaunchedEffect(flightViewModel) {
                    // the engine takes seconds to say its first words
                    if (app.calloutSettings.any) app.voice
                    flightViewModel.callouts.collect { app.voice.speak(it) }
                }
                var callouts by remember { mutableStateOf(app.calloutSettings) }

                // the first start sets up units, glider and maps; later, once
                // per app start, the plane and crew of this flight (when
                // there is a plane to choose: "Not now" means not now)
                var screen by rememberSaveable {
                    mutableStateOf(when {
                        app.core == null -> Screen.FLIGHT
                        !app.setupDone -> Screen.WELCOME
                        app.crewChosen || !app.hasPlanes -> Screen.FLIGHT
                        else -> Screen.CREW
                    })
                }
                // the flight screen takes the whole screen, as XCSoar's: a
                // swipe from the edge shows the phone's bars for a few
                // seconds (Battery percent and Time local are tiles); pages
                // keep them
                val fullScreen = screen == Screen.FLIGHT
                LaunchedEffect(fullScreen) {
                    val bars = WindowCompat.getInsetsController(window, window.decorView)
                    bars.systemBarsBehavior =
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    if (fullScreen) bars.hide(WindowInsetsCompat.Type.systemBars())
                    else bars.show(WindowInsetsCompat.Type.systemBars())
                }
                /* the plane editor opens from the crew page and the first start */
                var planeEditBack by rememberSaveable { mutableStateOf(Screen.CREW) }
                /* the crew screen was opened from the menu (it has Back) */
                var crewFromMenu by rememberSaveable { mutableStateOf(false) }
                /* downloads open from data files and from the weather */
                var downloadBack by rememberSaveable { mutableStateOf(Screen.DATA_FILES) }
                /* the wind opens from flight setup, the wind chip and its tiles */
                var windBack by rememberSaveable { mutableStateOf(Screen.FLIGHT) }
                /* the units open from the map's page, or the menu without a map */
                var unitsBack by rememberSaveable { mutableStateOf(Screen.MAP_SETTINGS) }
                /* the flight menu; it stays open under the pages it opens,
                   so their Back comes back to it */
                var menuOpen by rememberSaveable { mutableStateOf(false) }
                // not saved: the map's mode does not outlive the activity
                var taskOnMap by remember { mutableStateOf<TaskMapMode?>(null) }
                fun showTaskOnMap(mode: TaskMapMode?, next: Screen) {
                    taskOnMap = mode
                    // the menu stays open under the pages it opens: not
                    // over the task on the map
                    if (mode != null) menuOpen = false
                    flightViewModel.hideMapItems()
                    screen = next
                }
                val crewDone = {
                    app.crewChosen = true
                    screen = Screen.FLIGHT
                }
                if ((screen == Screen.CREW && !crewFromMenu) || screen == Screen.WELCOME) {
                    // never hold up a flight: the app restarted in the air
                    val flying = flightViewModel.flightState.collectAsState().value?.flying
                    LaunchedEffect(flying) { if (flying == true) crewDone() }
                }
                // the end of a flight: its summary, unless another page is open
                LaunchedEffect(flightViewModel) {
                    flightViewModel.flightEnded.collect { end ->
                        if (screen == Screen.FLIGHT && summaryViewModel.open(end)) {
                            menuOpen = false
                            screen = Screen.SUMMARY
                        }
                    }
                }
                if (screen == Screen.SUMMARY) {
                    // a landing that was none (a bounce, a ground run): back to flying
                    val state = flightViewModel.flightState.collectAsState().value
                    LaunchedEffect(state?.flying) {
                        if (state?.flying == true && !state.replay) screen = Screen.FLIGHT
                    }
                }

                Box(Modifier
                    .fillMaxSize()
                    .then(if (screen != Screen.FLIGHT) Modifier.clearAndSetSemantics {}
                          else Modifier)) {
                    FlightScreen(
                        flightViewModel,
                        onOpenDataFiles = { screen = Screen.DATA_FILES },
                        onOpenWaypoints = { screen = Screen.WAYPOINTS },
                        onOpenFlightSetup = { screen = Screen.FLIGHT_SETUP },
                        onOpenFlights = { screen = Screen.FLIGHTS },
                        onOpenTask = { screen = Screen.TASK },
                        onOpenAnalysis = { screen = Screen.ANALYSIS },
                        onOpenCrew = {
                            crewFromMenu = true
                            screen = Screen.CREW
                        },
                        onOpenWeather = { screen = Screen.WEATHER },
                        onOpenPreflight = { screen = Screen.PREFLIGHT },
                        onOpenAirspaceAlerts = { screen = Screen.AIRSPACE_ALERTS },
                        onOpenUnits = {
                            unitsBack = Screen.FLIGHT
                            screen = Screen.UNITS
                        },
                        onOpenMapSettings = { screen = Screen.MAP_SETTINGS },
                        onOpenSafety = { screen = Screen.SAFETY },
                        onOpenWind = {
                            windBack = Screen.FLIGHT
                            screen = Screen.WIND
                        },
                        onOpenStatus = { screen = Screen.STATUS },
                        onOpenSound = { screen = Screen.SOUND },
                        onOpenTracking = { screen = Screen.TRACKING },
                        onOpenProfiles = { screen = Screen.PROFILES },
                        onTileAction = { action ->
                            when (action.action) {
                                "analysis" -> {
                                    AnalysisPage.fromCore(action.page)?.let(analysisViewModel::show)
                                    screen = Screen.ANALYSIS
                                }
                                // the nearest landables first, like upstream's lists
                                "alternates", "waypoints", "waypoint" -> screen = Screen.WAYPOINTS
                                // QNH, as in upstream's altitude setup panel
                                "altitude" -> screen = Screen.FLIGHT_SETUP
                                // upstream's MacCready panels: the value and Auto
                                "maccready" -> screen = Screen.FLIGHT_SETUP
                                // upstream's basic settings: ballast, bugs, QNH
                                "flight_setup" -> screen = Screen.FLIGHT_SETUP
                                // upstream's wind settings dialog
                                "wind" -> {
                                    windBack = Screen.FLIGHT
                                    screen = Screen.WIND
                                }
                                // upstream's status dialog, on the page it asks for
                                "status" -> {
                                    statusViewModel.show(action.page.ifEmpty { null })
                                    screen = Screen.STATUS
                                }
                                // upstream's team code dialog
                                "team_code" -> screen = Screen.TEAM_CODE
                                // upstream's radio frequency panels
                                "radio" -> screen = Screen.RADIO
                                // upstream's ATC reference and setup panels
                                "atc" -> screen = Screen.ATC
                                // upstream's details of the nearest airspace
                                "airspace" -> {
                                    airspaceDetailsViewModel.reset()
                                    screen = Screen.AIRSPACE_DETAILS
                                }
                                // "simulator": XCSoar's simulator mode only
                            }
                        },
                        onEditTile = { layout, tile ->
                            tilePickerViewModel.open(layout, tile)
                            screen = Screen.TILE_PICKER
                        },
                        tileRows = tileRows,
                        onTileRows = {
                            tileRows = it
                            app.tileRows = it
                        },
                        vectorMapDir = app.flightData.regionDir,
                        vectorMapVersion = flightDataState.regionsVersion,
                        vectorMapZooms = app.vectorMapZooms,
                        onVectorMapZooms = { cruise, circling -> app.vectorMapZooms = cruise to circling },
                        vectorMapPlace = app.vectorMapPlace,
                        onVectorMapPlace = { latitude, longitude ->
                            app.vectorMapPlace = latitude to longitude
                        },
                        theme = theme,
                        onTheme = {
                            theme = it
                            app.theme = it
                        },
                        menuOpen = menuOpen,
                        onMenuOpen = { menuOpen = it },
                        covered = screen != Screen.FLIGHT,
                        taskPanel = when (val mode = taskOnMap) {
                            null -> null
                            TaskMapMode.Edit -> ({
                                TaskEditPanel(
                                    taskViewModel,
                                    onList = { showTaskOnMap(null, Screen.TASK) },
                                    onClose = { showTaskOnMap(null, Screen.FLIGHT) })
                            })
                            is TaskMapMode.Target -> ({
                                TargetPanel(
                                    taskViewModel, mode.index,
                                    onShowTarget = {
                                        showTaskOnMap(TaskMapMode.Target(it), Screen.FLIGHT)
                                    },
                                    onClose = { showTaskOnMap(null, Screen.FLIGHT) })
                            })
                            TaskMapMode.Preview -> ({
                                RoutePreviewPanel(
                                    taskViewModel,
                                    onList = { showTaskOnMap(null, Screen.TASK) },
                                    onClose = { showTaskOnMap(null, Screen.FLIGHT) })
                            })
                        },
                        taskMapMode = taskOnMap,
                        onTaskMapTap = { latitude, longitude, waypointId ->
                            when (val mode = taskOnMap) {
                                TaskMapMode.Edit -> taskViewModel.addWaypoint(waypointId)
                                is TaskMapMode.Target ->
                                    taskViewModel.moveTarget(mode.index, latitude, longitude)
                                TaskMapMode.Preview, null -> {}
                            }
                        },
                    )
                }

                // pages slide in over the flight screen, which keeps
                // running under them (the map is not torn down)
                AnimatedContent(
                    targetState = screen,
                    transitionSpec = { pageTransition() },
                    modifier = Modifier.fillMaxSize(),
                    label = "page",
                ) { page ->
                    if (page != Screen.FLIGHT) Page(terrain) {
                        when (page) {
                            Screen.TILE_PICKER -> TilePickerScreen(tilePickerViewModel) {
                                flightViewModel.refreshTiles()
                                screen = Screen.FLIGHT
                            }
                            Screen.WELCOME -> WelcomeScreen(
                                welcomeViewModel,
                                onAddPlane = {
                                    planeEditViewModel.open(null)
                                    planeEditBack = Screen.WELCOME
                                    screen = Screen.PLANE_EDIT
                                },
                                onDone = {
                                    app.setupDone = true
                                    crewDone()
                                })
                            Screen.CREW -> CrewScreen(
                                crewViewModel,
                                onEditPlane = { plane ->
                                    planeEditViewModel.open(plane)
                                    planeEditBack = Screen.CREW
                                    screen = Screen.PLANE_EDIT
                                },
                                onEditPilot = { screen = Screen.PILOT },
                                onDone = crewDone,
                                onBack = if (crewFromMenu) crewDone else null,
                            )
                            Screen.PLANE_EDIT -> PlaneEditScreen(
                                planeEditViewModel,
                                onSaved = { path ->
                                    if (planeEditBack == Screen.WELCOME)
                                        welcomeViewModel.loadPlanes(select = path)
                                    else
                                        crewViewModel.load(select = path)
                                    screen = planeEditBack
                                },
                                onDeleted = {
                                    if (planeEditBack == Screen.WELCOME)
                                        welcomeViewModel.loadPlanes()
                                    else
                                        crewViewModel.load()
                                    screen = planeEditBack
                                },
                                onBack = { screen = planeEditBack },
                            )
                            Screen.PREFLIGHT -> PreflightScreen(
                                preflightViewModel, onBack = { screen = Screen.FLIGHT })
                            Screen.WEATHER -> WeatherScreen(
                                weatherViewModel,
                                onDownloadRasp = {
                                    downloadBack = Screen.WEATHER
                                    downloadViewModel.open(DataFile.RASP)
                                    screen = Screen.DOWNLOAD
                                },
                                onBack = { screen = Screen.FLIGHT })
                            Screen.NOTAMS -> NotamScreen(
                                notamViewModel, onBack = { screen = Screen.AIRSPACE_ALERTS })
                            Screen.TRACKING -> TrackingScreen(
                                trackingViewModel, onBack = { screen = Screen.FLIGHT })
                            Screen.SOUND -> SoundScreen(
                                varioSoundViewModel, callouts,
                                voiceAvailable = app.voice.available.collectAsState().value,
                                onCallouts = {
                                    callouts = it
                                    app.calloutSettings = it
                                },
                                onSampleCallout = {
                                    Callouts.climb(Thermal(2.1, 450.0, 300.0))
                                        ?.let(app.voice::speak)
                                },
                                onBack = { screen = Screen.FLIGHT })
                            Screen.SAFETY -> SafetyScreen(
                                safetyViewModel, onBack = { screen = Screen.FLIGHT })
                            Screen.STATUS -> StatusScreen(
                                statusViewModel, onBack = { screen = Screen.FLIGHT })
                            Screen.TEAM_CODE -> TeamCodeScreen(
                                teamCodeViewModel,
                                onPickReference = { screen = Screen.TEAM_REFERENCE },
                                onBack = { screen = Screen.FLIGHT })
                            Screen.TEAM_REFERENCE -> WaypointsScreen(
                                pickViewModel, onDone = { screen = Screen.TEAM_CODE },
                                title = stringResource(R.string.waypoints_reference),
                                pickLabel = stringResource(R.string.waypoints_set),
                                onPick = { teamCodeViewModel.setReference(it.id) })
                            Screen.RADIO -> RadioScreen(
                                radioViewModel, onBack = { screen = Screen.FLIGHT })
                            Screen.ATC -> AtcScreen(
                                atcViewModel, onRelocate = { screen = Screen.ATC_REFERENCE },
                                onBack = { screen = Screen.FLIGHT })
                            Screen.ATC_REFERENCE -> WaypointsScreen(
                                pickViewModel, onDone = { screen = Screen.ATC },
                                title = stringResource(R.string.waypoints_relocate),
                                pickLabel = stringResource(R.string.waypoints_set),
                                onPick = { atcViewModel.setReference(it.id) })
                            Screen.AIRSPACE_DETAILS -> AirspaceDetailsScreen(
                                airspaceDetailsViewModel, onBack = { screen = Screen.FLIGHT })
                            Screen.WIND -> WindScreen(
                                windViewModel, onBack = { screen = windBack })
                            Screen.AIRSPACE_ALERTS -> AirspaceAlertsScreen(
                                airspaceAlertsViewModel,
                                onOpenNotams = { screen = Screen.NOTAMS },
                                onBack = { screen = Screen.FLIGHT })
                            Screen.PILOT -> PilotScreen(
                                pilotViewModel, onBack = { screen = Screen.CREW })
                            Screen.DATA_FILES -> DataFilesScreen(
                                dataViewModel,
                                onChoose = { kind ->
                                    pickingKind = kind
                                    // file types are not reliable for .xcm/.txt: offer every file
                                    filePicker.launch(arrayOf("*/*"))
                                },
                                onDownload = { kind ->
                                    downloadBack = Screen.DATA_FILES
                                    downloadViewModel.open(kind)
                                    screen = Screen.DOWNLOAD
                                },
                                onBack = { screen = Screen.FLIGHT },
                            )
                            Screen.DOWNLOAD -> DownloadScreen(
                                downloadViewModel, onDone = { screen = downloadBack })
                            Screen.WAYPOINTS -> WaypointsScreen(
                                waypointsViewModel,
                                // flying somewhere new: back to the map itself
                                onDone = {
                                    menuOpen = false
                                    screen = Screen.FLIGHT
                                },
                                onBack = { screen = Screen.FLIGHT })
                            Screen.MAP_SETTINGS -> MapSettingsScreen(
                                mapViewModel,
                                onOpenUnits = {
                                    unitsBack = Screen.MAP_SETTINGS
                                    screen = Screen.UNITS
                                },
                                onBack = { screen = Screen.FLIGHT })
                            Screen.FLIGHT_SETUP -> FlightSetupScreen(
                                setupViewModel,
                                onOpenWind = {
                                    windBack = Screen.FLIGHT_SETUP
                                    screen = Screen.WIND
                                },
                                onBack = { screen = Screen.FLIGHT })
                            Screen.UNITS -> UnitsScreen(
                                unitsViewModel, onBack = { screen = unitsBack })
                            Screen.ANALYSIS -> AnalysisScreen(
                                analysisViewModel, onBack = { screen = Screen.FLIGHT })
                            Screen.TASK -> TaskScreen(
                                taskViewModel,
                                onAddPoint = { screen = Screen.TASK_ADD_POINT },
                                onAddStart = { screen = Screen.TASK_ADD_START },
                                onOpenRules = { screen = Screen.TASK_RULES },
                                onOpenFiles = { screen = Screen.TASK_FILES },
                                onOpenMap = { showTaskOnMap(TaskMapMode.Edit, Screen.FLIGHT) },
                                onOpenTargets = {
                                    showTaskOnMap(TaskMapMode.Target(it), Screen.FLIGHT)
                                },
                                onPreview = { showTaskOnMap(TaskMapMode.Preview, Screen.FLIGHT) },
                                onBack = { screen = Screen.FLIGHT })
                            Screen.TASK_RULES -> TaskRulesScreen(
                                taskViewModel, onBack = { screen = Screen.TASK })
                            Screen.TASK_ADD_START -> WaypointsScreen(
                                pickViewModel, onDone = { screen = Screen.TASK },
                                title = stringResource(R.string.task_add_start),
                                onPick = taskViewModel::addOptionalStart)
                            Screen.TASK_FILES -> TaskFilesScreen(
                                taskViewModel, onBack = { screen = Screen.TASK })
                            Screen.TASK_ADD_POINT -> WaypointsScreen(
                                pickViewModel, onDone = { screen = Screen.TASK },
                                title = stringResource(R.string.waypoints_add_point), onPick = taskViewModel::add)
                            Screen.SUMMARY -> SummaryScreen(
                                summaryViewModel, flightsViewModel, onShare = ::share,
                                onOpenUrl = ::openUrl, onDone = { screen = Screen.FLIGHT })
                            Screen.FLIGHTS -> FlightsScreen(
                                flightsViewModel,
                                onOpen = {
                                    flightsViewModel.open(it)
                                    screen = Screen.FLIGHT_LOG
                                },
                                onBack = { screen = Screen.FLIGHT })
                            Screen.FLIGHT_LOG -> FlightLogScreen(
                                flightsViewModel,
                                // replays and deletions are not for the air
                                onGround = flightViewModel.flightState
                                    .collectAsState().value?.flying == false,
                                // replaying: back to the map itself
                                onReplay = {
                                    flightViewModel.startReplay(it.path)
                                    menuOpen = false
                                    screen = Screen.FLIGHT
                                },
                                onShare = ::share, onOpenUrl = ::openUrl,
                                onBack = { screen = Screen.FLIGHTS })
                            Screen.PROFILES -> ProfilesScreen(
                                profilesViewModel,
                                flying = flightViewModel.flightState
                                    .collectAsState().value?.flying == true,
                                // .prf has no registered type
                                onImport = { profilePicker.launch(arrayOf("*/*")) },
                                onBack = { screen = Screen.FLIGHT })
                            Screen.FLIGHT -> {}
                        }
                    }
                }
            }
        }
    }

    /**
     * Fly with another profile: the core saves the one in use and stops,
     * and the app starts again with [path] (XCSoar reads its profile at
     * startup only).
     */
    private suspend fun switchProfile(path: String) {
        app.anyCore.stop()
        app.profile = path
        FlightService.stop(this)
        startActivity(Intent.makeRestartActivityTask(ComponentName(this, MainActivity::class.java)))
        Runtime.getRuntime().exit(0)
    }

    /** A web page, e.g. the flight on WeGlide, in the browser. */
    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            // no browser
        }
    }

    /** Hand an IGC file to another app (mail, WeGlide, XContest…). */
    private fun share(flight: FlightLog) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", File(flight.path))
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/octet-stream")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, flight.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // the chooser takes the grant from the clip data (for its preview)
        send.clipData = ClipData.newRawUri(flight.name, uri)
        startActivity(Intent.createChooser(send, flight.name))
    }

    override fun onStart() {
        super.onStart()
        // keeps flying when the screen goes off or another app is in front
        FlightService.start(this)
    }

    override fun onResume() {
        super.onResume()
        app.permissionManager.requester = AppPermissionManager.Requester { permission, onResult ->
            if (permissionResult != null) {
                onResult(false)   // another dialog is open
            } else {
                permissionResult = onResult
                permissionLauncher.launch(permission)
            }
        }
    }

    override fun onDestroy() {
        if (isFinishing)
            FlightService.stop(this)
        alerts.release()
        super.onDestroy()
    }

    override fun onPause() {
        app.permissionManager.requester = null
        super.onPause()
    }
}
