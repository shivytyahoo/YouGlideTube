package io.github.aedev.flow.ui.screens.settings.diagnostics

import android.content.Context
import android.net.Uri
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.player.stream.ResetYouTubeSession
import io.github.aedev.flow.ui.screens.settings.SettingsViewModel
import io.github.aedev.flow.utils.DeviceInfo
import io.github.aedev.flow.utils.FlowDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class DiagnosticsViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val preferences: PlayerPreferences,
        private val resetYouTubeSession: ResetYouTubeSession,
    ) : SettingsViewModel() {
        val device: DeviceInfo by lazy { FlowDiagnostics.deviceInfo(context) }

        private val _session = MutableStateFlow<LogState>(LogState.Loading)
        private val _crashes = MutableStateFlow<LogState>(LogState.Loading)
        val session: StateFlow<LogState> = _session.asStateFlow()
        val crashes: StateFlow<LogState> = _crashes.asStateFlow()

        private var sessionText = ""

        init {
            viewModelScope.launch(Dispatchers.IO) {
                sessionText = FlowDiagnostics.readSessionLogs()
                _session.value = parseLog(sessionText, ::logcatLevel)
                _crashes.value = parseLog(FlowDiagnostics.crashLogsOrNull(context), ::crashLevel)
            }
        }

        suspend fun report(): String = withContext(Dispatchers.IO) { FlowDiagnostics.buildFullReport(context, sessionText) }

        fun clearCrashes() =
            write {
                withContext(Dispatchers.IO) { FlowDiagnostics.clearCrashLogs(context) }
                _crashes.value = LogState.Empty
            }

        suspend fun resetSession() = resetYouTubeSession()
    }
