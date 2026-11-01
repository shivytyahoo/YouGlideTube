package io.github.aedev.flow.data.music.model

/**
 * Identity for an artist across the music UI: the YouTube Music browseId (case preserved)
 * when known; the lowercased display name otherwise.
 */
fun musicArtistKey(
    artistId: String?,
    artistName: String?,
): String {
    val id = artistId?.trim().orEmpty()
    if (id.isNotEmpty()) return id
    return artistName?.trim().orEmpty().lowercase()
}

/** Primary-artist key for a track: browseId when known, lowercased name otherwise. */
fun MusicTrack.primaryArtistKey(): String {
    val primary = artists.firstOrNull()
    return musicArtistKey(primary?.id ?: channelId.takeIf { it.isNotBlank() }, primary?.name ?: artist)
}

/**
 * Matches on both key forms because [hidden] carries both: a track's id key when
 * it has one, and always its lowercased display name. EVERY credited artist is
 * tested, not just the primary — a blocked artist's collabs and album cards are
 * still that artist's output to the user.
 */
fun MusicTrack.isHiddenArtist(hidden: Set<String>): Boolean {
    if (hidden.isEmpty()) return false
    if (primaryArtistKey() in hidden) return true
    for (credited in artists) {
        if (musicArtistKey(credited.id, credited.name) in hidden) return true
        val creditedName = credited.name.trim().lowercase()
        if (creditedName.isNotEmpty() && creditedName in hidden) return true
    }
    val name = (artists.firstOrNull()?.name ?: artist).trim().lowercase()
    return name.isNotEmpty() && name in hidden
}

/**
 * Feedback filter for album/playlist cards. Their [MusicPlaylist.author] is a
 * display subtitle (album cards carry the release year there), so matching uses
 * the structured [MusicPlaylist.authorId]/[MusicPlaylist.authorName] first and
 * falls back to the subtitle only when it is all we have.
 */
fun MusicPlaylist.isHiddenAuthor(hidden: Set<String>): Boolean {
    if (hidden.isEmpty()) return false
    if (musicArtistKey(authorId, authorName ?: author) in hidden) return true
    return listOf(authorName, author).any { !it.isNullOrBlank() && it.trim().lowercase() in hidden }
}

/** Whether an artist page belongs to a hidden artist. */
fun ArtistDetails.isHiddenArtist(hidden: Set<String>): Boolean {
    if (hidden.isEmpty()) return false
    if (musicArtistKey(channelId.takeIf { it.isNotBlank() }, name) in hidden) return true
    val lowered = name.trim().lowercase()
    return lowered.isNotEmpty() && lowered in hidden
}
