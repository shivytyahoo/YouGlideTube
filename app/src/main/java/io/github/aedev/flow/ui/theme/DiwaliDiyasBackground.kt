package io.github.aedev.flow.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import io.github.aedev.flow.ui.components.shared.LocalBatterySaver
import kotlin.math.PI
import kotlin.math.sin

/**
 * Diwali diyas over the Morphe circles: six small clay lamps rising from the bottom of the
 * screen, swaying gently as they climb, their flames flickering, with a few glowing embers
 * rising faster between them. One shared clock per motion (rise, sway, flicker, embers)
 * with a per-lamp phase offset keeps the lamps out of step without a transition per lamp.
 *
 * The lamps are drawn, not images: a half-disc bowl, a saffron rim, and a two-layer
 * teardrop flame with a radial glow behind it. The glow is the lamp's own light, not a
 * surface fill. The rise is a full-screen journey in about eleven seconds, so it reads
 * visibly faster than the circles' slow drift.
 *
 * [animated] gates the frame clock exactly like the circles: when a full-screen surface
 * covers the layer, the transitions are not created at all and the lamps sit still, so
 * no frame is burned for a layer nobody can see.
 */
@Composable
fun DiwaliDiyasBackground(
    modifier: Modifier = Modifier,
    animated: Boolean = true,
) {
    // Battery saver: the diyas are decoration — off entirely, like the circles.
    if (LocalBatterySaver.current) return
    val rise = animatedClock(animated, 11_000, restart = true, "rise")
    val sway = animatedClock(animated, 4_000, restart = false, "sway")
    val flicker = animatedClock(animated, 160, restart = false, "flicker")
    val embers = animatedClock(animated, 6_500, restart = true, "embers")

    Canvas(modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        // Embers first, behind the lamps.
        ember(0.08f, 0.00f, 2.5f, w, h, embers)
        ember(0.22f, 0.31f, 2.0f, w, h, embers)
        ember(0.38f, 0.58f, 3.0f, w, h, embers)
        ember(0.55f, 0.14f, 2.0f, w, h, embers)
        ember(0.70f, 0.76f, 2.5f, w, h, embers)
        ember(0.86f, 0.42f, 3.0f, w, h, embers)
        ember(0.95f, 0.88f, 2.0f, w, h, embers)
        ember(0.47f, 0.93f, 2.5f, w, h, embers)

        // Six lamps, staggered across the rise so they are spread over the screen.
        diya(0.12f, 0.00f, 34f, 0.00f, w, h, rise, sway, flicker)
        diya(0.32f, 0.17f, 28f, 0.13f, w, h, rise, sway, flicker)
        diya(0.48f, 0.34f, 40f, 0.29f, w, h, rise, sway, flicker)
        diya(0.66f, 0.50f, 30f, 0.47f, w, h, rise, sway, flicker)
        diya(0.82f, 0.67f, 36f, 0.61f, w, h, rise, sway, flicker)
        diya(0.92f, 0.84f, 26f, 0.83f, w, h, rise, sway, flicker)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.diya(
    baseX: Float,
    riseOffset: Float,
    bowlWidthDp: Float,
    phase: Float,
    w: Float,
    h: Float,
    rise: Float,
    sway: Float,
    flicker: Float,
) {
    val progress = (rise + riseOffset) % 1f
    // Fade in at the bottom edge and out at the top, so the restart never pops.
    val fade = (minOf(progress, 1f - progress) / 0.08f).coerceIn(0f, 1f)
    if (fade <= 0f) return
    val s = bowlWidthDp * density
    val swayX = sin((sway + phase) * 2f * PI.toFloat()) * 22f * density
    val x = w * baseX + swayX
    val y = h * (1.08f - progress * 1.16f)
    val flick = 0.5f + 0.5f * sin((flicker * 3f + phase) * 2f * PI.toFloat())
    val flameScale = 0.92f + 0.16f * flick
    val flameAlpha = (0.85f + 0.15f * flick) * fade

    // The lamp's own glow, centred on the flame.
    val glowCenter = Offset(x, y - s * 0.55f)
    val glowRadius = s * 1.6f
    drawCircle(
        brush =
            Brush.radialGradient(
                colors = listOf(DiyaGlow.copy(alpha = 0.16f * fade), Color.Transparent),
                center = glowCenter,
                radius = glowRadius,
            ),
        radius = glowRadius,
        center = glowCenter,
    )

    // Flame: a teardrop, amber outside, pale yellow core, sitting on the bowl's rim.
    val flameH = s * 0.72f * flameScale
    val flameW = s * 0.30f
    val flameTop = y - s * 0.18f - flameH
    drawPath(
        path = teardrop(Offset(x, flameTop), flameW, flameH),
        color = DiyaFlameOuter.copy(alpha = flameAlpha),
    )
    drawPath(
        path = teardrop(Offset(x, flameTop + flameH * 0.28f), flameW * 0.55f, flameH * 0.55f),
        color = DiyaFlameInner.copy(alpha = flameAlpha),
    )

    // Bowl: a half-disc with a saffron rim.
    drawArc(
        color = DiyaBowl.copy(alpha = fade),
        startAngle = 0f,
        sweepAngle = 180f,
        useCenter = true,
        topLeft = Offset(x - s / 2f, y - s * 0.18f),
        size = Size(s, s * 0.36f),
    )
    drawOval(
        color = DiyaBowlRim.copy(alpha = fade),
        topLeft = Offset(x - s / 2f, y - s * 0.22f),
        size = Size(s, s * 0.08f),
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.ember(
    baseX: Float,
    offset: Float,
    radiusDp: Float,
    w: Float,
    h: Float,
    clock: Float,
) {
    val progress = (clock + offset) % 1f
    val alpha = (1f - progress) * 0.55f
    val x = w * baseX + sin(progress * 2f * PI.toFloat() * 2f) * 10f * density
    val y = h * (1.05f - progress * 1.1f)
    drawCircle(
        color = DiyaEmber.copy(alpha = alpha),
        radius = radiusDp * density,
        center = Offset(x, y),
    )
}

/** A teardrop with its tip at [top], [width] at its widest, [height] tall. */
private fun teardrop(
    top: Offset,
    width: Float,
    height: Float,
): Path =
    Path().apply {
        val cx = top.x
        val bottom = top.y + height
        val halfW = width / 2f
        moveTo(cx, top.y)
        cubicTo(
            cx + halfW * 0.9f, top.y + height * 0.35f,
            cx + halfW, top.y + height * 0.62f,
            cx + halfW * 0.72f, bottom - height * 0.12f,
        )
        cubicTo(
            cx + halfW * 0.4f, bottom,
            cx - halfW * 0.4f, bottom,
            cx - halfW * 0.72f, bottom - height * 0.12f,
        )
        cubicTo(
            cx - halfW, top.y + height * 0.62f,
            cx - halfW * 0.9f, top.y + height * 0.35f,
            cx, top.y,
        )
        close()
    }

/**
 * One leg of the motion, or a frozen mid-pose when the layer is covered. The transition
 * only exists while animated, so a covered layer costs zero frames.
 */
@Composable
private fun animatedClock(
    animated: Boolean,
    durationMs: Int,
    restart: Boolean,
    label: String,
): Float {
    if (!animated) return 0.5f
    val transition = rememberInfiniteTransition(label = "diwali-$label")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                tween(durationMs, easing = LinearEasing),
                if (restart) RepeatMode.Restart else RepeatMode.Reverse,
            ),
        label = label,
    )
    return progress
}
