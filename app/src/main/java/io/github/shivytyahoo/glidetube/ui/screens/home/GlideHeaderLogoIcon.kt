package io.github.shivytyahoo.glidetube.ui.screens.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import io.github.shivytyahoo.glidetube.R

/** Long-press on the Home logo or on the wordmark beside it switches Deep Glide. */
@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.deepGlideLongPress(
    label: String,
    onLongPress: () -> Unit,
): Modifier = combinedClickable(onLongClickLabel = label, onClick = {}, onLongClick = onLongPress)

/**
 * The app mark in the Home top bar and on the signed-out landing. Deep Glide still changes
 * the mark: full colour normally, the monochrome silhouette while private mode is on.
 */
@Composable
internal fun GlideHeaderLogoIcon(
    isDeepGlideActive: Boolean,
    modifier: Modifier = Modifier,
) {
    val markAlpha by animateFloatAsState(
        targetValue = if (isDeepGlideActive) 0f else 1f,
        animationSpec = tween(durationMillis = 250),
        label = "deepGlideMarkAlpha",
    )
    val silhouetteAlpha by animateFloatAsState(
        targetValue = if (isDeepGlideActive) 1f else 0f,
        animationSpec = tween(durationMillis = 250),
        label = "deepGlideSilhouetteAlpha",
    )
    Box(modifier = modifier) {
        if (markAlpha > 0f) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().alpha(markAlpha),
                contentScale = ContentScale.Fit,
            )
        }
        if (silhouetteAlpha > 0f) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_dynamic_foreground),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().alpha(silhouetteAlpha),
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
                contentScale = ContentScale.Fit,
            )
        }
    }
}
