package io.github.aedev.flow.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.aedev.flow.player.preview.FeedPreviewController
import kotlinx.coroutines.delay
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.VideoHistoryEntry
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.ui.components.FeedGridLayout
import io.github.aedev.flow.ui.components.home.ContinueWatchingShelf
import io.github.aedev.flow.ui.components.home.ContinueWatchingShelfDefaults
import io.github.aedev.flow.ui.components.layout.flowBottomContentPadding
import io.github.aedev.flow.ui.components.shared.FeedPreviewSurface
import io.github.aedev.flow.ui.components.shared.FlowFeedProgress
import io.github.aedev.flow.ui.components.shared.LocalBatterySaver
import io.github.aedev.flow.ui.components.shared.LocalDpadNavigation
import io.github.aedev.flow.ui.components.shared.MediaShortsShelf
import io.github.aedev.flow.ui.components.shared.card.MediaVideoCard
import io.github.aedev.flow.ui.components.shared.card.VideoCardLayout
import io.github.aedev.flow.ui.components.shared.dpadFocusGroup
import io.github.aedev.flow.ui.components.shared.feedStripCardWidth
import io.github.aedev.flow.ui.components.shared.rememberFeedGridPlan

private const val FEED_FOOTER_MIN_VIDEOS = 100

/** Scroll must rest this long before the most-visible card starts its muted preview. */
private const val PREVIEW_DWELL_MS = 400L
private val FeedTopPadding = 4.dp

@Composable
internal fun HomeFeedGrid(
    uiState: HomeUiState,
    feedLayout: FeedGridLayout,
    isListView: Boolean,
    gridState: LazyGridState,
    onVideoClick: (Video) -> Unit,
    onEnrichChannelMetadata: (Video) -> Unit,
    onContinueWatchingClick: (VideoHistoryEntry) -> Unit,
    onContinueWatchingRemove: (String) -> Unit,
    onShortClick: (List<Video>, Video) -> Unit,
    onSeeAllHistory: () -> Unit,
    onOpenShortsFeed: () -> Unit,
    onShortsShown: (ids: List<String>) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    onRetryLoadMore: () -> Unit = {},
    footerMinVideos: Int = FEED_FOOTER_MIN_VIDEOS,
    showShorts: Boolean = true,
) {
    val videos = uiState.videos
    val channelMemoryReason = stringResource(R.string.home_reason_channel_memory)
    val rows =
        remember(videos, feedLayout.columns, uiState.continueWatchingVideos.isNotEmpty(), showShorts && uiState.shorts.isNotEmpty()) {
            homeFeedRows(
                videos = videos,
                columns = feedLayout.columns,
                showContinueWatching = uiState.continueWatchingVideos.isNotEmpty(),
                showShorts = showShorts && uiState.shorts.isNotEmpty(),
            )
        }
    val plan =
        rememberFeedGridPlan(
            layout = feedLayout,
            listMode = isListView,
            itemCount = rows.size,
            spansOwnRow = { rows[it].spansRow },
            includeLastRun = !uiState.hasMorePages,
            itemsKey = rows,
            compactRowSpacing = if (isListView) 0.dp else feedLayout.cardSpacing,
        )
    val stripCardWidth = feedStripCardWidth(feedLayout.rowWidth, ContinueWatchingShelfDefaults.CardWidth)

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewVideoId by FeedPreviewController.previewVideoId.collectAsState()
    val previewReady by FeedPreviewController.previewReady.collectAsState()

    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_PAUSE -> FeedPreviewController.pause()
                    Lifecycle.Event.ON_RESUME -> FeedPreviewController.resume()
                    else -> Unit
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(Unit) { onDispose { FeedPreviewController.release() } }

    // The most-visible card previews once scrolling settles; the dwell keeps a fast fling
    // from minting an extraction per card it passes. Live and upcoming videos never preview.
    // A Shorts shelf previews its first visible short the same way, fed by the shelf's own
    // visible-ids report, so the shelf is no longer the one row that never previews. The
    // effect restarts when that id changes, so a horizontal shelf swipe re-targets the
    // preview without waiting for a vertical scroll.
    // On TV there is no scroll to settle: the remote moves focus, so the preview follows
    // the focused card instead of the top-most visible one — otherwise the first video of
    // the top row would keep previewing no matter which card the pointer sits on.
    val isDpad = LocalDpadNavigation.current
    val batterySaver = LocalBatterySaver.current
    var focusedVideoId by remember { mutableStateOf<String?>(null) }
    var shelfFirstVisibleShortId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(gridState, rows, shelfFirstVisibleShortId, focusedVideoId, batterySaver) {
        // Battery saver: no feed previews at all — stop any playing one and stay stopped
        // until the saver is off again (the key change restarts the effect then).
        if (batterySaver) {
            FeedPreviewController.stop()
            return@LaunchedEffect
        }
        snapshotFlow { gridState.isScrollInProgress }.collect { scrolling ->
            if (scrolling) return@collect
            delay(PREVIEW_DWELL_MS)
            val info = gridState.layoutInfo
            val viewportHeight = info.viewportSize.height
            val fallback =
                info.visibleItemsInfo.firstNotNullOfOrNull { item ->
                    val row = rows.getOrNull(item.index) as? HomeFeedRow.VideoCard ?: return@firstNotNullOfOrNull null
                    val video = row.video
                    if (video.isLive || video.isUpcoming) null else video.id
                }
            val firstFullyVisible =
                info.visibleItemsInfo.firstOrNull { item ->
                    item.offset.y >= 0 && item.offset.y + item.size.height <= viewportHeight
                }?.let { rows.getOrNull(it.index) }
            val viewportTarget =
                when (firstFullyVisible) {
                    is HomeFeedRow.Shorts -> shelfFirstVisibleShortId
                    is HomeFeedRow.VideoCard ->
                        firstFullyVisible.video.takeIf { !it.isLive && !it.isUpcoming }?.id ?: fallback
                    else -> fallback
                }
            val target = if (isDpad && focusedVideoId != null) focusedVideoId else viewportTarget
            if (target != null) FeedPreviewController.play(context, target) else FeedPreviewController.stop()
        }
    }

    LazyVerticalGrid(
        columns = plan.cells,
        modifier =
            modifier
                .fillMaxSize()
                .dpadFocusGroup()
                .testTag("home_feed"),
        state = gridState,
        contentPadding = plan.contentPadding(top = FeedTopPadding, bottom = flowBottomContentPadding()),
        verticalArrangement = Arrangement.spacedBy(plan.rowSpacing),
    ) {
        itemsIndexed(
            items = rows,
            key = { _, row -> row.key },
            contentType = { _, row -> row::class },
            span = { index, row -> plan.span(index, row.spansRow, maxLineSpan) },
        ) { index, row ->
            when (row) {
                is HomeFeedRow.VideoCard -> {
                    LaunchedEffect(row.video.id, row.video.channelId, row.video.channelThumbnailUrl) {
                        onEnrichChannelMetadata(row.video)
                    }
                    MediaVideoCard(
                        video = row.video,
                        layout = if (plan.isListCard(index)) VideoCardLayout.Row else VideoCardLayout.Stacked,
                        onClick = { onVideoClick(row.video) },
                        useInternalPadding = !feedLayout.cardsBleed,
                        thumbnailWidth = plan.listThumbnailWidth,
                        reason = channelMemoryReason.takeIf { uiState.origins[row.video.id]?.kind == HomeCardOrigin.Kind.CHANNEL_MEMORY },
                        whyShown = uiState.origins[row.video.id]?.label(),
                        modifier = Modifier.testTag("home_video_card"),
                        previewContent =
                            if (!plan.isListCard(index) && row.video.id == previewVideoId) {
                                { FeedPreviewSurface(previewReady) }
                            } else {
                                null
                            },
                        onFocusChanged = { focused ->
                            if (isDpad) focusedVideoId = if (focused) row.video.id else null
                        },
                    )
                }

                HomeFeedRow.ContinueWatching -> {
                    ContinueWatchingShelf(
                        entries = uiState.continueWatchingVideos,
                        onVideoClick = { videoId ->
                            uiState.continueWatchingVideos
                                .find { it.videoId == videoId }
                                ?.let(onContinueWatchingClick)
                        },
                        onRemove = onContinueWatchingRemove,
                        onSeeAllClick = onSeeAllHistory,
                        cardWidth = stripCardWidth,
                        modifier = Modifier.testTag("home_continue_watching_shelf"),
                    )
                }

                HomeFeedRow.Shorts -> {
                    MediaShortsShelf(
                        shorts = uiState.shorts,
                        onShortClick = onShortClick,
                        onSeeAllClick = onOpenShortsFeed,
                        onShortsShown = { ids ->
                            onShortsShown(ids)
                            shelfFirstVisibleShortId = ids.firstOrNull()
                        },
                        previewVideoId = previewVideoId,
                        previewReady = previewReady,
                        modifier = Modifier.testTag("home_shorts_shelf"),
                    )
                }
            }
        }

        if (uiState.isLoadingMore) {
            item(
                key = "loading_indicator",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                FlowFeedProgress()
            }
        }

        if (uiState.loadMoreFailed && !uiState.isLoadingMore) {
            item(
                key = "load_more_failed",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                HomeLoadMoreFailed(onRetry = onRetryLoadMore)
            }
        }

        if (!uiState.hasMorePages && videos.size > footerMinVideos && !uiState.isLoadingMore) {
            item(
                key = "feed_footer",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                FlowFeedFooter(
                    videoCount = videos.size,
                    onRefresh = onRefresh,
                )
            }
        }
    }
}
