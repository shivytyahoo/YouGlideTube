package io.github.aedev.flow.ui.components.shared

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.account.AccountPlaylistIds
import io.github.aedev.flow.data.model.Video

@Composable
fun SaveVideoSheet(
    video: Video,
    onDismiss: () -> Unit,
    viewModel: SaveVideoViewModel = hiltViewModel(),
) {
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val watchLaterVideos by viewModel.watchLaterVideos.collectAsStateWithLifecycle()
    val savedIds by viewModel.savedIds.collectAsStateWithLifecycle()
    val isCreating by viewModel.isCreating.collectAsStateWithLifecycle()
    var showCreateDialog by remember { mutableStateOf(false) }

    LaunchedEffect(video.id) { viewModel.loadMembership(video.id) }

    val watchLaterEntry =
        CollectionSheetEntry(
            id = AccountPlaylistIds.WATCH_LATER,
            name = stringResource(R.string.watch_later),
            supporting =
                pluralStringResource(
                    R.plurals.videos_count_template,
                    watchLaterVideos.size,
                    watchLaterVideos.size,
                ),
            thumbnailUrl = watchLaterVideos.firstOrNull()?.thumbnailUrl.orEmpty(),
            isSaved = AccountPlaylistIds.WATCH_LATER in savedIds,
        )
    val entries =
        listOf(watchLaterEntry) +
            // The aggregation carries Watch Later too, and this sheet already renders its own
            // row for it — two rows with id WL crash the keyed list below. Liked videos and
            // Liked music are system collections, not playlists to save to: a liked video
            // would otherwise show a filled bookmark here as if it were saved.
            playlists
                .filterNot {
                    it.id == AccountPlaylistIds.WATCH_LATER ||
                        it.id == AccountPlaylistIds.LIKED_VIDEOS ||
                        it.id == AccountPlaylistIds.LIKED_MUSIC
                }
                .map { playlist ->
                CollectionSheetEntry(
                    id = playlist.id,
                    name = playlist.name,
                    supporting =
                        pluralStringResource(
                            R.plurals.videos_count_template,
                            playlist.videoCount,
                            playlist.videoCount,
                        ),
                    thumbnailUrl = playlist.thumbnailUrl,
                    isSaved = playlist.id in savedIds,
                )
            }

    SaveToCollectionSheet(
        title = stringResource(R.string.save_to),
        entries = entries,
        placeholderIcon = Icons.AutoMirrored.Outlined.PlaylistPlay,
        createLabel = stringResource(R.string.create_new_playlist),
        emptyLabel = stringResource(R.string.no_playlists_found),
        onToggle = { entry -> viewModel.toggle(video, entry.id) },
        onCreateNew = { showCreateDialog = true },
        onDismiss = onDismiss,
    )

    if (showCreateDialog) {
        CollectionEditDialog(
            title = stringResource(R.string.create_new_playlist),
            confirmLabel = stringResource(R.string.create),
            icon = Icons.Default.PlaylistAdd,
            isBusy = isCreating,
            onDismiss = { showCreateDialog = false },
            onConfirm = { name, description ->
                viewModel.createAndAdd(video, name, description) { showCreateDialog = false }
            },
        )
    }
}
