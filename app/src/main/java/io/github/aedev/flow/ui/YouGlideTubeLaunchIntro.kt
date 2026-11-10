package io.github.aedev.flow.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.shared.LocalBatterySaver
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * YouGlideTube's cold-start brand moment: on the brand violet, a saffron arc draws itself
 * around the badge (a coral arc chases it), the badge springs in, the wordmark rises
 * letter by letter, and the tagline fades in — then the whole overlay fades away. It is
 * a pure overlay: the home feed composes and loads underneath from the first frame, so
 * the intro never delays content, and it leaves composition for good once dismissed.
 */
@Composable
internal fun YouGlideTubeLaunchIntro(modifier: Modifier = Modifier) {
    // Battery saver: the intro is a brand animation — skip it and open straight into the app.
    if (LocalBatterySaver.current) return
    var dismissed by remember { mutableStateOf(false) }
    var appeared by remember { mutableStateOf(false) }
    var fading by remember { mutableStateOf(false) }
    val ring = remember { Animatable(0f) }
    val appName = stringResource(R.string.app_name)
    val letters = remember { appName.map { Animatable(0f) } }

    LaunchedEffect(Unit) {
        appeared = true
        launch { ring.animateTo(1f, tween(780, easing = FastOutSlowInEasing)) }
        letters.forEachIndexed { index, animatable ->
            launch {
                delay(140L + index * 42L)
                animatable.animateTo(1f, tween(320, easing = FastOutSlowInEasing))
            }
        }
        delay(1330)
        fading = true
        delay(340)
        dismissed = true
    }
    if (dismissed) return

    val motionScheme = MaterialTheme.motionScheme
    val alpha by animateFloatAsState(
        targetValue = if (fading) 0f else 1f,
        animationSpec = motionScheme.defaultEffectsSpec(),
        label = "launchIntroAlpha",
    )
    val scale by animateFloatAsState(
        targetValue = if (appeared) 1f else 0.8f,
        animationSpec = motionScheme.defaultSpatialSpec(),
        label = "launchIntroScale",
    )
    val taglineAlpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(500, delayMillis = 480, easing = FastOutSlowInEasing),
        label = "launchIntroTagline",
    )
    val foreground = colorResource(R.color.splash_foreground)
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(colorResource(R.color.splash_background))
                .graphicsLayer { this.alpha = alpha }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {},
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(176.dp)) {
                    val stroke = 4.dp.toPx()
                    val inset = stroke / 2f + 3.dp.toPx()
                    val arcSize = Size(size.width - inset * 2f, size.height - inset * 2f)
                    val topLeft = Offset(inset, inset)
                    val style = Stroke(width = stroke, cap = StrokeCap.Round)
                    drawArc(
                        color = foreground.copy(alpha = 0.14f),
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = style,
                    )
                    drawArc(
                        color = primary,
                        startAngle = -90f,
                        sweepAngle = 305f * ring.value,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = style,
                    )
                    drawArc(
                        color = secondary,
                        startAngle = -90f + 305f * ring.value + 14f,
                        sweepAngle = 44f * ring.value,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = style,
                    )
                }
                Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier =
                        Modifier
                            .size(124.dp)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                            },
                )
            }
            Spacer(Modifier.height(18.dp))
            Row {
                appName.forEachIndexed { index, char ->
                    val progress = letters.getOrNull(index)?.value ?: 1f
                    Text(
                        text = char.toString(),
                        style = MaterialTheme.typography.headlineLarge,
                        color = foreground,
                        modifier =
                            Modifier.graphicsLayer {
                                this.alpha = progress
                                translationY = (1f - progress) * 14.dp.toPx()
                            },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.app_tagline),
                style = MaterialTheme.typography.labelLarge,
                color = foreground.copy(alpha = 0.75f),
                modifier = Modifier.graphicsLayer { this.alpha = taglineAlpha },
            )
        }
    }
}
