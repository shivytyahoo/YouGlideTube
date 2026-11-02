package io.github.aedev.flow.data.account

import io.github.aedev.flow.data.engagement.FeedInvalidationBus
import io.github.aedev.flow.data.engagement.SessionLikeState
import io.github.aedev.flow.data.local.HomeFeedCacheRepository
import io.github.aedev.flow.data.music.MusicSessionLibrary
import io.github.aedev.flow.data.repository.YouTubeRepository
import io.github.aedev.flow.innertube.YouTube
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Every signed-in account and which one is active — the YouTube-style account switcher.
 *
 * Accounts are session data: each [SavedAccount] keeps the cookie captured at WebView sign-in,
 * the dataSyncId that went with it, and the display identity fetched from the account menu.
 * Switching applies the account's cookie + dataSyncId to the InnerTube layer, refreshes the
 * visitor identity, and resets every account-scoped cache so no surface can keep showing the
 * previous account's subscriptions, likes, playlists or feeds.
 */
@Singleton
class AccountSessionManager
    @Inject
    constructor(
        private val store: AccountCookieStore,
        private val accountSubscriptions: AccountSubscriptions,
        private val accountPlaylists: AccountPlaylists,
        private val sessionLikes: SessionLikeState,
        private val musicSessionLibrary: MusicSessionLibrary,
        private val youTubeRepository: YouTubeRepository,
        private val homeFeedCacheRepository: HomeFeedCacheRepository,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private val _accounts = MutableStateFlow<List<SavedAccount>>(emptyList())
        val accounts: StateFlow<List<SavedAccount>> = _accounts.asStateFlow()

        private val _active = MutableStateFlow<SavedAccount?>(null)
        val active: StateFlow<SavedAccount?> = _active.asStateFlow()

        val isSignedIn: Boolean get() = _active.value != null

        /**
         * Loads the saved accounts at startup and applies the active one. A cookie stored by a
         * pre-multi-account build becomes the first saved account; its identity is backfilled
         * the next time the account info answers.
         */
        fun restore() {
            var list = store.savedAccounts()
            if (list.isEmpty()) {
                store.restore()?.let { legacyCookie ->
                    val migrated =
                        SavedAccount(
                            key = legacyKey(legacyCookie),
                            cookie = legacyCookie,
                            dataSyncId = null,
                        )
                    list = listOf(migrated)
                    store.saveAccounts(list, migrated.key)
                }
            }
            _accounts.value = list
            val activeAccount =
                list.firstOrNull { it.key == store.activeAccountKey() } ?: list.firstOrNull()
            _active.value = activeAccount
            if (activeAccount != null) applySession(activeAccount)
            backfillIdentityIfNeeded()
        }

        /**
         * A completed WebView sign-in: the account joins the list (or refreshes the entry it
         * already had) and becomes active. The session applies immediately; the display identity
         * is fetched from the account menu and merged in when it answers.
         */
        fun onWebLogin(cookie: String) {
            val dataSyncId = YouTube.dataSyncId
            val placeholder =
                SavedAccount(
                    key = legacyKey(cookie),
                    cookie = cookie,
                    dataSyncId = dataSyncId,
                )
            val existing = _accounts.value.firstOrNull { it.cookie == cookie }
            val entry = existing ?: placeholder
            _accounts.value = (_accounts.value.filterNot { it.key == entry.key } + entry)
            _active.value = entry
            persist()
            applySession(entry)
            resetAccountScopedState()
            FeedInvalidationBus.emit(FeedInvalidationBus.Event.AccountChanged)
            scope.launch { fetchIdentity(cookie)?.let { identified -> mergeIdentity(identified) } }
        }

        /**
         * A completed TV-code (OAuth) sign-in: the OAuth session joins the account
         * list so the account page can show whose it is, exactly like a WebView
         * sign-in. The entry carries no cookie — its session is the stored OAuth
         * token — so its key is the fixed "oauth" until the fetched identity
         * replaces it, and the identity merge matches it by its blank cookie.
         */
        fun onOAuthLogin() {
            val existing = _accounts.value.firstOrNull { it.cookie.isBlank() }
            val entry =
                existing ?: SavedAccount(
                    key = "oauth",
                    cookie = "",
                )
            _accounts.value = (_accounts.value.filterNot { it.key == entry.key } + entry)
            _active.value = entry
            persist()
            applySession(entry)
            resetAccountScopedState()
            FeedInvalidationBus.emit(FeedInvalidationBus.Event.AccountChanged)
            scope.launch { fetchIdentity("")?.let { identified -> mergeIdentity(identified) } }
        }

        /** Makes the saved account with [key] the active one. */
        fun switchTo(key: String) {
            val target = _accounts.value.firstOrNull { it.key == key } ?: return
            if (target.key == _active.value?.key) return
            _active.value = target
            persist()
            applySession(target)
            resetAccountScopedState()
            FeedInvalidationBus.emit(FeedInvalidationBus.Event.AccountChanged)
            backfillIdentityIfNeeded()
        }

        /**
         * Signs out of the ACTIVE account: its entry is removed, the next saved account (if
         * any) becomes active, and with none left the app is a guest again.
         */
        fun signOutActive() {
            val current = _active.value ?: return
            removeEntry(current.key)
        }

        /** Removes one saved account; removing the active one signs out of it (see [signOutActive]). */
        fun removeAccount(key: String) = removeEntry(key)

        private fun removeEntry(key: String) {
            val wasActive = _active.value?.key == key
            val remaining = _accounts.value.filterNot { it.key == key }
            _accounts.value = remaining
            if (!wasActive) {
                persist()
                return
            }
            val next = remaining.firstOrNull()
            _active.value = next
            persist()
            applySession(next)
            resetAccountScopedState()
            FeedInvalidationBus.emit(FeedInvalidationBus.Event.AccountChanged)
            backfillIdentityIfNeeded()
        }

        /** Fetches the active account's identity once, for entries saved before it was known. */
        private fun backfillIdentityIfNeeded() {
            val current = _active.value ?: return
            if (current.name != null && (current.cookie.isNotBlank() || current.pageIdToken != null)) return
            scope.launch { fetchIdentity(current.cookie)?.let { identified -> mergeIdentity(identified) } }
        }

        private suspend fun fetchIdentity(cookie: String): SavedAccount? {
            if (YouTube.cookie != cookie) return null
            if (cookie.isBlank()) {
                // TV-code sessions never get an identity from the account menu —
                // its shape does not parse on the TV client. accounts_list is the
                // TV identity endpoint (SmartTube): it returns the name and, for
                // brand channels, the page id token authed browses must carry.
                // An empty token means the list answered with no brand page.
                val identity = YouTube.oauthIdentity().getOrNull() ?: return null
                val key =
                    identity.channelHandle?.takeIf { it.isNotBlank() }
                        ?: identity.email?.takeIf { it.isNotBlank() }
                        ?: identity.name?.takeIf { it.isNotBlank() }
                        ?: return null
                return SavedAccount(
                    key = key,
                    cookie = "",
                    pageIdToken = identity.pageIdToken ?: "",
                    name = identity.name,
                    email = identity.email,
                    channelHandle = identity.channelHandle,
                )
            }
            val info = YouTube.accountInfo().getOrNull() ?: return null
            val key =
                info.channelHandle?.takeIf { it.isNotBlank() }
                    ?: info.email?.takeIf { it.isNotBlank() }
                    ?: info.name.takeIf { it.isNotBlank() }
                    ?: return null
            return SavedAccount(
                key = key,
                cookie = cookie,
                dataSyncId = YouTube.dataSyncId,
                name = info.name,
                email = info.email,
                channelHandle = info.channelHandle,
                thumbnailUrl = info.thumbnailUrl,
            )
        }

        private fun mergeIdentity(identified: SavedAccount) {
            val current = _accounts.value.firstOrNull { it.cookie == identified.cookie } ?: return
            val merged =
                identified.copy(
                    cookie = current.cookie,
                    dataSyncId = identified.dataSyncId ?: current.dataSyncId,
                    pageIdToken = identified.pageIdToken ?: current.pageIdToken,
                )
            _accounts.value = _accounts.value.map { if (it.key == current.key) merged else it }
            if (_active.value?.key == current.key) _active.value = merged
            persist()
        }

        /** Applies one account's session to the InnerTube layer; null returns the app to guest. */
        private fun applySession(account: SavedAccount?) {
            YouTube.cookie = account?.cookie
            YouTube.dataSyncId = account?.dataSyncId
            YouTube.pageIdToken = account?.pageIdToken?.takeIf { it.isNotBlank() }
            YouTube.useLoginForBrowse = account != null
            // A fresh visitor identity for the new session; InnerTube re-fetches it on demand.
            YouTube.visitorData = null
            // The single-cookie store stays the mirror of the active session for its other readers.
            store.save(account?.cookie)
        }

        /** Every account-scoped cache resets, so the new account never sees the old one's data. */
        private fun resetAccountScopedState() {
            accountSubscriptions.resetForAccountSwitch()
            accountPlaylists.invalidate()
            sessionLikes.resetForAccountSwitch()
            musicSessionLibrary.resetAccountData()
            youTubeRepository.clearWatchNextCache()
            scope.launch { runCatching { homeFeedCacheRepository.clearAll() } }
        }

        private fun persist() {
            store.saveAccounts(_accounts.value, _active.value?.key)
        }

        private fun legacyKey(cookie: String): String = "cookie:" + cookie.hashCode().toString(16)
    }

/** Hilt entry point for the account session manager, for the non-injected login activity. */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface AccountSessionEntryPoint {
    fun accountSessionManager(): AccountSessionManager
}
