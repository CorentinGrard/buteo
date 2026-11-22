// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile

import android.Manifest
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.xcsoar.mobile.core.PlaneDetails
import org.xcsoar.mobile.core.PlaneEdit
import org.xcsoar.mobile.core.TileLayout
import org.xcsoar.mobile.ui.Format
import java.io.File

/**
 * L5 (mobile/docs/ARCHITECTURE.md §6): the real core, optimised as it
 * flies (build type l5), replays the demo flight through the app, and
 * the flight screen shows it flying, with a vario and a wind estimate;
 * a tap on a tile asks for the page its InfoBox opens; a plane's details
 * go through the JNI binding unchanged.
 *
 * Runs on an emulator in CI and on a phone: the l5 app has its own
 * application id, so the pilot's app and data are never touched.
 */
@RunWith(AndroidJUnit4::class)
class ReplayEndToEndTest {
    private val permissions = GrantPermissionRule.grant(*buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray())

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(permissions).around(compose)

    @Test
    fun aRecordedFlightFliesOnTheFlightScreen() {
        val flight = copyTestFlight()
        startFlightScreen()

        // Flights, the flight's page with the core's analysis of the
        // file, and its Replay
        compose.onNodeWithContentDescription(text(R.string.menu)).performClick()
        compose.onNodeWithText(text(R.string.menu_flights)).performScrollTo().performClick()
        compose.onNodeWithText(flight.name, substring = true).performClick()
        waitFor("the flight's analysis", STARTUP_MS) {
            descriptions("Time ").any { !it.contains(Format.INVALID) }
        }
        compose.onNodeWithText("Replay").performClick()
        waitFor("the replay bar", STARTUP_MS) {
            exists(hasContentDescription(text(R.string.replay_skip)))
        }

        // XCSoar sees the take-off a few minutes into the file; "On ground"
        // also goes when the core has no state ("Starting…"): not a take-off
        compose.onNodeWithContentDescription(text(R.string.replay_skip)).performClick()
        waitFor("the take-off", FLIGHT_MS) {
            !exists(hasText(text(R.string.flight_on_ground))) &&
                !exists(hasText(text(R.string.flight_starting))) &&
                exists(hasContentDescription(text(R.string.replay_stop)))
        }

        val vario = prefix(R.string.flight_vario_spoken)
        waitFor("a climb or sink on the vario", FLIGHT_MS) {
            descriptions(vario).any { !it.startsWith("${vario}0.0") &&
                                      !it.startsWith("$vario${Format.INVALID}") }
        }

        // a circling estimate needs a few turns
        compose.onNodeWithContentDescription(text(R.string.replay_skip)).performClick()
        waitFor("a wind estimate", FLIGHT_MS) {
            descriptions(prefix(R.string.flight_wind_from)).any { !it.contains(Format.INVALID) }
        }

        compose.onNodeWithContentDescription(text(R.string.replay_stop)).performClick()
        waitFor("the replay stopped", STARTUP_MS) {
            !exists(hasContentDescription(text(R.string.replay_stop)))
        }
    }

    /**
     * What a tapped tile opens, from the optimised core: the host tests
     * (debug builds) missed that it folded the panels' seams into one
     * function, so every panel tile opened Flight setup.
     */
    @Test
    fun tilesOpenTheirInfoBoxPage() = runBlocking {
        startFlightScreen()
        val core = requireNotNull(ApplicationProvider.getApplicationContext<XcsoarApp>().core) {
            "no native core"
        }

        // InfoBox types (src/InfoBoxes/Content/Type.hpp) and their pages
        val pages = mapOf(
            10 to "maccready",  // e_MacCready
            25 to "wind",       // e_WindSpeed_Est
            55 to "team_code",  // e_Team_Code
            94 to "altitude",   // NavAltitude
            103 to "atc",       // ATC_RADIAL
            115 to "radio",     // e_ActiveRadio
            116 to "radio",     // e_StandbyRadio
        )
        for ((type, page) in pages) {
            core.setTile(TileLayout.CIRCLING, 5, type)
            assertEquals("tile type $type", page,
                         core.tapTile(TileLayout.CIRCLING, 5)?.action)
        }
    }

    /**
     * A plane's details through the JNI binding and back: JNI finds the
     * native function by its name alone, so arguments in the wrong order
     * would arrive as wrong values, unseen by the host tests.
     */
    @Test
    fun planeDetailsRoundTrip() = runBlocking {
        startFlightScreen()
        val core = requireNotNull(ApplicationProvider.getApplicationContext<XcsoarApp>().core) {
            "no native core"
        }
        val path = core.savePlane(PlaneEdit(path = "", registration = "L5-TEST",
                                            competitionId = "L5", type = "Test", polar = 0,
                                            weGlideType = 0, doubleSeater = false))
        try {
            val details = PlaneDetails(emptyMass = 250.0, referenceMass = 350.0,
                                       maxBallast = 100.0, dumpTime = 150, maxSpeed = 70.0,
                                       wingArea = 10.5, handicap = 105, maxTakeoffMass = 525.0)
            core.setPlaneDetails(path, details)
            assertEquals(details, core.planes()?.planes?.single { it.path == path }?.details)
        } finally {
            core.deletePlane(path)
        }
    }

    /**
     * The test flight (the l5 app's asset, test/data/01lz1hq1.igc)
     * among the recorded flights of the l5 app.
     */
    private fun copyTestFlight(): File {
        val app = ApplicationProvider.getApplicationContext<XcsoarApp>()
        val file = File(app.xcsoarDataDir, "logs/2010-01-21-TEST-01.igc")
        file.parentFile?.mkdirs()
        app.assets.open("01lz1hq1.igc").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return file
    }

    /** Past the first start's setup or the plane picker, to the flight screen. */
    private fun startFlightScreen() {
        if (exists(hasText("Set up later")))
            compose.onNodeWithText("Set up later").performClick()
        if (exists(hasText("Not now")))
            compose.onNodeWithText("Not now").performClick()
        waitFor("the core started", STARTUP_MS) {
            exists(hasText(text(R.string.flight_on_ground)))
        }
    }

    /** The app's text, in the phone's language. */
    private fun text(@StringRes id: Int) = compose.activity.getString(id)

    /** The app's text up to its first placeholder: "Wind from ". */
    private fun prefix(@StringRes id: Int) = text(id).substringBefore("%1\$")

    private fun exists(matcher: SemanticsMatcher) =
        compose.onAllNodes(matcher, useUnmergedTree = true)
            .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()

    /** The content descriptions starting with [prefix] now. */
    private fun descriptions(prefix: String): List<String> =
        compose.onAllNodes(hasContentDescription(prefix, substring = true),
                           useUnmergedTree = true)
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
            .flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
            .filter { it.startsWith(prefix) }

    private fun waitFor(what: String, timeoutMs: Long, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMs, condition)
        } catch (e: Throwable) {
            // what the flight screen showed instead
            val seen = (descriptions("Vario ") + descriptions("Wind from ") +
                        listOf("On ground", "Starting…", "NO GPS", "REPLAY")
                            .filter { exists(hasText(it)) }).joinToString(" | ")
            throw AssertionError("$what: not within ${timeoutMs / 1000} s; seen: $seen", e)
        }
    }

    private companion object {
        const val STARTUP_MS = 30_000L
        /** the replay runs at 10× and skips at ~80× */
        const val FLIGHT_MS = 120_000L
    }
}
