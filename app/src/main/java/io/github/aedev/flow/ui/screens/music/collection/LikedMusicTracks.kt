package io.github.aedev.flow.ui.screens.music.collection

import io.github.aedev.flow.data.local.LikedVideoInfo
import io.github.aedev.flow.data.model.toMusicTrack
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.utils.ThumbnailUrlResolver

/**
 * Liked songs in the order they were liked. A song also kept in the music favorites comes with the
 * album, artists and length saved there; any other is built from what its like recorded.
 * A favorite whose saved picture is blank does not win over the like's picture: the like's row
 * always carries a picture (the parser normalizes it), so a blank favorite leaves row 1 and the
 * header cover grey while the other rows show theirs.
 */
internal fun likedMusicTracks(
    liked: List<LikedVideoInfo>,
    favorites: List<MusicTrack>,
): List<MusicTrack> {
    val saved = favorites.associateBy { it.videoId }
    return liked.map { info ->
        val favorite = saved[info.videoId] ?: return@map info.toMusicTrack()
        if (favorite.thumbnailUrl.isNotBlank()) {
            favorite
        } else {
            favorite.copy(
                thumbnailUrl =
                    info.thumbnail.ifBlank {
                        ThumbnailUrlResolver.buildHighQualityYoutubeThumbnail(info.videoId)
                    },
            )
        }
    }
}
