package io.github.shivytyahoo.glidetube.ui.screens.settings.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.shivytyahoo.glidetube.R
import io.github.shivytyahoo.glidetube.data.local.PersonProfile
import io.github.shivytyahoo.glidetube.ui.components.settings.SettingsPage
import io.github.shivytyahoo.glidetube.ui.components.settings.group
import io.github.shivytyahoo.glidetube.ui.components.settings.nav
import io.github.shivytyahoo.glidetube.ui.components.settings.row
import io.github.shivytyahoo.glidetube.ui.components.settings.switch
import io.github.shivytyahoo.glidetube.ui.components.shared.GlideAlertDialog
import io.github.shivytyahoo.glidetube.ui.components.shared.GlideNavRow
import io.github.shivytyahoo.glidetube.ui.screens.settings.index.ProfilesIndex

private enum class PinPurpose { SWITCH, SET }

/**
 * People on this device. A row tap switches to that person (their PIN first, when they have
 * one); the lock sets or changes the PIN, the bin removes the person and their settings.
 * Downloads, history and the library stay shared between people.
 */
@Composable
internal fun PersonProfilesScreen(
    onBack: (() -> Unit)?,
    highlight: String?,
    viewModel: PersonProfilesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var addOpen by remember { mutableStateOf(false) }
    var pinFor by remember { mutableStateOf<PersonProfile?>(null) }
    var pinPurpose by remember { mutableStateOf(PinPurpose.SET) }
    var pinError by remember { mutableStateOf(false) }
    var removeFor by remember { mutableStateOf<PersonProfile?>(null) }

    SettingsPage(
        title = stringResource(R.string.settings_profiles_title),
        onBack = onBack,
        highlight = highlight,
    ) {
        group(key = "profiles.people", header = R.string.settings_profiles_title, footer = R.string.profiles_shared_note) {
            state.persons.forEach { person ->
                row("profiles.person_${person.id}") { shape ->
                    GlideNavRow(
                        title = person.name,
                        leadingIcon = Icons.Outlined.Person,
                        showChevron = false,
                        onClick = {
                            if (person.pinHash != null) {
                                pinFor = person
                                pinPurpose = PinPurpose.SWITCH
                                pinError = false
                            } else {
                                viewModel.pickPerson(person)
                            }
                        },
                        shape = shape,
                        trailingContent = {
                            Row {
                                if (person.id == state.activeId) {
                                    Icon(Icons.Filled.Check, contentDescription = null)
                                }
                                IconButton(
                                    onClick = {
                                        pinFor = person
                                        pinPurpose = PinPurpose.SET
                                        pinError = false
                                    },
                                ) {
                                    Icon(
                                        if (person.pinHash != null) Icons.Filled.Lock else Icons.Outlined.Lock,
                                        contentDescription =
                                            stringResource(
                                                if (person.pinHash != null) R.string.profiles_change_pin else R.string.profiles_set_pin,
                                            ),
                                    )
                                }
                                IconButton(onClick = { removeFor = person }) {
                                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.delete))
                                }
                            }
                        },
                    )
                }
            }
            nav(ProfilesIndex.addPerson, onClick = { addOpen = true })
        }
        group(key = "profiles.options") {
            switch(ProfilesIndex.askOnOpen, checked = state.askOnOpen, onCheckedChange = viewModel::setAskOnOpen)
        }
    }

    if (addOpen) {
        AddPersonDialog(
            onConfirm = { name ->
                viewModel.addPerson(name)
                addOpen = false
            },
            onDismiss = { addOpen = false },
        )
    }
    pinFor?.let { person ->
        PinEntryDialog(
            title =
                when (pinPurpose) {
                    PinPurpose.SWITCH -> {
                        stringResource(R.string.profiles_enter_pin, person.name)
                    }

                    PinPurpose.SET -> {
                        stringResource(
                            if (person.pinHash != null) R.string.profiles_change_pin else R.string.profiles_set_pin,
                        )
                    }
                },
            error = if (pinError) stringResource(R.string.profiles_wrong_pin) else null,
            onConfirm = { pin ->
                when (pinPurpose) {
                    PinPurpose.SWITCH -> {
                        if (viewModel.checkPin(person, pin)) {
                            pinFor = null
                            viewModel.pickPerson(person)
                        } else {
                            pinError = true
                        }
                    }

                    PinPurpose.SET -> {
                        viewModel.setPin(person.id, pin)
                        pinFor = null
                    }
                }
            },
            onDismiss = { pinFor = null },
            onRemovePin =
                if (pinPurpose == PinPurpose.SET && person.pinHash != null) {
                    {
                        viewModel.setPin(person.id, null)
                        pinFor = null
                    }
                } else {
                    null
                },
        )
    }
    removeFor?.let { person ->
        GlideAlertDialog(
            onDismissRequest = { removeFor = null },
            title = { Text(person.name) },
            text = { Text(stringResource(R.string.profiles_remove_confirm, person.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        removeFor = null
                        viewModel.removePerson(person.id)
                    },
                ) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { removeFor = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** A name, and nothing else: the new person starts with the settings on screen now. */
@Composable
private fun AddPersonDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }
    GlideAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profiles_add_person)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        showError = false
                    },
                    label = { Text(stringResource(R.string.profiles_name_hint)) },
                    singleLine = true,
                )
                if (showError) {
                    Text(stringResource(R.string.profiles_enter_name), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isBlank()) {
                        showError = true
                    } else {
                        onConfirm(name)
                    }
                },
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
