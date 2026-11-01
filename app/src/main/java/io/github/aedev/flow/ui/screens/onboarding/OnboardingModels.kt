package io.github.aedev.flow.ui.screens.onboarding

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.Channel

internal const val MIN_TOPICS = 3
internal const val STAGGER_DELAY_MS = 50L

/** The setup steps in order. Welcome and Ready bookend the two steps the progress bar counts. */
enum class OnboardingStep(
    @StringRes val labelRes: Int?,
) {
    WELCOME(null),
    SIGNIN(R.string.onboarding_step_signin),
    CHANNELS(R.string.onboarding_step_channels),
    READY(null),
    ;

    val index: Int get() = ordinal

    val showsProgress: Boolean get() = labelRes != null

    companion object {
        val progressSteps: List<OnboardingStep> = entries.filter { it.showsProgress }
    }
}

@Immutable
data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.entries.first(),
    val topics: Set<String> = emptySet(),
    val query: String = "",
    val results: List<Channel> = emptyList(),
    val searching: Boolean = false,
    val subscribed: List<Channel> = emptyList(),
    val notifying: Set<String> = emptySet(),
    val completed: Boolean = false,
    /** True once a Google session exists; subscriptions then land on the account itself. */
    val signedIn: Boolean = false,
    /** A signed-out subscribe tap asks for sign-in instead of writing a local subscription. */
    val showSignInPrompt: Boolean = false,
) {
    val topicsLeft: Int get() = (MIN_TOPICS - topics.size).coerceAtLeast(0)

    /** Both counted steps are optional: sign-in and subscribing can always be skipped. */
    val canAdvance: Boolean get() = true

    fun isSubscribed(channelId: String): Boolean = subscribed.any { it.id == channelId }
}
