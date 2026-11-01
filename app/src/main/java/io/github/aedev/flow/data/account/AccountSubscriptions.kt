package io.github.aedev.flow.data.account

import io.github.aedev.flow.utils.ShivLog

import io.github.aedev.flow.data.model.Channel
import io.github.aedev.flow.innertube.YouTube
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The account's subscription membership: ONE list, loaded once from the guide, that every
 * surface reads — the player, Shorts, channel pages and featured rows used to each keep
 * their own answer (a header guess, a feed sample, a process cache that froze whatever a
 * failed lookup once concluded), and the answers disagreed on screen.
 *
 * The guide is the complete list, so membership here is a lookup, not an inference. Toggles
 * land here first (optimistically) and then on the account; a channel page that learned the
 * state from its own header corrects the set through [noteState], so the list self-heals
 * instead of drifting.
 */
@Singleton
class AccountSubscriptions
    @Inject
    constructor() {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private val _channels = MutableStateFlow<List<Channel>>(emptyList())
        val channels: StateFlow<List<Channel>> = _channels.asStateFlow()

        private val _ids = MutableStateFlow<Set<String>>(emptySet())
        val ids: StateFlow<Set<String>> = _ids.asStateFlow()

        @Volatile
        private var loadedForCookie: Int? = null

        @Volatile
        private var loading = false

        /** Membership of one channel as a live flow; reading it also kicks the first load. */
        fun isSubscribed(channelId: String): Flow<Boolean> {
            ensureLoaded()
            return _ids.map { channelId in it }.distinctUntilChanged()
        }

        /** Drops the loaded list so the next read loads the (new) active account's guide. */
        fun resetForAccountSwitch() {
            _channels.value = emptyList()
            _ids.value = emptySet()
            loadedForCookie = null
        }

        /** Loads the guide once per signed-in session; a different session reloads. */
        fun ensureLoaded() {
            if (!YouTube.isSignedIn()) {
                if (_ids.value.isNotEmpty()) {
                    _ids.value = emptySet()
                    _channels.value = emptyList()
                    loadedForCookie = null
                }
                return
            }
            val cookieKey = YouTube.cookie?.hashCode()
            if (loading || (loadedForCookie != null && loadedForCookie == cookieKey)) return
            loading = true
            scope.launch {
                try {
                    val guide = YouTube.guideSubscriptions().getOrNull()
                    val list = guide?.takeIf { it.isNotEmpty() } ?: fallbackChannels()
                    _channels.value = list
                    _ids.value = list.mapTo(HashSet()) { it.id }
                    ShivLog.i("subsLoaded guide=${guide != null} count=${list.size}",
                    )
                    // Only a real answer may settle: a failed guide plus an empty fallback
                    // means the network blinked, and an empty list remembered as fact is
                    // the old bug in new clothes — the next ensureLoaded must retry.
                    if (guide != null || list.isNotEmpty()) {
                        loadedForCookie = YouTube.cookie?.hashCode()
                    }
                } finally {
                    loading = false
                }
            }
        }

        /** The subscriptions feed's own channels, when the guide could not be read. */
        private suspend fun fallbackChannels(): List<Channel> {
            val page = YouTube.serverSubscriptions().getOrNull() ?: return emptyList()
            val byId = LinkedHashMap<String, Channel>()
            page.channels.forEach { channel -> byId[channel.id] = channel.copy(isSubscribed = true) }
            (page.videos + page.shorts).forEach { video ->
                if (video.channelId.isNotBlank() && video.channelId !in byId) {
                    byId[video.channelId] =
                        Channel(
                            id = video.channelId,
                            name = video.channelName,
                            thumbnailUrl = video.channelThumbnailUrl.orEmpty(),
                            subscriberCount = 0L,
                            isSubscribed = true,
                        )
                }
            }
            return byId.values.toList()
        }

        /** Waits briefly for the first load, so a toggle decided right after sign-in reads the truth. */
        suspend fun awaitLoaded() {
            ensureLoaded()
            withTimeoutOrNull(8_000) {
                while (loading) delay(100)
            }
        }

        /** Applies a toggle here first, then on the account; a failed call reloads the truth. */
        fun setSubscribed(
            channel: Channel,
            subscribed: Boolean,
        ) {
            // Subscriptions are account-only: an optimistic local write for a guest
            // sticks when the server call fails, and the button reads subscribed.
            if (!YouTube.isSignedIn()) return
            _ids.update { if (subscribed) it + channel.id else it - channel.id }
            _channels.update { list ->
                if (subscribed) {
                    if (list.any { it.id == channel.id }) list else list + channel.copy(isSubscribed = true)
                } else {
                    list.filterNot { it.id == channel.id }
                }
            }
            ShivLog.i("subToggle id=${channel.id.take(8)} to=$subscribed",
            )
            scope.launch {
                val result = YouTube.subscribeChannel(channel.id, subscribed)
                ShivLog.i("subToggleResult id=${channel.id.take(8)} ok=${result.isSuccess}",
                )
                if (result.isFailure) {
                    loadedForCookie = null
                    ensureLoaded()
                }
            }
        }

        /** A surface that learned the state from the page itself corrects the set with it. */
        fun noteState(
            channelId: String,
            subscribed: Boolean,
        ) {
            _ids.update { if (subscribed) it + channelId else it - channelId }
        }
    }

/**
 * Reaches [AccountSubscriptions] from Android boundaries Hilt cannot construct — currently the
 * root composable's startup warm-up. Confined to this file so callers never become service
 * locators themselves.
 */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface AccountSubscriptionsEntryPoint {
    fun accountSubscriptions(): AccountSubscriptions
}

/** Warms the account subscription list at startup, when the user asked for a startup refresh. */
fun warmAccountSubscriptions(context: android.content.Context) {
    if (YouTube.cookie == null) return
    dagger.hilt.android.EntryPointAccessors
        .fromApplication<AccountSubscriptionsEntryPoint>(context.applicationContext)
        .accountSubscriptions()
        .ensureLoaded()
}
