package io.github.shivytyahoo.glidetube.service

import kotlin.math.abs

/**
 * What decides whether the music player may use audio offload. Offloaded audio skips every audio
 * processor and may not apply a speed or pitch, so offload stays off while the sound is changed. A
 * paused offloaded track also keeps the audio hardware out of standby, and the system holds its
 * AudioOffload wake lock until the player lets that track go, so a pause that lasts gives offload up.
 * Crossfade lines two players up by their positions, which an offloaded track reports too coarsely,
 * so offload stays off while it is on.
 */
internal data class MusicOffloadState(
    val processing: Boolean = false,
    val playbackChanged: Boolean = false,
    val pausedLong: Boolean = false,
    val crossfade: Boolean = false,
) {
    val allowed: Boolean get() = !processing && !playbackChanged && !pausedLong && !crossfade

    companion object {
        // Sonic's own threshold: a speed saved from a slider can read 0.99999994, which plays untouched.
        private const val UNCHANGED_TOLERANCE = 0.0001f

        fun changesPlayback(
            speed: Float,
            pitch: Float,
        ): Boolean = abs(speed - 1f) >= UNCHANGED_TOLERANCE || abs(pitch - 1f) >= UNCHANGED_TOLERANCE
    }
}
