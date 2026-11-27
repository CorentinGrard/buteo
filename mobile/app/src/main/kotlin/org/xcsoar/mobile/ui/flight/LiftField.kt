// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.flight

import android.graphics.Bitmap
import org.json.JSONException
import org.json.JSONObject
import org.xcsoar.mobile.core.METRES_PER_DEGREE
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A sample of the climb: where, and the netto vario there (m/s). */
data class LiftSample(val latitude: Double, val longitude: Double, val climb: Double)

/** How far a sample weighs, m (the weights' standard deviation). */
private const val BANDWIDTH = 60.0
/** How far past the samples the field reaches, fading out, m. */
private const val REACH = 250.0
/** The cells: this size at least (m), this many a side at most. */
private const val MIN_CELL = 12.0
private const val MAX_CELLS = 48
/** The samples a field is made of at most: a long climb is thinned. */
private const val MAX_SAMPLES = 240
/** Fewer samples make no field. */
private const val MIN_SAMPLES = 8
/** Holds the planes' slopes back where few samples tell them (ridge). */
private const val SLOPE_DAMPING = 0.1
/** A field goes no higher than this times the best sample. */
private const val EXTRAPOLATION_LIMIT = 1.25

/**
 * The climb around a thermal over the map, from the climb's samples
 * (`xcs_map_features` LIFT): at each cell of a grid round them, a plane
 * fitted to the samples nearby, each weighted by its distance (local
 * linear regression).  A climb growing across the circle goes on
 * growing past it, towards the thermal's centre; far from every sample
 * the field fades out ([confidence]).
 *
 * @param climb m/s per cell, row by row from the north-west
 * @param confidence 0..1 per cell: 1 near the samples, 0 [REACH] away
 * @param best the top of the colour scale: the best climb where the
 * field is sure, 1 m/s at least
 */
class LiftField(
    val north: Double, val south: Double, val west: Double, val east: Double,
    val columns: Int, val rows: Int,
    val climb: FloatArray, val confidence: FloatArray, val best: Double,
) {
    /** The cell's climb at a place, and how sure it is; null outside the grid. */
    internal fun at(latitude: Double, longitude: Double): Pair<Float, Float>? {
        val column = floor((longitude - west) / (east - west) * columns).toInt()
        val row = floor((north - latitude) / (north - south) * rows).toInt()
        if (column !in 0 until columns || row !in 0 until rows) return null
        val i = row * columns + column
        return climb[i] to confidence[i]
    }
}

/** The samples of `xcs_map_features` LIFT (GeoJSON points with "climb"). */
internal fun parseLiftSamples(json: String): List<LiftSample> = try {
    val features = JSONObject(json).getJSONArray("features")
    (0 until features.length()).map { i ->
        val f = features.getJSONObject(i)
        val at = f.getJSONObject("geometry").getJSONArray("coordinates")
        LiftSample(at.getDouble(1), at.getDouble(0),
                   f.getJSONObject("properties").getDouble("climb"))
    }
} catch (_: JSONException) {
    emptyList()
}

/** The field of [samples]; null with too few of them. */
internal fun liftField(samples: List<LiftSample>): LiftField? {
    if (samples.size < MIN_SAMPLES) return null
    val step = ceil(samples.size.toDouble() / MAX_SAMPLES).toInt()
    val used = samples.filterIndexed { i, _ -> i % step == 0 }

    // metres east and north of the samples' middle
    val latitude0 = used.sumOf { it.latitude } / used.size
    val longitude0 = used.sumOf { it.longitude } / used.size
    val kx = METRES_PER_DEGREE * cos(Math.toRadians(latitude0))
    val xs = DoubleArray(used.size) { (used[it].longitude - longitude0) * kx }
    val ys = DoubleArray(used.size) { (used[it].latitude - latitude0) * METRES_PER_DEGREE }
    val vs = DoubleArray(used.size) { used[it].climb }

    val minX = xs.min() - REACH
    val maxX = xs.max() + REACH
    val minY = ys.min() - REACH
    val maxY = ys.max() + REACH
    val cell = max(MIN_CELL, max(maxX - minX, maxY - minY) / MAX_CELLS)
    val columns = ceil((maxX - minX) / cell).toInt()
    val rows = ceil((maxY - minY) / cell).toInt()

    val lowest = vs.min()
    val highest = vs.max() * EXTRAPOLATION_LIMIT
    val twoSigma2 = 2 * BANDWIDTH * BANDWIDTH
    val ridge = SLOPE_DAMPING * BANDWIDTH * BANDWIDTH
    val climb = FloatArray(columns * rows)
    val confidence = FloatArray(columns * rows)
    val d2 = DoubleArray(used.size)
    for (row in 0 until rows) {
        val cy = maxY - (row + 0.5) * cell
        for (column in 0 until columns) {
            val cx = minX + (column + 0.5) * cell
            var nearest = Double.MAX_VALUE
            for (i in xs.indices) {
                val dx = xs[i] - cx
                val dy = ys[i] - cy
                d2[i] = dx * dx + dy * dy
                nearest = min(nearest, d2[i])
            }
            // weights relative to the nearest sample's: the far cells
            // keep the plane of the samples nearest to them
            var s0 = 0.0; var sx = 0.0; var sy = 0.0
            var sxx = 0.0; var sxy = 0.0; var syy = 0.0
            var sv = 0.0; var svx = 0.0; var svy = 0.0
            for (i in xs.indices) {
                val e = (d2[i] - nearest) / twoSigma2
                if (e > 9) continue
                val w = exp(-e)
                val dx = xs[i] - cx
                val dy = ys[i] - cy
                s0 += w; sx += w * dx; sy += w * dy
                sxx += w * dx * dx; sxy += w * dx * dy; syy += w * dy * dy
                sv += w * vs[i]; svx += w * vs[i] * dx; svy += w * vs[i] * dy
            }
            sxx += ridge * s0
            syy += ridge * s0
            // the plane's height here (Cramer's rule), else the weighted mean
            val det = s0 * (sxx * syy - sxy * sxy) - sx * (sx * syy - sxy * sy) +
                sy * (sx * sxy - sxx * sy)
            val value = if (det > 1e-9 * s0 * sxx * syy)
                (sv * (sxx * syy - sxy * sxy) - sx * (svx * syy - sxy * svy) +
                    sy * (svx * sxy - sxx * svy)) / det
            else sv / s0
            val i = row * columns + column
            climb[i] = value.coerceIn(lowest, highest).toFloat()
            val distance = sqrt(nearest)
            confidence[i] = (1 - (distance - BANDWIDTH) / (REACH - BANDWIDTH))
                .coerceIn(0.0, 1.0).toFloat()
        }
    }

    var best = 1.0
    for (i in climb.indices)
        if (confidence[i] >= 0.9f) best = max(best, climb[i].toDouble())
    // the grid's own edges: whole cells
    return LiftField(maxY / METRES_PER_DEGREE + latitude0,
                     (maxY - rows * cell) / METRES_PER_DEGREE + latitude0,
                     minX / kx + longitude0, (minX + columns * cell) / kx + longitude0,
                     columns, rows, climb, confidence, best)
}

/* the colour scale, warm (ColorBrewer's YlOrRd): yellow for a weak climb, red for the best */
private val SCALE = intArrayOf(0xFED976, 0xFD8D3C, 0xE31A1C)

/**
 * The field as an image, north up, a pixel a cell (the map smooths
 * it): no colour without a climb, then yellow to red up to
 * [LiftField.best], fading out where the field is not sure.
 */
fun LiftField.toBitmap(): Bitmap {
    val pixels = IntArray(climb.size) { i ->
        val t = (climb[i] / best).coerceIn(0.0, 1.0)
        if (t <= 0.0) return@IntArray 0
        val alpha = (255 * confidence[i] * (0.15 + 0.6 * t)).roundToInt()
        // between the scale's colours
        val at = t * (SCALE.size - 1)
        val k = min(at.toInt(), SCALE.size - 2)
        val f = at - k
        fun channel(shift: Int): Int {
            val a = (SCALE[k] shr shift) and 0xFF
            val b = (SCALE[k + 1] shr shift) and 0xFF
            return (a + (b - a) * f).roundToInt()
        }
        (alpha shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
    return Bitmap.createBitmap(pixels, columns, rows, Bitmap.Config.ARGB_8888)
}
