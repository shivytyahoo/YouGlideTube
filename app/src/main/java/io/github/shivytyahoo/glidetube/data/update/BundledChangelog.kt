package io.github.shivytyahoo.glidetube.data.update

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The changelog the app ships for a version ("1.1.2" lives at changelog/v1.1.2.txt): a header
 * with the version and date, then one bullet per change. Null when the app ships no asset
 * for the version, so callers can fall back to the release body.
 */
suspend fun loadBundledChangelog(
    context: Context,
    versionName: String,
): List<String>? {
    val result =
        withContext(Dispatchers.IO) {
            runCatching {
                context.assets
                    .open("changelog/v$versionName.txt")
                    .bufferedReader()
                    .use { reader -> reader.readText() }
            }
        }
    return result.getOrNull()?.let { parseBundledChangelog(it) }
}

/** Drops the header lines and blanks, keeping one change per line without its bullet. */
fun parseBundledChangelog(text: String): List<String> =
    text
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("VERSION:") && !it.startsWith("DATE:") }
        .map { it.trimStart('•', '-', '*', ' ').trim() }
        .filter { it.isNotEmpty() }
        .toList()
