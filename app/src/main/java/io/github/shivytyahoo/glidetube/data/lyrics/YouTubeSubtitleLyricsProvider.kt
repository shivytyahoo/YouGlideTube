//==================================================================================================
//This implementation was based on metrolist's (https://github.com/MetrolistGroup/Metrolist)
//==================================================================================================

package io.github.shivytyahoo.glidetube.data.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class YouTubeSubtitleLyricsProvider : LyricsProvider {
    override val name = "YouTubeSubtitle"

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?
    ): Result<List<LyricsEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            val lrc = io.github.shivytyahoo.glidetube.innertube.YouTube.transcript(id).getOrThrow()
            LyricsUtils.parseLyrics(lrc)
        }
    }
}
