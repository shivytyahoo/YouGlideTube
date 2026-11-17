// ==================================================================================================
// This implementation was based on metrolist's (https://github.com/MetrolistGroup/Metrolist)
// ==================================================================================================

package io.github.shivytyahoo.glidetube.data.lyrics

import io.github.shivytyahoo.glidetube.data.lyrics.kugou.KuGou
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

class KuGouLyricsProvider(
    client: OkHttpClient,
) : LyricsProvider {
    override val name = "KuGou"

    private val kuGou = KuGou(client)

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<List<LyricsEntry>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val lrc = kuGou.getLyrics(title, artist, duration, album).getOrThrow()
                LyricsUtils.parseLyrics(lrc)
            }
        }
}
