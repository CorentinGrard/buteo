// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.flight

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PointF
import android.location.Location
import android.util.Log
import android.view.Gravity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.OnCameraTrackingChangedListener
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.sources.GeoJsonSource
import org.xcsoar.mobile.R
import org.xcsoar.mobile.core.AirspaceState
import org.xcsoar.mobile.core.FlightState
import org.xcsoar.mobile.core.MapCoverage
import org.xcsoar.mobile.core.MapFeatures
import org.xcsoar.mobile.core.MapItemInfo
import org.xcsoar.mobile.core.MapOrientation
import org.xcsoar.mobile.core.OfflineMap
import org.xcsoar.mobile.core.TaskOutline
import org.xcsoar.mobile.core.WaypointInfo
import org.xcsoar.mobile.ui.Format
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

private const val TAG = "VectorMap"

/**
 * Frames a second while the map follows the glider.  Unlimited,
 * MapLibre drew 38 on the Redmi 5 in flight (its camera's animation)
 * and the app took 73-82 % of a core at 1x replay; 52-60 % at 10 frames,
 * 41-47 % at 5, against 24-28 % with XCSoar's map.  A pan or a pinch
 * lifts the limit.
 */
private const val FOLLOW_FPS = 5

/** Zooms of a first start: about 30 km across, and 3 km around a thermal. */
const val VECTOR_CRUISE_ZOOM = 10.0
const val VECTOR_CIRCLING_ZOOM = 12.5

/** The task on the map instead of the flight (D30). */
sealed interface TaskMapMode {
    /** Building the edited task: every waypoint shows, a tap adds the one under the finger. */
    data object Edit : TaskMapMode

    /** Moving the target of the active task's area [index]: a tap puts it there. */
    data class Target(val index: Int) : TaskMapMode
}

/**
 * The vector map's camera and what the flight screen's buttons do to
 * it (prototype, map review 2026-11-24).
 *
 * @param cruiseZoom the zooms the pilot last chose, cruising and
 *   circling (upstream's cruise and circling map scales)
 * @param onZooms keeps them when the pilot changes one
 */
@Stable
class VectorMapState(
    private var cruiseZoom: Double = VECTOR_CRUISE_ZOOM,
    private var circlingZoom: Double = VECTOR_CIRCLING_ZOOM,
    private val onZooms: (cruise: Double, circling: Double) -> Unit = { _, _ -> },
) {
    /** Following the glider; a pan stops it, the centre button resumes. */
    var follows by mutableStateOf(true)
        private set

    /** The task on the map, else null: the flight. */
    var taskMode by mutableStateOf<TaskMapMode?>(null)
        private set

    /** What the phone has of the map for this flight; null before the style. */
    var coverage by mutableStateOf<MapCoverage?>(null)
        internal set

    internal var map: MapLibreMap? = null
    internal var northUp = true
    /** Around what the task modes frame: the sides and the map's buttons, pixels. */
    internal var frameMargin = 0
    internal var frameBottom = 0

    /** The glider circles (the flight mode the tiles show, as upstream's display mode). */
    private var circling = false
    /** A zoom of its own while circling (upstream's CircleZoom). */
    internal var circlingZoomEnabled = true
    private val modeZoom get() = if (circling && circlingZoomEnabled) circlingZoom else cruiseZoom
    /** The pilot zoomed or moved the map: its zoom is the mode's once it stops. */
    private var pilotMoved = false
    /** The map's height and where in it the glider goes, pixels. */
    internal var height = 0
        set(value) {
            field = value
            applyPadding()
        }
    private var focusY = -1

    fun zoom(steps: Int) {
        val map = map ?: return
        pilotMoved = true
        val location = map.locationComponent
        val target = map.cameraPosition.zoom - steps
        if (follows && location.isLocationComponentActivated)
            // a camera move of the map's own would end the tracking
            location.zoomWhileTracking(target, 250)
        else
            map.animateCamera(CameraUpdateFactory.zoomTo(target), 250)
    }

    /** The centre button: the glider again, or in a task mode what it frames. */
    fun follow() {
        if (taskMode != null) {
            frameTask(lastFrame)
            return
        }
        follows = true
        applyCamera()
    }

    /** What the task mode framed last. */
    private var lastFrame: LatLngBounds? = null

    /** A pan or a pinch began. */
    internal fun gesture() {
        pilotMoved = true
    }

    /**
     * The camera stopped: a zoom the pilot chose while following is the
     * flight mode's from now on (upstream keeps a panned zoom apart).
     */
    internal fun idle() {
        val map = map ?: return
        if (!pilotMoved) return
        pilotMoved = false
        if (!follows || taskMode != null) return
        val zoom = map.cameraPosition.zoom
        if (circling && circlingZoomEnabled) circlingZoom = zoom else cruiseZoom = zoom
        onZooms(cruiseZoom, circlingZoom)
    }

    /** Circling or cruising: the zoom of that flight mode, while following. */
    internal fun setCircling(value: Boolean) {
        if (value == circling) return
        circling = value
        if (circlingZoomEnabled && follows && taskMode == null) zoomTo(modeZoom, 600)
    }

    private fun zoomTo(zoom: Double, millis: Long) {
        val map = map ?: return
        val location = map.locationComponent
        if (follows && location.isLocationComponentActivated)
            location.zoomWhileTracking(zoom, millis)
        else
            map.animateCamera(CameraUpdateFactory.zoomTo(zoom), millis.toInt())
    }

    /** The first fix: there, at the flight mode's zoom. */
    internal fun place(latitude: Double, longitude: Double) {
        moveTo(latitude, longitude, modeZoom)
        applyCamera()
    }

    /**
     * The task on the map: north up, not following, framed by
     * [frameTask]; null: the flight again, following at its zoom.
     */
    internal fun setTaskMode(mode: TaskMapMode?) {
        if (mode == taskMode) return
        taskMode = mode
        if (mode != null) {
            follows = false
            track()
        } else {
            follows = true
            applyCamera()
            zoomTo(modeZoom, 400)
        }
    }

    /** What a task mode shows, north up, in the part of the map the cards leave free. */
    internal fun frameTask(bounds: LatLngBounds?) {
        lastFrame = bounds
        val map = map ?: return
        val top = (2 * focusY - height).coerceAtLeast(0) + frameMargin
        val camera = if (bounds != null)
            map.getCameraForLatLngBounds(
                bounds, intArrayOf(frameMargin, top, frameMargin, frameBottom), 0.0, 0.0)
        else null
        val position = camera?.takeIf { !it.zoom.isNaN() }
            // nothing to frame yet: north up where the map is
            ?: CameraPosition.Builder(map.cameraPosition).bearing(0.0).build()
        ownMove { map.animateCamera(CameraUpdateFactory.newCameraPosition(position), 500) }
    }

    /** Our own camera moves (padding, the first fix) end tracking too. */
    private var moving = false

    internal fun dismissed() {
        if (!moving) follows = false
    }

    private inline fun ownMove(block: () -> Unit) {
        moving = true
        try {
            block()
        } finally {
            moving = false
        }
    }

    internal fun moveTo(latitude: Double, longitude: Double, zoom: Double) = ownMove {
        map?.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(latitude, longitude), zoom))
    }

    /**
     * The glider [y] pixels from the top: the middle of what the cards
     * leave free, as the core's map does.
     */
    fun setFocus(y: Int) {
        focusY = y
        applyPadding()
    }

    private fun applyPadding() {
        val map = map ?: return
        if (focusY < 0 || height <= 0) return
        val top = (2 * focusY - height).coerceAtLeast(0).toDouble()
        val bottom = (height - 2 * focusY).coerceAtLeast(0).toDouble()
        ownMove { map.moveCamera(CameraUpdateFactory.paddingTo(0.0, top, 0.0, bottom)) }
        track()
    }

    internal fun applyCamera() {
        applyPadding()
        track()
    }

    /** MapLibre's tracking as [follows] and the orientation want it. */
    private fun track() {
        val location = map?.locationComponent ?: return
        if (!location.isLocationComponentActivated) return
        location.cameraMode = when {
            !follows -> CameraMode.NONE
            northUp -> CameraMode.TRACKING_GPS_NORTH
            else -> CameraMode.TRACKING_GPS
        }
    }
}

/**
 * The map drawn by MapLibre (prototype): OpenStreetMap vector tiles
 * from the region files on the phone ([OfflineMap]), the relief shaded
 * from the core's terrain, with
 * the core's airspace, landables, task and trail (`xcs_map_features`)
 * drawn over them.  Pan and pinch are MapLibre's own; it turns only
 * as the orientation says (north up unless the pilot chose otherwise).
 *
 * @param regionDir where the region files are (XCSoarData/maps), read
 *   once
 * @param features one layer of the core's data as GeoJSON
 * @param terrain the core's terrain heights for a tile of the relief
 * @param landables the nearest landables, with arrival heights
 * @param live the latest snapshot, for the glider
 * @param onItems what the pilot held, for the map's card
 * @param circling the flight mode the tiles show: the circling zoom
 * @param circlingZoom a zoom of its own while circling (Map & units)
 * @param taskMode the task on the map instead of the flight
 * @param onTaskTap a tap in a task mode: where, and the waypoint under
 *   the finger while building the task
 */
@Composable
fun VectorMap(
    state: VectorMapState,
    regionDir: File?,
    features: suspend (MapFeatures) -> String?,
    terrain: suspend (zoom: Int, x: Int, y: Int, size: Int) -> ShortArray?,
    landables: suspend () -> List<WaypointInfo>,
    live: StateFlow<FlightState?>,
    orientation: MapOrientation?,
    onItems: (List<MapItemInfo>) -> Unit,
    modifier: Modifier = Modifier,
    circling: Boolean = false,
    circlingZoom: Boolean = true,
    taskMode: TaskMapMode? = null,
    onTaskTap: (latitude: Double, longitude: Double, waypointId: Int?) -> Unit = { _, _, _ -> },
) {
    val context = LocalContext.current
    val mapView = remember {
        MapLibre.getInstance(context)
        MapNetwork.install()
        MapView(context).apply { onCreate(null) }
    }
    MapNetwork.terrain = terrain
    val offline = remember(regionDir) {
        OfflineMap.open(regionDir) { file, e -> Log.w(TAG, "region map $file", e) }
    }
    DisposableEffect(offline) {
        MapNetwork.offline = offline
        onDispose {
            // the map made again for new regions may have set its own already
            if (MapNetwork.offline === offline) MapNetwork.offline = null
            offline?.close()
        }
    }
    var style by remember { mutableStateOf<Style?>(null) }
    val currentOnItems by rememberUpdatedState(onItems)
    val currentOnTaskTap by rememberUpdatedState(onTaskTap)
    val density = LocalDensity.current.density
    state.frameMargin = (24 * density).roundToInt()
    // the menu and zoom buttons along the map's bottom
    state.frameBottom = (96 * density).roundToInt()

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        var started = false
        var resumed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> { mapView.onStart(); started = true }
                Lifecycle.Event.ON_RESUME -> { mapView.onResume(); resumed = true }
                Lifecycle.Event.ON_PAUSE -> { mapView.onPause(); resumed = false }
                Lifecycle.Event.ON_STOP -> { mapView.onStop(); started = false }
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (resumed) mapView.onPause()
            if (started) mapView.onStop()
            mapView.onDestroy()
            state.map = null
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { map ->
            map.uiSettings.apply {
                isCompassEnabled = false
                isLogoEnabled = false
                isTiltGesturesEnabled = false
                // the orientation turns it, not the fingers
                isRotateGesturesEnabled = false
                attributionGravity = Gravity.TOP or Gravity.END
            }
            map.addOnMapLongClickListener { point ->
                // the task modes have taps, not holds
                if (state.taskMode == null)
                    currentOnItems(itemsAt(map, map.projection.toScreenLocation(point)))
                true
            }
            map.addOnMapClickListener { point ->
                val mode = state.taskMode ?: return@addOnMapClickListener false
                val waypoint = if (mode == TaskMapMode.Edit)
                    waypointAt(map, map.projection.toScreenLocation(point), 24 * density)
                else null
                currentOnTaskTap(point.latitude, point.longitude, waypoint)
                true
            }
            mapView.setMaximumFps(FOLLOW_FPS)
            map.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                    state.gesture()
                    mapView.setMaximumFps((mapView.display?.refreshRate ?: 60f).roundToInt())
                }
            }
            map.addOnCameraIdleListener {
                state.idle()
                mapView.setMaximumFps(FOLLOW_FPS)
            }
            val json = context.resources.openRawResource(R.raw.vector_map_style)
                .bufferedReader().use { it.readText() }
            Log.i(TAG, "region maps: ${offline?.regions?.map { it.file.name } ?: "none"}")
            map.setStyle(Style.Builder().fromJson(vectorMapStyle(json, offline))) { loaded ->
                activateGlider(context, map, loaded, state)
                state.map = map
                state.applyCamera()
                style = loaded
            }
        }
    }

    LaunchedEffect(orientation) {
        state.northUp = orientation == null || orientation == MapOrientation.NORTH_UP
        state.applyCamera()
    }

    LaunchedEffect(circling, circlingZoom) {
        state.circlingZoomEnabled = circlingZoom
        state.setCircling(circling)
    }

    val loaded = style
    if (loaded != null) {
        // the core's data: what changes with the data files, rarely
        LaunchedEffect(loaded) {
            while (true) {
                features(MapFeatures.AIRSPACE)?.let { loaded.source("airspace")?.setGeoJson(it) }
                features(MapFeatures.WAYPOINTS)?.let { loaded.source("waypoints")?.setGeoJson(it) }
                delay(5 * 60_000)
            }
        }
        // the task on the map: every waypoint while building it
        LaunchedEffect(loaded, taskMode) {
            state.setTaskMode(taskMode)
            val visibility = PropertyFactory.visibility(
                if (taskMode == TaskMapMode.Edit) Property.VISIBLE else Property.NONE)
            loaded.getLayer("waypoint-circle")?.setProperties(visibility)
            loaded.getLayer("waypoint-label")?.setProperties(visibility)
        }
        // what changes in flight
        LaunchedEffect(loaded) {
            var tick = 0
            var task: TaskOutline? = null
            // the task mode framed last, framed once on entering it
            var framed: TaskMapMode? = null
            val changes = StyleChanges()
            while (true) {
                features(MapFeatures.AIRSPACE_STATE)?.let { json ->
                    changes.apply("warnings", json) { showWarnings(loaded, json) }
                }
                val mode = state.taskMode
                if (mode == null) framed = null
                // the edited task while building it, else the active one
                val shown = if (mode == TaskMapMode.Edit) MapFeatures.TASK_EDITED else MapFeatures.TASK
                if (tick % 3 == 0 || mode != null) {
                    features(shown)?.let { json ->
                        changes.apply("task", json) {
                            loaded.source("task")?.setGeoJson(json)
                            if (shown == MapFeatures.TASK) task = try {
                                TaskOutline.parse(json)
                            } catch (e: Exception) {
                                Log.w(TAG, "task outline", e)
                                null
                            }
                        }
                        if (mode != null && mode != framed) {
                            framed = mode
                            state.frameTask(taskBounds(json, (mode as? TaskMapMode.Target)?.index))
                        }
                    }
                    features(MapFeatures.TRAIL)?.let { json ->
                        changes.apply("trail", json) { loaded.source("trail")?.setGeoJson(json) }
                    }
                }
                state.coverage = MapCoverage.of(offline, task, live.value?.position)
                if (tick % 5 == 0)
                    try {
                        showReach(loaded, landables(), changes)
                    } catch (e: Exception) {
                        Log.w(TAG, "landables", e)
                    }
                tick++
                delay(1000)
            }
        }
        LaunchedEffect(loaded) {
            var lastAltitude = Double.NaN
            var lastSequence = -1L
            var placed = false
            live.filterNotNull().collect { s ->
                if (s.sequence == lastSequence) return@collect
                lastSequence = s.sequence
                MapNetwork.inFlight = s.flying
                val position = s.position ?: return@collect
                val location = Location("xcsoar").apply {
                    latitude = position.latitude
                    longitude = position.longitude
                    s.track?.let { bearing = it.toFloat() }
                    s.groundSpeed?.let { speed = it.toFloat() }
                    time = System.currentTimeMillis()
                }
                val map = state.map
                val component = map?.locationComponent
                if (component?.isLocationComponentActivated == true)
                    component.forceLocationUpdate(location)
                if (!placed && map != null && state.taskMode == null) {
                    // the first fix: there, at the flight mode's zoom
                    placed = true
                    Log.i(TAG, "first position ${position.latitude} ${position.longitude}")
                    state.place(position.latitude, position.longitude)
                }
                val altitude = s.navAltitude ?: return@collect
                if (lastAltitude.isNaN() || abs(altitude - lastAltitude) > 100) {
                    lastAltitude = altitude
                    fadeFarAirspace(loaded, altitude)
                }
            }
        }
    }

    AndroidView(factory = { mapView },
                modifier = modifier.onSizeChanged { state.height = it.height })
}

private fun Style.source(id: String) = getSourceAs<GeoJsonSource>(id)

/**
 * A change of the style made only when it differs from the last one:
 * each filter, property or data set has MapLibre lay out the layer
 * again (for the warnings, all of the country's airspace), which kept
 * the Redmi 5 at 41 % of a core with nothing moving.
 */
private class StyleChanges {
    private val last = HashMap<String, String>()

    fun apply(key: String, value: String, change: () -> Unit) {
        if (last.put(key, value) != value) change()
    }
}

@SuppressLint("MissingPermission") // fed by the core, not Android's location
private fun activateGlider(context: Context, map: MapLibreMap, style: Style,
                           state: VectorMapState) {
    val options = LocationComponentOptions.builder(context)
        .foregroundDrawable(R.drawable.map_glider)
        .gpsDrawable(R.drawable.map_glider)
        .bearingDrawable(R.drawable.map_glider)
        .backgroundDrawable(R.drawable.map_none)
        .accuracyAlpha(0f)
        .elevation(0f)
        .build()
    val component = map.locationComponent
    component.activateLocationComponent(
        LocationComponentActivationOptions.builder(context, style)
            .useDefaultLocationEngine(false)
            .locationComponentOptions(options)
            .build())
    component.isLocationComponentEnabled = true
    component.renderMode = RenderMode.GPS
    // the glider glides between fixes; at the screen's 90 Hz that cost
    // the app 84 % of a core in a replay (XCSoar's map: 30 %)
    component.setMaxAnimationFps(FOLLOW_FPS)
    component.addOnCameraTrackingChangedListener(object : OnCameraTrackingChangedListener {
        override fun onCameraTrackingDismissed() = state.dismissed()
        override fun onCameraTrackingChanged(currentMode: Int) {}
    })
}

/** `[[id, "near"|"inside", acked], ...]` into the warning layers' filters. */
private fun showWarnings(style: Style, json: String) {
    val states = AirspaceState.parseList(json)
    val near = states.filter { !it.inside }.map { it.id }
    val inside = states.filter { it.inside }.map { it.id }
    fun filter(ids: List<Int>) =
        Expression.raw("""["in", ["get", "id"], ["literal", [${ids.joinToString(",")}]]]""")
    style.getLayerAs<FillLayer>("airspace-near")?.setFilter(filter(near))
    style.getLayerAs<LineLayer>("airspace-near-line")?.setFilter(filter(near))
    style.getLayerAs<FillLayer>("airspace-inside")?.setFilter(filter(inside))
    style.getLayerAs<LineLayer>("airspace-inside-line")?.setFilter(filter(inside))
}

/**
 * Airspace well below or above the glider drawn faintly: what it
 * cannot fly into soon.
 */
private fun fadeFarAirspace(style: Style, altitude: Double) {
    val below = (altitude - 300).toInt()
    val above = (altitude + 1500).toInt()
    val far = """["any", ["<", ["get", "top_m"], $below], [">", ["get", "base_m"], $above]]"""
    style.getLayer("airspace-line")?.setProperties(
        PropertyFactory.lineOpacity(Expression.raw("""["case", $far, 0.22, 0.9]""")))
    style.getLayer("airspace-fill")?.setProperties(
        PropertyFactory.fillOpacity(Expression.raw("""["case", $far, 0.0, 0.035]""")))
    style.getLayer("airspace-label")?.setProperties(
        PropertyFactory.textOpacity(Expression.raw("""["case", $far, 0.0, 1.0]""")))
}

/** The landables' colour (reachable green, not orange) and arrival heights. */
private fun showReach(style: Style, nearby: List<WaypointInfo>, changes: StyleChanges) {
    val reachable = nearby.filter { it.reachable == true }.map { it.id }
    val unreachable = nearby.filter { it.reachable == false }.map { it.id }
    fun among(ids: List<Int>) =
        """["in", ["get", "id"], ["literal", [${ids.joinToString(",")}]]]"""
    val color = """["case", ${among(reachable)}, "#0b7a5c", ${among(unreachable)}, "#c2410c", "#8a8f8c"]"""
    val arrivals = nearby.filter { it.arrival != null }
    val label = if (arrivals.isEmpty()) """["get", "label"]""" else buildString {
        append("""["concat", ["get", "label"], ["match", ["get", "id"]""")
        for (w in arrivals) {
            val v = Format.altitudeDifference(w.arrival!!.toDouble())
            // a number, a sign and a unit: nothing to escape but the newline
            append(""", ${w.id}, "\n${v.text} ${v.unit}"""")
        }
        append(""", ""]]""")
    }
    changes.apply("landable-color", color) {
        style.getLayer("landable-circle")?.setProperties(
            PropertyFactory.circleColor(Expression.raw(color)))
    }
    changes.apply("landable-label", label) {
        style.getLayer("landable-label")?.setProperties(
            PropertyFactory.textField(Expression.raw(label)))
    }
}

/**
 * The bounds of the task's features (GeoJSON from `xcs_map_features`),
 * only those of point [index] if given (an area and its target); null
 * without any.
 */
private fun taskBounds(json: String, index: Int?): LatLngBounds? {
    val points = mutableListOf<LatLng>()
    try {
        val features = JSONObject(json).getJSONArray("features")
        for (i in 0 until features.length()) {
            val f = features.getJSONObject(i)
            if (index != null && f.getJSONObject("properties").optInt("index", -1) != index)
                continue
            val geometry = f.getJSONObject("geometry")
            val coordinates = geometry.getJSONArray("coordinates")
            fun add(c: JSONArray) = points.add(LatLng(c.getDouble(1), c.getDouble(0)))
            if (geometry.getString("type") == "Point") add(coordinates)
            else for (j in 0 until coordinates.length()) add(coordinates.getJSONArray(j))
        }
    } catch (e: JSONException) {
        Log.w(TAG, "task bounds", e)
        return null
    }
    val first = points.firstOrNull() ?: return null
    // one point: about 10 km around it
    if (points.all { it == first }) {
        points += LatLng(first.latitude + 0.05, first.longitude + 0.07)
        points += LatLng(first.latitude - 0.05, first.longitude - 0.07)
    }
    return LatLngBounds.Builder().includes(points).build()
}

/**
 * The waypoint (any, or a landable) nearest to [point] within
 * [radius] pixels, while building the task; null without one.
 */
private fun waypointAt(map: MapLibreMap, point: PointF, radius: Float): Int? {
    val box = android.graphics.RectF(point.x - radius, point.y - radius,
                                     point.x + radius, point.y + radius)
    return map.queryRenderedFeatures(box, "waypoint-circle", "landable-circle",
                                     "waypoint-label", "landable-label")
        .mapNotNull { f ->
            val id = f.getNumberProperty("id")?.toInt() ?: return@mapNotNull null
            val location = (f.geometry() as? org.maplibre.geojson.Point) ?: return@mapNotNull null
            val at = map.projection.toScreenLocation(LatLng(location.latitude(), location.longitude()))
            id to ((at.x - point.x) * (at.x - point.x) + (at.y - point.y) * (at.y - point.y))
        }
        .minByOrNull { it.second }?.first
}

/** What is at [point]: airspace and landables, for the map's card. */
private fun itemsAt(map: MapLibreMap, point: PointF): List<MapItemInfo> {
    val items = mutableListOf<MapItemInfo>()
    val seen = mutableSetOf<String>()
    val box = android.graphics.RectF(point.x - 24, point.y - 24, point.x + 24, point.y + 24)
    for (f in map.queryRenderedFeatures(box, "landable-circle")) {
        val id = f.getNumberProperty("id")?.toInt() ?: continue
        if (!seen.add("w$id")) continue
        items += MapItemInfo(
            type = "waypoint", id = id, name = f.getStringProperty("name"),
            landable = true,
            elevation = f.getNumberProperty("elevation")?.toDouble(),
            frequency = f.getStringProperty("frequency"))
    }
    for (f in map.queryRenderedFeatures(point, "airspace-fill")) {
        val id = f.getNumberProperty("id")?.toInt() ?: continue
        if (!seen.add("a$id")) continue
        items += MapItemInfo(
            type = "airspace", name = f.getStringProperty("name"),
            `class` = f.getStringProperty("class"),
            top = f.getStringProperty("top"), base = f.getStringProperty("base"))
    }
    return items
}
