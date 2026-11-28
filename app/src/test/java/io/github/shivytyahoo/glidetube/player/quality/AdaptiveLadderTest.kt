package io.github.shivytyahoo.glidetube.player.quality

import com.google.common.truth.Truth.assertThat
import io.github.shivytyahoo.glidetube.player.quality.LadderTestStreams.av1
import io.github.shivytyahoo.glidetube.player.quality.LadderTestStreams.h264
import io.github.shivytyahoo.glidetube.player.quality.LadderTestStreams.vp9
import io.github.shivytyahoo.glidetube.player.stream.VideoCodecUtils
import org.junit.Test

class AdaptiveLadderTest {
    private fun heights(rungs: List<org.schabi.newpipe.extractor.stream.VideoStream>) = rungs.map(VideoCodecUtils::qualityHeightFromStream)

    @Test
    fun `a ladder holds every height across families, lightest bitrate first`() {
        val anchor = h264(720, 2_500_000)
        val streams = listOf(h264(1080, 4_500_000), anchor, h264(360, 700_000), vp9(480, 900_000))

        val rungs = AdaptiveLadder.rungs(anchor, streams)

        assertThat(heights(rungs)).containsExactly(360, 480, 720, 1080).inOrder()
        assertThat(VideoCodecUtils.codecKeyFromStream(rungs[1])).isEqualTo("vp9")
    }

    @Test
    fun `HDR and SDR never share a ladder`() {
        val anchor = vp9(720, 2_000_000)
        val streams = listOf(anchor, vp9(1080, 3_500_000), vp9(1080, 5_000_000, hdr = true), vp9(480, 900_000, hdr = true))

        val rungs = AdaptiveLadder.rungs(anchor, streams)

        assertThat(heights(rungs)).containsExactly(720, 1080).inOrder()
        assertThat(rungs.none(AdaptiveLadder::isHighBitDepth)).isTrue()
    }

    @Test
    fun `streams without byte ranges are left out`() {
        val anchor = h264(720, 2_500_000)

        val rungs = AdaptiveLadder.rungs(anchor, listOf(anchor, h264(1080, 4_500_000, ranged = false)))

        assertThat(rungs).isEmpty()
    }

    @Test
    fun `one height keeps its lightest bitrate copy`() {
        val anchor = h264(720, 2_500_000)
        val richer = h264(720, 2_900_000)

        val rungs = AdaptiveLadder.rungs(anchor, listOf(anchor, richer, h264(360, 700_000)))

        assertThat(rungs.last()).isSameInstanceAs(anchor)
    }

    @Test
    fun `a pinned preload gets the ladder around the pin, lightest copy per height`() {
        val streams = listOf(h264(720, 2_500_000), h264(360, 700_000), vp9(720, 2_000_000), vp9(360, 600_000))

        val pinned = AdaptiveLadder.forPreload(streams[0], streams, null)
        assertThat(heights(pinned)).containsExactly(360, 720).inOrder()
        assertThat(pinned.map(VideoCodecUtils::codecKeyFromStream).distinct()).containsExactly("vp9")
        val rungs = AdaptiveLadder.forPreload(null, streams, "vp9")
        assertThat(rungs.map(VideoCodecUtils::codecKeyFromStream).distinct()).containsExactly("vp9")
    }

    @Test
    fun `a weak device anchors on H264 even with an AV1 preference`() {
        val streams = listOf(av1(1080, 1_500_000), av1(720, 900_000), h264(1080, 4_500_000), h264(720, 2_500_000))

        val rungs = AdaptiveLadder.forPreload(null, streams, "av1", weakDevice = true)
        assertThat(rungs.map(VideoCodecUtils::codecKeyFromStream).distinct()).containsExactly("h264")

        // Without the override the preference wins and the ladder stays AV1.
        val preferred = AdaptiveLadder.forPreload(null, streams, "av1")
        assertThat(preferred.map(VideoCodecUtils::codecKeyFromStream).distinct()).containsExactly("av1")
    }
}
