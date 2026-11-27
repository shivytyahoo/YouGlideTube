package io.github.shivytyahoo.glidetube.ui.screens.settings.profiles

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import io.github.shivytyahoo.glidetube.R
import io.github.shivytyahoo.glidetube.data.local.PersonProfile
import io.github.shivytyahoo.glidetube.data.local.PersonProfilesState
import io.github.shivytyahoo.glidetube.ui.components.shared.GlideAlertDialog
import io.github.shivytyahoo.glidetube.ui.components.shared.GlideNavRow

/**
 * Who's watching, asked on open while more than one person exists. A person with a PIN asks
 * for it first; the active person just continues. Dismissing is continuing as the active person.
 */
@Composable
internal fun PersonGateDialog(
    state: PersonProfilesState,
    onPick: (PersonProfile) -> Unit,
    onContinue: () -> Unit,
    checkPin: (PersonProfile, String) -> Boolean,
) {
    var pinTarget by remember { mutableStateOf<PersonProfile?>(null) }
    var pinError by remember { mutableStateOf(false) }
    val target = pinTarget
    if (target != null) {
        PinEntryDialog(
            title = stringResource(R.string.profiles_enter_pin, target.name),
            error = if (pinError) stringResource(R.string.profiles_wrong_pin) else null,
            onConfirm = { pin ->
                if (checkPin(target, pin)) {
                    onPick(target)
                } else {
                    pinError = true
                }
            },
            onDismiss = {
                pinTarget = null
                pinError = false
            },
        )
        return
    }
    GlideAlertDialog(
        onDismissRequest = onContinue,
        title = { Text(stringResource(R.string.profiles_who_watching)) },
        text = {
            Column {
                state.persons.forEach { person ->
                    GlideNavRow(
                        title = person.name,
                        leadingIcon = Icons.Outlined.Person,
                        showChevron = false,
                        onClick = {
                            if (person.pinHash != null) {
                                pinTarget = person
                            } else {
                                onPick(person)
                            }
                        },
                        trailingContent =
                            if (person.id == state.activeId) {
                                { Icon(Icons.Filled.Check, contentDescription = null) }
                            } else {
                                null
                            },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onContinue) { Text(stringResource(R.string.startup_continue)) } },
    )
}
