// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.profiles

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.xcsoar.mobile.R
import org.xcsoar.mobile.core.ProfileInfo
import org.xcsoar.mobile.ui.ActionButton
import org.xcsoar.mobile.ui.PageLayout
import org.xcsoar.mobile.ui.ScreenHeader
import org.xcsoar.mobile.ui.SettingsGroup
import org.xcsoar.mobile.ui.UiText
import org.xcsoar.mobile.ui.theme.XcsTheme

/**
 * @param flying profiles switch on the ground only
 * @param onImport pick a .prf file (the result goes to the view model)
 */
@Composable
fun ProfilesScreen(
    viewModel: ProfilesViewModel,
    flying: Boolean,
    onImport: () -> Unit,
    onBack: () -> Unit,
) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }
    BackHandler(onBack = onBack)

    // what the pilot is about to do (protected profiles: with the password)
    var switching by remember { mutableStateOf<ProfileInfo?>(null) }
    var deleting by remember { mutableStateOf<ProfileInfo?>(null) }
    var creating by remember { mutableStateOf(false) }

    ProfilesContent(profiles, error, flying, onUse = { switching = it },
                    onDelete = { deleting = it }, onNew = { creating = true },
                    onImport = onImport, onBack = onBack)

    switching?.let { profile ->
        ConfirmDialog(
            title = stringResource(R.string.profiles_fly_with, profile.name),
            text = stringResource(R.string.profiles_switch_text,
                                  profiles?.firstOrNull { it.current }?.name
                                      ?: stringResource(R.string.profiles_this_profile),
                                  profile.name),
            confirm = stringResource(R.string.profiles_restart),
            password = profile.password,
            check = { viewModel.checkPassword(profile, it) },
            onConfirm = {
                switching = null
                viewModel.use(profile)
            },
            onDismiss = { switching = null },
        )
    }
    deleting?.let { profile ->
        ConfirmDialog(
            title = stringResource(R.string.profiles_delete_title, profile.name),
            text = stringResource(R.string.profiles_delete_text),
            confirm = stringResource(R.string.flights_delete),
            password = profile.password,
            check = { viewModel.checkPassword(profile, it) },
            onConfirm = { password ->
                deleting = null
                viewModel.delete(profile, password)
            },
            onDismiss = { deleting = null },
        )
    }
    if (creating)
        NewProfileDialog(
            current = profiles?.firstOrNull { it.current }?.name,
            onCreate = { name, copy ->
                creating = false
                viewModel.create(name, copy)
            },
            onDismiss = { creating = false },
        )
}

/**
 * The profiles, the one in use first marked; Use and Delete for the
 * others.
 */
@Composable
fun ProfilesContent(
    profiles: List<ProfileInfo>?,
    error: UiText?,
    flying: Boolean,
    onUse: (ProfileInfo) -> Unit,
    onDelete: (ProfileInfo) -> Unit,
    onNew: () -> Unit,
    onImport: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = XcsTheme.colors
    PageLayout {
        ScreenHeader(stringResource(R.string.profiles_title), onBack)
        Text(stringResource(R.string.profiles_intro),
             color = colors.textSecondary, fontSize = 15.sp,
             modifier = Modifier.padding(horizontal = 4.dp))
        if (flying)
            Text(stringResource(R.string.profiles_ground),
                 color = colors.caution, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                 modifier = Modifier.padding(horizontal = 4.dp))

        SettingsGroup {
            profiles.orEmpty().sortedByDescending { it.current }.forEachIndexed { i, profile ->
                if (i > 0) HorizontalDivider(color = colors.panelBorder)
                ProfileRow(profile, canSwitch = !flying, onUse = { onUse(profile) },
                           onDelete = { onDelete(profile) })
            }
        }

        error?.let { Text(it.text(), color = colors.warning, fontSize = 16.sp) }

        ActionButton(stringResource(R.string.profiles_new), Modifier.fillMaxWidth(), outlined = true, onClick = onNew)
        ActionButton(stringResource(R.string.profiles_import), Modifier.fillMaxWidth(), outlined = true,
                     onClick = onImport)
    }
}

@Composable
private fun ProfileRow(profile: ProfileInfo, canSwitch: Boolean, onUse: () -> Unit,
                       onDelete: () -> Unit) {
    val colors = XcsTheme.colors
    Row(Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(profile.name, color = colors.text, fontSize = 17.sp,
                 fontWeight = FontWeight.SemiBold, maxLines = 1,
                 overflow = TextOverflow.Ellipsis)
            val inUse = stringResource(R.string.profiles_in_use)
            val protected = stringResource(R.string.profiles_protected)
            val detail = listOfNotNull(inUse.takeIf { profile.current },
                                       protected.takeIf { profile.password })
            if (detail.isNotEmpty())
                Text(detail.joinToString(" · "), color = colors.textSecondary, fontSize = 14.sp)
        }
        if (!profile.current) {
            ActionButton(stringResource(R.string.flights_delete), onClick = onDelete)
            ActionButton(stringResource(R.string.profiles_use), primary = true, enabled = canSwitch, onClick = onUse)
        }
    }
}

/**
 * Confirm an action on a profile; a protected one asks for its
 * password first, checked before [onConfirm].
 */
@Composable
private fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    password: Boolean,
    check: suspend (String) -> Boolean,
    onConfirm: (password: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var typed by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text)
                if (password) {
                    OutlinedTextField(typed, { typed = it; wrong = false }, singleLine = true,
                                      label = { Text(stringResource(R.string.track_password)) },
                                      visualTransformation = PasswordVisualTransformation(),
                                      keyboardOptions = KeyboardOptions(
                                          keyboardType = KeyboardType.Password),
                                      isError = wrong)
                    if (wrong) Text(stringResource(R.string.profiles_wrong_password), color = XcsTheme.colors.warning)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (!password) onConfirm(null)
                else scope.launch {
                    if (check(typed)) onConfirm(typed) else wrong = true
                }
            }) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun NewProfileDialog(current: String?, onCreate: (String, Boolean) -> Unit,
                             onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var copy by remember { mutableStateOf(current != null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profiles_new)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, singleLine = true,
                                  label = { Text(stringResource(R.string.waypoints_name)) })
                if (current != null)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(copy, { copy = it })
                        Text(stringResource(R.string.profiles_copy, current))
                    }
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name, copy) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.profiles_create))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun ProfilesPreview() {
    XcsTheme(dark = false) {
        ProfilesContent(listOf(
            ProfileInfo("Club", "/x/profiles/Club.prf", password = true),
            ProfileInfo("default", "/x/profiles/default.prf", current = true),
        ), null, flying = false, {}, {}, {}, {}, {})
    }
}
