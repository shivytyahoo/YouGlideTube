package io.github.aedev.flow.data.recommendation.music

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class FavouriteArtist(
    val id: String,
    val name: String,
    val thumbnailUrl: String = "",
)

/**
 * The artists the viewer picked by hand, for this session. They used to persist in their own
 * DataStore and feed the music brain's affinity; both stores are gone, so the picker keeps its
 * picks in memory and they reset on a fresh launch.
 */
@Singleton
class FavouriteArtistsStore
    @Inject
    constructor() {
        private val json = Json { ignoreUnknownKeys = true }
        private val state = MutableStateFlow<List<FavouriteArtist>>(emptyList())

        val artists: Flow<List<FavouriteArtist>> = state

        suspend fun setFavourite(
            artist: FavouriteArtist,
            favourite: Boolean,
        ) {
            val others = state.value.filterNot { it.id == artist.id }
            state.value = if (favourite) others + artist else others
        }

        suspend fun currentIds(): Set<String> = artists.first().mapTo(HashSet(), FavouriteArtist::id)

        /** The picks serialized; null when there are none. */
        suspend fun export(): ByteArray? = state.value.takeIf { it.isNotEmpty() }?.let { json.encodeToString(it).toByteArray() }

        /** Adds serialized picks to the current ones. */
        suspend fun restore(bytes: ByteArray) {
            val restored = decode(bytes.toString(Charsets.UTF_8))
            if (restored.isEmpty()) return
            state.value = (state.value + restored).distinctBy { it.id }
        }

        private fun decode(raw: String?): List<FavouriteArtist> =
            raw?.let { runCatching { json.decodeFromString<List<FavouriteArtist>>(it) }.getOrNull() }.orEmpty()
    }
