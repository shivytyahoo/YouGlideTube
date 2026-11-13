package io.github.aedev.flow.utils

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import coil3.BitmapImage
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Saves a community post image to the gallery, in Pictures/YouGlideTube. The image is fetched
 * through Coil, so a picture the user already opened comes from the cache instead of a second
 * download. Returns the saved file name, or null when the image could not be fetched or saved.
 */
object ImageGallerySaver {
    suspend fun save(
        context: Context,
        imageUrl: String,
    ): String? =
        withContext(Dispatchers.IO) {
            try {
                val bitmap = fetchBitmap(context, imageUrl) ?: return@withContext null
                writeToGallery(context, bitmap)
            } catch (_: Exception) {
                null
            }
        }

    private suspend fun fetchBitmap(
        context: Context,
        imageUrl: String,
    ): Bitmap? {
        val request =
            ImageRequest
                .Builder(context)
                .data(imageUrl)
                .allowHardware(false)
                .build()
        val result = ImageLoader(context).execute(request)
        return (result as? SuccessResult)?.image
            ?.let { it as? BitmapImage }
            ?.bitmap
    }

    private fun writeToGallery(
        context: Context,
        bitmap: Bitmap,
    ): String? {
        val png = bitmap.hasAlpha()
        val format = if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        val extension = if (png) "png" else "jpg"
        val mimeType = if (png) "image/png" else "image/jpeg"
        val name =
            "YouGlideTube_" +
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) +
                ".$extension"
        val resolver = context.contentResolver
        val values =
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/YouGlideTube",
                    )
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                } else {
                    val dir =
                        File(
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                            "YouGlideTube",
                        )
                    put(MediaStore.Images.Media.DATA, File(dir, name).absolutePath)
                }
            }
        val uri =
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return null
        try {
            resolver.openOutputStream(uri)?.use { out ->
                if (!bitmap.compress(format, 95, out)) return null
            } ?: return null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null,
                    null,
                )
            }
            return name
        } catch (_: Exception) {
            resolver.delete(uri, null, null)
            return null
        }
    }
}
