package io.github.shivytyahoo.glidetube.service

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * The music session's player, with the current lyric line as its title: the system media card and
 * lock screen read only the title and artist keys. With no line showing it reports the player
 * unchanged, so playback, the notification and controllers behave exactly as without it.
 */
@OptIn(UnstableApi::class)
internal class SessionPlayer(
    player: Player,
) : ForwardingSimpleBasePlayer(player) {
    private var lyricLine: String? = null

    /** Reports [line] as the title: HyperOS's shade and lock screen read only the title and artist keys. */
    fun setLyricLine(line: String?) {
        if (lyricLine == line) return
        lyricLine = line
        invalidateState()
    }

    override fun getState(): State {
        val state = super.getState()
        return state.withLyricLine()
    }

    private fun State.withLyricLine(): State {
        val line = lyricLine ?: return this
        if (playlist.isEmpty()) return this
        val metadata =
            currentMetadata
                .buildUpon()
                .setTitle(line)
                .setArtist(sessionLyricArtist(currentMetadata.title, currentMetadata.artist))
                .build()
        return buildUpon().setPlaylist(timeline, currentTracks, metadata).build()
    }
}
