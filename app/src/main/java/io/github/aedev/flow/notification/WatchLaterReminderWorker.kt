package io.github.aedev.flow.notification

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Fires when a video the user saved to Watch Later while it was still upcoming
 * finally premieres. The post itself goes through
 * [NotificationHelper.showWatchLaterReminder], so the General Notifications
 * setting still decides at fire time whether anything shows.
 */
class WatchLaterReminderWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val videoId = inputData.getString(KEY_VIDEO_ID).orEmpty()
        val title = inputData.getString(KEY_TITLE).orEmpty()
        val thumbnailUrl = inputData.getString(KEY_THUMBNAIL_URL)
        if (videoId.isBlank() || title.isBlank()) return Result.failure()
        NotificationHelper.showWatchLaterReminder(applicationContext, title, videoId, thumbnailUrl)
        return Result.success()
    }

    companion object {
        private const val KEY_VIDEO_ID = "video_id"
        private const val KEY_TITLE = "title"
        private const val KEY_THUMBNAIL_URL = "thumbnail_url"

        fun scheduleReminder(
            context: Context,
            videoId: String,
            releaseTimeMs: Long,
            title: String,
            thumbnailUrl: String?,
        ) {
            val delayMs = (releaseTimeMs - System.currentTimeMillis()).coerceAtLeast(0L)
            val inputData =
                Data.Builder()
                    .putString(KEY_VIDEO_ID, videoId)
                    .putString(KEY_TITLE, title)
                    .putString(KEY_THUMBNAIL_URL, thumbnailUrl)
                    .build()
            val request =
                OneTimeWorkRequestBuilder<WatchLaterReminderWorker>()
                    .setInputData(inputData)
                    .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                    .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueWorkName(videoId),
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        fun cancelReminder(context: Context, videoId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(uniqueWorkName(videoId))
        }

        private fun uniqueWorkName(videoId: String): String = "watch-later-reminder-$videoId"
    }
}
