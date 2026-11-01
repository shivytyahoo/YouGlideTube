package io.github.aedev.flow.ui.components.shared

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R

/**
 * YouGlideTube's loading mark: two arcs chase each other around the badge glyph —
 * the saffron arc leads, the coral arc follows the other way — while the glyph
 * pulses in the middle. Used everywhere the app waits: full surfaces, the
 * player transport while buffering, and the Shorts overlay.
 */
@Composable
fun YouGlideTubeLoader(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "glide_loader")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1050, easing = LinearEasing)),
        label = "sweep",
    )
    val sweepBack by transition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1650, easing = LinearEasing)),
        label = "sweep_back",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = (size.minDimension * 0.09f).coerceAtLeast(2.dp.toPx())
            val inset = stroke / 2f + 1.dp.toPx()
            val arcSize = Size(size.width - inset * 2f, size.height - inset * 2f)
            val topLeft = Offset(inset, inset)
            val style = Stroke(width = stroke, cap = StrokeCap.Round)
            drawArc(track, 0f, 360f, false, topLeft, arcSize, style = style)
            drawArc(primary, sweep, 110f, false, topLeft, arcSize, style = style)
            drawArc(secondary, sweepBack, 60f, false, topLeft, arcSize, style = style)
        }
        Icon(
            painter = painterResource(R.drawable.ic_launcher_dynamic_foreground),
            contentDescription = null,
            tint = primary,
            modifier =
                Modifier
                    .fillMaxSize(0.52f)
                    .graphicsLayer {
                        scaleX = pulse
                        scaleY = pulse
                    },
        )
    }
}

/**
 * The feed-tail progress: a saffron segment with a coral shadow sliding along
 * a track — YouGlideTube's indeterminate bar for paged lists and buffering rows.
 */
@Composable
fun YouGlideTubeProgressBar(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "glide_progress")
    val slide by transition.animateFloat(
        initialValue = -0.45f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(1150, easing = FastOutSlowInEasing)),
        label = "slide",
    )
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(modifier.fillMaxWidth().height(4.dp)) {
        val y = size.height / 2f
        val stroke = size.height
        drawLine(track, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke, cap = StrokeCap.Round)
        val segment = size.width * 0.32f
        val head = slide * size.width
        drawLine(secondary, Offset(head - segment * 0.62f, y), Offset(head - segment * 0.14f, y), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(primary, Offset(head, y), Offset(head + segment, y), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}
