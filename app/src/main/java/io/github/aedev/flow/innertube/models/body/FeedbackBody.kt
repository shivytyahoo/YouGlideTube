package io.github.aedev.flow.innertube.models.body

import io.github.aedev.flow.innertube.models.Context
import kotlinx.serialization.Serializable

/**
 * youtubei.js FeedbackEndpoint.buildRequest: the tokens the server issued
 * with the history controls, sent to youtubei/v1/feedback. The response
 * carries feedbackResponses[0].isProcessed.
 */
@Serializable
data class FeedbackBody(
    val context: Context,
    val feedbackTokens: List<String>,
    val shouldMerge: Boolean = false,
    val isFeedbackTokenUnencrypted: Boolean = false,
)
