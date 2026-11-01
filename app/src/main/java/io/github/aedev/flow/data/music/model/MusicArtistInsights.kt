package io.github.aedev.flow.data.music.model

/** This session's view of one artist, rendered on that artist's page. */
data class MusicArtistInsights(
    val plays: Int,
    val liked: Boolean,
    val topTracks: List<MusicTrack>,
)
