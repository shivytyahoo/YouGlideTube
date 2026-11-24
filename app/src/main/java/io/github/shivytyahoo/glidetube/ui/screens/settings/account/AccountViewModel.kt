package io.github.shivytyahoo.glidetube.ui.screens.settings.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.shivytyahoo.glidetube.data.account.AccountCookieStore
import io.github.shivytyahoo.glidetube.data.account.AccountSessionManager
import io.github.shivytyahoo.glidetube.data.account.SavedAccount
import io.github.shivytyahoo.glidetube.innertube.YouTube
import io.github.shivytyahoo.glidetube.ui.screens.home.HomeFeedCache
import io.github.shivytyahoo.glidetube.utils.potoken.WebPoTokenSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
internal class AccountViewModel
    @Inject
    constructor(
        private val store: AccountCookieStore,
        private val oauthSessionManager: io.github.shivytyahoo.glidetube.data.account.OAuthSessionManager,
        private val accountSessionManager: AccountSessionManager,
    ) : ViewModel() {
        private val _signedIn = MutableStateFlow(isSignedIn())
        val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

        /** Every saved account, for the switcher list. */
        val accounts: StateFlow<List<SavedAccount>> = accountSessionManager.accounts

        /** The account currently in use, if any. */
        val activeAccount: StateFlow<SavedAccount?> = accountSessionManager.active

        /** Either session kind counts: a saved WebView-cookie account, or a stored OAuth session. */
        private fun isSignedIn(): Boolean = accountSessionManager.isSignedIn || store.restoreOAuth() != null

        /** The login screens write the store from outside this graph, so re-read on resume. */
        fun refresh() {
            _signedIn.value = isSignedIn()
        }

        /** Switches the active account; every feed and account surface reloads for it. */
        fun switchAccount(key: String) {
            accountSessionManager.switchTo(key)
            refresh()
        }

        /** Signs out of the active account (the next saved one takes over, or guest). */
        fun signOut() {
            val wasSignedIn = accountSessionManager.isSignedIn
            if (wasSignedIn) {
                // An OAuth entry carries no cookie: removing its row alone would
                // leave its token live and the app signed in with no account.
                val wasOAuth = activeAccount.value?.cookie.isNullOrBlank()
                accountSessionManager.signOutActive()
                if (wasOAuth) {
                    oauthSessionManager.signOut()
                    YouTube.useLoginForBrowse = false
                    HomeFeedCache.clear()
                }
            } else {
                oauthSessionManager.signOut()
                YouTube.useLoginForBrowse = false
                HomeFeedCache.clear()
            }
            // Signing out of the last account leaves the app a guest on the same visitor
            // that carried the signed-in session: give the anonymous session a fresh
            // visitor instead of inheriting the mixed-history one.
            if (wasSignedIn && !accountSessionManager.isSignedIn) {
                viewModelScope.launch { WebPoTokenSession.resetIdentity() }
            }
            refresh()
        }
    }
