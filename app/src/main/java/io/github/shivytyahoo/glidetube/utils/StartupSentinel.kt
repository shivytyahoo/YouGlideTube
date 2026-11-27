package io.github.shivytyahoo.glidetube.utils

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.os.Process
import androidx.datastore.preferences.core.edit
import io.github.shivytyahoo.glidetube.data.local.DataStoreRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A launch that never reaches the first screen leaves its mark behind: the next launch sees the
 * mark and offers safe mode instead of crashing the same way again. The mark is a synchronous
 * SharedPreferences commit — a crash cannot swallow it the way it could an async write — and it
 * is cleared the moment the first screen is known, so a crash later in the session is not
 * mistaken for a startup crash.
 */
object StartupSentinel {
    private const val PREFS_NAME = "startup_sentinel"
    private const val KEY_LAUNCH_STARTED = "launch_started"
    private const val KEY_SAFE_MODE = "safe_mode"
    private const val LANGUAGE_PREFS_NAME = "flow_language_prefs"

    /** Set at the start of MainActivity.onCreate; cleared when the first screen is known. */
    fun markLaunchStarted(context: Context) {
        prefs(context).edit().putBoolean(KEY_LAUNCH_STARTED, true).commit()
    }

    /**
     * Cleared at every point the first screen is known to have mounted — including when the
     * recovery dialog itself mounts, which is proof the app can draw UI. A launch that reaches
     * here has proven itself, so it also drops safe mode: the next launch is normal again.
     */
    fun markReady(context: Context) {
        prefs(context)
            .edit()
            .putBoolean(KEY_LAUNCH_STARTED, false)
            .putBoolean(KEY_SAFE_MODE, false)
            .commit()
    }

    /**
     * A deliberate restart (a person switch) is not a crash: clear the mark, but keep safe mode
     * — the switch has nothing to do with the crash that caused it, and the new launch has not
     * proven itself yet.
     */
    fun markRestarted(context: Context) {
        prefs(context).edit().putBoolean(KEY_LAUNCH_STARTED, false).commit()
    }

    /** True when the previous launch set its mark and never cleared it: it died during startup. */
    fun previousLaunchCrashed(context: Context): Boolean = prefs(context).getBoolean(KEY_LAUNCH_STARTED, false)

    /** Safe mode skips the startup warm-ups; set when the viewer chooses it. */
    fun isSafeMode(context: Context): Boolean = prefs(context).getBoolean(KEY_SAFE_MODE, false)

    fun setSafeMode(
        context: Context,
        safe: Boolean,
    ) {
        prefs(context).edit().putBoolean(KEY_SAFE_MODE, safe).commit()
    }

    /**
     * Every setting back to its default: all opened preferences stores are emptied, and the
     * language choice with them. Nothing else is touched — downloads, history, accounts and
     * caches are not settings and survive a reset.
     */
    suspend fun resetSettings(context: Context) =
        withContext(Dispatchers.IO) {
            for (store in DataStoreRegistry.snapshot().values) {
                runCatching { store.edit { it.clear() } }
            }
            runCatching {
                context
                    .getSharedPreferences(LANGUAGE_PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .commit()
            }
        }

    /** Relaunches the app into a fresh process, a moment from now, then ends this one. */
    fun restart(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        if (intent != null) {
            val pending =
                PendingIntent.getActivity(
                    context,
                    0,
                    intent,
                    PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            alarm?.set(AlarmManager.RTC, System.currentTimeMillis() + 100, pending)
        }
        Process.killProcess(Process.myPid())
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
