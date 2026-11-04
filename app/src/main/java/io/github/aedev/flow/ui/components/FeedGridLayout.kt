package io.github.aedev.flow.ui.components

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import io.github.aedev.flow.data.local.HomeFeedColumns
import io.github.aedev.flow.ui.components.shared.card.VideoCardDefaults
import io.github.aedev.flow.ui.theme.Dimensions

/**
 * Narrowest a grid cell may be before the grid drops a column: a 260 dp card plus the 12 dp gutter
 * the grid used to add between cells, so the counts the per-screen width tables produced survive.
 */
val FeedCardMinWidth = 272.dp

/** Widest a capped cell may grow before a very wide window adds a column anyway. */
private val FeedCardMaxWidth = 460.dp

private class FeedGridSpacing(
    val margin: Dp,
    val rowSpacing: Dp,
)

private val CompactWindowSpacing = FeedGridSpacing(margin = 0.dp, rowSpacing = Dimensions.ItemSpacing)
private val WideWindowSpacing = FeedGridSpacing(margin = 16.dp, rowSpacing = Dimensions.ItemSpacing)
private val LargeWindowSpacing = FeedGridSpacing(margin = 24.dp, rowSpacing = 16.dp)

/**
 * The single layout decision behind every vertical video grid, so every screen sizes the same cards
 * the same way.
 *
 * A compact window is one column of boxed cards: every card keeps its inset, so
 * there is a gap at each edge instead of a full-bleed strip. Wider windows fill
 * with as many cells of at least [FeedCardMinWidth] as fit, capped by
 * `maxAutoColumns` until a cell would pass [FeedCardMaxWidth].
 *
 * Each card insets itself by [VideoCardDefaults.Inset], so the grid pads
 * the window margin minus that inset: thumbnails land on the 16/24 dp margin, two cards' insets make
 * the gap between them, and a thumbnail-left row lines up with the column above it.
 */
data class FeedGridLayout(
    val cells: GridCells,
    val columns: Int,
    /** Horizontal padding of the grid itself; the cards' own inset brings thumbnails to the margin. */
    val contentPadding: Dp,
    /** Vertical space between rows of cards. */
    val cardSpacing: Dp,
    val isCompact: Boolean,
    /** How wide one cell of this grid is, inset included. */
    val cardWidth: Dp,
    /**
     * The thumbnail of an inset card in one cell, for rows that have to line up with the grid. A
     * wide grid pinned to one column sizes it to the automatic column instead of the whole width.
     */
    val thumbnailWidth: Dp = cardWidth - VideoCardDefaults.Inset * 2,
) {
    /** The width between the grid's own padding, for strips that span a whole row. */
    val rowWidth: Dp get() = cardWidth * columns

    /**
     * Whether stacked cards run edge to edge. Never: every card keeps its inset, so a
     * single compact column reads as a box with a gap at each edge instead of a
     * full-bleed strip — the Material 3 single-pane convention, deliberately not
     * followed, because the cards are meant to look like Morphe's boxes everywhere.
     */
    val cardsBleed: Boolean get() = false
}

/**
 * [columnPreference] pins the count the user chose in settings; [maxAutoColumns] caps only the
 * automatic derivation. [minCellWidth] is the narrowest cell before a column drops; a TV canvas,
 * seen from across the room at 960 dp wide, fits a fourth column by passing a smaller one.
 */
fun feedGridLayoutFor(
    maxWidth: Dp,
    columnPreference: HomeFeedColumns = HomeFeedColumns.AUTO,
    maxAutoColumns: Int = FEED_MAX_AUTO_COLUMNS,
    minCellWidth: Dp = FeedCardMinWidth,
): FeedGridLayout {
    val isCompact = maxWidth < WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND.dp
    val spacing =
        when {
            maxWidth >= WindowSizeClass.WIDTH_DP_LARGE_LOWER_BOUND.dp -> LargeWindowSpacing
            isCompact -> CompactWindowSpacing
            else -> WideWindowSpacing
        }
    val contentPadding = (spacing.margin - VideoCardDefaults.Inset).coerceAtLeast(0.dp)
    val availableWidth = (maxWidth - contentPadding * 2).coerceAtLeast(0.dp)
    val autoColumns = if (isCompact) 1 else adaptiveColumnsFor(availableWidth, minCellWidth)
    val automaticColumns = cappedColumns(availableWidth, autoColumns, maxAutoColumns)
    val columns = columnPreference.fixedCount ?: automaticColumns
    val alignedColumns = if (columns == 1 && !isCompact) automaticColumns else columns
    val cells =
        if (columnPreference.fixedCount != null || isCompact || columns < autoColumns) {
            GridCells.Fixed(columns)
        } else {
            GridCells.Adaptive(minCellWidth)
        }
    return FeedGridLayout(
        cells = cells,
        columns = columns,
        contentPadding = contentPadding,
        cardSpacing = spacing.rowSpacing,
        isCompact = isCompact,
        cardWidth = availableWidth / columns,
        thumbnailWidth = availableWidth / alignedColumns - VideoCardDefaults.Inset * 2,
    )
}

/** The count [GridCells.Adaptive] derives for [minCellWidth]: as many whole cells as fit. */
private fun adaptiveColumnsFor(
    availableWidth: Dp,
    minCellWidth: Dp,
): Int = (availableWidth / minCellWidth).toInt().coerceAtLeast(1)

private fun cappedColumns(
    availableWidth: Dp,
    autoColumns: Int,
    maxAutoColumns: Int,
): Int {
    var columns = autoColumns.coerceAtMost(maxAutoColumns.coerceAtLeast(1))
    while (columns < autoColumns && availableWidth / columns > FeedCardMaxWidth) columns++
    return columns
}

@Composable
fun rememberFeedGridLayout(
    maxWidth: Dp,
    columnPreference: HomeFeedColumns = HomeFeedColumns.AUTO,
    maxAutoColumns: Int = FEED_MAX_AUTO_COLUMNS,
    minCellWidth: Dp = FeedCardMinWidth,
): FeedGridLayout =
    remember(maxWidth, columnPreference, maxAutoColumns, minCellWidth) {
        feedGridLayoutFor(maxWidth, columnPreference, maxAutoColumns, minCellWidth)
    }
