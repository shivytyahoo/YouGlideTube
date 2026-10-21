package io.github.aedev.flow.data.account

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The signed-in YouTube session cookie across restarts, and the one place it is written.
 *
 * The cookie is a credential: it lives in its own preferences file, which the backup rules
 * never list, so it cannot leave the device through a backup or a device transfer. Signing
 * out deletes it outright rather than blanking it.
 */
@Singleton
class AccountCookieStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val prefs: SharedPreferences by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

        /** The stored session cookie, or null when signed out. */
        fun restore(): String? = prefs.getString(KEY_COOKIE, null)?.takeIf { it.isNotBlank() }

        fun isSignedIn(): Boolean = restore() != null

        /** Records [cookie] as the session in use; null or blank signs out. */
        fun save(cookie: String?) {
            if (cookie.isNullOrBlank()) {
                clear()
                return
            }
            if (prefs.getString(KEY_COOKIE, null) == cookie) return
            prefs.edit().putString(KEY_COOKIE, cookie).apply()
        }

        fun clear() {
            prefs.edit().remove(KEY_COOKIE).apply()
        }

        private companion object {
            const val PREFS_NAME = "youtube_account"
            const val KEY_COOKIE = "session_cookie"
        }
    }
