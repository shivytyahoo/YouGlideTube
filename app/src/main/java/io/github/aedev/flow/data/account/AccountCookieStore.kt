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

        /** Every saved account for switching, in the order they were added. */
        fun savedAccounts(): List<SavedAccount> =
            prefs
                .getString(KEY_ACCOUNTS, null)
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { accountJson.decodeFromString<List<SavedAccount>>(it) }.getOrNull() }
                .orEmpty()

        /** The identity key of the account currently in use, when one is chosen. */
        fun activeAccountKey(): String? = prefs.getString(KEY_ACTIVE_ACCOUNT, null)?.takeIf { it.isNotBlank() }

        /** Persists the whole account list and which of them is active. */
        fun saveAccounts(
            accounts: List<SavedAccount>,
            activeKey: String?,
        ) {
            prefs
                .edit()
                .putString(KEY_ACCOUNTS, accountJson.encodeToString(accounts))
                .putString(KEY_ACTIVE_ACCOUNT, activeKey)
                .apply()
        }

        /** OAuth tokens from a TV-code sign-in; the cookie session above is the other sign-in kind. */
        data class OAuthTokens(
            val accessToken: String,
            val refreshToken: String?,
            val expiresAtMs: Long,
        )

        fun saveOAuth(tokens: OAuthTokens) {
            prefs
                .edit()
                .putString(KEY_OAUTH_ACCESS, tokens.accessToken)
                .putString(KEY_OAUTH_REFRESH, tokens.refreshToken)
                .putLong(KEY_OAUTH_EXPIRES_AT, tokens.expiresAtMs)
                .apply()
        }

        fun restoreOAuth(): OAuthTokens? {
            val access = prefs.getString(KEY_OAUTH_ACCESS, null)?.takeIf { it.isNotBlank() } ?: return null
            return OAuthTokens(
                accessToken = access,
                refreshToken = prefs.getString(KEY_OAUTH_REFRESH, null),
                expiresAtMs = prefs.getLong(KEY_OAUTH_EXPIRES_AT, 0L),
            )
        }

        fun clearOAuth() {
            prefs
                .edit()
                .remove(KEY_OAUTH_ACCESS)
                .remove(KEY_OAUTH_REFRESH)
                .remove(KEY_OAUTH_EXPIRES_AT)
                .apply()
        }

        private companion object {
            const val PREFS_NAME = "youtube_account"
            const val KEY_COOKIE = "session_cookie"
            const val KEY_ACCOUNTS = "saved_accounts"
            const val KEY_ACTIVE_ACCOUNT = "active_account_key"

            val accountJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            const val KEY_OAUTH_ACCESS = "oauth_access_token"
            const val KEY_OAUTH_REFRESH = "oauth_refresh_token"
            const val KEY_OAUTH_EXPIRES_AT = "oauth_expires_at"
        }
    }
