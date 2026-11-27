package io.github.shivytyahoo.glidetube.ui.screens.settings.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.shivytyahoo.glidetube.R
import io.github.shivytyahoo.glidetube.data.local.SettingsBackup
import io.github.shivytyahoo.glidetube.ui.components.settings.SettingsPage
import io.github.shivytyahoo.glidetube.ui.components.settings.group
import io.github.shivytyahoo.glidetube.ui.components.settings.nav
import io.github.shivytyahoo.glidetube.ui.components.shared.GlideAlertDialog
import io.github.shivytyahoo.glidetube.ui.screens.settings.index.BackupIndex
import kotlinx.coroutines.launch
import java.time.LocalDate

/** The whole settings state as one file, out and back in again. */
@Composable
internal fun SettingsBackupScreen(
    onBack: (() -> Unit)?,
    highlight: String?,
    viewModel: SettingsBackupViewModel = hiltViewModel(),
) {
    val covered by viewModel.coveredStores.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var pendingExport by remember { mutableStateOf<String?>(null) }
    var exportOutcome by remember { mutableStateOf<SettingsBackupViewModel.ExportOutcome?>(null) }
    var importOutcome by remember { mutableStateOf<SettingsBackup.ImportResult?>(null) }
    LaunchedEffect(Unit) { viewModel.refresh() }
    LaunchedEffect(viewModel) { viewModel.exportState.collect { exportOutcome = it } }
    LaunchedEffect(viewModel) { viewModel.importResult.collect { importOutcome = it } }

    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val text = pendingExport
            pendingExport = null
            if (uri == null || text == null) return@rememberLauncherForActivityResult
            viewModel.writeExport(uri, text)
        }
    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) viewModel.importFrom(uri)
        }

    SettingsPage(
        title = stringResource(R.string.settings_backup_title),
        onBack = onBack,
        highlight = highlight,
    ) {
        group(key = "backup.export", header = R.string.settings_backup_tab_export) {
            nav(
                BackupIndex.export,
                value = stringResource(R.string.backup_stores_covered, covered.size),
                onClick = {
                    scope.launch {
                        val text = viewModel.prepareExport()
                        if (text == null) {
                            exportOutcome = SettingsBackupViewModel.ExportOutcome.Failed
                        } else {
                            pendingExport = text
                            exportLauncher.launch("youglidetube-settings-${LocalDate.now()}.json")
                        }
                    }
                },
            )
        }
        group(key = "backup.import", header = R.string.settings_backup_tab_import) {
            nav(
                BackupIndex.import,
                value = stringResource(R.string.backup_preserved_note),
                onClick = { importLauncher.launch(arrayOf("application/json")) },
            )
        }
    }

    exportOutcome?.let { outcome ->
        GlideAlertDialog(
            onDismissRequest = { exportOutcome = null },
            title = { Text(stringResource(R.string.settings_backup_tab_export)) },
            text = {
                Text(
                    stringResource(
                        when (outcome) {
                            SettingsBackupViewModel.ExportOutcome.Saved -> R.string.settings_backup_exported
                            SettingsBackupViewModel.ExportOutcome.Failed -> R.string.settings_backup_export_failed
                        },
                    ),
                )
            },
            confirmButton = { TextButton(onClick = { exportOutcome = null }) { Text(stringResource(R.string.close)) } },
        )
    }
    importOutcome?.let { outcome ->
        GlideAlertDialog(
            onDismissRequest = { importOutcome = null },
            title = { Text(stringResource(R.string.settings_backup_tab_import)) },
            text = {
                Text(
                    when (outcome) {
                        is SettingsBackup.ImportResult.Restored -> {
                            stringResource(R.string.backup_imported, outcome.stores)
                        }

                        SettingsBackup.ImportResult.Invalid -> {
                            stringResource(R.string.backup_invalid)
                        }
                    },
                )
            },
            confirmButton = { TextButton(onClick = { importOutcome = null }) { Text(stringResource(R.string.close)) } },
        )
    }
}
