package io.github.aedev.flow.innertube.models.body

import io.github.aedev.flow.innertube.models.Context
import kotlinx.serialization.Serializable

/**
 * `live_chat/send_message`, per YouTube.js `LiveChat.sendMessage`: one text segment, a fresh
 * client message id, and the proto-encoded [params]. Typed on purpose, like the comment write
 * bodies — a hand-built JSON body drops the context's defaulted parts.
 */
@Serializable
data class LiveChatSendBody(
    val context: Context,
    val richMessage: RichMessage,
    val clientMessageId: String,
    val params: String,
) {
    @Serializable
    data class RichMessage(
        val textSegments: List<TextSegment>,
    )

    @Serializable
    data class TextSegment(
        val text: String,
    )
}
