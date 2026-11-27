package io.github.shivytyahoo.glidetube.player.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The weak-device H.264-first override and the TV family order sit on top of the existing codec
 * preference machinery — they never replace it, so a preference still wins and the lone-preference
 * rank stays exactly -1.
 */
class VideoCodecUtilsWeakDeviceTest {
    @Test
    fun `a weak device ranks H264 first even with an AV1 preference`() {
        assertTrue(
            VideoCodecUtils.codecRankWithPreference("h264", "av1", weakDevice = true) <
                VideoCodecUtils.codecRankWithPreference("av1", "av1", weakDevice = true),
        )
        assertTrue(
            VideoCodecUtils.codecRankWithPreference("h264", null, weakDevice = true) <
                VideoCodecUtils.codecRankWithPreference("vp9", null, weakDevice = true),
        )
    }

    @Test
    fun `a weak device does not disturb the preference rank of other codecs`() {
        assertEquals(-1, VideoCodecUtils.codecRankWithPreference("av1", "av1", weakDevice = true))
        assertTrue(
            VideoCodecUtils.codecRankWithPreference("av1", "av1", weakDevice = true) <
                VideoCodecUtils.codecRankWithPreference("vp9", "av1", weakDevice = true),
        )
    }

    @Test
    fun `the family order ranks before the built-in order when no preference is set`() {
        val familyOrder = listOf("vp9", "h264")
        assertTrue(
            VideoCodecUtils.codecRankWithPreference("vp9", null, familyOrder = familyOrder) <
                VideoCodecUtils.codecRankWithPreference("h264", null, familyOrder = familyOrder),
        )
        // AV1 is not in the family order, so it falls back to the built-in rank and loses.
        assertTrue(
            VideoCodecUtils.codecRankWithPreference("h264", null, familyOrder = familyOrder) <
                VideoCodecUtils.codecRankWithPreference("av1", null, familyOrder = familyOrder),
        )
    }

    @Test
    fun `a preference still beats the family order`() {
        val familyOrder = listOf("vp9", "av1")
        assertTrue(
            VideoCodecUtils.codecRankWithPreference("av1", "av1", familyOrder = familyOrder) <
                VideoCodecUtils.codecRankWithPreference("vp9", "av1", familyOrder = familyOrder),
        )
    }
}
