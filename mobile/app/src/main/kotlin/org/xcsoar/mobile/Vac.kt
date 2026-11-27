// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
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

    /** SIA publishes the fields of France: ICAO codes LF.. */
    fun covers(icao: String) = icao.length == 4 && icao.startsWith("LF")

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
}

/** A chart on the phone, of [cycle]; [current] false when a newer one could not be had. */
data class VacChart(val file: File, val cycle: LocalDate, val current: Boolean)

/**
 * The charts kept in XCSoarData/vac, so that they open without network
 * in flight: the one of the cycle in force, downloaded once; the last
 * one when it cannot be.
 */
class VacStore(private val dir: File, private val downloader: Downloader) {
    private fun copies(icao: String): List<Pair<File, LocalDate>> =
        dir.listFiles()?.mapNotNull { file ->
            val match = NAME.matchEntire(file.name) ?: return@mapNotNull null
            if (match.groupValues[1] != icao) return@mapNotNull null
            val cycle = try {
                LocalDate.parse(match.groupValues[2])
            } catch (_: Exception) {
                return@mapNotNull null
            }
            file to cycle
        }.orEmpty().sortedByDescending { it.second }

    /**
     * [icao]'s chart: the cycle in force's, downloaded if needed, else
     * the newest copy.
     *
     * @throws Exception when there is neither
     */
    suspend fun chart(icao: String): VacChart = withContext(Dispatchers.IO) {
        val cycle = Vac.currentCycle()
        val newest = copies(icao).firstOrNull()
        if (newest != null && newest.second >= cycle)
            return@withContext VacChart(newest.first, newest.second, true)

        val target = File(dir, Vac.fileName(icao, cycle))
        try {
            downloader.download(Vac.url(icao, cycle), target, null) {}
            // a server's error page is no chart
            val head = target.inputStream().use { it.readNBytes(4) }
            if (!head.contentEquals("%PDF".toByteArray())) {
                target.delete()
                throw java.io.IOException("Not a PDF: ${Vac.url(icao, cycle)}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (newest != null)
                return@withContext VacChart(newest.first, newest.second, false)
            throw e
        }
        // the older editions are of no use any more
        copies(icao).filter { it.second < cycle }.forEach { it.first.delete() }
        VacChart(target, cycle, true)
    }

    private companion object {
        val NAME = Regex("""AD-2\.([A-Z]{4})\.(\d{4}-\d{2}-\d{2})\.pdf""")
    }
}
