package io.github.aedev.flow.ui.screens.notifications

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.notification.InboxNotification
import io.github.aedev.flow.notification.NotificationHelper
import io.github.aedev.flow.notification.collectFreshSubscriptionVideos
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
internal class NotificationsViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : ViewModel() {
        private val preferences = PlayerPreferences(context)

        val entries: StateFlow<List<InboxNotification>> =
            preferences.inboxNotifications.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                emptyList(),
            )

        private val _isRefreshing = MutableStateFlow(false)
        val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

        /** Last time the section checked for new videos on its own; opening it refreshes at most this often. */
        private var lastAutoRefreshMs = 0L
        private val autoRefreshMinIntervalMs = 5L * 60 * 1000

        /** Tapping a notification to watch it is what clears its badge — opening the section does not. */
        fun markRead(videoId: String) {
            viewModelScope.launch {
                preferences.markInboxRead(videoId)
                NotificationHelper.syncSubscriptionSummary(context)
            }
        }

        fun dismiss(videoId: String) {
            viewModelScope.launch { NotificationHelper.dismissInboxVideo(context, videoId) }
        }

        fun clearAll() {
            viewModelScope.launch { NotificationHelper.clearInboxAndTray(context) }
        }

        /** Opening the section fetches fresh videos itself, so the bell is live without a manual pull. */
        fun refreshIfStale() {
            val now = System.currentTimeMillis()
            if (now - lastAutoRefreshMs < autoRefreshMinIntervalMs) return
            lastAutoRefreshMs = now
            refresh()
        }

        fun refresh() {
            if (_isRefreshing.value) return
            viewModelScope.launch {
                // Same gates the worker applies: a manual refresh must not fill
                // the inbox while the master switch or New Videos is off.
                if (!preferences.notificationsEnabled.first() || !preferences.notifNewVideosEnabled.first()) return@launch
                _isRefreshing.value = true
                runCatching { collectFreshSubscriptionVideos(context) }
                // The inbox changed, so the tray summary (and the launcher badge) must follow.
                runCatching { NotificationHelper.syncSubscriptionSummary(context) }
                _isRefreshing.value = false
            }
        }
    }
