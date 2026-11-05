package io.github.aedev.flow.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.aedev.flow.data.local.LocalDataManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val type = intent.getStringExtra("type") ?: return
        
        when (type) {
            "bedtime" -> {
                NotificationHelper.showReminderNotification(
                    context,
                    "It's bedtime! 😴",
                    "Time to wind down based on your schedule."
                )
                // One-shot by construction: re-arm tomorrow's alarm while still enabled.
                runCatching {
                    runBlocking {
                        val data = LocalDataManager(context.applicationContext)
                        if (data.bedtimeReminder.first()) {
                            ReminderManager.scheduleBedtimeReminder(
                                context,
                                data.bedtimeStartHour.first(),
                                data.bedtimeStartMinute.first(),
                            )
                        }
                    }
                }
            }
            "break" -> {
                 NotificationHelper.showReminderNotification(
                    context,
                    "Take a break! ☕",
                    "You've been watching for a while."
                )
                
                // Reschedule if it's a repeating break reminder
                val frequency = intent.getIntExtra("frequency", -1)
                if (frequency > 0) {
                    ReminderManager.scheduleBreakReminder(context, frequency)
                }
            }
        }
    }
}
