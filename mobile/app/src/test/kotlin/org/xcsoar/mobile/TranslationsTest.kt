// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * The generated translations (mobile/docs/DECISIONS.md D28): every
 * language formats every text with placeholders as the English does, and
 * French has them all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TranslationsTest {
    private val app: Context = ApplicationProvider.getApplicationContext()

    private val ids = R.string::class.java.fields.map { it.getInt(null) }

    /** XCSoar's languages, as Android names them (mobile/tools/po2strings.py). */
    private val languages = listOf(
        "bg", "ca", "cs", "da", "de", "el", "es", "fi", "fr", "he", "hr", "hu", "it",
        "ja", "ko", "lt", "nb", "nl", "pl", "pt", "pt-BR", "ro", "ru", "sk", "sl", "sr",
        "sv", "te", "tr", "uk", "vi", "zh-CN", "zh-Hant")

    private fun context(tag: String): Context =
        app.createConfigurationContext(Configuration(app.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(tag))
        })

    /** Arguments of the right types for a text's %1$s, %2$d… */
    private fun arguments(english: String): Array<Any> =
        Regex("""%(\d+)\$[-#+ 0,(]*\d*(?:\.\d+)?([a-zA-Z])""").findAll(english)
            .map { it.groupValues[1].toInt() to it.groupValues[2] }
            .sortedBy { it.first }.distinctBy { it.first }
            .map { (_, type) ->
                when (type) {
                    "d" -> 1
                    "f" -> 1.5
                    else -> "x"
                }
            }.toList().toTypedArray()

    @Test
    fun everyLanguageFormatsEveryText() {
        val english = context("en")
        for (tag in languages) {
            val localized = context(tag)
            for (id in ids) {
                val args = arguments(english.getString(id))
                // throws on a placeholder the arguments do not fit
                val text = localized.getString(id, *args)
                assertTrue("$tag ${app.resources.getResourceEntryName(id)}", text.isNotBlank())
            }
        }
    }

    @Test
    fun frenchIsComplete() {
        val english = context("en")
        val french = context("fr")
        val missing = ids.filter {
            english.resources.getText(it) == french.resources.getText(it) &&
                // the same in both languages, and the name
                app.resources.getResourceEntryName(it) !in SAME_IN_FRENCH
        }.map { app.resources.getResourceEntryName(it) }
        assertEquals("texts without a French translation (mobile/po/fr.po)",
                     emptyList<String>(), missing)
    }

    private companion object {
        /** Texts French writes as English does. */
        val SAME_IN_FRENCH = setOf(
            "app_name", "auto", "mac_cready", "netto", "menu", "flight_mc_auto",
            // a field's page: "Code", "VAC LFLQ"
            "field_code", "field_chart_open", "field_chart_page",
            // a brand name
            "menu_map_xcsoar",
            // the same word, or XCSoar's own (po/fr.po) is the same
            "airalerts_vibration", "airdetails_base", "airdetails_station", "airdetails_type",
            "atc_east", "crew_solo", "data_waypoints", "map_trail_long", "notam_permanent",
            "notam_title", "plane_handicap", "plane_handicap_name", "plane_spoken_litres",
            "plane_weglide_type", "radio_title", "ramp_imhof", "ramp_pastel", "setup_ballast",
            "sound_tone_vario", "sound_volume", "sound_volume_name", "task_list_scores",
            "task_nominal", "track_cloud", "track_test", "units_altitude", "units_distance",
            // ICAO abbreviations; XCSoar's "Angle"
            "task_agl", "task_msl", "task_sector_angle", "task_target_range")
    }
}
