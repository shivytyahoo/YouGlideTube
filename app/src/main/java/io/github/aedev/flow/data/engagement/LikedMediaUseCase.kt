package io.github.aedev.flow.data.engagement

import io.github.aedev.flow.data.local.LikedVideoInfo
import io.github.aedev.flow.data.model.toMusicTrack
import io.github.aedev.flow.data.scrobble.Scrobbler
import javax.inject.Inject
import io.github.aedev.flow.data.music.MusicSessionLibrary as MusicLibrary

/**
 * Likes and unlikes from the Liked videos and Liked music pages. A liked song is also kept in the
 * music library's favorites, which the music recommendations read, so both change together.
 */
class LikedMediaUseCase
    @Inject
    constructor(
        private val likes: SessionLikeState,
        private val musicLibrary: MusicLibrary,
        private val scrobbler: Scrobbler,
    ) {
        /**
         * Unlikes the given likes; the result is what [restore] needs to undo it. Each removal
         * goes through the session like state, which writes the account unlike.
         */
        suspend fun unlike(liked: List<LikedVideoInfo>): List<LikedVideoInfo> {
            val taken = liked.filter { it.videoId.isNotBlank() }
            taken.forEach { info ->
                likes.removeLikeState(info.videoId)
                if (info.isMusic) {
                    musicLibrary.removeFromFavorites(info.videoId)
                    scrobbler.onLikeChanged(info.toMusicTrack(), liked = false)
                }
            }
            return taken
        }

        suspend fun restore(taken: List<LikedVideoInfo>) {
            likes.restoreLikes(taken)
            taken.filter { it.isMusic }.map { it.toMusicTrack() }.forEach {
                musicLibrary.addToFavorites(it)
                scrobbler.onLikeChanged(it, liked = true)
            }
        }
    }
