// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.flight

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xcsoar.mobile.ui.Format

/** The earlier thermals on the map: where the core puts them, labelled with their climb. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ThermalsJsonTest {
    @Test
    fun labelledWithTheirClimb() {
        val core = """{"type":"FeatureCollection","features":[
            {"type":"Feature","geometry":{"type":"Point","coordinates":[5.2,46.1]},
             "properties":{"lift":2.4,"age":300}}]}"""
        val features = JSONObject(thermalsJson(core)).getJSONArray("features")
        assertEquals(1, features.length())
        val f = features.getJSONObject(0)
        assertEquals(5.2, f.getJSONObject("geometry").getJSONArray("coordinates").getDouble(0), 0.0)
        assertEquals(Format.vario(2.4).text, f.getJSONObject("properties").getString("text"))
    }

    @Test
    fun noneFromNothing() {
        assertEquals(0, JSONObject(thermalsJson("not json")).getJSONArray("features").length())
    }
}
