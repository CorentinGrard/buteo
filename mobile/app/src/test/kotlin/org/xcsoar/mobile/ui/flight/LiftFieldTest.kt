// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.flight

import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xcsoar.mobile.core.METRES_PER_DEGREE
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** The climb around a thermal, fitted to its samples (Robolectric: org.json, Bitmap). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LiftFieldTest {
    private val metresPerLongitude = METRES_PER_DEGREE * cos(Math.toRadians(45.0))

    /* a circle of 100 m round (45°N, 6°E), each sample's climb from its metres east */
    private fun circle(climb: (east: Double) -> Double) = (0 until 36).map { i ->
        val a = Math.toRadians(i * 10.0)
        val east = 100 * sin(a)
        LiftSample(45.0 + 100 * cos(a) / METRES_PER_DEGREE, 6.0 + east / metresPerLongitude,
                   climb(east))
    }

    private fun LiftField.metresFromCentre(east: Double, north: Double) =
        at(45.0 + north / METRES_PER_DEGREE, 6.0 + east / metresPerLongitude)!!

    @Test
    fun growsTowardsTheBetterSide() {
        // from -1 m/s on the west of the circle to 3 m/s on the east
        val field = liftField(circle { 1 + it / 50 })!!
        val west = field.metresFromCentre(-200.0, 0.0).first
        val centre = field.metresFromCentre(0.0, 0.0).first
        val east = field.metresFromCentre(200.0, 0.0).first
        assertEquals(1f, centre, 0.3f)
        assertTrue("$west < $centre < $east", west < centre && centre < east)
        // past the circle, more than any sample
        assertTrue("$east", east > 3f)
    }

    @Test
    fun sureNearTheSamplesOnly() {
        val field = liftField(circle { 1.0 })!!
        assertEquals(1f, field.metresFromCentre(0.0, 0.0).first, 0.05f)
        assertEquals(1f, field.metresFromCentre(100.0, 0.0).second, 1e-3f)
        // the grid's corner, farther than its reach from every sample
        val corner = field.at(field.north - 1e-6, field.west + 1e-6)!!
        assertEquals(0f, corner.second, 1e-3f)
    }

    @Test
    fun noFieldFromAFewSamples() {
        assertNull(liftField(circle { 1.0 }.take(5)))
    }

    @Test
    fun samplesFromTheCore() {
        val json = """{"type":"FeatureCollection","features":[{"type":"Feature",
            "geometry":{"type":"Point","coordinates":[6.1,45.2]},"properties":{"climb":2.5}}]}"""
        assertEquals(listOf(LiftSample(45.2, 6.1, 2.5)), parseLiftSamples(json))
        assertEquals(emptyList<LiftSample>(), parseLiftSamples("not json"))
    }

    @Test
    fun noColourWithoutClimb() {
        // sinking on the west of the circle, climbing on the east
        val field = liftField(circle { it / 50 })!!
        val image = field.toBitmap()
        fun pixel(east: Double) = image.getPixel(
            floor((6.0 + east / metresPerLongitude - field.west) /
                  (field.east - field.west) * field.columns).toInt(),
            floor((field.north - 45.0) / (field.north - field.south) * field.rows).toInt())
        assertEquals(0, Color.alpha(pixel(-100.0)))
        assertTrue(Color.alpha(pixel(100.0)) > 0)
        assertTrue(Color.blue(pixel(100.0)) > Color.red(pixel(100.0)))
    }
}
