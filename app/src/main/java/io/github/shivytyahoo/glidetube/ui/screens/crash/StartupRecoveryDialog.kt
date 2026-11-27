package io.github.shivytyahoo.glidetube.ui.screens.crash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.shivytyahoo.glidetube.R
import io.github.shivytyahoo.glidetube.ui.components.shared.GlideAlertDialog

/**
 * The escape hatch after a launch died during startup: try again as-is, try again without the
 * startup warm-ups, or wipe the settings and start over. The dialog has two action slots, so
 * the destructive third action sits under the text instead of in the action row.
 */
@Composable
internal fun StartupRecoveryDialog(
    onContinue: () -> Unit,
    onSafeMode: () -> Unit,
    onResetSettings: () -> Unit,
) {
    GlideAlertDialog(
        onDismissRequest = onContinue,
        title = { Text(stringResource(R.string.startup_crashed_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.startup_crashed_text))
                TextButton(onClick = onResetSettings) {
                    Text(stringResource(R.string.startup_reset_settings), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton(onClick = onSafeMode) { Text(stringResource(R.string.startup_safe_mode)) } },
        dismissButton = { TextButton(onClick = onContinue) { Text(stringResource(R.string.startup_continue)) } },
    )
}
