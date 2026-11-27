// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import org.xcsoar.mobile.core.Vac

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

        val target = try {
            fetch(icao, cycle)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (newest != null)
                return@withContext VacChart(newest.first, newest.second, false)
            throw e
        }
        VacChart(target, cycle, true)
    }

    /**
     * Download [icao]'s chart of [cycle] and drop the older editions.
     * When SIA has none (HTTP 404), a marker says so, so that the
     * pre-flight check does not ask again this cycle.
     */
    suspend fun fetch(icao: String, cycle: LocalDate): File = withContext(Dispatchers.IO) {
        val target = File(dir, Vac.fileName(icao, cycle))
        try {
            downloader.download(Vac.url(icao, cycle), target, null) {}
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e.message?.contains("HTTP 404") == true)
                File(dir, Vac.noneName(icao, cycle)).writeText("")
            throw e
        }
        // a server's error page is no chart
        val head = target.inputStream().use { it.readNBytes(4) }
        if (!head.contentEquals("%PDF".toByteArray())) {
            target.delete()
            throw java.io.IOException("Not a PDF: ${Vac.url(icao, cycle)}")
        }
        // the older editions are of no use any more
        copies(icao).filter { it.second < cycle }.forEach { it.first.delete() }
        dir.listFiles { f -> f.name.startsWith("AD-2.$icao.") && f.name.endsWith(".none") }
            ?.forEach { it.delete() }
        target
    }

    /** The names of the files kept, for the pre-flight check. */
    fun names(): Set<String> = dir.list()?.toSet().orEmpty()

    private companion object {
        val NAME = Regex("""AD-2\.([A-Z]{4})\.(\d{4}-\d{2}-\d{2})\.pdf""")
    }
}
