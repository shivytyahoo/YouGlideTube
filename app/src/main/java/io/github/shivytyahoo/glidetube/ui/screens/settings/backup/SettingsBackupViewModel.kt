package io.github.shivytyahoo.glidetube.ui.screens.settings.backup

import android.content.Context
import android.net.Uri
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.shivytyahoo.glidetube.data.local.SettingsBackup
import io.github.shivytyahoo.glidetube.ui.screens.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

/** Export the settings to a file, and import them back. */
@HiltViewModel
class SettingsBackupViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val backup: SettingsBackup,
    ) : SettingsViewModel() {
        sealed interface ExportOutcome {
            data object Saved : ExportOutcome

            data object Failed : ExportOutcome
        }

        private val _coveredStores = MutableStateFlow(backup.coveredStores())
        val coveredStores: StateFlow<List<String>> = _coveredStores.asStateFlow()

        private val _exportState = MutableSharedFlow<ExportOutcome>(extraBufferCapacity = 1)
        val exportState: SharedFlow<ExportOutcome> = _exportState.asSharedFlow()

        private val _importResult = MutableSharedFlow<SettingsBackup.ImportResult>(extraBufferCapacity = 1)
        val importResult: SharedFlow<SettingsBackup.ImportResult> = _importResult.asSharedFlow()

        /** Stores opened since the page was last shown join the export. */
        fun refresh() {
            _coveredStores.value = backup.coveredStores()
        }

        /** The export text, ready for the file picker; null when it could not be built. */
        suspend fun prepareExport(): String? = runCatching { backup.export() }.getOrNull()

        /** Writes prepared [text] to the file the viewer chose. */
        fun writeExport(
            uri: Uri,
            text: String,
        ) {
            viewModelScope.launch {
                val saved =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            context.contentResolver.openOutputStream(uri, "w")?.use {
                                it.write(text.toByteArray(Charsets.UTF_8))
                            } ?: throw IOException("No stream for $uri")
                        }.isSuccess
                    }
                _exportState.tryEmit(if (saved) ExportOutcome.Saved else ExportOutcome.Failed)
            }
        }

        /** Reads the file the viewer chose and imports it when it is a backup of this app. */
        fun importFrom(uri: Uri) {
            viewModelScope.launch {
                val text =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            context.contentResolver.openInputStream(uri)?.use {
                                it.readBytes().toString(Charsets.UTF_8)
                            }
                        }.getOrNull()
                    }
                val result = text?.let { backup.import(it) } ?: SettingsBackup.ImportResult.Invalid
                _importResult.tryEmit(result)
            }
        }
    }
