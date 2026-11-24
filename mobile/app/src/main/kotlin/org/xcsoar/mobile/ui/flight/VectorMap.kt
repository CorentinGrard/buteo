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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
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
import org.maplibre.android.style.sources.GeoJsonSource
import org.xcsoar.mobile.R
import org.xcsoar.mobile.core.AirspaceState
import org.xcsoar.mobile.core.FlightState
import org.xcsoar.mobile.core.MapFeatures
import org.xcsoar.mobile.core.MapItemInfo
import org.xcsoar.mobile.core.MapOrientation
import org.xcsoar.mobile.core.WaypointInfo
import org.xcsoar.mobile.ui.Format
import kotlin.math.abs

private const val TAG = "VectorMap"

/**
 * The vector map's camera and what the flight screen's buttons do to
 * it (prototype, map review 2026-11-24).
 */
@Stable
class VectorMapState {
    /** Following the glider; a pan stops it, the centre button resumes. */
    var follows by mutableStateOf(true)
        private set

    internal var map: MapLibreMap? = null
    internal var northUp = true
    /** The map's height and where in it the glider goes, pixels. */
    internal var height = 0
        set(value) {
            field = value
            applyPadding()
        }
    private var focusY = -1

    fun zoom(steps: Int) {
        map?.animateCamera(CameraUpdateFactory.zoomBy(-steps.toDouble()), 250)
    }

    fun follow() {
        follows = true
        applyCamera()
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
 * from OpenFreeMap and elevation tiles for the relief, both fetched and
 * cached as the map needs them, with the core's airspace, landables,
 * task and trail (`xcs_map_features`) drawn over them.  Pan, pinch and
 * rotate are MapLibre's own.
 *
 * @param features one layer of the core's data as GeoJSON
 * @param landables the nearest landables, with arrival heights
 * @param live the latest snapshot, for the glider
 * @param onItems what the pilot held, for the map's card
 */
@Composable
fun VectorMap(
    state: VectorMapState,
    features: suspend (MapFeatures) -> String?,
    landables: suspend () -> List<WaypointInfo>,
    live: StateFlow<FlightState?>,
    orientation: MapOrientation?,
    onItems: (List<MapItemInfo>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(null) }
    }
    var style by remember { mutableStateOf<Style?>(null) }
    val currentOnItems by rememberUpdatedState(onItems)

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
                attributionGravity = Gravity.TOP or Gravity.END
            }
            map.addOnMapLongClickListener { point ->
                currentOnItems(itemsAt(map, map.projection.toScreenLocation(point)))
                true
            }
            val json = context.resources.openRawResource(R.raw.vector_map_style)
                .bufferedReader().use { it.readText() }
            map.setStyle(Style.Builder().fromJson(json)) { loaded ->
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
        // what changes in flight
        LaunchedEffect(loaded) {
            var tick = 0
            while (true) {
                features(MapFeatures.AIRSPACE_STATE)?.let { showWarnings(loaded, it) }
                if (tick % 3 == 0) {
                    features(MapFeatures.TASK)?.let { loaded.source("task")?.setGeoJson(it) }
                    features(MapFeatures.TRAIL)?.let { loaded.source("trail")?.setGeoJson(it) }
                }
                if (tick % 5 == 0)
                    try {
                        showReach(loaded, landables())
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
                if (!placed && map != null) {
                    // the first fix: there, at about 30 km across
                    placed = true
                    Log.i(TAG, "first position ${position.latitude} ${position.longitude}")
                    state.moveTo(position.latitude, position.longitude, 10.0)
                    state.applyCamera()
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
    component.setMaxAnimationFps(15)
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
private fun showReach(style: Style, nearby: List<WaypointInfo>) {
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
    style.getLayer("landable-circle")?.setProperties(
        PropertyFactory.circleColor(Expression.raw(color)))
    style.getLayer("landable-label")?.setProperties(
        PropertyFactory.textField(Expression.raw(label)))
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
