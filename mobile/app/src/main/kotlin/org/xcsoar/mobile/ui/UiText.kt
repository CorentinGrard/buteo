// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/**
 * A message the logic makes and a screen shows: a text of
 * `res/values/strings.xml` with its arguments, so it comes out in the
 * phone's language when it is shown, not when it is made
 * (mobile/docs/DECISIONS.md D28).  [Plain] is a text that is no
 * message of ours: a file name, what the core or the network said.
 */
sealed interface UiText {
    data class Plain(val text: String) : UiText

    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    /** An argument may itself be a [UiText]: "Not created: <why>". */
    fun resolve(context: Context): String = when (this) {
        is Plain -> text
        is Res -> context.getString(
            id, *args.map { if (it is UiText) it.resolve(context) else it }.toTypedArray())
    }

    /** In a composable: follows the language the screen is in. */
    @Composable
    fun text(): String {
        LocalConfiguration.current  // recompose when the language changes
        return resolve(LocalContext.current)
    }
}

/** `text(R.string.task_full)`, `text(R.string.saved_as, name)`. */
fun text(@StringRes id: Int, vararg args: Any): UiText = UiText.Res(id, args.toList())

fun String.asText(): UiText = UiText.Plain(this)

/**
 * A failure with a message for the pilot, for the code that has no
 * [Context] (the downloader): [message] is English, for the log.
 */
class MessageException(val text: UiText, message: String) : java.io.IOException(message)

/** What to tell the pilot about a failure: ours translated, others as they say. */
fun Throwable.toText(): UiText =
    (this as? MessageException)?.text ?: (message ?: toString()).asText()
