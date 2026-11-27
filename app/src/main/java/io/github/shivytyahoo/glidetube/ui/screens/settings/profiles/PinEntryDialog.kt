package io.github.shivytyahoo.glidetube.ui.screens.settings.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.shivytyahoo.glidetube.R
import io.github.shivytyahoo.glidetube.ui.components.shared.GlideAlertDialog

/** Four digits, hidden as typed. [error] is shown under the field, such as a wrong PIN. */
@Composable
internal fun PinEntryDialog(
    title: String,
    error: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    onRemovePin: (() -> Unit)? = null,
) {
    var pin by remember { mutableStateOf("") }
    GlideAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { value -> if (value.length <= 4 && value.all { it.isDigit() }) pin = value },
                    label = { Text(stringResource(R.string.profiles_pin_hint)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                onRemovePin?.let { remove ->
                    TextButton(onClick = remove) {
                        Text(stringResource(R.string.profiles_remove_pin), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = pin.length == 4, onClick = { onConfirm(pin) }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
