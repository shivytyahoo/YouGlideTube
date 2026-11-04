package io.github.aedev.flow.ui.theme

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur

/**
 * Morphe's brand blue, used for the selected tab's pill. The batch 4 glass brushes lived
 * here too — a uniform blue-teal gradient stamped on every card and row — and were deleted
 * in batch 6: Morphe's own cards carry their own color each, and its settings rows are flat,
 * so a single gradient for everything was the striped look the user rejected.
 *
 * WHY this file exists despite the project's Anti-Slop rule (no gradients/glass): the user
 * explicitly instructed the Morphe look for v2's theme layer, and the user outranks house
 * rules — this override covers the theme layer only, not the rest of the app.
 */
val MorpheBrandBlue = Color(0xFF1E5AA8)

/**
 * Morphe's glass recipe, taken from its GlassBlur.kt: the backdrop blurred under a tint
 * (the tint at 0.55 over the background), with a solid tinted fallback where no haze
 * source exists. Batch 7's pill was only translucent, which is why it read as a dim bar
 * instead of glass — translucency alone never blurs what passes underneath.
 */
private fun glassBlurStyle(tint: Color, background: Color): HazeBlurStyle {
    val solid = tint.compositeOver(background)
    return HazeBlurStyle {
        backgroundColor(background)
        colorEffects(listOf(HazeColorEffect.tint(solid.copy(alpha = 0.40f))))
        fallbackColorEffect(HazeColorEffect.tint(solid))
    }
}

@Composable
fun Modifier.morpheGlassFill(
    glass: Color,
    shape: Shape,
    haze: HazeState?,
): Modifier {
    val screen = MaterialTheme.colorScheme.background
    if (haze == null) return background(glass.compositeOver(screen), shape)
    val style = remember(glass, screen) { glassBlurStyle(glass, screen) }
    return clip(shape).hazeBlur(HazeInput.Sources(haze), style)
}

/**
 * Morphe's per-card gradient, built from the card's own color: three stops along a
 * diagonal sweep. Morphe's cards carry their bundle's colors; a video card's own color
 * is its thumbnail palette base. Dark bases sweep up toward light, light bases sweep
 * down, so the sweep stays visible on both.
 */
fun videoCardGradient(base: Color): Brush {
    val light = base.luminance() >= 0.5f
    val middle = if (light) base.darken(0.16f) else base.lighten(0.18f)
    val end = if (light) base.darken(0.34f) else base.lighten(0.38f)
    return Brush.linearGradient(listOf(base, middle, end))
}

private fun Color.lighten(fraction: Float): Color =
    Color(
        red = red + (1f - red) * fraction,
        green = green + (1f - green) * fraction,
        blue = blue + (1f - blue) * fraction,
        alpha = alpha,
    )

private fun Color.darken(fraction: Float): Color =
    Color(
        red = red * (1f - fraction),
        green = green * (1f - fraction),
        blue = blue * (1f - fraction),
        alpha = alpha,
    )
