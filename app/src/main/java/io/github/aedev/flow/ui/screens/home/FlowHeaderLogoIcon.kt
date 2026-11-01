package io.github.aedev.flow.ui.screens.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.PathParser

// SVG path data — a single unbreakable token, so the line-length rule cannot be satisfied by wrapping.
@Suppress("ktlint:standard:max-line-length")
private const val FLOW_LOGO_BG_PATH = "M12,2 C17.52,2 22,6.48 22,12 C22,17.52 17.52,22 12,22 C6.48,22 2,17.52 2,12 C2,6.48 6.48,2 12,2 Z"
private const val FLOW_LOGO_GLYPH_PATH = "M9.72,8.96 C12,8.96 12.38,10.67 11.24,12 C12.38,13.33 12,15.04 9.72,15.04 L8.96,15.04 C10.86,13.9 10.86,10.1 8.96,8.96 Z M11.62,10.1 L15.04,12 L11.62,13.9 Z"

@Suppress("ktlint:standard:max-line-length")
private const val FLOW_INCOGNITO_GLYPH_PATH = "M17.06 13C15.2 13 13.64 14.33 13.24 16.1C12.29 15.69 11.42 15.8 10.76 16.09C10.35 14.31 8.79 13 6.94 13C4.77 13 3 14.79 3 17C3 19.21 4.77 21 6.94 21C9 21 10.68 19.38 10.84 17.32C11.18 17.08 12.07 16.63 13.16 17.34C13.34 19.39 15 21 17.06 21C19.23 21 21 19.21 21 17C21 14.79 19.23 13 17.06 13M6.94 19.86C5.38 19.86 4.13 18.58 4.13 17S5.39 14.14 6.94 14.14C8.5 14.14 9.75 15.42 9.75 17S8.5 19.86 6.94 19.86M17.06 19.86C15.5 19.86 14.25 18.58 14.25 17S15.5 14.14 17.06 14.14C18.62 14.14 19.88 15.42 19.88 17S18.61 19.86 17.06 19.86M22 10.5H2V12H22V10.5M15.53 2.63C15.31 2.14 14.75 1.88 14.22 2.05L12 2.79L9.77 2.05L9.72 2.04C9.19 1.89 8.63 2.17 8.43 2.68L6 9H18L15.56 2.68L15.53 2.63Z"

/** Long-press on the Home logo or on the wordmark beside it switches Deep Flow. */
@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.deepFlowLongPress(
    label: String,
    onLongPress: () -> Unit,
): Modifier = combinedClickable(onLongClickLabel = label, onClick = {}, onLongClick = onLongPress)

@Composable
internal fun FlowHeaderLogoIcon(
    isDeepFlowActive: Boolean,
    modifier: Modifier = Modifier,
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val onPrimaryColor = MaterialTheme.colorScheme.onPrimary

    val bgPath =
        remember {
            PathParser().parsePathString(FLOW_LOGO_BG_PATH).toPath()
        }
    val glyphPath =
        remember {
            PathParser().parsePathString(FLOW_LOGO_GLYPH_PATH).toPath()
        }
    val incognitoPath =
        remember {
            PathParser().parsePathString(FLOW_INCOGNITO_GLYPH_PATH).toPath()
        }

    val glyphAlpha by animateFloatAsState(
        targetValue = if (isDeepFlowActive) 0f else 1f,
        animationSpec = tween(durationMillis = 250),
        label = "deepFlowGlyphAlpha",
    )
    val incognitoAlpha by animateFloatAsState(
        targetValue = if (isDeepFlowActive) 1f else 0f,
        animationSpec = tween(durationMillis = 250),
        label = "deepFlowIncognitoAlpha",
    )

    Canvas(modifier = modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        drawContext.canvas.save()
        drawContext.canvas.scale(sx, sy)
        drawPath(path = bgPath, color = primaryColor)

        if (glyphAlpha > 0f) {
            drawPath(path = glyphPath, color = onPrimaryColor.copy(alpha = glyphAlpha))
        }
        if (incognitoAlpha > 0f) {
            val incognitoScale = 0.65f
            val incognitoOffset = 12f * (1f - incognitoScale)
            drawContext.canvas.save()
            drawContext.canvas.translate(incognitoOffset, incognitoOffset)
            drawContext.canvas.scale(incognitoScale, incognitoScale)
            drawPath(path = incognitoPath, color = onPrimaryColor.copy(alpha = incognitoAlpha))
            drawContext.canvas.restore()
        }

        drawContext.canvas.restore()
    }
}
