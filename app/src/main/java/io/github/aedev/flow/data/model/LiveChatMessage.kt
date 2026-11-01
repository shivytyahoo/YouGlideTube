package io.github.aedev.flow.data.model

data class LiveChatSegment(
    val text: String,
    val emojiImageUrl: String? = null,
)

data class LiveChatMessage(
    val id: String,
    val author: String,
    val authorPhotoUrl: String?,
    val message: String,
    val segments: List<LiveChatSegment> = emptyList(),
    val timestamp: String?,
    val type: LiveChatMessageType = LiveChatMessageType.TEXT,
    val isOwner: Boolean = false,
    /** True when the author handle matches the signed-in account's handle (normalised). */
    val isMine: Boolean = false,
    /** Params for the message's context-menu endpoint; null when the poll carried none. */
    val menuParams: String? = null,
    /** apiUrl from the context-menu endpoint's command metadata, if the poll carried it. */
    val menuApiUrl: String? = null,
    val isModerator: Boolean = false,
    val isVerified: Boolean = false,
    val isMember: Boolean = false,
    val memberBadgeUrl: String? = null,
    // Super-chat only. ARGB colors are packed ints suitable for Compose Color(argb).
    val superChatAmount: String? = null,
    val superChatArgb: Int? = null,
    val superChatHeaderArgb: Int? = null,
)

enum class LiveChatMessageType { TEXT, SUPER_CHAT, MEMBERSHIP }
