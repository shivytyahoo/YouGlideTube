package io.github.aedev.flow.data.video

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.model.Video
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

/** A video queue as it was left: its videos, the one playing and the title it was started under. */
data class SavedVideoQueue(
    val videos: List<Video>,
    val index: Int,
    val title: String?,
)

@Serializable
internal data class StoredQueue(
    val items: List<StoredQueueItem>,
    val index: Int,
    val title: String? = null,
)

@Serializable
internal data class StoredQueueItem(
    val id: String,
    val title: String,
    val channelName: String,
    val channelId: String,
    val thumbnailUrl: String,
    val duration: Int,
    val isMusic: Boolean = false,
    val isShort: Boolean = false,
)

/**
 * Keeps the video queue for this session, the way the music player keeps its own. It used to
 * survive process death through a DataStore; that store is gone, so the queue is memory-only.
 */
@Singleton
class VideoQueueStore
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        @Volatile
        private var stored: StoredQueue? = null

        suspend fun save(
            videos: List<Video>,
            index: Int,
            title: String?,
        ) {
            stored =
                if (videos.size < 2 || index !in videos.indices) {
                    null
                } else {
                    StoredQueue(videos.map { it.toStored() }, index, title)
                }
        }

        suspend fun load(): SavedVideoQueue? {
            val current = stored ?: return null
            return SavedVideoQueue(current.items.map { it.toVideo() }, current.index, current.title)
                .takeIf { it.index in it.videos.indices }
        }
    }

private fun Video.toStored() = StoredQueueItem(id, title, channelName, channelId, thumbnailUrl, duration, isMusic, isShort)

private fun StoredQueueItem.toVideo() =
    Video(
        id = id,
        title = title,
        channelName = channelName,
        channelId = channelId,
        thumbnailUrl = thumbnailUrl,
        duration = duration,
        viewCount = 0,
        uploadDate = "",
        isMusic = isMusic,
        isShort = isShort,
    )
