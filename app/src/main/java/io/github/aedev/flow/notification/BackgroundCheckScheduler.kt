package io.github.aedev.flow.notification

import android.content.Context
import io.github.aedev.flow.BuildConfig
import io.github.aedev.flow.data.local.PlayerPreferences
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keeps the periodic background checks in step with the notification switches, wherever they are
 * changed (settings, onboarding, a restored backup). A check whose notifications are off is
 * cancelled, so Flow is never woken for a result it would throw away.
 */
internal object BackgroundCheckScheduler {
    fun updateCheckWanted(
        notificationsEnabled: Boolean,
        updatesEnabled: Boolean,
    ): Boolean = notificationsEnabled && updatesEnabled

    fun subscriptionCheckWanted(
        notificationsEnabled: Boolean,
        newVideosEnabled: Boolean,
    ): Boolean = notificationsEnabled && newVideosEnabled

    /**
     * Runs for the life of the process. The first value only confirms the schedule a previous
     * process left (KEEP, so its timer is not reset); later changes replace it.
     */
    suspend fun follow(
        context: Context,
        preferences: PlayerPreferences,
    ) = coroutineScope {
        if (BuildConfig.UPDATER_ENABLED) {
            launch {
                combine(preferences.notificationsEnabled, preferences.notifUpdatesEnabled, ::updateCheckWanted)
                    .distinctUntilChanged()
                    .collectIndexed { index, wanted ->
                        if (wanted) {
                            UpdateCheckWorker.schedulePeriodicCheck(context, reschedule = index > 0)
                        } else {
                            UpdateCheckWorker.cancelScheduledChecks(context)
                        }
                    }
            }
        }
        launch {
            combine(preferences.notificationsEnabled, preferences.notifNewVideosEnabled, ::subscriptionCheckWanted)
                .distinctUntilChanged()
                .collectIndexed { index, wanted ->
                    if (wanted) {
                        SubscriptionCheckWorker.schedulePeriodicCheck(context, reschedule = index > 0)
                    } else {
                        SubscriptionCheckWorker.cancelScheduledChecks(context)
                    }
                }
        }
    }
}
