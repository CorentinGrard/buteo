// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.flight

import androidx.compose.ui.geometry.Offset
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xcsoar.mobile.core.LiftCircle
import org.xcsoar.mobile.core.METRES_PER_DEGREE
import org.xcsoar.mobile.core.TileGraphic
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Where the thermal assistant puts the lift (ThermalAssistantRenderer's
 * geometry), on the screen and on the map (Robolectric: org.json).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ThermalAssistantTest {
    /* lift only on one heading */
    private fun onlyOn(heading: Int, left: Boolean) = TileGraphic(
        TileGraphic.THERMAL_ASSISTANT, circling = true, left = left,
        lift = (0 until 36).map { if (it * 10 == heading) 2.0 else 0.0 })

    private fun strongestPoint(g: TileGraphic, up: Double): Offset {
        val points = liftPoints(g, Offset(0f, 0f), 100f, up)!!
        return points.maxBy { it.getDistance() }
    }

    @Test
    fun liftWhereTheGliderWasOnTheCircle() {
        // turning left on a heading of north, the glider is east of the centre
        val east = strongestPoint(onlyOn(0, left = true), up = 0.0)
        assertEquals(100f, east.x, 0.01f)
        assertEquals(0f, east.y, 0.01f)
        assertEquals(90.0, strongestBearing(onlyOn(0, left = true))!!, 1e-9)
        // turning right, west
        assertEquals(-100f, strongestPoint(onlyOn(0, left = false), up = 0.0).x, 0.01f)
        assertEquals(270.0, strongestBearing(onlyOn(0, left = false))!!, 1e-9)
    }

    @Test
    fun turnsWithTheMap() {
        // east of the centre on a map whose top is east: straight up
        val up = strongestPoint(onlyOn(0, left = true), up = 90.0)
        assertEquals(0f, up.x, 0.01f)
        assertEquals(-100f, up.y, 0.01f)
    }

    /* metres east and north of the circle's centre */
    private fun JSONArray.metres(circle: LiftCircle): Offset {
        val east = (getDouble(0) - circle.longitude) * METRES_PER_DEGREE *
            cos(Math.toRadians(circle.latitude))
        val north = (getDouble(1) - circle.latitude) * METRES_PER_DEGREE
        return Offset(east.toFloat(), north.toFloat())
    }

    @Test
    fun onTheMapOnTheGlidersCircle() {
        val circle = LiftCircle(45.0, 6.0, 120.0)
        val features = JSONObject(liftCircleJson(onlyOn(0, left = true).copy(circle = circle))!!)
            .getJSONArray("features")
        fun coordinates(i: Int) =
            features.getJSONObject(i).getJSONObject("geometry").getJSONArray("coordinates")
        fun kind(i: Int) = features.getJSONObject(i).getJSONObject("properties").getString("kind")

        // the ring is the glider's circle, the half ring no climb
        assertEquals("ring", kind(0))
        for (j in 0 until coordinates(0).length())
            assertEquals(120f, coordinates(0).getJSONArray(j).metres(circle).getDistance(), 0.5f)
        assertEquals(60f, coordinates(1).getJSONArray(0).metres(circle).getDistance(), 0.5f)

        // the climb where the glider flew north, turning left: east of the centre
        assertEquals("lift", kind(2))
        val outline = coordinates(2).getJSONArray(0)
        val strongest = (0 until outline.length()).map { outline.getJSONArray(it).metres(circle) }
            .maxBy { hypot(it.x, it.y) }
        assertEquals(120f, strongest.x, 0.5f)
        assertEquals(0f, strongest.y, 0.5f)

        // the advisor from the centre
        assertEquals("advisor", kind(3))
        assertEquals(0f, coordinates(3).getJSONArray(0).metres(circle).getDistance(), 0.01f)
    }

    @Test
    fun notOnTheMapWithoutTheCircle() {
        assertNull(liftCircleJson(onlyOn(0, left = true)))
        assertNull(liftCircleJson(TileGraphic(TileGraphic.THERMAL_ASSISTANT,
                                              circle = LiftCircle(45.0, 6.0, 120.0))))
    }

    @Test
    fun noLiftNoPoints() {
        assertNull(liftPoints(TileGraphic(TileGraphic.THERMAL_ASSISTANT), Offset.Zero, 100f, 0.0))
        assertNull(strongestBearing(TileGraphic(TileGraphic.THERMAL_ASSISTANT,
                                                lift = List(36) { 0.0 })))
        // no climb: every point halfway, on the inner circle
        val calm = liftPoints(TileGraphic(TileGraphic.THERMAL_ASSISTANT, lift = List(36) { 0.0 }),
                              Offset.Zero, 100f, 0.0)!!
        calm.forEach { assertEquals(50f, it.getDistance(), 0.01f) }
    }
}
