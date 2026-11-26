// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class IgcFileTest {
    private fun start(vararg lines: String) = IgcFile.start(lines.asSequence())

    @Test
    fun dateAndFirstFix() {
        // test/data/01lz1hq1.igc's header
        assertEquals(Instant.parse("2010-01-21T00:26:05Z").toEpochMilli(),
                     start("AZAN1HQ ZANDER/SDI GP941 V2.11", "HFDTE210110",
                           "HFPLT Pilot             :TEST", "I023638FXA3940SIU",
                           "B0026053559520S14621495EV0016600000000000",
                           "B0026093559520S14621495EV0016600000000000"))
        // the 2015 format, as XCSoar writes it
        assertEquals(Instant.parse("2026-11-21T12:00:01Z").toEpochMilli(),
                     start("HFDTEDATE:211126,01", "B1200014512345N00412345EA0012300150"))
        assertEquals(Instant.parse("1999-12-31T23:59:59Z").toEpochMilli(),
                     start("HFDTE:311299", "B2359594512345N00412345EA0012300150"))
    }

    @Test
    fun notAFlight() {
        assertNull(start("HFDTE210110"))
        assertNull(start("B0026053559520S14621495EV0016600000000000"))
        assertNull(start("HFDTE310210", "B0026053559520S14621495EV0016600000000000"))
        assertNull(start("HFDTE210110", "B2626053559520S14621495EV0016600000000000"))
        assertNull(start("HFDTEXX0110", "B0026053559520S14621495EV0016600000000000"))
        assertNull(start())
    }
}
