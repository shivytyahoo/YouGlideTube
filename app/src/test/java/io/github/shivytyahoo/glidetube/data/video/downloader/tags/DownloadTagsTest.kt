package io.github.shivytyahoo.glidetube.data.video.downloader.tags

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class DownloadTagsTest {
    private val full =
        DownloadTags(
            kind = DownloadKind.VIDEO,
            videoId = "abc123DEF45",
            title = "Title 🎵",
            artists = listOf("A, with comma", "B"),
            channelId = "UC123",
            channelName = "Channel",
            album = "Album",
            albumId = "MPREb",
            albumArtist = "A",
            trackNumber = 3,
            trackTotal = 12,
            playlistId = "PL1",
            releaseDate = "2024-02-29",
            description = "Line one\nLine two",
            sourceUrl = "https://www.youtube.com/watch?v=abc123DEF45",
            viewCount = 42L,
            likeCount = 7L,
            thumbnailUrl = "https://i.ytimg.com/vi/abc123DEF45/hqdefault.jpg",
            lyrics = "la la",
        )

    @Test
    fun `json round trip keeps every field`() {
        val json = Json.encodeToString(DownloadTags.serializer(), full)
        assertThat(Json.decodeFromString(DownloadTags.serializer(), json)).isEqualTo(full)
    }

    @Test
    fun `json with only required fields decodes with defaults`() {
        val decoded = Json.decodeFromString(DownloadTags.serializer(), """{"kind":"SHORT","videoId":"id","title":"t"}""")
        assertThat(decoded).isEqualTo(DownloadTags(kind = DownloadKind.SHORT, videoId = "id", title = "t"))
        assertThat(decoded.schema).isEqualTo(DownloadTags.SCHEMA_VERSION)
    }

    @Test
    fun `display artist joins distinct credited artists`() {
        val tags = full.copy(artists = listOf(" Alice ", "bob", "Alice", "", "Bob"))
        assertThat(tags.displayArtist()).isEqualTo("Alice, bob")
    }

    @Test
    fun `display artist falls back to the channel without the topic suffix`() {
        val tags = full.copy(artists = emptyList(), channelName = "Queen - Topic")
        assertThat(tags.displayArtist()).isEqualTo("Queen")
        assertThat(tags.copy(channelName = null).displayArtist()).isNull()
        assertThat(tags.copy(channelName = " - Topic").displayArtist()).isNull()
    }

    @Test
    fun `field codec round trips everything except lyrics`() {
        val fields = GlideTagFields.encode(full)
        assertThat(fields).doesNotContainKey("lyrics")
        assertThat(GlideTagFields.decode(fields)).isEqualTo(full.copy(lyrics = null))
    }

    @Test
    fun `field codec omits absent values and needs id kind and title`() {
        val minimal = DownloadTags(kind = DownloadKind.MUSIC, videoId = "id", title = "t")
        val fields = GlideTagFields.encode(minimal)
        assertThat(fields.keys).containsExactly("schema", "kind", "videoId", "title")
        assertThat(GlideTagFields.decode(fields - "videoId")).isNull()
        assertThat(GlideTagFields.decode(fields - "title")).isNull()
        assertThat(GlideTagFields.decode(fields + ("kind" to "PODCAST"))).isNull()
    }

    @Test
    fun `mdta keys are namespaced`() {
        assertThat(GlideTagFields.mdtaKey("videoId")).isEqualTo("io.github.shivytyahoo.glidetube.videoId")
        assertThat(GlideTagFields.fieldFromMdtaKey("io.github.shivytyahoo.glidetube.videoId")).isEqualTo("videoId")
        assertThat(GlideTagFields.fieldFromMdtaKey("com.android.capture.fps")).isNull()
    }

    @Test
    fun `truncate never splits a surrogate pair`() {
        val text = "ab🎵cd"
        assertThat(GlideTagFields.truncate(text, 3)).isEqualTo("ab")
        assertThat(GlideTagFields.truncate(text, 4)).isEqualTo("ab🎵")
        assertThat(GlideTagFields.truncate(text, 100)).isEqualTo(text)
    }
}
