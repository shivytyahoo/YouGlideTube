package io.github.shivytyahoo.glidetube.player.stream

import io.github.shivytyahoo.glidetube.R

/**
 * Which InnerTube client supplies a Watch video's streams. The player itself never changes —
 * only the client the extractor asks changes.
 *
 * Auto is today's client ladder, unchanged. A forced value is tried on its own: no other client
 * is attempted, except that a forced SABR failure falls back to the direct ladder (see
 * [InnerTubeVideoStreamExtractor]) and a live broadcast always plays through the live branch.
 * Stored as the raw string below; "" is Auto, so users who never touch the setting keep the
 * exact behaviour they have today.
 */
enum class PlaybackMethod(
    /** The value stored in DataStore. */
    val storedValue: String,
    /** The label the Settings row and the player sheet show. */
    val labelRes: Int,
) {
    AUTO("", R.string.player_settings_playback_method_auto),
    VISIONOS("VISIONOS", R.string.player_settings_playback_method_visionos),
    ANDROID_VR("ANDROID_VR", R.string.player_settings_playback_method_android_vr),
    KIDS("KIDS", R.string.player_settings_playback_method_kids),
    SABR("SABR", R.string.player_settings_playback_method_sabr),
    TV("TV", R.string.player_settings_playback_method_tv),
    ;

    companion object {
        /** The order the Settings dialog and the player sheet list the methods in. */
        val menuOrder: List<PlaybackMethod> = listOf(AUTO, VISIONOS, ANDROID_VR, KIDS, SABR, TV)

        /** Unknown stored values (a future method, a corrupt write) read as Auto. */
        fun fromStored(raw: String?): PlaybackMethod = entries.firstOrNull { it.storedValue == raw } ?: AUTO
    }
}
