package io.github.aedev.flow.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.sin

/**
 * Morphe's Circles background, as Morphe Manager itself draws it: six small, faint, solid
 * circles in the theme's primary, secondary and tertiary, drifting on slow sine legs. The
 * radii are tens of dp and the alphas are around 0.04, so the background stays a quiet
 * texture behind the content instead of a set of loud gradient blobs — the batch 4 version
 * used four huge radial gradients at up to 0.20 alpha, which is why it shouted.
 *
 * Parallax is left out: Morphe tilts its circles with the device sensors, but a phone held
 * still would never see it, and the drift alone carries the look.
 *
 * [animated] gates the frame clock: when a full-screen surface (Shorts, expanded player)
 * covers the circles, the transitions are not created at all and the circles sit still, so
 * no frame is burned for a layer nobody can see.
 */
@Composable
fun MorpheCirclesBackground(
    modifier: Modifier = Modifier,
    animated: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val drift1 = animatedProgress(animated, 8000, "drift-1")
    val drift2 = animatedProgress(animated, 9000, "drift-2")
    val drift3 = animatedProgress(animated, 7500, "drift-3")
    val drift4 = animatedProgress(animated, 9500, "drift-4")
    val drift5 = animatedProgress(animated, 8200, "drift-5")
    val drift6 = animatedProgress(animated, 8800, "drift-6")

    Canvas(modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        fun circle(
            baseX: Float,
            baseY: Float,
            ampX: Float,
            ampY: Float,
            progress: Float,
            radiusDp: Float,
            color: androidx.compose.ui.graphics.Color,
            alpha: Float,
        ) {
            val sway = sin(progress * PI.toFloat())
            val x = w * baseX + ampX * sway
            val y = h * baseY + ampY * sway
            drawCircle(
                color = color.copy(alpha = alpha),
                radius = radiusDp * density,
                center = Offset(x, y),
            )
        }

        circle(0.20f, 0.225f, w * 0.05f, h * 0.025f, drift1, 133f, scheme.primary, 0.05f)
        circle(0.85f, 0.185f, w * 0.03f, h * 0.035f, drift2, 93f, scheme.tertiary, 0.035f)
        circle(0.715f, 0.44f, w * 0.035f, h * 0.04f, drift3, 67f, scheme.tertiary, 0.04f)
        circle(0.815f, 0.785f, w * 0.035f, h * 0.035f, drift4, 107f, scheme.secondary, 0.035f)
        circle(0.24f, 0.765f, w * 0.04f, h * 0.035f, drift5, 60f, scheme.primary, 0.04f)
        circle(0.525f, 0.895f, w * 0.025f, h * 0.025f, drift6, 73f, scheme.secondary, 0.04f)
    }
}

/**
 * One leg of the drift, or a frozen mid-drift pose when the layer is covered. The transition
 * only exists while animated, so a covered layer costs zero frames; the pose jump on return
 * is bounded by the small drift amplitude.
 */
@Composable
private fun animatedProgress(
    animated: Boolean,
    durationMs: Int,
    label: String,
): Float {
    if (!animated) return 0.5f
    val transition = rememberInfiniteTransition(label = "morphe-circles-$label")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMs, easing = LinearEasing), RepeatMode.Reverse),
        label = label,
    )
    return progress
}
