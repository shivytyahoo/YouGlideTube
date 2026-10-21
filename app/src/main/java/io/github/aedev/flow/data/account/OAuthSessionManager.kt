package io.github.aedev.flow.data.account

import io.github.aedev.flow.innertube.YouTube
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the TV-code (OAuth) session: persisting tokens, putting them into the InnerTube
 * layer, refreshing them when they age out, and clearing them on sign-out. The cookie
 * session (WebView sign-in) is separate and always wins when both exist.
 */
@Singleton
class OAuthSessionManager
    @Inject
    constructor(
        private val store: AccountCookieStore,
    ) {
        /** Loads any stored OAuth session into the InnerTube layer, refreshing first if it aged out. */
        suspend fun restore() {
            val tokens = store.restoreOAuth() ?: return
            apply(tokens)
            if (tokens.expiresAtMs <= System.currentTimeMillis() + REFRESH_SKEW_MS) {
                refreshNow()
            }
        }

        /** Records a fresh sign-in (from the TV-code dialog) and puts it to work. */
        fun onSignedIn(tokens: TvOAuth.Tokens) {
            val stored =
                AccountCookieStore.OAuthTokens(
                    accessToken = tokens.accessToken,
                    refreshToken = tokens.refreshToken,
                    expiresAtMs = System.currentTimeMillis() + tokens.expiresInSeconds * 1000,
                )
            store.saveOAuth(stored)
            apply(stored)
        }

        /** The [YouTube.onOAuthTokenExpired] hook: swap the aged access token for a fresh one. */
        suspend fun refreshNow(): String? {
            val refreshToken = YouTube.oauthRefreshToken ?: store.restoreOAuth()?.refreshToken ?: return null
            val fresh = TvOAuth.refreshAccessToken(refreshToken).getOrNull() ?: return null
            onSignedIn(fresh)
            return fresh.accessToken
        }

        fun signOut() {
            store.clearOAuth()
            YouTube.oauthAccessToken = null
            YouTube.oauthRefreshToken = null
            YouTube.oauthExpiresAtMs = 0L
        }

        /** Installs the expiry hook the InnerTube layer calls on a 401. */
        fun bind() {
            YouTube.onOAuthTokenExpired = { refreshNow() }
        }

        private fun apply(tokens: AccountCookieStore.OAuthTokens) {
            YouTube.oauthAccessToken = tokens.accessToken
            YouTube.oauthRefreshToken = tokens.refreshToken
            YouTube.oauthExpiresAtMs = tokens.expiresAtMs
            YouTube.useLoginForBrowse = true
        }

        private companion object {
            const val REFRESH_SKEW_MS = 60_000L
        }
    }
