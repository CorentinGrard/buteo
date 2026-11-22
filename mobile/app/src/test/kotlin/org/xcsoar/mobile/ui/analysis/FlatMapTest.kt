// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.analysis

import org.junit.Assert.assertEquals
import org.junit.Test
import org.xcsoar.mobile.core.LatLon

/**
 * The terrain the core draws for a map's box must lie under the map:
 * the box's corners are the canvas's corners.
 */
class FlatMapTest {
    private val flight = listOf(LatLon(45.2, 5.8), LatLon(45.6, 6.4), LatLon(44.9, 6.1))

    private fun assertCorners(map: FlatMap, width: Float, height: Float) {
        val nw = map.at(LatLon(map.north, map.west))
        val se = map.at(LatLon(map.south, map.east))
        assertEquals(0f, nw.x, 0.01f)
        assertEquals(0f, nw.y, 0.01f)
        assertEquals(width, se.x, 0.01f)
        assertEquals(height, se.y, 0.01f)
    }

    @Test
    fun boxIsTheCanvas() {
        // wider than the flight: margins left and right
        assertCorners(FlatMap(flight, 1200f, 1000f, 48f), 1200f, 1000f)
        // taller: margins above and below
        assertCorners(FlatMap(flight, 400f, 1000f, 48f), 400f, 1000f)
    }

    @Test
    fun flightInsideTheInset() {
        val map = FlatMap(flight, 1200f, 1000f, 48f)
        for (p in flight) {
            val o = map.at(p)
            assert(o.x in 47.9f..1152.1f && o.y in 47.9f..952.1f) { "$p at $o" }
        }
    }
}
