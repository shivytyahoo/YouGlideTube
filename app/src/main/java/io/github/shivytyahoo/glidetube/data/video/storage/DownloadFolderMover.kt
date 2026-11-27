package io.github.shivytyahoo.glidetube.data.video.storage

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.shivytyahoo.glidetube.data.local.dao.DownloadDao
import io.github.shivytyahoo.glidetube.data.local.entity.DownloadFileType
import io.github.shivytyahoo.glidetube.data.local.entity.DownloadItemEntity
import io.github.shivytyahoo.glidetube.data.local.entity.DownloadItemStatus
import io.github.shivytyahoo.glidetube.data.video.VideoDownloadManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Moves every finished download of one kind into a new folder, keeping the collection subfolder
 * a file already sits in, and repointing the library's rows at the new files. A download still
 * running, queued or failed refuses the whole move, so nothing is ever moved from under an
 * active transfer; a failure halfway through puts every file and row already moved back where
 * it was. The database itself never moves — it stays in the app's own data, only its file
 * paths change. Files that already live in a picked folder (documents, not files) cannot be
 * renamed by path and are left where they are, counted as skipped.
 */
@Singleton
class DownloadFolderMover
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val downloadDao: DownloadDao,
        private val downloads: VideoDownloadManager,
    ) {
        companion object {
            private const val TAG = "DownloadFolderMover"
        }

        sealed interface Result {
            data object ActiveDownloads : Result

            data class Done(
                val moved: Int,
                val total: Int,
                val skipped: Int,
            ) : Result

            data object Failed : Result
        }

        suspend fun move(
            isMusic: Boolean,
            location: DownloadLocation,
        ): Result =
            withContext(Dispatchers.IO) {
                val fileType = if (isMusic) DownloadFileType.AUDIO else DownloadFileType.VIDEO
                val relevant = downloadDao.getAllDownloadsWithItemsOnce().filter { it.isAudioOnly == isMusic }
                if (relevant.any { it.overallStatus != DownloadItemStatus.COMPLETED }) {
                    return@withContext Result.ActiveDownloads
                }
                val destination = downloads.resolveDestination(fileType, location)
                if (destination.fellBack) return@withContext Result.Failed
                val oldBase =
                    downloads.resolveDestination(fileType, downloads.savedLocation(isMusic)).directory.canonicalFile
                val moved = mutableListOf<MovedItem>()
                var skipped = 0
                var total = 0
                try {
                    for (download in relevant) {
                        for (item in download.items.filter { it.status == DownloadItemStatus.COMPLETED }) {
                            total++
                            when (val outcome = moveItem(item, oldBase, destination)) {
                                is ItemMove.Moved -> moved += outcome.record
                                ItemMove.Skipped -> skipped++
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Folder move failed, rolling back ${moved.size} files", e)
                    rollback(moved)
                    return@withContext Result.Failed
                }
                Result.Done(moved = moved.size, total = total, skipped = skipped)
            }

        private sealed interface ItemMove {
            data class Moved(
                val record: MovedItem,
            ) : ItemMove

            data object Skipped : ItemMove
        }

        private data class MovedItem(
            val itemId: Int,
            val oldPath: String,
            val oldFileName: String,
            val newPath: String,
            val isDocument: Boolean,
        )

        private class MoveFailed(
            message: String,
        ) : Exception(message)

        private suspend fun moveItem(
            item: DownloadItemEntity,
            oldBase: File,
            destination: DownloadDestination,
        ): ItemMove {
            if (DownloadFiles.isDocument(item.filePath)) return ItemMove.Skipped
            val source = File(item.filePath)
            if (!source.exists()) return ItemMove.Skipped
            val tree = destination.exportTreeUri
            return if (tree != null) {
                moveItemToTree(item, source, oldBase, tree)
            } else {
                moveItemToDirectory(item, source, oldBase, destination.directory)
            }
        }

        private suspend fun moveItemToDirectory(
            item: DownloadItemEntity,
            source: File,
            oldBase: File,
            directory: File,
        ): ItemMove {
            val targetDir = subfolderOf(source, oldBase)?.let { File(directory, it) } ?: directory
            if (runCatching { source.parentFile?.canonicalFile == targetDir.canonicalFile }.getOrDefault(false)) {
                return ItemMove.Skipped
            }
            if (!targetDir.isDirectory && !targetDir.mkdirs()) throw MoveFailed("Cannot create $targetDir")
            val name = DownloadNaming.unique(item.fileName) { File(targetDir, it).exists() }
            val moved =
                DownloadFiles.moveInto(source, targetDir, name)
                    ?: throw MoveFailed("Cannot move ${source.name} to $targetDir")
            downloadDao.updateItemLocation(item.id, moved.absolutePath, moved.name)
            return ItemMove.Moved(MovedItem(item.id, item.filePath, item.fileName, moved.absolutePath, false))
        }

        private suspend fun moveItemToTree(
            item: DownloadItemEntity,
            source: File,
            oldBase: File,
            tree: String,
        ): ItemMove {
            // A tree writer can only make one folder at its root, so only a single-level
            // collection folder is kept; deeper nesting is flattened into the tree root.
            val subfolder = subfolderOf(source, oldBase)?.takeIf { File.separatorChar !in it }
            val parent = subfolder?.let { DownloadFiles.ensureTreeDirectory(context, tree, it) }
            val name = DownloadNaming.unique(item.fileName) { DownloadFiles.treeHasChild(context, tree, parent, it) }
            val document =
                DownloadFiles.exportToTree(context, source, tree, name, parent)
                    ?: throw MoveFailed("Cannot export ${source.name} to $tree")
            if (!source.delete()) {
                DownloadFiles.deleteDocument(context, document)
                throw MoveFailed("Cannot remove ${source.absolutePath} after export")
            }
            val fileName = DownloadFiles.displayName(context, document) ?: name
            downloadDao.updateItemLocation(item.id, document, fileName)
            return ItemMove.Moved(MovedItem(item.id, item.filePath, item.fileName, document, true))
        }

        // The collection folder a file sits in, relative to the folder downloads live in now, or
        // null when the file sits directly in it — or anywhere else, which is placed at the root.
        private fun subfolderOf(
            source: File,
            oldBase: File,
        ): String? {
            val parent = source.parentFile?.canonicalFile ?: return null
            val relative = runCatching { parent.relativeTo(oldBase) }.getOrNull() ?: return null
            if (relative.path.isEmpty()) return null
            if (relative.path == ".." || relative.path.startsWith("..${File.separatorChar}")) return null
            return relative.path
        }

        // Best effort, newest first: a file that cannot be put back keeps its row pointing at the
        // new location, where the file actually is, rather than at a path that no longer holds it.
        private suspend fun rollback(moved: List<MovedItem>) {
            for (record in moved.asReversed()) {
                runCatching {
                    if (record.isDocument) {
                        val restored = File(record.oldPath)
                        restored.parentFile?.mkdirs()
                        context.contentResolver.openInputStream(Uri.parse(record.newPath))?.use { input ->
                            restored.outputStream().use { input.copyTo(it) }
                        } ?: return@runCatching
                        DownloadFiles.deleteDocument(context, record.newPath)
                        downloadDao.updateItemLocation(record.itemId, record.oldPath, record.oldFileName)
                    } else {
                        val current = File(record.newPath)
                        if (!current.exists()) return@runCatching
                        val parent = File(record.oldPath).parentFile ?: return@runCatching
                        val back = DownloadFiles.moveInto(current, parent, record.oldFileName) ?: return@runCatching
                        downloadDao.updateItemLocation(record.itemId, back.absolutePath, back.name)
                    }
                }.onFailure { Log.w(TAG, "Rollback of ${record.oldPath} failed", it) }
            }
        }
    }
