package io.github.shivytyahoo.glidetube.utils

import android.content.Context
import android.net.Uri
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A content URI other apps can read for [file], which must sit in a cache folder the provider lists. */
fun sharedFileUri(
    context: Context,
    file: File,
): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)

private const val PNG_QUALITY = 100

/** Writes the bitmap to [out] as a PNG. A hardware bitmap is copied first, since it cannot be compressed. */
fun Bitmap.writePngTo(out: OutputStream): Boolean {
    val software = if (config == Bitmap.Config.HARDWARE) copy(Bitmap.Config.ARGB_8888, false) else this
    return software.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
}

/**
 * Writes [bitmap] to the cache folder [dir] as [name] and returns a URI other apps can read, or
 * null when it could not be written. One name per folder, so each share replaces the last one.
 */
suspend fun sharedPngUri(
    context: Context,
    bitmap: Bitmap,
    dir: String,
    name: String,
): Uri? =
    withContext(Dispatchers.IO) {
        runCatching {
            val file = File(File(context.cacheDir, dir).apply { mkdirs() }, name)
            val written = file.outputStream().use { out -> bitmap.writePngTo(out) }
            if (written) sharedFileUri(context, file) else null
        }.getOrNull()
    }
