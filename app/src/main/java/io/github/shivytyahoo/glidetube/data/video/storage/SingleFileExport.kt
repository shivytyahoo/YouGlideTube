package io.github.shivytyahoo.glidetube.data.video.storage

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.shivytyahoo.glidetube.data.local.entity.DownloadFileType
import io.github.shivytyahoo.glidetube.data.local.entity.DownloadItemEntity
import io.github.shivytyahoo.glidetube.data.local.entity.DownloadItemStatus
import io.github.shivytyahoo.glidetube.data.local.entity.DownloadWithItems
import io.github.shivytyahoo.glidetube.data.video.VideoDownloadManager
import io.github.shivytyahoo.glidetube.data.video.downloader.tags.Mp4Remuxer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A finished download as one file in the downloads folder, so the viewer can share it or open it
 * in another app. Normally that is the merged file the downloader already produced, copied out;
 * only a legacy download that still holds its video and audio as separate files is remuxed
 * first, through the same muxer the download itself uses. The library's own copy and its
 * database rows are left alone — the export is a second file.
 */
@Singleton
class SingleFileExport
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val downloads: VideoDownloadManager,
        private val remuxer: Mp4Remuxer,
    ) {
        companion object {
            private const val TAG = "SingleFileExport"
            private const val TITLE_MAX_BYTES = 120
        }

        sealed interface Result {
            data class Success(
                val path: String,
                val fileName: String,
            ) : Result

            data class Failure(
                val reason: Reason,
            ) : Result
        }

        enum class Reason { NOT_COMPLETED, NO_FILE, MERGE_FAILED, SAVE_FAILED }

        suspend fun export(download: DownloadWithItems): Result =
            withContext(Dispatchers.IO) {
                if (download.overallStatus != DownloadItemStatus.COMPLETED) {
                    return@withContext Result.Failure(Reason.NOT_COMPLETED)
                }
                val completed = download.items.filter { it.status == DownloadItemStatus.COMPLETED }
                val video = completed.firstOrNull { it.fileType == DownloadFileType.VIDEO }
                val audios = completed.filter { it.fileType == DownloadFileType.AUDIO }
                val single = video ?: audios.firstOrNull() ?: return@withContext Result.Failure(Reason.NO_FILE)
                val extension = if (download.isAudioOnly) "m4a" else "mp4"
                val base =
                    DownloadNaming.truncateToBytes(
                        DownloadNaming.sanitize(download.download.title, download.download.videoId),
                        TITLE_MAX_BYTES,
                    )
                val name = "$base.$extension"
                val tempDir = File(context.cacheDir, "single_file_export").apply { mkdirs() }
                val temps = mutableListOf<File>()
                try {
                    val source =
                        if (video != null && audios.any { it.filePath != video.filePath }) {
                            merge(video, audios, tempDir, temps, extension)
                                ?: return@withContext Result.Failure(Reason.MERGE_FAILED)
                        } else {
                            localFile(single.filePath, tempDir, temps)
                                ?: return@withContext Result.Failure(Reason.NO_FILE)
                        }
                    place(source, name, download.isAudioOnly)
                } finally {
                    temps.forEach { it.delete() }
                }
            }

        // A video and its audio as separate files become one, the way a fresh download is merged.
        private fun merge(
            video: DownloadItemEntity,
            audios: List<DownloadItemEntity>,
            tempDir: File,
            temps: MutableList<File>,
            extension: String,
        ): File? {
            val videoFile = localFile(video.filePath, tempDir, temps) ?: return null
            val audioInputs =
                audios.mapNotNull { audio ->
                    localFile(audio.filePath, tempDir, temps)?.let { Mp4Remuxer.AudioInput(path = it.absolutePath) }
                }
            if (audioInputs.size != audios.size) return null
            val output = File(tempDir, "merged.$extension").also { temps += it }
            val merged =
                runCatching {
                    remuxer.remux(
                        videoPath = videoFile.absolutePath,
                        audioInputs = audioInputs,
                        outputPath = output.absolutePath,
                    )
                }.onFailure { Log.w(TAG, "Remux for single-file export failed", it) }
                    .getOrNull()
            if (merged !is Mp4Remuxer.Result.Success || !output.exists()) {
                Log.w(TAG, "Remux for single-file export failed: $merged")
                return null
            }
            return output
        }

        // A plain file is used as it is; a document in a picked folder is staged into the cache
        // first, because the muxer and the tree writer both need a file path. Staged copies are
        // recorded in [temps] so the caller can delete them; the original is never touched.
        private fun localFile(
            path: String,
            tempDir: File,
            temps: MutableList<File>,
        ): File? {
            if (!DownloadFiles.isDocument(path)) {
                return File(path).takeIf { it.exists() }
            }
            val staged = File(tempDir, "part${temps.size}")
            return runCatching {
                context.contentResolver.openInputStream(Uri.parse(path))?.use { input ->
                    staged.outputStream().use { input.copyTo(it) }
                } ?: return null
                temps += staged
                staged
            }.onFailure { Log.w(TAG, "Could not stage $path for single-file export", it) }
                .getOrNull()
        }

        private suspend fun place(
            source: File,
            name: String,
            isMusic: Boolean,
        ): Result {
            val fileType = if (isMusic) DownloadFileType.AUDIO else DownloadFileType.VIDEO
            val destination = downloads.resolveDestination(fileType, downloads.savedLocation(isMusic))
            val tree = destination.exportTreeUri
            if (tree != null) {
                val unique = DownloadNaming.unique(name) { DownloadFiles.treeHasChild(context, tree, null, it) }
                val document =
                    DownloadFiles.exportToTree(context, source, tree, unique)
                        ?: return Result.Failure(Reason.SAVE_FAILED)
                return Result.Success(document, DownloadFiles.displayName(context, document) ?: unique)
            }
            val directory = destination.directory
            if (!directory.isDirectory && !directory.mkdirs()) return Result.Failure(Reason.SAVE_FAILED)
            val unique = DownloadNaming.unique(name) { File(directory, it).exists() }
            val target = File(directory, unique)
            runCatching { source.copyTo(target, overwrite = false) }
                .onFailure {
                    target.delete()
                    Log.w(TAG, "Could not copy ${source.name} to $directory", it)
                    return Result.Failure(Reason.SAVE_FAILED)
                }
            downloads.scanFile(target.absolutePath, if (isMusic) "audio/mp4" else "video/mp4")
            return Result.Success(target.absolutePath, unique)
        }
    }
