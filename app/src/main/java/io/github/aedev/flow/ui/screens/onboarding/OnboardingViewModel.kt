package io.github.aedev.flow.ui.screens.onboarding

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.model.Channel
import io.github.aedev.flow.data.paging.ChannelSearch
import io.github.aedev.flow.data.recommendation.music.FavouriteArtistCatalog
import io.github.aedev.flow.data.recommendation.music.FavouriteArtistPicker
import io.github.aedev.flow.data.recommendation.music.FavouriteArtistsStore
import io.github.aedev.flow.innertube.YouTube
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val SEARCH_DEBOUNCE_MS = 400L
private const val KEY_STEP = "step"
private const val KEY_TOPICS = "topics"
private const val KEY_QUERY = "query"
private const val KEY_SUB_IDS = "subscribedIds"
private const val KEY_SUB_NAMES = "subscribedNames"
private const val KEY_SUB_THUMBS = "subscribedThumbs"
private const val KEY_NOTIFYING = "notifying"

/**
 * First-run setup. Everything a person picks is kept in [SavedStateHandle], so a recreated
 * activity or a killed process resumes on the same step with the same choices.
 */
@HiltViewModel
class OnboardingViewModel
    @Inject
    constructor(
        private val savedState: SavedStateHandle,
        private val channelSearch: ChannelSearch,
        private val preferences: PlayerPreferences,
        favouriteArtists: FavouriteArtistsStore,
        artistCatalog: FavouriteArtistCatalog,
    ) : ViewModel() {
        private val _state = MutableStateFlow(restore())
        val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

        /** Picks are saved as they are made, the same as in Settings, so nothing waits for Done. */
        val artists = FavouriteArtistPicker(viewModelScope, favouriteArtists, artistCatalog)

        val newVideoAlerts: StateFlow<Boolean> =
            preferences.notifNewVideosEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

        private var searchJob: Job? = null
        private var completion: Job? = null

        init {
            if (_state.value.query.isNotBlank()) search(_state.value.query)
        }

        fun next() {
            val next = OnboardingStep.entries.getOrNull(_state.value.step.index + 1)
            if (next == null) complete() else goTo(next)
        }

        /** Steps back; false on the first step, so the system can handle back itself. */
        fun back(): Boolean {
            val previous = OnboardingStep.entries.getOrNull(_state.value.step.index - 1) ?: return false
            goTo(previous)
            return true
        }

        fun goTo(step: OnboardingStep) = edit { it.copy(step = step) }

        fun toggleTopic(topic: String) =
            edit { state ->
                state.copy(topics = if (topic in state.topics) state.topics - topic else state.topics + topic)
            }

        fun search(query: String) {
            edit { it.copy(query = query) }
            searchJob?.cancel()
            if (query.isBlank()) {
                _state.update { it.copy(results = emptyList(), searching = false) }
                return
            }
            searchJob =
                viewModelScope.launch {
                    delay(SEARCH_DEBOUNCE_MS)
                    _state.update { it.copy(searching = true) }
                    val results = channelSearch.search(query)
                    _state.update { it.copy(results = results, searching = false) }
                }
        }

        fun toggleSubscription(channel: Channel) {
            // Subscribing is an account action now. Signed out, the tap points at
            // sign-in instead of quietly writing a local subscription.
            if (YouTube.cookie.isNullOrBlank() && !YouTube.hasOAuthSession()) {
                edit { it.copy(showSignInPrompt = true) }
                return
            }
            val subscribed = _state.value.isSubscribed(channel.id)
            edit { state ->
                state.copy(
                    subscribed =
                        if (subscribed) {
                            state.subscribed.filterNot { it.id == channel.id }
                        } else {
                            state.subscribed + channel
                        },
                    notifying = state.notifying - channel.id,
                    showSignInPrompt = false,
                )
            }
            viewModelScope.launch {
                YouTube.subscribeChannel(channel.id, !subscribed)
            }
        }

        /** Re-reads the session after returning from the sign-in screens. */
        fun refreshSignedIn() {
            val signedIn = !YouTube.cookie.isNullOrBlank() || YouTube.hasOAuthSession()
            edit {
                it.copy(
                    signedIn = signedIn,
                    showSignInPrompt = if (signedIn) false else it.showSignInPrompt,
                )
            }
        }

        fun setChannelNotifications(
            channelId: String,
            enabled: Boolean,
        ) {
            edit { state -> state.copy(notifying = if (enabled) state.notifying + channelId else state.notifying - channelId) }
        }

        fun setNewVideoAlerts(enabled: Boolean) {
            viewModelScope.launch { preferences.setNotifNewVideosEnabled(enabled) }
        }

        /** Marks onboarding done once; a second tap while it runs does nothing. */
        fun complete() {
            if (completion != null) return
            completion =
                viewModelScope.launch {
                    preferences.setOnboardingDone(true)
                    _state.update { it.copy(completed = true) }
                }
        }

        private fun edit(transform: (OnboardingUiState) -> OnboardingUiState) {
            val state = _state.updateAndGet(transform)
            savedState[KEY_STEP] = state.step.name
            savedState[KEY_TOPICS] = ArrayList(state.topics)
            savedState[KEY_QUERY] = state.query
            savedState[KEY_SUB_IDS] = ArrayList(state.subscribed.map { it.id })
            savedState[KEY_SUB_NAMES] = ArrayList(state.subscribed.map { it.name })
            savedState[KEY_SUB_THUMBS] = ArrayList(state.subscribed.map { it.thumbnailUrl })
            savedState[KEY_NOTIFYING] = ArrayList(state.notifying)
        }

        private fun restore(): OnboardingUiState {
            val ids = savedState.get<ArrayList<String>>(KEY_SUB_IDS).orEmpty()
            val names = savedState.get<ArrayList<String>>(KEY_SUB_NAMES).orEmpty()
            val thumbs = savedState.get<ArrayList<String>>(KEY_SUB_THUMBS).orEmpty()
            return OnboardingUiState(
                step =
                    savedState
                        .get<String>(KEY_STEP)
                        ?.let { name -> OnboardingStep.entries.firstOrNull { it.name == name } }
                        ?: OnboardingStep.entries.first(),
                topics = savedState.get<ArrayList<String>>(KEY_TOPICS).orEmpty().toSet(),
                query = savedState.get<String>(KEY_QUERY).orEmpty(),
                subscribed =
                    ids.mapIndexed { index, id ->
                        Channel(
                            id = id,
                            name = names.getOrElse(index) { "" },
                            thumbnailUrl = thumbs.getOrElse(index) { "" },
                            subscriberCount = -1L,
                        )
                    },
                notifying = savedState.get<ArrayList<String>>(KEY_NOTIFYING).orEmpty().toSet(),
                signedIn = !YouTube.cookie.isNullOrBlank() || YouTube.hasOAuthSession(),
            )
        }
    }
