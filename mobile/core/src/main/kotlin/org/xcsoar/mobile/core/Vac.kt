// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.core

import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * The French visual approach charts (VAC) of SIA's eAIP: one PDF a
 * field, in a folder of the AIRAC cycle in force (SIA removes the
 * folders of past cycles).
 */
object Vac {
    /** An AIRAC effective date (SIA's eAIP_26_NOV_2026); cycles are 28 days. */
    private val REFERENCE: LocalDate = LocalDate.of(2026, 11, 26)
    private const val CYCLE_DAYS = 28L

    private val MONTHS = arrayOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN",
                                 "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")

    /** The XCSoarData folder of the charts. */
    const val FOLDER = "vac"

    /** SIA publishes the fields of France: ICAO codes LF.. (four letters). */
    fun covers(icao: String) = icao.length == 4 && icao.startsWith("LF") &&
        icao.all { it in 'A'..'Z' }

    /** The effective date of the cycle in force on [date]. */
    fun cycle(date: LocalDate): LocalDate {
        val days = ChronoUnit.DAYS.between(REFERENCE, date)
        return REFERENCE.plusDays(Math.floorDiv(days, CYCLE_DAYS) * CYCLE_DAYS)
    }

    /** The cycle in force now (cycles change at 00:00 UTC). */
    fun currentCycle(): LocalDate = cycle(LocalDate.now(ZoneOffset.UTC))

    fun url(icao: String, cycle: LocalDate): String =
        String.format(Locale.ROOT,
            "https://www.sia.aviation-civile.gouv.fr/media/dvd/eAIP_%02d_%s_%04d/" +
                "Atlas-VAC/PDF_AIPparSSection/VAC/AD/AD-2.%s.pdf",
            cycle.dayOfMonth, MONTHS[cycle.monthValue - 1], cycle.year, icao)

    /** The local copy of [icao]'s chart of [cycle]. */
    fun fileName(icao: String, cycle: LocalDate) = "AD-2.$icao.$cycle.pdf"

    /** What says SIA has no chart of [icao] in [cycle]: not asked again. */
    fun noneName(icao: String, cycle: LocalDate) = "AD-2.$icao.$cycle.none"

    /** The field of a chart's file name; null for another file. */
    fun icaoOf(fileName: String): String? =
        Regex("""AD-2\.([A-Z]{4})\.\d{4}-\d{2}-\d{2}\.pdf""").matchEntire(fileName)?.groupValues?.get(1)
}
