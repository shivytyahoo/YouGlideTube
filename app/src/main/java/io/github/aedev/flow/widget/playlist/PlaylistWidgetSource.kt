package io.github.aedev.flow.widget.playlist

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.R
import io.github.aedev.flow.data.account.AccountPlaylistIds
import io.github.aedev.flow.data.account.AccountPlaylists
import io.github.aedev.flow.data.model.Playlist
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.repository.YouTubePlaylistRepository
import io.github.aedev.flow.widget.core.refresh.WidgetContentKey
import io.github.aedev.flow.widget.core.refresh.WidgetContentSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** A playlist as its widget draws it; [coverUrl] falls back to the first item when the playlist has none. */
data class WidgetPlaylist(
    val id: String,
    val name: String,
    val count: Int,
    val coverUrl: String?,
    val isMusic: Boolean,
)

/** What one Playlist widget draws: the playlist, or null once it is gone, and as many rows as the list holds. */
data class WidgetPlaylistContent(
    val playlist: WidgetPlaylist?,
    val items: List<Video>,
)

/**
 * The Playlist widget's data, account-only: the picker lists the account's playlists (Watch
 * later first) and a widget's rows are read from the account's playlist pages each refresh.
 */
@Singleton
class PlaylistWidgetSource
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val accountPlaylists: AccountPlaylists,
        private val remotePlaylists: YouTubePlaylistRepository,
    ) : WidgetContentSource {
        override val key = WidgetContentKey.PLAYLISTS
        override val widget: GlanceAppWidget get() = PlaylistWidget()

        /** Every playlist the widget can show, Watch later first, then the account's playlists. */
        fun options(): Flow<List<WidgetPlaylist>> =
            accountPlaylists.playlists.map { playlists ->
                val watchLater =
                    WidgetPlaylist(
                        id = AccountPlaylistIds.WATCH_LATER,
                        name = context.getString(R.string.watch_later),
                        count = remotePlaylists.cachedComplete(AccountPlaylistIds.WATCH_LATER)?.videos?.size ?: 0,
                        coverUrl = null,
                        isMusic = false,
                    )
                listOf(watchLater) +
                    playlists
                        .filterNot { it.id == AccountPlaylistIds.WATCH_LATER }
                        .map { it.toWidget() }
            }

        /** One playlist and its first rows, read from the account's pages. */
        fun content(id: String): Flow<WidgetPlaylistContent> =
            flow {
                val videos = remotePlaylists.complete(id)?.videos.orEmpty()
                val option = options().first().firstOrNull { it.id == id }
                emit(
                    WidgetPlaylistContent(
                        playlist =
                            option?.let {
                                if (it.coverUrl.isNullOrBlank()) {
                                    it.copy(coverUrl = videos.firstOrNull()?.thumbnailUrl)
                                } else {
                                    it
                                }
                            },
                        items = videos.take(MAX_ITEMS),
                    ),
                )
            }.distinctUntilChanged()

        /** Nothing is cached locally anymore; a refresh simply re-reads the account's pages. */
        suspend fun refresh(id: String) {
            remotePlaylists.complete(id)
        }

        override fun changes(): Flow<Unit> =
            options()
                .map { signature() }
                .distinctUntilChanged()
                .map { }

        override suspend fun signature(): String =
            chosenPlaylistIds()
                .map { id ->
                    val content = content(id).first()
                    val playlist = content.playlist?.let { "${it.name}:${it.count}:${it.coverUrl}" }
                    "$id=$playlist=" + content.items.joinToString(",") { "${it.id}:${it.title}:${it.thumbnailUrl}" }
                }.joinToString(";")

        private suspend fun chosenPlaylistIds(): List<String> =
            GlanceAppWidgetManager(context)
                .getGlanceIds(PlaylistWidget::class.java)
                .map { PlaylistWidgetConfig.load(context, it) ?: DEFAULT_PLAYLIST_ID }
                .distinct()

        private fun Playlist.toWidget() =
            WidgetPlaylist(
                id = id,
                name = name,
                count = videoCount,
                coverUrl = thumbnailUrl.takeIf { it.isNotBlank() },
                isMusic = false,
            )

        companion object {
            /** What a widget shows before a playlist is chosen, and what the picker starts on. */
            const val DEFAULT_PLAYLIST_ID = AccountPlaylistIds.WATCH_LATER

            // Each row carries its own thumbnail bitmap in the RemoteViews, which has a size cap.
            const val MAX_ITEMS = 12
        }
    }
