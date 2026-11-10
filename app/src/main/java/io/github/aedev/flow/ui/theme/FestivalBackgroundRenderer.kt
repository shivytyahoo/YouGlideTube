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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.ui.components.shared.LocalBatterySaver
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * One animated background over the Morphe circles, chosen in Settings → Appearance →
 * Animated backgrounds. Diwali keeps its own tested diya layer; every other festival is
 * drawn here from its theme: a few deterministic particles per element, each placed by a
 * hash of its index (stable across recompositions) and moved by shared clocks, so the
 * layer costs the same handful of transitions no matter which festival is picked.
 *
 * Frame discipline matches the circles and diyas: the clocks only exist while [animated],
 * and Battery saver drops the whole layer.
 */
@Composable
fun FestivalBackgroundLayer(
    background: FestivalBackground,
    modifier: Modifier = Modifier,
    animated: Boolean = true,
) {
    if (LocalBatterySaver.current) return
    if (background == FestivalBackground.DIWALI) {
        DiwaliDiyasBackground(modifier = modifier, animated = animated)
        return
    }
    if (background == FestivalBackground.NONE || background.elements.isEmpty()) return
    val drift = festivalClock(animated, 12_000, restart = true, "drift")
    val sway = festivalClock(animated, 4_000, restart = false, "sway")
    val twinkle = festivalClock(animated, 1_200, restart = false, "twinkle")
    val burst = festivalClock(animated, 3_500, restart = true, "burst")

    Canvas(modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        for (element in background.elements) {
            for (index in 0 until element.count) {
                val color = element.colors[index % element.colors.size]
                val seed = hash(index, element.shape.ordinal)
                val phase = ((drift * (12_000f / element.durationMs) + seed) % 1f + 1f) % 1f
                val alpha = (0.35f + 0.65f * (0.5f + 0.5f * sin((twinkle + seed) * 2f * PI.toFloat()))).coerceIn(0f, 1f)
                when (element.motion) {
                    FestivalMotion.RISE -> {
                        val x = (seed * 0.9f + 0.05f) * w + sin((sway + seed) * 2f * PI.toFloat()) * 14.dp.toPx()
                        val y = h * (1f - phase)
                        drawFestivalShape(element.shape, Offset(x, y), element.sizeDp.dp.toPx(), color, alpha * edgeFade(phase))
                    }
                    FestivalMotion.FALL -> {
                        val x = (seed * 0.9f + 0.05f) * w + sin((sway + seed) * 2f * PI.toFloat()) * 14.dp.toPx()
                        val y = h * phase
                        drawFestivalShape(element.shape, Offset(x, y), element.sizeDp.dp.toPx(), color, alpha * edgeFade(phase))
                    }
                    FestivalMotion.FLOAT -> {
                        val x = (seed * 0.9f + 0.05f) * w + sin((sway + seed) * 2f * PI.toFloat()) * 18.dp.toPx()
                        val y = (seed * 0.7f + 0.1f) * h + cos((drift + seed) * 2f * PI.toFloat()) * 12.dp.toPx()
                        drawFestivalShape(element.shape, Offset(x, y), element.sizeDp.dp.toPx(), color, alpha)
                    }
                    FestivalMotion.BURST -> {
                        val centers = listOf(Offset(w * 0.25f, h * 0.3f), Offset(w * 0.7f, h * 0.22f), Offset(w * 0.5f, h * 0.45f))
                        val center = centers[index % centers.size]
                        val progress = (burst + seed) % 1f
                        drawFirework(center, progress, element.sizeDp.dp.toPx(), color)
                    }
                }
            }
        }
    }
}

/** A particle fades in at its start and out at its end, so nothing pops in or out. */
private fun edgeFade(phase: Float): Float = (sin(phase * PI.toFloat())).coerceIn(0f, 1f)

/** A deterministic 0..1 value from a particle index, stable across recompositions. */
private fun hash(index: Int, salt: Int): Float {
    val value = (index * 2654435761L + salt * 40503L) % 1000L
    return (if (value < 0) value + 1000L else value) / 1000f
}

/**
 * One leg of the motion, or a frozen mid-pose when the layer is covered. The transition
 * only exists while animated, so a covered layer costs zero frames.
 */
@Composable
private fun festivalClock(
    animated: Boolean,
    durationMs: Int,
    restart: Boolean,
    label: String,
): Float {
    if (!animated) return 0.5f
    val transition = rememberInfiniteTransition(label = "festival-$label")
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

private fun DrawScope.drawFestivalShape(
    shape: FestivalShape,
    center: Offset,
    size: Float,
    color: Color,
    alpha: Float,
) {
    when (shape) {
        FestivalShape.DOT -> drawCircle(color.copy(alpha = alpha), size / 2f, center)
        FestivalShape.PETAL -> petal(center, size, color, alpha)
        FestivalShape.LEAF -> leaf(center, size, color, alpha)
        FestivalShape.STAR -> star(center, size, color, alpha)
        FestivalShape.HEART -> heart(center, size, color, alpha)
        FestivalShape.MOON -> moon(center, size, color, alpha)
        FestivalShape.LANTERN -> lantern(center, size, color, alpha)
        FestivalShape.KITE -> kite(center, size, color, alpha)
        FestivalShape.BALLOON -> balloon(center, size, color, alpha)
        FestivalShape.FLAME -> flame(center, size, color, alpha)
        FestivalShape.SNOWFLAKE -> snowflake(center, size, color, alpha)
        FestivalShape.PUMPKIN -> pumpkin(center, size, color, alpha)
        FestivalShape.BAT -> bat(center, size, color, alpha)
        FestivalShape.EGG -> drawOval(color.copy(alpha = alpha), Offset(center.x - size * 0.35f, center.y - size / 2f), Size(size * 0.7f, size))
        FestivalShape.SHAMROCK -> shamrock(center, size, color, alpha)
        FestivalShape.FLOWER -> flower(center, size, color, alpha)
        FestivalShape.DIYA -> diya(center, size, color, alpha)
        FestivalShape.FIREWORK -> drawFirework(center, 0.5f, size, color)
    }
}

private fun DrawScope.petal(center: Offset, size: Float, color: Color, alpha: Float) {
    rotate(30f, center) {
        drawOval(color.copy(alpha = alpha), Offset(center.x - size * 0.2f, center.y - size / 2f), Size(size * 0.4f, size))
    }
}

private fun DrawScope.leaf(center: Offset, size: Float, color: Color, alpha: Float) {
    rotate(45f, center) {
        drawOval(color.copy(alpha = alpha), Offset(center.x - size * 0.25f, center.y - size / 2f), Size(size * 0.5f, size))
    }
    drawLine(color.copy(alpha = alpha), center, Offset(center.x, center.y + size * 0.6f), strokeWidth = 1.5f)
}

private fun DrawScope.star(center: Offset, size: Float, color: Color, alpha: Float) {
    val r = size / 2f
    val inner = r * 0.28f
    val path = Path()
    path.moveTo(center.x, center.y - r)
    path.lineTo(center.x + inner, center.y - inner)
    path.lineTo(center.x + r, center.y)
    path.lineTo(center.x + inner, center.y + inner)
    path.lineTo(center.x, center.y + r)
    path.lineTo(center.x - inner, center.y + inner)
    path.lineTo(center.x - r, center.y)
    path.lineTo(center.x - inner, center.y - inner)
    path.close()
    drawPath(path, color.copy(alpha = alpha))
}

private fun DrawScope.heart(center: Offset, size: Float, color: Color, alpha: Float) {
    val w = size / 2f
    val path = Path()
    path.moveTo(center.x, center.y + w * 0.9f)
    path.cubicTo(center.x - w * 1.2f, center.y, center.x - w * 0.6f, center.y - w, center.x, center.y - w * 0.35f)
    path.cubicTo(center.x + w * 0.6f, center.y - w, center.x + w * 1.2f, center.y, center.x, center.y + w * 0.9f)
    path.close()
    drawPath(path, color.copy(alpha = alpha))
}

private fun DrawScope.moon(center: Offset, size: Float, color: Color, alpha: Float) {
    val r = size / 2f
    val path = Path()
    path.addArc(Rect(center.x - r, center.y - r, center.x + r, center.y + r), -70f, 300f)
    path.arcTo(Rect(center.x - r * 0.55f, center.y - r * 0.75f, center.x + r * 0.85f, center.y + r * 0.75f), 55f, -210f, false)
    path.close()
    drawPath(path, color.copy(alpha = alpha))
}

private fun DrawScope.lantern(center: Offset, size: Float, color: Color, alpha: Float) {
    val w = size * 0.55f
    val h = size * 0.8f
    drawRoundRect(color.copy(alpha = alpha), Offset(center.x - w / 2f, center.y - h / 2f), Size(w, h), androidx.compose.ui.geometry.CornerRadius(size * 0.18f))
    drawLine(FestivalYellow.copy(alpha = alpha), Offset(center.x - w / 2f, center.y - h * 0.15f), Offset(center.x + w / 2f, center.y - h * 0.15f), strokeWidth = 1.5f)
    drawLine(FestivalYellow.copy(alpha = alpha), Offset(center.x - w / 2f, center.y + h * 0.15f), Offset(center.x + w / 2f, center.y + h * 0.15f), strokeWidth = 1.5f)
    drawLine(FestivalYellow.copy(alpha = alpha), Offset(center.x, center.y + h / 2f), Offset(center.x, center.y + h * 0.75f), strokeWidth = 1.5f)
}

private fun DrawScope.kite(center: Offset, size: Float, color: Color, alpha: Float) {
    val r = size / 2f
    val path = Path()
    path.moveTo(center.x, center.y - r)
    path.lineTo(center.x + r * 0.7f, center.y)
    path.lineTo(center.x, center.y + r)
    path.lineTo(center.x - r * 0.7f, center.y)
    path.close()
    drawPath(path, color.copy(alpha = alpha))
    drawLine(color.copy(alpha = alpha * 0.7f), Offset(center.x, center.y + r), Offset(center.x + r * 0.3f, center.y + r * 1.6f), strokeWidth = 1.5f)
}

private fun DrawScope.balloon(center: Offset, size: Float, color: Color, alpha: Float) {
    val r = size * 0.42f
    drawCircle(color.copy(alpha = alpha), r, Offset(center.x, center.y - size * 0.1f))
    val knot = Path()
    knot.moveTo(center.x - r * 0.2f, center.y - size * 0.1f + r * 0.9f)
    knot.lineTo(center.x + r * 0.2f, center.y - size * 0.1f + r * 0.9f)
    knot.lineTo(center.x, center.y - size * 0.1f + r * 1.25f)
    knot.close()
    drawPath(knot, color.copy(alpha = alpha))
    drawLine(color.copy(alpha = alpha * 0.6f), Offset(center.x, center.y + size * 0.35f), Offset(center.x, center.y + size * 0.9f), strokeWidth = 1.5f)
}

private fun DrawScope.flame(center: Offset, size: Float, color: Color, alpha: Float) {
    val path = Path()
    path.moveTo(center.x, center.y - size / 2f)
    path.cubicTo(center.x + size * 0.32f, center.y - size * 0.1f, center.x + size * 0.28f, center.y + size * 0.28f, center.x, center.y + size * 0.32f)
    path.cubicTo(center.x - size * 0.28f, center.y + size * 0.28f, center.x - size * 0.32f, center.y - size * 0.1f, center.x, center.y - size / 2f)
    path.close()
    drawPath(path, color.copy(alpha = alpha))
    drawCircle(DiyaFlameInner.copy(alpha = alpha), size * 0.14f, Offset(center.x, center.y + size * 0.12f))
}

private fun DrawScope.snowflake(center: Offset, size: Float, color: Color, alpha: Float) {
    val r = size / 2f
    for (angle in 0 until 3) {
        val a = angle * PI.toFloat() / 3f
        val dx = cos(a) * r
        val dy = sin(a) * r
        drawLine(color.copy(alpha = alpha), Offset(center.x - dx, center.y - dy), Offset(center.x + dx, center.y + dy), strokeWidth = 2f)
    }
}

private fun DrawScope.pumpkin(center: Offset, size: Float, color: Color, alpha: Float) {
    val r = size * 0.42f
    drawCircle(color.copy(alpha = alpha), r, center)
    drawLine(FestivalGreen.copy(alpha = alpha), Offset(center.x, center.y - r), Offset(center.x + r * 0.15f, center.y - r * 1.3f), strokeWidth = 2.5f)
    drawCircle(Color.Black.copy(alpha = 0.55f * alpha), r * 0.14f, Offset(center.x - r * 0.35f, center.y - r * 0.15f))
    drawCircle(Color.Black.copy(alpha = 0.55f * alpha), r * 0.14f, Offset(center.x + r * 0.35f, center.y - r * 0.15f))
    drawArc(Color.Black.copy(alpha = 0.55f * alpha), 20f, 140f, false, Offset(center.x - r * 0.4f, center.y - r * 0.1f), Size(r * 0.8f, r * 0.7f), style = Stroke(width = 2f))
}

private fun DrawScope.bat(center: Offset, size: Float, color: Color, alpha: Float) {
    val r = size / 2f
    val path = Path()
    path.moveTo(center.x - r, center.y)
    path.quadraticTo(center.x - r * 0.5f, center.y - r * 0.7f, center.x, center.y - r * 0.15f)
    path.quadraticTo(center.x + r * 0.5f, center.y - r * 0.7f, center.x + r, center.y)
    path.quadraticTo(center.x + r * 0.5f, center.y + r * 0.5f, center.x, center.y + r * 0.25f)
    path.quadraticTo(center.x - r * 0.5f, center.y + r * 0.5f, center.x - r, center.y)
    path.close()
    drawPath(path, color.copy(alpha = alpha))
}

private fun DrawScope.shamrock(center: Offset, size: Float, color: Color, alpha: Float) {
    val r = size * 0.22f
    drawCircle(color.copy(alpha = alpha), r, Offset(center.x, center.y - r * 0.9f))
    drawCircle(color.copy(alpha = alpha), r, Offset(center.x - r * 0.9f, center.y + r * 0.3f))
    drawCircle(color.copy(alpha = alpha), r, Offset(center.x + r * 0.9f, center.y + r * 0.3f))
    drawLine(color.copy(alpha = alpha), Offset(center.x, center.y + r * 0.5f), Offset(center.x + r * 0.3f, center.y + r * 1.6f), strokeWidth = 2f)
}

private fun DrawScope.flower(center: Offset, size: Float, color: Color, alpha: Float) {
    val petalR = size * 0.28f
    for (i in 0 until 5) {
        val a = i * 2f * PI.toFloat() / 5f - PI.toFloat() / 2f
        val px = center.x + cos(a) * size * 0.3f
        val py = center.y + sin(a) * size * 0.3f
        drawCircle(color.copy(alpha = alpha), petalR, Offset(px, py))
    }
    drawCircle(FestivalYellow.copy(alpha = alpha), size * 0.16f, center)
}

private fun DrawScope.diya(center: Offset, size: Float, color: Color, alpha: Float) {
    val bowlR = size * 0.4f
    val path = Path()
    path.moveTo(center.x - bowlR, center.y + size * 0.1f)
    path.arcTo(Rect(center.x - bowlR, center.y - bowlR * 0.4f, center.x + bowlR, center.y + bowlR), 0f, 180f, false)
    path.close()
    drawPath(path, DiyaBowl.copy(alpha = alpha))
    drawLine(DiyaBowlRim.copy(alpha = alpha), Offset(center.x - bowlR, center.y + size * 0.1f), Offset(center.x + bowlR, center.y + size * 0.1f), strokeWidth = 2f)
    flame(Offset(center.x, center.y - size * 0.15f), size * 0.55f, color, alpha)
}

private fun DrawScope.drawFirework(center: Offset, progress: Float, size: Float, color: Color) {
    val radius = progress * size
    val alpha = (1f - progress).coerceIn(0f, 1f)
    for (i in 0 until 10) {
        val a = i * 2f * PI.toFloat() / 10f
        val x = center.x + cos(a) * radius
        val y = center.y + sin(a) * radius
        drawCircle(color.copy(alpha = alpha), size * 0.05f + 1f, Offset(x, y))
    }
    drawCircle(color.copy(alpha = alpha * 0.8f), size * 0.06f, center)
}
