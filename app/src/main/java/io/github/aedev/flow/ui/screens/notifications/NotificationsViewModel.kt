package io.github.aedev.flow.ui.screens.notifications

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.notification.InboxNotification
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

        fun markAllRead() {
            viewModelScope.launch { preferences.markInboxAllRead() }
        }

        fun dismiss(videoId: String) {
            viewModelScope.launch { preferences.removeInboxNotification(videoId) }
        }

        fun clearAll() {
            viewModelScope.launch { preferences.clearInboxNotifications() }
        }

        fun refresh() {
            if (_isRefreshing.value) return
            viewModelScope.launch {
                // Same gates the worker applies: a manual refresh must not fill
                // the inbox while the master switch or New Videos is off.
                if (!preferences.notificationsEnabled.first() || !preferences.notifNewVideosEnabled.first()) return@launch
                _isRefreshing.value = true
                runCatching { collectFreshSubscriptionVideos(context) }
                _isRefreshing.value = false
            }
        }
    }
