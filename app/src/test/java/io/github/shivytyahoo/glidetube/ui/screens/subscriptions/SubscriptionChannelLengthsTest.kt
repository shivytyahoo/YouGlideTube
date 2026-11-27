package io.github.shivytyahoo.glidetube.ui.screens.subscriptions

import io.github.shivytyahoo.glidetube.data.model.Video
import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionChannelLengthsTest {
    @Test
    fun bulkLengthsFillOnlyTheMissingOnes() {
        val videos = listOf(video("a"), video("b").copy(duration = 120), video("c"))
        val lengths = mapOf("a" to 90, "b" to 999, "c" to 0, "d" to 60)

        val result = videos.withChannelLengths(lengths)

        assertEquals(listOf(90, 120, 0), result.map { it.duration })
        assertEquals(listOf("a", "b", "c"), result.map { it.id })
    }

    @Test
    fun emptyBulkLengthsChangeNothing() {
        val videos = listOf(video("a"), video("b"))

        assertEquals(videos, videos.withChannelLengths(emptyMap()))
    }

    private fun video(id: String) =
        Video(
            id = id,
            title = "Video $id",
            channelName = "Channel",
            channelId = "channel",
            thumbnailUrl = "",
            duration = 0,
            viewCount = 0,
            uploadDate = "",
        )
}
