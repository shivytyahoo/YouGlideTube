package io.github.aedev.flow.data.model

import kotlinx.serialization.Serializable
import org.schabi.newpipe.extractor.Page

@Serializable
data class VideoCollaborator(
    val name: String,
    val channelId: String = "",
    val thumbnailUrl: String = "",
    val subscriberCountText: String = "",
)

data class Video(
    val id: String,
    val title: String,
    val channelName: String,
    val channelId: String,
    val thumbnailUrl: String,
    val duration: Int, // in seconds
    val viewCount: Long,
    val likeCount: Long = 0,
    val uploadDate: String,
    val timestamp: Long = System.currentTimeMillis(),
    // True only when [timestamp] is a real publish time (RSS); list responses carry only "3 weeks ago".
    val timestampIsExact: Boolean = false,
    val description: String = "",
    val channelThumbnailUrl: String = "",
    val tags: List<String> = emptyList(),
    // YouTube's category, such as "Music"; kept out of [tags] so it never reads as a creator keyword.
    val category: String? = null,
    val isMusic: Boolean = false,
    val isLive: Boolean = false,
    val isShort: Boolean = false,
    val isUpcoming: Boolean = false,
    // An upcoming row that is a scheduled live stream rather than a premiere.
    val isScheduledLive: Boolean = false,
    // YouTube's own label, so it arrives translated; null on an ordinary video.
    val membersOnlyText: String? = null,
    val commentCountText: String = "",
    val channelThumbnailUrls: List<String> = emptyList(),
    val collaborators: List<VideoCollaborator> = emptyList(),
    val isVerifiedChannel: Boolean = false,
    // YouTube's own pills on a search result — "4K", "CC", "New" — already localised.
    val badges: List<String> = emptyList(),
    // The sentence from the description or transcript that matched the query, on search results only.
    val snippet: String = "",
    val snippetHighlights: List<IntRange> = emptyList(),
    // Transient: when this video was added to the playlist currently being viewed. Not persisted
    // on the video row — populated only by playlist-scoped queries.
    val addedAtInPlaylist: Long? = null,
    // Transient: the entry id the account's playlist editor needs to remove this video from the
    // playlist it was listed in — populated only by account playlist page parses.
    val playlistSetVideoId: String? = null,
)

data class Channel(
    val id: String,
    val name: String,
    val thumbnailUrl: String,
    val subscriberCount: Long,
    val description: String = "",
    val isSubscribed: Boolean = false,
    val isMusic: Boolean = false,
    val handle: String = "",
    val videoCount: Int = 0,
    val isVerified: Boolean = false,
    // Full channel URL for navigation
    val url: String = "",
)

data class Playlist(
    val id: String,
    val name: String,
    val thumbnailUrl: String,
    val videoCount: Int,
    val description: String = "",
    val videos: List<Video> = emptyList(),
    val isLocal: Boolean = true,
    val isCourse: Boolean = false,
)

data class Comment(
    val id: String,
    val author: String,
    val authorThumbnail: String,
    val text: String,
    val likeCount: Int,
    val publishedTime: String,
    val replies: List<Comment> = emptyList(),
    val replyCount: Int = 0,
    val repliesPage: Page? = null,
    val isPinned: Boolean = false,
    val continuationToken: String? = null,
    val authorChannelId: String = "",
    val richText: RichText? = null,
    val likeCountText: String = "",
    val pinnedByText: String? = null,
    val isHearted: Boolean = false,
    val heartedByText: String? = null,
    val isVerified: Boolean = false,
    val isCreator: Boolean = false,
    val isArtist: Boolean = false,
    val isLiked: Boolean = false,
    val isDisliked: Boolean = false,
    val likeCountLikedText: String = "",
    // The toolbar surface's own perform-action endpoints, raw JSON; null when the section
    // carried none (signed out, or a legacy renderer), where voting cannot be offered.
    val likeAction: String? = null,
    val unlikeAction: String? = null,
    val dislikeAction: String? = null,
    val undislikeAction: String? = null,
    // The reply dialog's own token (`createCommentReplyEndpoint.createReplyParams`), from the
    // same toolbar surface, and the path it posts to; null when replies cannot be posted
    // (signed out, or a legacy renderer). NOT the create box's createCommentParams — the
    // reply endpoint carries its own field, which is why a create-params search found nothing.
    val replyParams: String? = null,
    val replyPath: String? = null,
    val isOwn: Boolean = false,
    // An own comment's menu writes, matched by structure in its toolbar surface's menu
    // (the Limusic/youtubei.js recipe): edit replays an update endpoint with its own params
    // field and text field; delete replays a perform-action endpoint like a vote.
    val editParams: String? = null,
    val editPath: String? = null,
    val editParamsField: String? = null,
    val editTextField: String? = null,
    val editPrefill: String? = null,
    val deleteAction: String? = null,
)

enum class SearchFilter {
    ALL,
    VIDEOS,
    CHANNELS,
    PLAYLISTS,
}
