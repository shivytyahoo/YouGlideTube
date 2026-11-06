package io.github.aedev.flow.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Swiping a subscription notification away in the tray clears it from the inbox too, so the
 * launcher badge and the in-app bell always show the same count — and that count only drops
 * when the user clears something, never on its own.
 */
class NotificationDismissReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_CLEAR_INBOX = "io.github.aedev.flow.action.CLEAR_INBOX"
        const val ACTION_DISMISS_VIDEO = "io.github.aedev.flow.action.DISMISS_NOTIFICATION_VIDEO"
        const val EXTRA_VIDEO_ID = "video_id"
        private const val TAG = "NotifDismissReceiver"
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_CLEAR_INBOX -> NotificationHelper.clearInboxAndTray(context)
                    ACTION_DISMISS_VIDEO -> {
                        val videoId = intent.getStringExtra(EXTRA_VIDEO_ID)
                        if (!videoId.isNullOrBlank()) {
                            NotificationHelper.dismissInboxVideo(context, videoId)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear inbox from tray dismiss: ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }
    }
}
