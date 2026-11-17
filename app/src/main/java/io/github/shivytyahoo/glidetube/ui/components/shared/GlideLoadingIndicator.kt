/*
 * Maintained by Shivyt | YouGlideTube
 *
 * This file is part of YouGlideTube (https://github.com/shivytyahoo/YouGlideTube).
 */

package io.github.shivytyahoo.glidetube.ui.components.shared

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Full-surface loading state. Only ever composed while a surface has nothing else to show, so the
 * indicator runs while it is on screen and leaves the tree with it. YouGlideTube's own loader: the
 * dual-arc chase around the badge glyph.
 */
@Composable
fun GlideLoadingIndicator(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        YouGlideTubeLoader(modifier = Modifier.size(54.dp))
    }
}

/**
 * Inline loading row for a feed continuation or a paged list tail: YouGlideTube's sliding
 * saffron-and-coral progress bar.
 */
@Composable
fun GlideFeedProgress(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        YouGlideTubeProgressBar(modifier = Modifier.fillMaxWidth(0.55f))
    }
}
