package io.github.aedev.flow.notification

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import io.github.aedev.flow.data.local.PlayerPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * The subscriptions check the app never had: [NotificationHelper]'s new-video
 * posts existed with no caller, so no subscription notification could ever
 * arrive. This worker runs the subscriptions feed in the background and
 * announces only the videos it has not seen before. The first non-empty page
 * only seeds the seen set — announcing the whole backlog on first run would
 * spam every video the account's feed already carries.
 */
class SubscriptionCheckWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    companion object {
        const val WORK_NAME = "subscription_check_work"
        private const val TAG = "SubscriptionCheckWorker"

        /** WorkManager rejects periodic work below 15 minutes, so a stale value clamps instead of crashing the scheduler. */
        private const val MIN_INTERVAL_MINUTES = 15L

        /** Schedules the check at [intervalMinutes]; [BackgroundCheckScheduler] decides whether it should run at all. */
        fun schedulePeriodicCheck(
            context: Context,
            reschedule: Boolean,
            intervalMinutes: Long,
        ) {
            val constraints =
                Constraints
                    .Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()

            val workRequest =
                PeriodicWorkRequestBuilder<SubscriptionCheckWorker>(
                    intervalMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES),
                    TimeUnit.MINUTES,
                ).setConstraints(constraints)
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        WorkRequest.MIN_BACKOFF_MILLIS,
                        TimeUnit.MILLISECONDS,
                    ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                periodicWorkPolicy(reschedule),
                workRequest,
            )
            Log.d(TAG, "Scheduled subscription check every $intervalMinutes minutes")
        }

        fun cancelScheduledChecks(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "Cancelled scheduled subscription checks")
        }
    }

    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val preferences = PlayerPreferences(applicationContext)
            if (!preferences.notificationsEnabled.first() || !preferences.notifNewVideosEnabled.first()) {
                Log.d(TAG, "Subscription notifications are off, skipping check")
                return@withContext Result.success()
            }
            val fresh = collectFreshSubscriptionVideos(applicationContext)
            if (fresh.isNotEmpty()) {
                NotificationHelper.showSubscriptionUpdates(applicationContext, fresh)
                Log.d(TAG, "Announced ${fresh.size} new subscription videos")
            }
            Result.success()
        }
}
