package io.github.shivytyahoo.glidetube.ui.screens.settings.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.shivytyahoo.glidetube.BuildConfig
import io.github.shivytyahoo.glidetube.data.local.PlayerPreferences
import io.github.shivytyahoo.glidetube.data.update.AppRelease
import io.github.shivytyahoo.glidetube.data.update.UpdateRepository
import io.github.shivytyahoo.glidetube.player.DeepGlideManager
import io.github.shivytyahoo.glidetube.ui.components.shared.DeepGlideState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The update check the settings list runs on request; github builds only. */
sealed interface UpdateCheckState {
    data object Idle : UpdateCheckState

    data object Checking : UpdateCheckState

    data object UpToDate : UpdateCheckState

    data object Failed : UpdateCheckState

    data class Available(
        val release: AppRelease,
    ) : UpdateCheckState
}

@HiltViewModel
class SettingsHomeViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val playerPreferences: PlayerPreferences,
        private val updates: UpdateRepository,
    ) : ViewModel() {
        val deepGlide: StateFlow<DeepGlideState> =
            combine(
                playerPreferences.deepGlideActive,
                playerPreferences.deepGlideActivatedAt,
                playerPreferences.deepGlideExpireHours,
                playerPreferences.deepGlideSaveToHistory,
                playerPreferences.deepGlideScrobble,
            ) { active, activatedAt, expireHours, saveToHistory, scrobble ->
                DeepGlideState(active, activatedAt, expireHours, saveToHistory, scrobble)
            }.combine(playerPreferences.deepGlideOnSearch) { state, onSearch ->
                state.copy(showOnSearch = onSearch)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DeepGlideState())


        val batterySaver: StateFlow<Boolean> =
            playerPreferences.batterySaverEnabled
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

        private val _updateCheck = MutableStateFlow<UpdateCheckState>(UpdateCheckState.Idle)
        val updateCheck: StateFlow<UpdateCheckState> = _updateCheck.asStateFlow()

        fun setBatterySaverEnabled(enabled: Boolean) {
            viewModelScope.launch { playerPreferences.setBatterySaverEnabled(enabled) }
        }

        fun setDeepGlideEnabled(enabled: Boolean) {
            viewModelScope.launch { DeepGlideManager.setEnabled(context, enabled) }
        }

        fun setDeepGlideExpireHours(hours: Int) {
            viewModelScope.launch { playerPreferences.setDeepGlideExpireHours(hours) }
        }

        fun setDeepGlideSaveToHistory(enabled: Boolean) {
            viewModelScope.launch { playerPreferences.setDeepGlideSaveToHistory(enabled) }
        }

        fun setDeepGlideScrobble(enabled: Boolean) {
            viewModelScope.launch { playerPreferences.setDeepGlideScrobble(enabled) }
        }

        fun setDeepGlideOnSearch(enabled: Boolean) {
            viewModelScope.launch { playerPreferences.setDeepGlideOnSearch(enabled) }
        }

        fun checkForUpdates() {
            if (!BuildConfig.UPDATER_ENABLED || _updateCheck.value == UpdateCheckState.Checking) return
            _updateCheck.value = UpdateCheckState.Checking
            viewModelScope.launch {
                val result =
                    runCatching { updates.fetch() }
                        .fold(
                            onSuccess = { info -> info?.let { UpdateCheckState.Available(it) } ?: UpdateCheckState.UpToDate },
                            // A failed check is a failure, not "up to date": the
                            // Failed state already has its own snackbar wired.
                            onFailure = { UpdateCheckState.Failed },
                        )
                _updateCheck.value = result
            }
        }

        fun consumeUpdateCheck() {
            _updateCheck.update { if (it == UpdateCheckState.Checking) it else UpdateCheckState.Idle }
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
