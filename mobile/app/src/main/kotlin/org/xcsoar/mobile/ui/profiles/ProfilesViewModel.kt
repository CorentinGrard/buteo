// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.xcsoar.mobile.R
import org.xcsoar.mobile.core.ProfileInfo
import org.xcsoar.mobile.core.XcsoarCore
import org.xcsoar.mobile.ui.UiText
import org.xcsoar.mobile.ui.text
import org.xcsoar.mobile.ui.toText

/**
 * XCSoar's profiles: list, create, copy, import, delete, and switch
 * (which restarts the app with the other profile).
 *
 * @param importFile copy a picked document (URI) into XCSoarData/profiles
 * @param switch save the profile in use and restart with [ProfileInfo.path]
 */
class ProfilesViewModel(
    private val core: XcsoarCore,
    private val importFile: suspend (String) -> String,
    private val switch: suspend (String) -> Unit,
) : ViewModel() {
    private val profilesFlow = MutableStateFlow<List<ProfileInfo>?>(null)
    /** `null` while loading. */
    val profiles: StateFlow<List<ProfileInfo>?> = profilesFlow.asStateFlow()

    private val errorFlow = MutableStateFlow<UiText?>(null)
    /** Why the last action failed; cleared by the next one. */
    val error: StateFlow<UiText?> = errorFlow.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            profilesFlow.value = try {
                core.profiles()
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    /** The profile asks for a password before [use] or [delete]. */
    suspend fun checkPassword(profile: ProfileInfo, password: String): Boolean =
        try {
            core.checkProfilePassword(profile.path, password)
        } catch (_: Exception) {
            false
        }

    /** Fly with [profile] from now on: the app restarts with it. */
    fun use(profile: ProfileInfo) = act(text(R.string.profiles_cannot_switch)) { switch(profile.path) }

    /**
     * A new profile [name]: XCSoar's defaults, or a copy of the profile
     * in use.
     */
    fun create(name: String, copyCurrent: Boolean) =
        act(text(R.string.profiles_not_created, name.trim())) {
            val current = profilesFlow.value?.firstOrNull { it.current }
            core.createProfile(name.trim(), current?.path.takeIf { copyCurrent })
        }

    fun import(uri: String) = act(text(R.string.profiles_not_imported), showCause = true) { importFile(uri) }

    fun delete(profile: ProfileInfo, password: String? = null) =
        act(text(R.string.profiles_not_deleted, profile.name)) { core.deleteProfile(profile.path, password) }

    /** @param showCause add the exception's message (ours, not the core's) */
    private fun act(failure: UiText, showCause: Boolean = false, block: suspend () -> Unit) {
        errorFlow.value = null
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                errorFlow.value = e.takeIf { showCause }?.toText()
                    ?.let { text(R.string.error_with_cause, failure, it) } ?: failure
            }
            refresh()
        }
    }
}
