package io.github.aedev.flow.ui.screens.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.ui.components.shared.FlowMaxContentWidth
import io.github.aedev.flow.ui.utils.LocalWindowSizeClass
import io.github.aedev.flow.ui.utils.isExpandedWidth

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val newVideoAlerts by viewModel.newVideoAlerts.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    var interestsRevealed by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.completed) { if (state.completed) onComplete() }
    BackHandler(enabled = state.step.index > 0) { viewModel.back() }

    // Returning from Google sign-in or the TV-code dialog: pick up the new session.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshSignedIn() }

    // The step the backdrop and hero last settled on; a new step morphs out of it.
    var shownStep by remember { mutableStateOf(state.step) }
    var backdropFrom by remember { mutableStateOf(state.step) }
    val backdrop = remember { Animatable(1f) }
    val backdropSpec = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    val heroFrom = shownStep
    LaunchedEffect(state.step) {
        if (state.step == shownStep) return@LaunchedEffect
        backdropFrom = shownStep
        shownStep = state.step
        backdrop.snapTo(0f)
        backdrop.animateTo(1f, backdropSpec)
    }

    Box(Modifier.fillMaxSize().background(Color.Transparent)) {
        Spacer(
            Modifier
                .fillMaxSize()
                .onboardingBackdrop(backdropFrom, shownStep, MaterialTheme.colorScheme.surfaceContainer) { backdrop.value },
        )
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0.dp),
            topBar = { OnboardingTopBar(step = state.step, onSkip = viewModel::next) },
            bottomBar = {
                val besideList = LocalWindowSizeClass.current.isExpandedWidth && state.step.showsProgress
                Box(Modifier.fillMaxWidth(), contentAlignment = if (besideList) Alignment.CenterEnd else Alignment.Center) {
                    OnboardingBottomBar(
                        modifier = Modifier.widthIn(max = FlowMaxContentWidth),
                        state = state,
                        onBack = { viewModel.back() },
                        onNext = {
                            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            viewModel.next()
                        },
                    )
                }
            },
        ) { innerPadding ->
            SharedTransitionLayout(Modifier.fillMaxSize().padding(innerPadding)) {
                OnboardingSteps(
                    state = state,
                    heroFrom = heroFrom,
                    newVideoAlerts = newVideoAlerts,
                    interestsRevealed = interestsRevealed,
                    actions =
                        StepActions(
                            viewModel = viewModel,
                            onTopicToggle = { topic ->
                                val selecting = topic !in state.topics
                                haptic.performHapticFeedback(if (selecting) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                                viewModel.toggleTopic(topic)
                            },
                            onInterestsRevealed = { interestsRevealed = true },
                        ),
                )
            }
        }
    }
}
