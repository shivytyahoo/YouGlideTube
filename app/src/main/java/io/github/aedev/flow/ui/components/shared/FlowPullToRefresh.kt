package io.github.aedev.flow.ui.components.shared

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp

/**
 * Every pull-to-refresh surface in the app, on YouGlideTube's own loader — the
 * dual-arc chase around the badge glyph — instead of the Material default, which
 * read as the old Flow-era spinner wherever it appeared.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FlowPullToRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    state: PullToRefreshState = rememberPullToRefreshState(),
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier,
        state = state,
        contentAlignment = contentAlignment,
        indicator = {
            // Visible from the first pull: the loader fades in with the drag and
            // runs at full strength while the refresh itself is underway.
            if (isRefreshing || state.distanceFraction > 0f) {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.TopCenter)
                            .alpha(if (isRefreshing) 1f else state.distanceFraction.coerceIn(0f, 1f)),
                ) {
                    YouGlideTubeLoader(modifier = Modifier.size(48.dp))
                }
            }
        },
        content = content,
    )
}
