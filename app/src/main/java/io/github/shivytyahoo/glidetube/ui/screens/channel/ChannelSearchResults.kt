package io.github.shivytyahoo.glidetube.ui.screens.channel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.shivytyahoo.glidetube.R
import io.github.shivytyahoo.glidetube.data.local.HomeFeedColumns
import io.github.shivytyahoo.glidetube.data.model.Video
import io.github.shivytyahoo.glidetube.ui.components.rememberFeedGridLayout
import io.github.shivytyahoo.glidetube.ui.components.shared.GlideEmptyState
import io.github.shivytyahoo.glidetube.ui.components.shared.GlideLoadingIndicator
import io.github.shivytyahoo.glidetube.ui.components.shared.card.MediaVideoCard
import io.github.shivytyahoo.glidetube.ui.components.shared.card.VideoCardLayout
import io.github.shivytyahoo.glidetube.ui.components.shared.rememberFeedGridPlan

@Composable
internal fun ChannelSearchResults(
    uiState: ChannelUiState,
    listState: LazyGridState,
    contentPadding: PaddingValues,
    topInset: Dp,
    isGridView: Boolean,
    columnPreference: HomeFeedColumns,
    onVideoClick: (Video) -> Unit,
    onRetry: () -> Unit,
) {
    when {
        uiState.isSearching -> {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(top = topInset),
                contentAlignment = Alignment.Center,
            ) { GlideLoadingIndicator() }
        }

        uiState.searchErrorLog != null -> {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(top = topInset),
                contentAlignment = Alignment.Center,
            ) {
                ChannelRequestErrorState(
                    message = stringResource(R.string.channel_search_failed),
                    errorLog = uiState.searchErrorLog,
                    onRetry = onRetry,
                )
            }
        }

        uiState.searchResults.isEmpty() -> {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(top = topInset),
                contentAlignment = Alignment.Center,
            ) {
                GlideEmptyState(title = stringResource(R.string.channel_search_no_results, uiState.searchQuery))
            }
        }

        else -> {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val feedLayout = rememberFeedGridLayout(maxWidth, columnPreference)
                val plan =
                    rememberFeedGridPlan(
                        layout = feedLayout,
                        listMode = !isGridView,
                        itemCount = uiState.searchResults.size,
                        spansOwnRow = { false },
                        includeLastRun = true,
                        itemsKey = uiState.searchResults,
                        compactRowSpacing = if (!isGridView) 0.dp else feedLayout.cardSpacing,
                    )
                val padding =
                    remember(contentPadding, plan.horizontalPadding) {
                        plan.contentPadding(
                            top = contentPadding.calculateTopPadding(),
                            bottom = contentPadding.calculateBottomPadding(),
                        )
                    }
                LazyVerticalGrid(
                    columns = plan.cells,
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = padding,
                    verticalArrangement = Arrangement.spacedBy(plan.rowSpacing),
                ) {
                    itemsIndexed(
                        items = uiState.searchResults,
                        key = { _, video -> "search_${video.id}" },
                        contentType = { _, _ -> "video" },
                        span = { index, _ -> plan.span(index, spansOwnRow = false, maxLineSpan = maxLineSpan) },
                    ) { index, video ->
                        MediaVideoCard(
                            video = video,
                            layout = if (plan.isListCard(index)) VideoCardLayout.Row else VideoCardLayout.Stacked,
                            showChannel = false,
                            onClick = { onVideoClick(video) },
                            thumbnailWidth = plan.listThumbnailWidth,
                        )
                    }
                    item(key = "bottom_gap", span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}
