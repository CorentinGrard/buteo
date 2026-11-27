// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class VacTest {
    private fun day(s: String) = LocalDate.parse(s)

    /** SIA's eAIP folders seen: 19 MAR, 11 JUN, 26 NOV 2026. */
    @Test
    fun cycles() {
        assertEquals(day("2026-03-19"), Vac.cycle(day("2026-03-19")))
        assertEquals(day("2026-06-11"), Vac.cycle(day("2026-07-08")))
        assertEquals(day("2026-11-26"), Vac.cycle(day("2026-11-27")))
        assertEquals(day("2026-11-26"), Vac.cycle(day("2026-12-23")))
        assertEquals(day("2026-12-24"), Vac.cycle(day("2026-12-24")))
        assertEquals(day("2027-01-21"), Vac.cycle(day("2027-02-01")))
    }

    @Test
    fun url() {
        assertEquals("https://www.sia.aviation-civile.gouv.fr/media/dvd/eAIP_26_NOV_2026/" +
                         "Atlas-VAC/PDF_AIPparSSection/VAC/AD/AD-2.LFLQ.pdf",
                     Vac.url("LFLQ", day("2026-11-26")))
        assertEquals("AD-2.LFLQ.2026-11-26.pdf", Vac.fileName("LFLQ", day("2026-11-26")))
    }

    @Test
    fun icaoOfAFile() {
        assertEquals("LFLQ", Vac.icaoOf("AD-2.LFLQ.2026-11-26.pdf"))
        assertEquals(null, Vac.icaoOf("AD-2.LFLQ.2026-11-26.none"))
    }

    @Test
    fun france() {
        assertTrue(Vac.covers("LFLQ"))
        assertFalse(Vac.covers("LSGG"))
        assertFalse(Vac.covers("LF38"))
    }
}
