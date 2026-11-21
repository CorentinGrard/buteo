// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.settings

import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.xcsoar.mobile.core.SoundOption
import org.xcsoar.mobile.ui.flight.CalloutSettings
import org.xcsoar.mobile.ui.theme.XcsTheme

/**
 * L4: the Sound page, the vario tone then the voice callouts
 * (mobile/docs/ARCHITECTURE.md §6); as tall as the whole page.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w390dp-h1500dp-xhdpi")
class SoundScreenshotTest {
    private val vario = mapOf(
        SoundOption.VARIO to 1, SoundOption.VARIO_VOLUME to 80,
        SoundOption.VARIO_SWITCHING to 0, SoundOption.VARIO_DEAD_BAND to 1,
        SoundOption.VARIO_DEAD_BAND_MIN to -50, SoundOption.VARIO_DEAD_BAND_MAX to 50)

    @Test
    fun sound() = captureRoboImage("src/test/screenshots/sound.png") {
        XcsTheme(dark = false) {
            SoundContent(vario, { _, _ -> }, CalloutSettings(climbs = false),
                         voiceAvailable = true, {}, {}, {})
        }
    }

    /* no vario output, no text-to-speech voice */
    @Test
    fun nothingToPlay() = captureRoboImage("src/test/screenshots/sound_unavailable.png") {
        XcsTheme(dark = true) {
            SoundContent(emptyMap(), { _, _ -> }, CalloutSettings(), voiceAvailable = false,
                         {}, {}, {})
        }
    }
}
