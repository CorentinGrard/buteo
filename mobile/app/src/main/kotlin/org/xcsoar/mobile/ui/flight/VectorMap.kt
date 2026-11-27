// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.flight

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.os.SystemClock
import android.util.Log
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
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.gestures.MoveGestureDetector
import org.maplibre.android.gestures.StandardScaleGestureDetector
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PaintPropertyValue
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.ImageSource
import org.xcsoar.mobile.R
import org.xcsoar.mobile.core.AirspaceState
import org.xcsoar.mobile.core.FlightState
import org.xcsoar.mobile.core.GeoPosition
import org.xcsoar.mobile.core.MapCoverage
import org.xcsoar.mobile.core.MapFeatures
import org.xcsoar.mobile.core.MapItemInfo
import org.xcsoar.mobile.core.MapOrientation
import org.xcsoar.mobile.core.OfflineMap
import org.xcsoar.mobile.core.TaskOutline
import org.xcsoar.mobile.core.TileGraphic
import org.xcsoar.mobile.core.WaypointInfo
import org.xcsoar.mobile.ui.Format
import java.io.File
import java.io.IOException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

private const val TAG = "VectorMap"

/**
 * Frames a second while the map follows the glider: one for every
 * [PIXELS_PER_FRAME] that the map or the glider moves, within these.  A
 * frame takes MapLibre several ms of the Redmi 5's CPU, so the screen's
 * rate all the time would cost most of a core; following in cruise the
 * map moves a pixel or two a second, track up while circling a hundred
 * or more.  Gestures and the pilot's own zooms draw at the screen's rate.
 */
private const val FOLLOW_MIN_FPS = 4
private const val FOLLOW_MAX_FPS = 30
/** Two pixels is a sixth of a millimetre on the Redmi 5: a step the eye does not see. */
private const val PIXELS_PER_FRAME = 2

/** The style's image of the thermal's core. */
private const val CORE_IMAGE = "thermal-core"

/** The climb around the thermal ([LiftField]): its image and layer, under the trail. */
private const val LIFT_SOURCE = "thermal-lift"
private const val LIFT_LAYER = "thermal-lift"
/** The lift's samples are fetched again this often while circling, ms. */
private const val LIFT_MILLIS = 1000L

/**
 * Circling, the camera looks at the thermal's core as long as the
 * glider stays this far from it, a share of the free part of the map.
 */
private const val FOCUS_OFFSET = 0.3

/** How long the zoom buttons, the centre button and the orientation animate. */
private const val MOVE_MILLIS = 300

/** A fix farther than this from the last one is jumped to (a replay's start). */
private const val JUMP_METRES = 5_000.0

/** The closest a task mode frames: a small area's streets. */
private const val MAX_FRAME_ZOOM = 13.0

/** Zooms of a first start: about 30 km across, and 3 km around a thermal. */
const val VECTOR_CRUISE_ZOOM = 10.0
const val VECTOR_CIRCLING_ZOOM = 12.5

/** The task on the map instead of the flight (D30). */
sealed interface TaskMapMode {
    /** Building the edited task: every waypoint shows, a tap adds the one under the finger. */
    data object Edit : TaskMapMode

    /** Moving the target of the active task's area [index]: a tap puts it there. */
    data class Target(val index: Int) : TaskMapMode

    /** A saved route before flying it (the edited task): framed, taps do nothing. */
    data object Preview : TaskMapMode
}

/**
 * The vector map's camera and the glider on it, and what the flight
 * screen's buttons do to them (map review 2026-11-24).
 *
 * Following, the camera glides from one fix to the next in MapLibre's
 * own animation, as long as the fixes are apart, so the map moves
 * evenly; the glider sits where the camera looks.  MapLibre's location
 * component did the same with animators on the UI thread and a symbol
 * for the glider, laid out again in every frame with every label of the
 * map: 22 ms of CPU a frame on the Redmi 5.
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
    /** where the glider was last: the map starts there before a fix */
    place: Pair<Double, Double>? = null,
    private val onPlace: (latitude: Double, longitude: Double) -> Unit = { _, _ -> },
) {
    private var lastPlace = place?.let { LatLng(it.first, it.second) }
    private var placeSaved = 0L

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
    internal var view: MapView? = null
    /** The style's glider layer (a location indicator). */
    internal var glider: Layer? = null
    /** The layer of the thermal's core mark (a location indicator too). */
    internal var coreLayer: Layer? = null
    /** The core mark's image while circling, else null. */
    private var coreImage: Bitmap? = null
    /** The climb around the thermal is drawn ([showLift]). */
    internal var liftShown by mutableStateOf(false)
        private set
    private var orientation: MapOrientation? = MapOrientation.NORTH_UP
    /** Around what the task modes frame: the sides and the map's buttons, pixels. */
    internal var frameMargin = 0
    internal var frameBottom = 0
    /** The glider's size, pixels: how far its wing tips move when it turns. */
    internal var gliderRadius = 0f

    /** The glider circles (the flight mode the tiles show, as upstream's display mode). */
    private var circling = false
    /** A zoom of its own while circling (upstream's CircleZoom). */
    internal var circlingZoomEnabled = true
    private val modeZoom get() = if (circling && circlingZoomEnabled) circlingZoom else cruiseZoom
    /** The zoom the camera keeps while following. */
    private var zoomGoal = cruiseZoom

    /** The map's height and where in it the glider goes, pixels. */
    internal var height = 0
        set(value) {
            field = value
            applyPadding()
        }
    private var focusY = -1

    /* the last fix, its track and when it came; the glider turns from
       [bearingFrom] to [bearingTo] (unwrapped) until the next one */
    private var fix: LatLng? = null
    /** Where the glider was drawn when [fix] came: it glides from there. */
    private var fixFrom: LatLng? = null
    /**
     * Where the lift is while circling (XCSoar's thermal locator), else
     * null: the camera looks there, and the core's mark sits there.
     */
    private var thermal: LatLng? = null
    /** Where the thermal's core was drawn when [fix] came. */
    private var thermalFrom: LatLng? = null
    private var fixTrack: Double? = null
    private var fixTime = 0L
    private var fixInterval = 0L
    private var bearingFrom = 0.0
    private var bearingTo = 0.0
    /** Where the map turns to: north, the track or the target. */
    private var upBearing = 0.0

    /** Fingers on the map (pan or pinch): the camera is theirs meanwhile. */
    private var gestures = 0
    private var followFps = FOLLOW_MIN_FPS
    private var boostedUntil = 0L
    private val restoreFps = Runnable { applyFps() }

    fun zoom(steps: Int) {
        val map = map ?: return
        if (following && fix != null) {
            zoomGoal = (zoomGoal - steps).coerceIn(map.minZoomLevel, map.maxZoomLevel)
            keepZoom()
            glide(MOVE_MILLIS)
        } else {
            // panned, or before a fix
            val zoom = map.cameraPosition.zoom - steps
            if (following) {
                zoomGoal = zoom.coerceIn(map.minZoomLevel, map.maxZoomLevel)
                keepZoom()
            }
            boost(MOVE_MILLIS)
            map.animateCamera(CameraUpdateFactory.zoomTo(zoom), MOVE_MILLIS)
        }
    }

    /** The centre button: the glider again, or in a task mode what it frames. */
    fun follow() {
        if (taskMode != null) {
            frameTask(lastFrame)
            return
        }
        follows = true
        glide(MOVE_MILLIS)
    }

    /** What the task mode framed last. */
    private var lastFrame: LatLngBounds? = null

    private val following get() = follows && taskMode == null

    /** The zoom the pilot chose while following is the flight mode's from now on. */
    private fun keepZoom() {
        if (circling && circlingZoomEnabled) circlingZoom = zoomGoal else cruiseZoom = zoomGoal
        onZooms(cruiseZoom, circlingZoom)
    }

    /** Circling or cruising: the zoom of that flight mode, while following. */
    internal fun setCircling(value: Boolean) {
        if (value == circling) return
        circling = value
        zoomGoal = modeZoom
        if (circlingZoomEnabled && following) glide(600)
    }

    /** North, track or target up (the menu's choice). */
    internal fun orient(value: MapOrientation?) {
        if (value == orientation) return
        orientation = value
        if (value == null || value == MapOrientation.NORTH_UP) upBearing = 0.0
        if (following) glide(MOVE_MILLIS)
    }

    /**
     * A snapshot: the glider at [latitude], [longitude] going [track]
     * (null: unknown); [target] is the next point's bearing, for target
     * up; [core] where the lift is while circling.  Nothing happens
     * while the fix stays the same.
     */
    internal fun onFix(latitude: Double, longitude: Double, track: Double?, target: Double?,
                       core: LatLng? = null) {
        val map = map ?: return
        val at = LatLng(latitude, longitude)
        val previous = fix
        if (at == previous && track == fixTrack && core == thermal) return
        val now = SystemClock.uptimeMillis()
        // the glider turns from where it is drawn now, the short way
        val start = gliderBearing(now)
        bearingFrom = start
        bearingTo = track?.let { start + shortest(start, it) } ?: start
        val interval = if (previous == null) 0L else (now - fixTime).coerceIn(100L, 2000L)
        fixFrom = previous?.let { gliderAt(now) }
        // the core glides too, from where it is drawn, unless it is new
        thermalFrom = if (core != null) thermalAt(now) else null
        thermal = core
        fix = at
        fixTrack = track
        fixTime = now
        fixInterval = interval
        // where to start next time, now and then
        if (now - placeSaved > 60_000) {
            placeSaved = now
            lastPlace = at
            onPlace(latitude, longitude)
        }
        upBearing = when (orientation) {
            null, MapOrientation.NORTH_UP -> 0.0
            MapOrientation.TARGET_UP -> target ?: track ?: upBearing
            else -> track ?: upBearing
        }

        if (!following || gestures > 0) {
            // the map stays: the glider moves on it
            showGlider(at, bearingTo)
            placeCore(core)
            return
        }
        if (previous == null || interval == 0L || previous.distanceTo(at) > JUMP_METRES) {
            // the first fix, or a replay's start: there at once
            Log.i(TAG, "glider at $latitude $longitude")
            fixInterval = 0
            fixFrom = null
            thermalFrom = null
            ownMove { map.moveCamera(CameraUpdateFactory.newCameraPosition(camera(map, at))) }
            showGlider(at, bearingTo)
            placeCore(core)
            return
        }
        // the camera glides to the fix (circling, the thermal) until the
        // next one comes
        val camera = camera(map, at)
        adaptFps(map, previous, at, camera.bearing, interval)
        ownMove {
            map.easeCamera(CameraUpdateFactory.newCameraPosition(camera), interval.toInt(), false)
        }
    }

    /** The camera on the glider at [at] ([focus]): the flight mode's zoom, which way is up. */
    private fun camera(map: MapLibreMap, at: LatLng) =
        CameraPosition.Builder(map.cameraPosition).target(focus(map, at)).zoom(zoomGoal)
            .bearing(upBearing).tilt(0.0).build()

    /**
     * Where the camera looks while following the glider at [at]: while
     * circling the thermal's core, as XCSoar's map
     * (GlueMapWindow::UpdateProjection()), so that the map and the
     * thermal stay and the glider circles on them; towards the glider
     * when the core is so far that it would leave the free part of the
     * map.
     */
    private fun focus(map: MapLibreMap, at: LatLng): LatLng {
        val core = thermal ?: return at
        val view = view ?: return at
        val a = map.projection.toScreenLocation(at)
        val b = map.projection.toScreenLocation(core)
        val apart = hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble())
        if (apart <= 0) return at
        val cards = (2 * focusY - height).coerceAtLeast(0)
        val most = minOf(view.width, height - cards) * FOCUS_OFFSET
        if (apart <= most) return core
        val t = most / apart
        return LatLng(at.latitude + (core.latitude - at.latitude) * t,
                      at.longitude + (core.longitude - at.longitude) * t)
    }

    /** Back to the glider in [millis], at the screen's frame rate. */
    private fun glide(millis: Int) {
        val map = map ?: return
        val at = fix ?: return
        boost(millis)
        ownMove {
            map.easeCamera(CameraUpdateFactory.newCameraPosition(camera(map, at)), millis, true)
        }
    }

    /**
     * After each frame: following, the glider on its way to the fix, as
     * the camera (one frame behind it, a pixel at most at these speeds).
     * Not the camera's target: zoomed out to the whole world, MapLibre
     * keeps the camera off the poles and the glider was drawn in Africa.
     */
    internal fun frameRendered() {
        if (map == null || fix == null) return
        val now = SystemClock.uptimeMillis()
        if (following && gestures == 0) {
            showGlider(gliderAt(now), gliderBearing(now))
            placeCore(thermalAt(now))
        }
    }

    /** The glider at [now], gliding evenly from [fixFrom] to [fix]. */
    private fun gliderAt(now: Long): LatLng = between(fixFrom, fix ?: return LatLng(), now)

    /** The thermal's core at [now], as the camera eases to it. */
    private fun thermalAt(now: Long): LatLng? = thermal?.let { between(thermalFrom, it, now) }

    /** [from] to [to] evenly from the last fix to the next. */
    private fun between(from: LatLng?, to: LatLng, now: Long): LatLng {
        if (from == null || fixInterval <= 0) return to
        val t = ((now - fixTime).toDouble() / fixInterval).coerceIn(0.0, 1.0)
        return LatLng(from.latitude + (to.latitude - from.latitude) * t,
                      from.longitude + (to.longitude - from.longitude) * t)
    }

    /* what the glider layer shows: set again only when it changes */
    private var shownAt: LatLng? = null
    private var shownBearing = Double.NaN

    private fun showGlider(at: LatLng, bearing: Double) {
        val layer = glider ?: return
        if (at == shownAt && bearing == shownBearing) return
        if (shownAt == null) layer.setProperties(PropertyFactory.visibility(Property.VISIBLE))
        shownAt = at
        shownBearing = bearing
        // the location indicator's own properties (MapLibre's factory for
        // them is not public)
        layer.setProperties(location(at), PaintPropertyValue("bearing", bearing))
    }

    private fun location(at: LatLng) =
        PaintPropertyValue("location", arrayOf(at.latitude, at.longitude, 0.0))

    /** The core's mark in [image], on the thermal's core while circling; null hides it. */
    fun showThermalCore(image: Bitmap?) {
        coreImage = image
        applyCore()
    }

    private fun applyCore() {
        val style = map?.style ?: return
        coreImage?.let { style.addImage(CORE_IMAGE, it) }
        placeCore(thermalAt(SystemClock.uptimeMillis()))
    }

    /**
     * The climb around the thermal on the map while [graphic] (the
     * thermal assistant, while circling) is there; null hides it.
     */
    fun showLift(graphic: TileGraphic?) {
        liftShown = graphic != null
    }

    /** Where the core's layer is; null while hidden. */
    private var coreAt: LatLng? = null

    /** The core's mark at [at] when there is one to show, else hidden. */
    private fun placeCore(at: LatLng?) {
        val layer = coreLayer ?: return
        val where = at?.takeIf { coreImage != null }
        if (where == coreAt) return
        when {
            where == null -> layer.setProperties(PropertyFactory.visibility(Property.NONE))
            coreAt == null ->
                layer.setProperties(PropertyFactory.visibility(Property.VISIBLE), location(where))
            else -> layer.setProperties(location(where))
        }
        coreAt = where
    }

    /** The glider's bearing at [now], turning evenly from one fix's track to the next. */
    private fun gliderBearing(now: Long): Double {
        if (fixInterval <= 0) return bearingTo
        val t = ((now - fixTime).toDouble() / fixInterval).coerceIn(0.0, 1.0)
        return bearingFrom + (bearingTo - bearingFrom) * t
    }

    /**
     * About one frame for each pixel that the map or the glider's wing
     * tips move until the next fix.
     */
    private fun adaptFps(map: MapLibreMap, from: LatLng, to: LatLng, bearing: Double,
                         interval: Long) {
        val projection = map.projection
        val a = projection.toScreenLocation(from)
        val b = projection.toScreenLocation(to)
        val moved = hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble())
        // turning track up, the map's corners move the most
        val turned = abs(shortest(map.cameraPosition.bearing, bearing)) * PI / 180 * height / 2
        // the glider's wing tips as it turns (circling, 15-20 degrees a second)
        val tips = abs(bearingTo - bearingFrom) * PI / 180 * gliderRadius
        val perSecond = (moved + turned + tips) * 1000 / interval
        followFps = (perSecond / PIXELS_PER_FRAME).roundToInt()
            .coerceIn(FOLLOW_MIN_FPS, FOLLOW_MAX_FPS)
        applyFps()
    }

    /** The screen's frame rate for [millis] (an animation the pilot asked for). */
    private fun boost(millis: Int) {
        val view = view ?: return
        boostedUntil = SystemClock.uptimeMillis() + millis + 100
        view.removeCallbacks(restoreFps)
        view.setMaximumFps(screenFps(view))
        view.postDelayed(restoreFps, millis + 100L)
    }

    private fun applyFps() {
        val view = view ?: return
        if (gestures > 0 || SystemClock.uptimeMillis() < boostedUntil) return
        view.setMaximumFps(followFps)
    }

    private fun screenFps(view: MapView) = (view.display?.refreshRate ?: 60f).roundToInt()

    /** A pan or a pinch began: the map draws at the screen's rate meanwhile. */
    internal fun gestureBegan(pan: Boolean) {
        gestures++
        view?.let { it.removeCallbacks(restoreFps); it.setMaximumFps(screenFps(it)) }
        // a pan with one finger leaves the glider; a pinch zooms around it
        if (pan && taskMode == null) follows = false
    }

    internal fun gestureEnded(zoomed: Boolean) {
        gestures = (gestures - 1).coerceAtLeast(0)
        if (gestures > 0) return
        val map = map ?: return
        if (zoomed && following) {
            zoomGoal = map.cameraPosition.zoom
            keepZoom()
        }
        // the fling ends at the screen's rate too
        boost(500)
        if (following) glide(MOVE_MILLIS)
    }

    /** Where the last map view looked when it went. */
    private var lastCamera: CameraPosition? = null

    /**
     * The map view goes (before its onDestroy): a new one comes with
     * new region files, or when the phone turns to landscape.
     */
    internal fun detached() {
        lastCamera = map?.cameraPosition
        view?.removeCallbacks(restoreFps)
        map = null
        view = null
        glider = null
        coreLayer = null
        coreAt = null
        shownAt = null
    }

    /**
     * The style is loaded: the camera at once where the flight screen
     * wants it (MapLibre refuses an ease of no time): the glider while
     * following, else where the last map view looked; the first time
     * where the glider was last, else over [regions] (the middle of the
     * map files).  A task mode frames its own ([applyPadding]).
     */
    internal fun attached(regions: LatLng?) {
        applyPadding()
        applyCore()
        val map = map ?: return
        if (taskMode != null) return
        val at = fix
        val last = lastCamera
        val camera = when {
            at != null && following -> camera(map, at)
            last != null -> CameraPosition.Builder(map.cameraPosition).target(last.target)
                .zoom(last.zoom).bearing(last.bearing).build()
            else -> (lastPlace ?: regions)?.let {
                CameraPosition.Builder(map.cameraPosition).target(it).zoom(zoomGoal).build()
            }
        } ?: return
        ownMove { map.moveCamera(CameraUpdateFactory.newCameraPosition(camera)) }
    }

    /**
     * The task on the map: north up, not following, framed by
     * [frameTask]; null: the flight again, following at its zoom.
     */
    internal fun setTaskMode(mode: TaskMapMode?) {
        if (mode == taskMode) return
        taskMode = mode
        // the centre button frames the task again meanwhile
        follows = mode == null
        if (mode == null) {
            zoomGoal = modeZoom
            applyPadding()
            glide(400)
        }
    }

    /** What a task mode shows, north up, in the part of the map the cards leave free. */
    internal fun frameTask(bounds: LatLngBounds?) {
        lastFrame = bounds
        val map = map ?: return
        if (focusY < 0 || height <= 0) return
        // the margins shrink with the free part: the target's panel is tall
        val cards = (2 * focusY - height).coerceAtLeast(0)
        val free = height - cards
        val margin = frameMargin.coerceAtMost(free / 8)
        val bottom = frameBottom.coerceAtMost(free / 3)
        val camera = if (bounds != null)
            map.getCameraForLatLngBounds(
                bounds, intArrayOf(margin, cards + margin, margin, bottom), 0.0, 0.0)
        else null
        val position = camera?.takeIf { !it.zoom.isNaN() }
            // a small area: not closer than the streets
            ?.let { CameraPosition.Builder(it).zoom(it.zoom.coerceAtMost(MAX_FRAME_ZOOM)).build() }
            // nothing to frame yet: north up where the map is
            ?: CameraPosition.Builder(map.cameraPosition).bearing(0.0).build()
        boost(500)
        ownMove { map.animateCamera(CameraUpdateFactory.newCameraPosition(position), 500) }
    }

    /** Our own camera moves are not the pilot's. */
    private var moving = false

    private inline fun ownMove(block: () -> Unit) {
        moving = true
        try {
            block()
        } finally {
            moving = false
        }
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
        // a task mode frames its own (the panel's height changed)
        if (taskMode != null) {
            frameTask(lastFrame)
            return
        }
        val top = (2 * focusY - height).coerceAtLeast(0).toDouble()
        val bottom = (height - 2 * focusY).coerceAtLeast(0).toDouble()
        ownMove { map.moveCamera(CameraUpdateFactory.paddingTo(0.0, top, 0.0, bottom)) }
    }
}

/** From [from] to [to] degrees the short way: -180 to 180. */
private fun shortest(from: Double, to: Double): Double {
    var d = (to - from) % 360
    if (d > 180) d -= 360
    if (d < -180) d += 360
    return d
}

/**
 * The map drawn by MapLibre: OpenStreetMap vector tiles from the region
 * files on the phone ([OfflineMap]), the relief shaded from the core's
 * terrain, with the core's airspace, landables, task and trail
 * (`xcs_map_features`) drawn over them.  Pan and pinch are MapLibre's
 * own; it turns only as the orientation says (north up unless the pilot
 * chose otherwise).
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
 * @param relief the shaded relief (Map & units)
 * @param taskMode the task on the map instead of the flight
 * @param onTaskTap a tap in a task mode: where, and the waypoint under
 *   the finger while building the task
 * @param onWaypoint a tap on a waypoint out of the task modes: its page
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
    relief: Boolean = true,
    taskMode: TaskMapMode? = null,
    onTaskTap: (latitude: Double, longitude: Double, waypointId: Int?) -> Unit = { _, _, _ -> },
    onWaypoint: (waypointId: Int) -> Unit = {},
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
    val currentOnWaypoint by rememberUpdatedState(onWaypoint)
    val density = LocalDensity.current.density
    state.frameMargin = (24 * density).roundToInt()
    // the menu and zoom buttons along the map's bottom
    state.frameBottom = (96 * density).roundToInt()
    state.gliderRadius = 26 * density

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
            // while the view still tells where it looks
            state.detached()
            lifecycle.removeObserver(observer)
            if (resumed) mapView.onPause()
            if (started) mapView.onStop()
            mapView.onDestroy()
        }
    }

    MapEffect(mapView) {
        mapView.getMapAsync { map ->
            map.uiSettings.apply {
                isCompassEnabled = false
                isLogoEnabled = false
                isTiltGesturesEnabled = false
                // the orientation turns it, not the fingers
                isRotateGesturesEnabled = false
                // zooms are the buttons' and the pinch's, as on XCSoar's map
                isDoubleTapGesturesEnabled = false
                isQuickZoomGesturesEnabled = false
                // the map data's credit is in the menu, under the map's
                // coverage (the pilot: no "i" on the map)
                isAttributionEnabled = false
            }
            map.addOnMapLongClickListener { point ->
                // the task modes have taps, not holds
                if (state.taskMode == null)
                    currentOnItems(itemsAt(map, map.projection.toScreenLocation(point)))
                true
            }
            map.addOnMapClickListener { point ->
                val mode = state.taskMode
                if (mode == null) {
                    // a field (or any waypoint) under the finger: its page
                    val waypoint = waypointAt(map, map.projection.toScreenLocation(point),
                                              24 * density) ?: return@addOnMapClickListener false
                    currentOnWaypoint(waypoint)
                    return@addOnMapClickListener true
                }
                if (mode == TaskMapMode.Preview) return@addOnMapClickListener true
                val waypoint = if (mode == TaskMapMode.Edit)
                    waypointAt(map, map.projection.toScreenLocation(point), 24 * density)
                else null
                currentOnTaskTap(point.latitude, point.longitude, waypoint)
                true
            }
            map.addOnMoveListener(object : MapLibreMap.OnMoveListener {
                override fun onMoveBegin(detector: MoveGestureDetector) =
                    state.gestureBegan(pan = detector.pointersCount == 1)
                override fun onMove(detector: MoveGestureDetector) {}
                override fun onMoveEnd(detector: MoveGestureDetector) =
                    state.gestureEnded(zoomed = false)
            })
            map.addOnScaleListener(object : MapLibreMap.OnScaleListener {
                override fun onScaleBegin(detector: StandardScaleGestureDetector) =
                    state.gestureBegan(pan = false)
                override fun onScale(detector: StandardScaleGestureDetector) {}
                override fun onScaleEnd(detector: StandardScaleGestureDetector) =
                    state.gestureEnded(zoomed = true)
            })
            // the glider where the camera looks, after each frame (a style
            // change in a camera callback would run MapLibre's update again
            // from within it)
            mapView.addOnDidFinishRenderingFrameListener { _, _, _ -> state.frameRendered() }
            mapView.setMaximumFps(FOLLOW_MIN_FPS)
            if (Log.isLoggable(TAG, Log.DEBUG)) logFrameStats(mapView)
            val json = context.resources.openRawResource(R.raw.vector_map_style)
                .bufferedReader().use { it.readText() }
            Log.i(TAG, "region maps: ${offline?.regions?.map { it.file.name } ?: "none"}")
            map.setStyle(Style.Builder().fromJson(vectorMapStyle(json, offline))) { loaded ->
                ContextCompat.getDrawable(context, R.drawable.map_glider)?.let {
                    loaded.addImage("glider", it.toBitmap())
                }
                state.glider = loaded.getLayer("glider")
                state.coreLayer = loaded.getLayer("thermal-core")
                state.view = mapView
                state.map = map
                state.attached(offline?.bounds?.let { b ->
                    LatLng((b[1] + b[3]) / 2, (b[0] + b[2]) / 2)
                })
                style = loaded
            }
        }
    }

    MapEffect(orientation) {
        state.orient(orientation)
    }

    MapEffect(circling, circlingZoom) {
        state.circlingZoomEnabled = circlingZoom
        state.setCircling(circling)
    }

    val loaded = style
    if (loaded != null) {
        val airspace = remember(loaded) { AirspaceLook() }
        // the core's data: what changes with the data files, rarely
        var landablesAt by remember { mutableStateOf<Map<Int, Landable>>(emptyMap()) }
        MapEffect(loaded) {
            while (true) {
                features(MapFeatures.AIRSPACE)?.let { loaded.source("airspace")?.setGeoJson(it) }
                features(MapFeatures.WAYPOINTS)?.let {
                    loaded.source("waypoints")?.setGeoJson(it)
                    landablesAt = withContext(Dispatchers.Default) { parseLandables(it) }
                }
                delay(5 * 60_000)
            }
        }
        MapEffect(loaded, relief) {
            loaded.getLayer("hillshade")?.setProperties(
                PropertyFactory.visibility(if (relief) Property.VISIBLE else Property.NONE))
        }
        // circling with the thermal assistant on: the climb around the
        // thermal, from its samples, as they change
        val liftShown = state.liftShown
        MapEffect(loaded, liftShown) {
            if (!liftShown) {
                showLiftField(loaded, null)
                return@MapEffect
            }
            var last: String? = null
            while (true) {
                val json = features(MapFeatures.LIFT)
                if (json != null && json != last) {
                    last = json
                    val field = withContext(Dispatchers.Default) {
                        liftField(parseLiftSamples(json))?.let { it to it.toBitmap() }
                    }
                    showLiftField(loaded, field)
                }
                delay(LIFT_MILLIS)
            }
        }
        // the task on the map: every waypoint while building it
        MapEffect(loaded, taskMode) {
            state.setTaskMode(taskMode)
            val visibility = PropertyFactory.visibility(
                if (taskMode == TaskMapMode.Edit) Property.VISIBLE else Property.NONE)
            loaded.getLayer("waypoint-circle")?.setProperties(visibility)
            loaded.getLayer("waypoint-label")?.setProperties(visibility)
        }
        // what changes in flight, each only when it changed: a new data
        // set or filter has MapLibre lay its layer out again
        MapEffect(loaded) {
            var tick = 0
            var task: TaskOutline? = null
            // the task mode framed last, framed once on entering it
            var framed: TaskMapMode? = null
            var lastShown: MapFeatures? = null
            val changes = StyleChanges()
            while (true) {
                features(MapFeatures.AIRSPACE_STATE)?.let { json ->
                    changes.apply("warnings", json) { airspace.warnings(loaded, json) }
                }
                val mode = state.taskMode
                if (mode == null) framed = null
                // the edited task while building or previewing it, else the active one
                val shown = if (mode == TaskMapMode.Edit || mode == TaskMapMode.Preview)
                    MapFeatures.TASK_EDITED else MapFeatures.TASK
                // back from the edited one: the active one at once
                if (tick % 3 == 0 || mode != null || shown != lastShown) {
                    lastShown = shown
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
                }
                // the whole trail now and then: its last two minutes come
                // with every fix (below)
                if (tick % 10 == 0)
                    features(MapFeatures.TRAIL)?.let { json ->
                        changes.apply("trail", json) { loaded.source("trail")?.setGeoJson(json) }
                    }
                // reads the region files' directories: not on the UI
                // thread, and a damaged file only leaves the last answer
                val outline = task
                val glider = live.value?.position
                try {
                    state.coverage = withContext(Dispatchers.IO) {
                        MapCoverage.of(offline, outline, glider)
                    }
                } catch (e: IOException) {
                    Log.w(TAG, "map coverage", e)
                }
                // the earlier thermals, at the glider's altitude: they move
                // as it climbs or sinks, and come at the end of each climb
                if (tick % 3 == 0)
                    features(MapFeatures.THERMALS)?.let { json ->
                        // twenty points at most: labelled here
                        changes.apply("thermals", json) {
                            loaded.source("thermals")?.setGeoJson(thermalsJson(json))
                        }
                    }
                // the glide range: the core solves it every few seconds
                if (tick % 3 == 0)
                    features(MapFeatures.REACH)?.let { json ->
                        changes.apply("glide-range", json) {
                            loaded.source("glide-range")?.setGeoJson(json)
                        }
                    }
                if (tick % 5 == 0 && landablesAt.isNotEmpty())
                    try {
                        val nearby = landables()
                        val at = landablesAt
                        val reach = withContext(Dispatchers.Default) { reachJson(nearby, at) }
                        changes.apply("reach", reach) { loaded.source("reach")?.setGeoJson(reach) }
                    } catch (e: Exception) {
                        Log.w(TAG, "landables", e)
                    }
                tick++
                delay(1000)
            }
        }
        MapEffect(loaded) {
            var lastSequence = -1L
            var lastPosition: GeoPosition? = null
            var recentTrail: String? = null
            var wasFlying: Boolean? = null
            var wasCircling: Boolean? = null
            live.filterNotNull().collect { s ->
                if (s.sequence == lastSequence) return@collect
                lastSequence = s.sequence
                MapNetwork.inFlight = s.flying
                // in and out of a flight or a climb, the trail at once: a
                // replay's goes when it stops (also without a fix), and the
                // trail starts or stops drifting with the wind (the whole
                // of it comes with every fix then, the core says which)
                if (s.flying != wasFlying || s.circling != wasCircling) {
                    wasFlying = s.flying
                    wasCircling = s.circling
                    features(MapFeatures.TRAIL)?.let { loaded.source("trail")?.setGeoJson(it) }
                    features(MapFeatures.TRAIL_RECENT)?.let {
                        recentTrail = it
                        loaded.source("trail-recent")?.setGeoJson(it)
                    }
                }
                val position = s.position ?: return@collect
                state.onFix(position.latitude, position.longitude, s.track, s.next?.bearing,
                            s.thermal?.let { LatLng(it.latitude, it.longitude) })
                s.navAltitude?.let { airspace.altitude(loaded, it) }
                // the trail's end with each fix, where the glider is going
                // (it glides there until the next one): the trail grows
                // behind it instead of in steps of seconds
                if (s.flying && position != lastPosition) {
                    lastPosition = position
                    features(MapFeatures.TRAIL_RECENT)?.takeIf { it != recentTrail }?.let {
                        recentTrail = it
                        loaded.source("trail-recent")?.setGeoJson(it)
                    }
                }

            }
        }
    }

    AndroidView(factory = { mapView },
                modifier = modifier.onSizeChanged { state.height = it.height })
}

/** MapLibre's own figures, every 50 frames (`setprop log.tag.VectorMap DEBUG`). */
private fun logFrameStats(mapView: MapView) {
    var frames = 0
    var encoding = 0.0
    var calls = 0
    mapView.addOnDidFinishRenderingFrameListener(
        MapView.OnDidFinishRenderingFrameWithStatsListener { _, stats ->
            frames++
            encoding += stats.encodingTime
            calls += stats.numDrawCalls
            if (frames == 50) {
                Log.d(TAG, "a frame: %.1f ms of CPU, %d draw calls".format(
                    encoding * 1000 / frames, calls / frames))
                frames = 0
                encoding = 0.0
                calls = 0
            }
        })
}

/**
 * A LaunchedEffect on the UI thread, where MapLibre takes its calls.
 * The app's effects run there anyway; Compose's test rule runs them on
 * a test dispatcher that goes on wherever a withContext() or the core
 * resumed it (an IO thread), and MapLibre threw in the end-to-end tests.
 */
@Composable
private fun MapEffect(vararg keys: Any?, block: suspend CoroutineScope.() -> Unit) =
    LaunchedEffect(*keys) { withContext(Dispatchers.Main.immediate, block) }

private fun Style.source(id: String) = getSourceAs<GeoJsonSource>(id)

/**
 * The climb around the thermal ([LiftField]) as an image over its
 * place, under the trail; null hides it.  Made the first time.
 */
private fun showLiftField(style: Style, field: Pair<LiftField, Bitmap>?) {
    val layer = style.getLayer(LIFT_LAYER)
    if (field == null) {
        layer?.setProperties(PropertyFactory.visibility(Property.NONE))
        return
    }
    val (f, image) = field
    val quad = LatLngQuad(LatLng(f.north, f.west), LatLng(f.north, f.east),
                          LatLng(f.south, f.east), LatLng(f.south, f.west))
    val source = style.getSourceAs<ImageSource>(LIFT_SOURCE)
    if (source == null) {
        style.addSource(ImageSource(LIFT_SOURCE, quad, image))
        // no fade: a new image every second would draw for 300 ms
        style.addLayerBelow(RasterLayer(LIFT_LAYER, LIFT_SOURCE).withProperties(
            PropertyFactory.rasterFadeDuration(0f),
            PropertyFactory.rasterResampling(Property.RASTER_RESAMPLING_LINEAR)), "trail")
        return
    }
    source.setCoordinates(quad)
    source.setImage(image)
    if (layer?.visibility?.value != Property.VISIBLE)
        layer?.setProperties(PropertyFactory.visibility(Property.VISIBLE))
}

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

/** A landable of the waypoint file: where, and its name for the map. */
internal class Landable(val longitude: Double, val latitude: Double, val label: String)

/** The landables of the core's waypoints (GeoJSON), by id. */
internal fun parseLandables(json: String): Map<Int, Landable> {
    val result = HashMap<Int, Landable>()
    try {
        val features = JSONObject(json).getJSONArray("features")
        for (i in 0 until features.length()) {
            val f = features.getJSONObject(i)
            val properties = f.getJSONObject("properties")
            if (!properties.optBoolean("landable")) continue
            val c = f.getJSONObject("geometry").getJSONArray("coordinates")
            result[properties.getInt("id")] =
                Landable(c.getDouble(0), c.getDouble(1), properties.optString("label"))
        }
    } catch (e: JSONException) {
        Log.w(TAG, "waypoints", e)
    }
    return result
}

/**
 * The landables near the glider as GeoJSON for the "reach" layers:
 * reachable or not, the name and the arrival height.  A source of their
 * own: changing the label of the country's waypoints every few seconds
 * laid them all out again, and their labels blinked.
 */
internal fun reachJson(nearby: List<WaypointInfo>, at: Map<Int, Landable>): String {
    val features = JSONArray()
    for (w in nearby) {
        val where = at[w.id] ?: continue
        val arrival = w.arrival?.let {
            Format.altitudeDifference(it.toDouble()).let { v -> "\n${v.text} ${v.unit}" }
        }.orEmpty()
        features.put(JSONObject()
            .put("type", "Feature")
            .put("geometry", JSONObject().put("type", "Point")
                .put("coordinates", JSONArray().put(where.longitude).put(where.latitude)))
            .put("properties", JSONObject()
                .put("id", w.id)
                .put("airport", w.airport)
                .put("reach", when (w.reachable) { true -> "yes"; false -> "no"; null -> "" })
                .put("text", where.label + arrival)))
    }
    return JSONObject().put("type", "FeatureCollection").put("features", features).toString()
}

/**
 * The earlier thermals (`MapFeatures.THERMALS`) with their average
 * climb in the pilot's unit, for the map's label.
 */
internal fun thermalsJson(json: String): String {
    val out = JSONArray()
    try {
        val features = JSONObject(json).getJSONArray("features")
        for (i in 0 until features.length()) {
            val f = features.getJSONObject(i)
            val lift = f.getJSONObject("properties").getDouble("lift")
            out.put(JSONObject()
                .put("type", "Feature")
                .put("geometry", f.getJSONObject("geometry"))
                .put("properties", JSONObject().put("text", Format.vario(lift).text)))
        }
    } catch (e: JSONException) {
        Log.w(TAG, "thermals", e)
    }
    return JSONObject().put("type", "FeatureCollection").put("features", out).toString()
}

/**
 * What the airspace layers show besides each airspace's kind: the
 * warnings (orange near, red inside, until acknowledged) and, faintly,
 * what lies well below or above the glider.
 *
 * Filters, not data-driven colours: MapLibre copies a layer's
 * per-feature paint values again in every frame, 3 ms for a country's
 * roads on the Redmi 5.  A filter costs a new layout of its layer, once,
 * when it changes.
 */
private class AirspaceLook {
    private var near = emptyList<Int>()
    private var inside = emptyList<Int>()
    private var altitude: Int? = null

    /** `[[id, "near"|"inside", acked], ...]` from the core. */
    fun warnings(style: Style, json: String) {
        val states = AirspaceState.parseList(json)
        near = states.filter { !it.inside }.map { it.id }
        inside = states.filter { it.inside }.map { it.id }
        fun among(ids: List<Int>) =
            Expression.raw("""["in", ["get", "id"], ["literal", [${ids.joinToString(",")}]]]""")
        style.getLayerAs<FillLayer>("airspace-near")?.setFilter(among(near))
        style.getLayerAs<LineLayer>("airspace-near-line")?.setFilter(among(near))
        style.getLayerAs<FillLayer>("airspace-inside")?.setFilter(among(inside))
        style.getLayerAs<LineLayer>("airspace-inside-line")?.setFilter(among(inside))
    }

    /** The glider's altitude, m: every 100 m, what lies far from it again. */
    fun altitude(style: Style, metres: Double) {
        val last = altitude
        if (last != null && abs(metres - last) <= 100) return
        val a = metres.roundToInt()
        altitude = a
        // what the glider cannot fly into soon
        val far = """["any", ["<", ["get", "top_m"], ${a - 300}], [">", ["get", "base_m"], ${a + 1500}]]"""
        style.getLayer("airspace-line")?.setProperties(
            PropertyFactory.lineOpacity(Expression.raw("""["case", $far, 0.22, 0.9]""")))
        style.getLayerAs<SymbolLayer>("airspace-label")?.setFilter(Expression.raw("""["!", $far]"""))
    }
}

/**
 * The bounds of the task's features (GeoJSON from `xcs_map_features`),
 * only point [index]'s zone and target if given (an area); null without
 * any.
 */
private fun taskBounds(json: String, index: Int?): LatLngBounds? {
    val points = mutableListOf<LatLng>()
    try {
        val features = JSONObject(json).getJSONArray("features")
        for (i in 0 until features.length()) {
            val f = features.getJSONObject(i)
            val properties = f.getJSONObject("properties")
            // an area: its zone and target, not the leg ending there (same index)
            if (index != null && (properties.optInt("index", -1) != index ||
                                  properties.optString("layer") == "leg"))
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
