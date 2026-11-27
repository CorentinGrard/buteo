// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.flight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.xcsoar.mobile.core.FlightTimes

class ReplayBarTest {
    @Test
    fun utcTimeOfDay() {
        // 2026-10-02 14:02:31 UTC
        assertEquals("14:02:31", utcTime(1_790_949_751.0))
        assertEquals("00:00:00", utcTime(86_400.0))
    }

    @Test
    fun flightTimeInHoursAndMinutes() {
        assertEquals("0:00", flightTime(-5.0))
        assertEquals("1:05", flightTime(3_930.0))
        assertEquals("5:14", flightTime(18_840.0))
    }

    @Test
    fun fromTheTakeOffToTheLanding() {
        // 2010-01-21: take-off 01:40, landing 06:54 UTC
        val w = ReplayWindow.of("2010-01-21", FlightTimes(6_000.0, landing = 24_840.0))!!
        assertEquals(1_264_032_000.0, w.dayStartUtc, 0.0)
        assertEquals(6_000.0, w.start, 0.0)
        // the roll to a stop after the landing
        assertEquals(24_900.0, w.end, 0.0)
        assertEquals(6_100.0, w.at(1_264_032_000.0 + 6_100), 0.0)
        // not landed: to the end of the flight's time
        assertEquals(9_600.0,
                     ReplayWindow.of("2010-01-21", FlightTimes(6_000.0, duration = 3_600.0))!!.end,
                     0.0)
        assertNull(ReplayWindow.of(null, FlightTimes(6_000.0, landing = 9_000.0)))
        assertNull(ReplayWindow.of("2010-01-21", null))
        assertNull(ReplayWindow.of("not a date", FlightTimes(6_000.0, landing = 9_000.0)))
    }
}
