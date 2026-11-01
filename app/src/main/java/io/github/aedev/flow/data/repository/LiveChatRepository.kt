package io.github.aedev.flow.data.repository

import android.util.Log
import io.github.aedev.flow.data.model.LiveChatMessage
import io.github.aedev.flow.data.model.LiveChatMessageType
import io.github.aedev.flow.data.model.LiveChatSegment
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.innertube.models.response.GetLiveChatResponse
import io.github.aedev.flow.utils.ShivLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** The outcome of a live-chat send, read off the send response's actions. */
enum class LiveChatSendStatus {
    POSTED,
    ATTESTATION,
    FAILED,
}

/**
 * Fetches YouTube live chat via the native InnerTube stack (no NewPipeExtractor). Ported from PipePipe's
 * YoutubeBulletCommentsExtractor
 */
@Singleton
class LiveChatRepository
    @Inject
    constructor() {
        data class LiveChatPage(
            val messages: List<LiveChatMessage>,
            val nextContinuation: String?,
            val timeoutMs: Long,
        )

        suspend fun initialContinuation(videoId: String): String? =
            withContext(Dispatchers.IO) {
                try {
                    YouTube.liveChatContinuation(videoId).getOrNull()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to seed live chat continuation for $videoId: ${e.message}")
                    null
                }
            }

        /** Deletes one own message; never throws — failures come back as false. */
        suspend fun deleteLiveChatMessage(
            message: LiveChatMessage,
        ): Boolean =
            withContext(Dispatchers.IO) {
                try {
                    YouTube.deleteLiveChatMessage(message).getOrThrow()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Logcat alone never reaches a Diagnostics report, so the
                    // reason is also written to ShivLog for the next report.
                    Log.w(TAG, "Failed to delete live chat message ${message.id}: ${e.message}")
                    ShivLog.i("liveChatDelete id=${message.id} error=${e.message}")
                    false
                }
            }

        /** Posts one message; never throws — failures come back as [LiveChatSendStatus.FAILED]. */
        suspend fun sendLiveChatMessage(
            videoId: String,
            channelId: String,
            text: String,
        ): LiveChatSendStatus =
            withContext(Dispatchers.IO) {
                try {
                    YouTube.sendLiveChatMessage(videoId, channelId, text).getOrThrow()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Live chat send failed: ${e.message}")
                    LiveChatSendStatus.FAILED
                }
            }

        suspend fun poll(continuation: String): LiveChatPage? =
            withContext(Dispatchers.IO) {
                val response =
                    try {
                        YouTube.liveChat(continuation).getOrNull()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Live chat poll failed: ${e.message}")
                        null
                    } ?: return@withContext null

                val cont = response.continuationContents?.liveChatContinuation ?: return@withContext null
                val continuationObj = cont.continuations.firstOrNull()
                val next = continuationObj?.token()
                val timeout = pollDelayMs(continuationObj?.timeoutMs())
                val messages = cont.actions.mapNotNull { it.addChatItemAction?.item?.toMessage() }
                LiveChatPage(messages = messages, nextContinuation = next, timeoutMs = timeout)
            }

        private fun GetLiveChatResponse.Action.Item.toMessage(): LiveChatMessage? {
            liveChatTextMessageRenderer?.let { return it.toMessage(LiveChatMessageType.TEXT) }
            liveChatPaidMessageRenderer?.let { return it.toMessage(LiveChatMessageType.SUPER_CHAT) }
            liveChatMembershipItemRenderer?.let { return it.toMessage(LiveChatMessageType.MEMBERSHIP) }
            return null
        }

        private fun GetLiveChatResponse.ChatMessageRenderer.toMessage(type: LiveChatMessageType): LiveChatMessage? {
            val msgId = id ?: return null
            val bodyMessage =
                when (type) {
                    LiveChatMessageType.MEMBERSHIP -> {
                        message?.takeIf { it.plainText().isNotBlank() } ?: headerSubtext
                    }

                    else -> {
                        message
                    }
                }
            val text = bodyMessage?.plainText().orEmpty()
            val segments = bodyMessage?.toSegments().orEmpty()

            var isOwner = false
            var isModerator = false
            var isVerified = false
            var isMember = false
            var memberBadgeUrl: String? = null
            authorBadges.forEach { badge ->
                val renderer = badge.liveChatAuthorBadgeRenderer ?: return@forEach
                when (renderer.icon?.iconType?.uppercase()) {
                    "OWNER" -> isOwner = true
                    "MODERATOR" -> isModerator = true
                    "VERIFIED" -> isVerified = true
                }
                renderer.customThumbnail?.bestUrl()?.let {
                    isMember = true
                    memberBadgeUrl = it
                }
            }

            return LiveChatMessage(
                id = msgId,
                author = authorName?.value() ?: "",
                authorPhotoUrl = authorPhoto?.bestUrl(),
                menuParams = contextMenuEndpoint?.liveChatItemContextMenuEndpoint?.params,
                menuApiUrl = contextMenuEndpoint?.commandMetadata?.webCommandMetadata?.apiUrl,
                message = text,
                segments = segments,
                timestamp = timestampText?.value(),
                type = type,
                isOwner = isOwner,
                isModerator = isModerator,
                isVerified = isVerified,
                isMember = isMember,
                memberBadgeUrl = memberBadgeUrl,
                superChatAmount = purchaseAmountText?.value(),
                superChatArgb = bodyBackgroundColor?.toInt(),
                superChatHeaderArgb = headerBackgroundColor?.toInt(),
            )
        }

        private fun GetLiveChatResponse.Message.toSegments(): List<LiveChatSegment> =
            runs.mapNotNull { run ->
                val emoji = run.emoji
                when {
                    run.text != null -> {
                        LiveChatSegment(text = run.text)
                    }

                    emoji != null -> {
                        val isCustom = emoji.isCustomEmoji == true
                        val unicode = emoji.emojiId?.takeIf { !isCustom && it.isNotBlank() }
                        if (unicode != null) {
                            LiveChatSegment(text = unicode)
                        } else {
                            LiveChatSegment(
                                text = emoji.shortcuts.firstOrNull() ?: emoji.emojiId ?: "",
                                emojiImageUrl = emoji.image?.bestUrl(),
                            )
                        }
                    }

                    else -> {
                        null
                    }
                }
            }

        companion object {
            private const val TAG = "LiveChatRepository"
            private const val DEFAULT_TIMEOUT_MS = 1000L
            private const val MIN_TIMEOUT_MS = 1000L
            private const val MAX_TIMEOUT_MS = 10_000L

            /** How long to wait before the next poll: the server's own `timeoutMs`, longer on a quiet chat. */
            internal fun pollDelayMs(serverTimeoutMs: Long?): Long =
                (serverTimeoutMs ?: DEFAULT_TIMEOUT_MS).coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        }
    }
