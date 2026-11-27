package io.github.shivytyahoo.glidetube.ui.screens.home

import com.google.common.truth.Truth.assertThat
import io.github.shivytyahoo.glidetube.data.engagement.FeedExclusions
import io.github.shivytyahoo.glidetube.data.local.GuestTasteData
import io.github.shivytyahoo.glidetube.data.local.VideoHistoryEntry
import io.github.shivytyahoo.glidetube.data.model.Video
import io.mockk.mockk
import org.junit.Test
import kotlin.random.Random

class GuestHomeFeedRankTest {
    // The ranking and shelving never touch the network or the store, so the mocks stay unused.
    private val feed = GuestHomeFeed(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))

    private fun video(
        id: String,
        title: String = "Title $id",
        channelId: String = "ch-$id",
    ) = Video(id, title, "Channel", channelId, "", 600, 1_000, uploadDate = "1 day ago")

    private fun rank(
        candidates: List<Cand>,
        taste: GuestTasteData = GuestTasteData(),
        history: Map<String, VideoHistoryEntry> = emptyMap(),
        skip: Set<String> = emptySet(),
        n: Int = 10,
    ) = feed.rankCandidates(
        candidates = candidates,
        n = n,
        skip = skip,
        taste = taste,
        history = history,
        exclusions = FeedExclusions.NONE,
        impressions = emptyMap(),
        bucket = "wdm",
        nowSec = 1_800_000_000L,
        random = Random(0),
    )

    @Test
    fun `a liked keyword outranks an unrelated video`() {
        val taste = GuestTasteData(kw = mapOf("tensor" to 10.0))
        val candidates =
            listOf(
                Cand(video("a", "Tensor G5 deep dive"), FeedSource.RELATED),
                Cand(video("b", "Baking sourdough at home"), FeedSource.DISCOVERY),
            )

        val ranked = rank(candidates, taste = taste)

        assertThat(ranked.map { it.id }).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `skipped and watched-out videos never rank`() {
        val candidates =
            listOf(
                Cand(video("a"), FeedSource.RELATED),
                Cand(video("b"), FeedSource.RELATED),
                Cand(video("c", "Desert storm three"), FeedSource.DISCOVERY),
            )
        val history =
            mapOf("b" to VideoHistoryEntry("b", 550, 600, 1L, "Title b", ""))

        val ranked = rank(candidates, history = history, skip = setOf("a"))

        assertThat(ranked.map { it.id }).containsExactly("c")
    }

    @Test
    fun `no channel takes more than three slots`() {
        val candidates =
            listOf(
                "Quantum leap one",
                "Solar flare two",
                "Desert storm three",
                "Ocean tide four",
            ).mapIndexed { index, title ->
                Cand(video("v$index", title, channelId = "ch"), FeedSource.RELATED)
            }

        val ranked = rank(candidates)

        assertThat(ranked).hasSize(3)
        assertThat(ranked.map { it.channelId }.toSet()).containsExactly("ch")
    }

    @Test
    fun `four related videos make one shelf`() {
        val videos = listOf(video("a"), video("b"), video("c"), video("d"))
        val sources = videos.associate { it.id to (FeedSource.RELATED to null) }

        val shelves = feed.groupShelves(videos, sources)

        assertThat(shelves).hasSize(1)
        assertThat(shelves.single().source).isEqualTo(FeedSource.RELATED)
        assertThat(shelves.single().videos).hasSize(4)
    }

    @Test
    fun `three related videos make no shelf`() {
        val videos = listOf(video("a"), video("b"), video("c"))
        val sources = videos.associate { it.id to (FeedSource.RELATED to null) }

        assertThat(feed.groupShelves(videos, sources)).isEmpty()
    }

    @Test
    fun `discovery splits by tag`() {
        val videos =
            listOf(video("a"), video("b"), video("c"), video("d")) +
                listOf(video("e"), video("f"), video("g"), video("h"))
        val sources =
            videos.associate {
                it.id to (FeedSource.DISCOVERY to if (it.id < "e") "tensor" else "cooking")
            }

        val shelves = feed.groupShelves(videos, sources)

        assertThat(shelves.map { it.tag }).containsExactly("tensor", "cooking")
    }

    @Test
    fun `a shelf caps at twelve videos`() {
        val videos = (0 until 14).map { video("v$it") }
        val sources = videos.associate { it.id to (FeedSource.RELATED to null) }

        val shelves = feed.groupShelves(videos, sources)

        assertThat(shelves).hasSize(1)
        assertThat(shelves.single().videos).hasSize(12)
    }
}
