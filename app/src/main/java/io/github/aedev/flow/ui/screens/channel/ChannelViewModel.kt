package io.github.aedev.flow.ui.screens.channel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.model.Comment
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.model.distinctByNonBlankKey
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.innertube.pages.channel.ChannelTabKind
import io.github.aedev.flow.innertube.pages.renderer.CommunityPost
import io.github.aedev.flow.innertube.pages.renderer.FeedItemOwner
import io.github.aedev.flow.ui.youtubeChannelBrowseId
import io.github.aedev.flow.ui.youtubeChannelUrl
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChannelViewModel
    @Inject
    constructor(
        @ApplicationContext private val appContext: Context,
        private val playerPreferences: PlayerPreferences,
        private val accountSubscriptions: io.github.aedev.flow.data.account.AccountSubscriptions,
    ) : ViewModel() {
        /** Which channels the user follows, so a featured-channel row can show its real state. */
        val subscribedChannelIds: StateFlow<Set<String>> =
            accountSubscriptions.ids
                .map { accountIds -> if (YouTube.isSignedIn()) accountIds else emptySet() }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(GROUPS_SUBSCRIPTION_TIMEOUT_MS), emptySet())

        fun setChannelSubscription(
            channel: io.github.aedev.flow.data.model.Channel,
            subscribed: Boolean,
        ) {
            if (YouTube.isSignedIn()) {
                accountSubscriptions.setSubscribed(channel, subscribed)
            }
        }

        val showShortsTab: StateFlow<Boolean> =
            playerPreferences.effectiveChannelShortsTabEnabled
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(GROUPS_SUBSCRIPTION_TIMEOUT_MS), true)

        private val _uiState = MutableStateFlow(ChannelUiState())
        val uiState: StateFlow<ChannelUiState> = _uiState.asStateFlow()
        private val communityController = ChannelCommunityController(viewModelScope)
        private var requestedChannelUrl: String? = null
        internal val communityUiState: StateFlow<ChannelCommunityUiState> = communityController.state

        private val tabController = ChannelTabController(viewModelScope)
        internal val tabStates: StateFlow<Map<ChannelTabKind, ChannelTabState>> = tabController.states

        private fun channelOwner(): FeedItemOwner {
            val state = _uiState.value
            return FeedItemOwner(
                id = state.channelId.orEmpty(),
                name = state.header?.title.orEmpty(),
                avatarUrl = state.header?.avatarUrl.orEmpty(),
            )
        }

        fun selectTabFilter(
            kind: ChannelTabKind,
            groupIndex: Int,
            optionIndex: Int,
        ) = tabController.selectFilter(kind, _uiState.value.tabParams(kind), groupIndex, optionIndex)

        var listScrollIndex: Int = 0
            private set
        var listScrollOffset: Int = 0
            private set

        fun saveScrollPosition(
            index: Int,
            offset: Int,
        ) {
            listScrollIndex = index
            listScrollOffset = offset
        }

        companion object {
            private const val TAG = "ChannelViewModel"
            private const val GROUPS_SUBSCRIPTION_TIMEOUT_MS = 5_000L
        }

        /**
         *  PERFORMANCE OPTIMIZED: Load channel with timeout protection
         */
        fun loadChannel(channelUrl: String) {
            if (!shouldLoadChannel(_uiState.value, requestedChannelUrl, channelUrl)) return
            requestedChannelUrl = channelUrl
            accountSubscriptions.ensureLoaded()
            val directId = youtubeChannelBrowseId(channelUrl)
            val vanityUrl = youtubeChannelUrl(channelUrl)
            if (directId == null && vanityUrl == null) {
                _uiState.update { it.copy(error = appContext.getString(R.string.error_invalid_channel_url), isLoading = false) }
                return
            }

            _uiState.update { it.copy(isLoading = true, error = null) }
            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                val browseId =
                    directId ?: YouTube.resolveChannelId(checkNotNull(vanityUrl)).getOrElse { error ->
                        Log.e(TAG, "Failed to resolve $vanityUrl", error)
                        _uiState.update { it.copy(error = appContext.getString(R.string.error_channel_link_not_found), isLoading = false) }
                        return@launch
                    }

                YouTube.channel(browseId).fold(
                    onSuccess = { page ->
                        val header = page.header
                        val channelId = header.id.ifBlank { browseId }
                        _uiState.update {
                            it.copy(
                                channelId = channelId,
                                header = header,
                                tabs = page.tabs,
                                isLoading = false,
                            )
                        }
                        communityController.reset(channelId, header.title, header.avatarUrl)
                        loadSubscriptionState(channelId, header.subscribed)
                        onTabsResolved()
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Failed to load channel", error)
                        _uiState.update {
                            it.copy(
                                error = error.message ?: appContext.getString(R.string.error_failed_to_load_channel),
                                isLoading = false,
                            )
                        }
                    },
                )
            }
        }

        private fun onTabsResolved() {
            val state = _uiState.value
            val channelId = state.channelId ?: return
            tabController.reset(channelId, channelOwner())
            val first = state.selectedTab ?: state.tabs.firstOrNull()?.kind ?: return
            _uiState.update { it.copy(selectedTab = first) }
            ensureTabLoaded(first)
        }

        private fun ensureTabLoaded(kind: ChannelTabKind) {
            if (kind == ChannelTabKind.Posts) {
                communityController.ensurePostsLoaded()
                return
            }
            if (kind == ChannelTabKind.Shorts) {
                // Read at load time, not once per channel, so turning the tab on while a channel is open still loads it.
                viewModelScope.launch {
                    if (playerPreferences.effectiveChannelShortsTabEnabled.first()) {
                        tabController.ensureLoaded(kind, _uiState.value.tabParams(kind))
                    }
                }
                return
            }
            tabController.ensureLoaded(kind, _uiState.value.tabParams(kind))
        }

        private var subStateJob: kotlinx.coroutines.Job? = null

        private fun loadSubscriptionState(
            channelId: String,
            accountState: Boolean? = null,
        ) {
            // Signed in, subscription state is the account's, held in ONE shared list. The
            // page header cross-checks that list (and paints fast); the list drives the button
            // from then on, so this page can never drift from the player or Shorts again.
            if (YouTube.isSignedIn()) {
                if (accountState != null) {
                    accountSubscriptions.noteState(channelId, accountState)
                    _uiState.update { it.copy(isSubscribed = accountState) }
                }
                subStateJob?.cancel()
                subStateJob =
                    viewModelScope.launch {
                        accountSubscriptions.isSubscribed(channelId).collect { subscribed ->
                            _uiState.update { it.copy(isSubscribed = subscribed) }
                        }
                    }
                return
            }
            // Signed out, subscriptions are account-only: the button starts unsubscribed.
            _uiState.update { it.copy(isSubscribed = false, isNotificationsEnabled = false) }
        }

        fun toggleSubscription() {
            val state = _uiState.value
            val channelId = state.channelId ?: return
            if (YouTube.isSignedIn()) {
                // The real account action, through the shared list every surface reads.
                viewModelScope.launch {
                    accountSubscriptions.awaitLoaded()
                    val target = channelId !in accountSubscriptions.ids.value
                    accountSubscriptions.setSubscribed(
                        io.github.aedev.flow.data.model.Channel(
                            id = channelId,
                            name = state.header?.title.orEmpty(),
                            thumbnailUrl = state.header?.avatarUrl.orEmpty(),
                            subscriberCount = 0L,
                        ),
                        target,
                    )
                }
                return
            }
            // Signed out there is no local subscribe anymore — subscribing needs the account.
            _uiState.update { it.copy(showSignInPrompt = true) }
        }

        fun dismissSignInPrompt() {
            _uiState.update { it.copy(showSignInPrompt = false) }
        }

        fun unsubscribe() {
            val state = _uiState.value
            val channelId = state.channelId ?: return
            if (YouTube.isSignedIn()) {
                accountSubscriptions.setSubscribed(
                    io.github.aedev.flow.data.model.Channel(
                        id = channelId,
                        name = state.header?.title.orEmpty(),
                        thumbnailUrl = state.header?.avatarUrl.orEmpty(),
                        subscriberCount = 0L,
                    ),
                    false,
                )
                return
            }
        }

        fun setNotificationState(enabled: Boolean) {
            // Per-channel notification preference lived in the local store; subscriptions are
            // account-only now and carry no local notification level.
        }

        fun selectTab(kind: ChannelTabKind) {
            _uiState.update { it.copy(selectedTab = kind) }
            ensureTabLoaded(kind)
        }

        fun openCommunityPostComments(post: CommunityPost) = communityController.openComments(post)

        fun closeCommunityPostComments() = communityController.closeComments()

        fun toggleCommunityPostLike(post: CommunityPost) {
            if (YouTube.isSignedIn()) {
                communityController.togglePostLike(post)
            } else {
                _uiState.update { it.copy(showSignInPrompt = true) }
            }
        }

        fun postCommunityPostComment(
            text: String,
            onResult: (Boolean) -> Unit,
        ) = communityController.postPostComment(text, onResult)

        fun toggleCommunityCommentLike(comment: Comment) =
            communityController.togglePostCommentVote(comment, like = true)

        fun toggleCommunityCommentDislike(comment: Comment) =
            communityController.togglePostCommentVote(comment, like = false)

        fun postCommunityCommentReply(
            comment: Comment,
            text: String,
            onResult: (Boolean) -> Unit,
        ) = communityController.postPostCommentReply(comment, text, onResult)

        fun editCommunityComment(
            comment: Comment,
            text: String,
            onResult: (Boolean) -> Unit,
        ) = communityController.editPostComment(comment, text, onResult)

        fun deleteCommunityComment(
            comment: Comment,
            onResult: (Boolean) -> Unit,
        ) = communityController.deletePostComment(comment, onResult)

        fun retryCommunityPosts() = communityController.retryPosts()

        fun loadMoreCommunityPosts() = communityController.loadMorePosts()

        fun loadMoreCommunityPostComments() = communityController.loadMoreComments()

        fun loadCommunityCommentReplies(comment: Comment) = communityController.loadReplies(comment, append = false)

        fun loadMoreCommunityCommentReplies(comment: Comment) = communityController.loadReplies(comment, append = true)

        // ── Channel search ────────────────────────────────────────────────────────

        fun setSearchActive(active: Boolean) {
            _uiState.update {
                it.copy(
                    searchActive = active,
                    searchQuery = if (!active) "" else it.searchQuery,
                    searchResults = if (!active) emptyList() else it.searchResults,
                    searchErrorLog = null,
                )
            }
        }

        fun searchInChannel(query: String) {
            val channelId = _uiState.value.channelId ?: return
            val header = _uiState.value.header ?: return
            val trimmed = query.trim()

            _uiState.update {
                it.copy(
                    searchQuery = query,
                    searchErrorLog = null,
                )
            }

            if (trimmed.isBlank()) {
                _uiState.update { it.copy(searchResults = emptyList(), isSearching = false) }
                return
            }

            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                _uiState.update { it.copy(isSearching = true) }
                try {
                    val result =
                        YouTube.channelSearch(
                            channelId = channelId,
                            channelName = header.title,
                            channelThumbnailUrl = header.avatarUrl,
                            query = trimmed,
                        )
                    result.fold(
                        onSuccess = { page ->
                            _uiState.update {
                                it.copy(
                                    searchResults = page.videos.distinctByNonBlankKey(Video::id),
                                    searchContinuation = page.continuation,
                                    isSearching = false,
                                    searchErrorLog = null,
                                )
                            }
                        },
                        onFailure = { e ->
                            Log.e(TAG, "Channel search failed", e)
                            _uiState.update {
                                it.copy(
                                    isSearching = false,
                                    searchErrorLog =
                                        buildChannelRequestErrorLog(
                                            operation = "channel_search",
                                            channelId = channelId,
                                            query = trimmed,
                                            error = e,
                                        ),
                                )
                            }
                        },
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Channel search error", e)
                    _uiState.update {
                        it.copy(
                            isSearching = false,
                            searchErrorLog =
                                buildChannelRequestErrorLog(
                                    operation = "channel_search",
                                    channelId = channelId,
                                    query = trimmed,
                                    error = e,
                                ),
                        )
                    }
                }
            }
        }
    }
