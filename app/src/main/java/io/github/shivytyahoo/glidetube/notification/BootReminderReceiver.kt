package io.github.shivytyahoo.glidetube.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.shivytyahoo.glidetube.data.local.LocalDataManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** Restores the enabled reminders after a reboot: alarms do not survive one. */
class BootReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
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
                if (data.breakReminder.first()) {
                    ReminderManager.scheduleBreakReminder(context, data.breakFrequency.first())
                }
            }
        }
    }
}
