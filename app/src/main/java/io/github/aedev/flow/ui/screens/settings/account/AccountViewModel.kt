package io.github.aedev.flow.ui.screens.settings.account

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.account.AccountCookieStore
import io.github.aedev.flow.data.account.AccountSessionManager
import io.github.aedev.flow.data.account.SavedAccount
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.ui.screens.home.HomeFeedCache
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@HiltViewModel
internal class AccountViewModel
    @Inject
    constructor(
        private val store: AccountCookieStore,
        private val oauthSessionManager: io.github.aedev.flow.data.account.OAuthSessionManager,
        private val accountSessionManager: AccountSessionManager,
    ) : ViewModel() {
        private val _signedIn = MutableStateFlow(isSignedIn())
        val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

        /** Every saved account, for the switcher list. */
        val accounts: StateFlow<List<SavedAccount>> = accountSessionManager.accounts

        /** The account currently in use, if any. */
        val activeAccount: StateFlow<SavedAccount?> = accountSessionManager.active

        /** Either session kind counts: a saved WebView-cookie account, or OAuth from a TV code. */
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
            if (accountSessionManager.isSignedIn) {
                accountSessionManager.signOutActive()
            } else {
                oauthSessionManager.signOut()
                YouTube.useLoginForBrowse = false
                HomeFeedCache.clear()
            }
            refresh()
        }
    }
