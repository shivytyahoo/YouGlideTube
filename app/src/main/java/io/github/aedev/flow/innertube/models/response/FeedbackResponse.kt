package io.github.aedev.flow.innertube.models.response

import kotlinx.serialization.Serializable

/** The youtubei/v1/feedback answer: one entry per token, processed when applied. */
@Serializable
data class FeedbackResponse(
    val feedbackResponses: List<FeedbackResult> = emptyList(),
) {
    @Serializable
    data class FeedbackResult(
        val isProcessed: Boolean = false,
    )
}
