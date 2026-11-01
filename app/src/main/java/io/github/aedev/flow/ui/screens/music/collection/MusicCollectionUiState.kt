package io.github.aedev.flow.ui.screens.music.collection

import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.music.model.PlaylistDetails

/** What kind of collection a page shows, which decides what it can do. */
enum class MusicCollectionKind {
    ALBUM,
    PLAYLIST,
    OWN,
    SAVED,
    DAILY_MIX,
    LIKED,
}

data class MusicCollectionUiState(
    val kind: MusicCollectionKind? = null,
    val details: PlaylistDetails? = null,
    /** When each song was added, for stored collections; empty for YouTube pages. */
    val addedAt: Map<String, Long> = emptyMap(),
    val isLoading: Boolean = true,
    val failed: Boolean = false,
    val isLoadingMore: Boolean = false,
    val moreFailed: Boolean = false,
    val isSaved: Boolean = false,
    val isDeleted: Boolean = false,
) {
    val isOwn: Boolean get() = kind == MusicCollectionKind.OWN

    /** Your own playlist or Liked music, where songs can be taken out. */
    val canRemove: Boolean get() = kind == MusicCollectionKind.OWN || kind == MusicCollectionKind.LIKED

    /** Collections live on the account now: sharing is always by link, never by local file. */
    val sharesAsFile: Boolean get() = false

    val canExport: Boolean get() = false

    val showsSuggestions: Boolean get() = kind != null && kind != MusicCollectionKind.ALBUM && kind != MusicCollectionKind.DAILY_MIX
}

internal fun remoteKind(id: String): MusicCollectionKind =
    if (id.startsWith("MPREb_") || id.startsWith("FEmusic_library_privately_owned_release_")) {
        MusicCollectionKind.ALBUM
    } else {
        MusicCollectionKind.PLAYLIST
    }

internal fun PlaylistDetails.appending(
    more: List<MusicTrack>,
    next: String?,
): PlaylistDetails {
    val known = tracks.mapTo(HashSet()) { it.videoId }
    val added = more.filter { known.add(it.videoId) }
    return copy(tracks = tracks + added, continuation = next, trackCount = maxOf(trackCount, tracks.size + added.size))
}

