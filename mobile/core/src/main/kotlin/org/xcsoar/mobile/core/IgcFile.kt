// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.core

import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * When the flight of an IGC file began: its date (the "HFDTE" header,
 * as upstream's IGCParseDateRecord() reads it) and the time of its first
 * fix ("B" record), both UTC.  For listing flights by their own date: a
 * file's modification time is that of a copy for a flight brought in.
 */
object IgcFile {
    /** The header is a few dozen lines; past this, the file is no IGC file. */
    private const val MAX_HEADER_LINES = 500

    /** The first fix's time, ms since the epoch (UTC); null without one or a date. */
    fun start(file: File): Long? = try {
        file.bufferedReader(Charsets.ISO_8859_1).useLines(::start)
    } catch (_: IOException) {
        null
    }

    fun start(lines: Sequence<String>): Long? {
        var date: LocalDate? = null
        for (line in lines.take(MAX_HEADER_LINES)) {
            when {
                line.startsWith("HFDTE") -> date = date(line.removePrefix("HFDTE"))
                line.startsWith("B") -> return fixTime(line, date ?: return null)
            }
        }
        return null
    }

    /** "DDMMYY", "DATE:DDMMYY,NN" or ":DDMMYY"; years 1990 to 2089, as upstream. */
    private fun date(field: String): LocalDate? {
        val digits = field.removePrefix("DATE").removePrefix(":").take(6)
        if (digits.length < 6 || !digits.all { it.isDigit() }) return null
        val value = digits.toInt()
        return try {
            LocalDate.of(1990 + (value % 100 + 10) % 100, value / 100 % 100, value / 10000)
        } catch (_: Exception) {
            null
        }
    }

    /** A B record's "HHMMSS" on [date]. */
    private fun fixTime(line: String, date: LocalDate): Long? {
        val hms = line.substring(1).take(6)
        if (hms.length < 6 || !hms.all { it.isDigit() }) return null
        val h = hms.substring(0, 2).toInt()
        val m = hms.substring(2, 4).toInt()
        val s = hms.substring(4, 6).toInt()
        if (h > 23 || m > 59 || s > 59) return null
        return date.atTime(h, m, s).toInstant(ZoneOffset.UTC).toEpochMilli()
    }
}
