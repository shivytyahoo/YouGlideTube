package io.github.aedev.flow.ui.screens.player.state

import io.github.aedev.flow.data.local.VideoQuality
import io.github.aedev.flow.data.model.LiveChatMessage
import io.github.aedev.flow.data.model.SponsorBlockSegment
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.innertube.models.YouTubeClient
import io.github.aedev.flow.innertube.models.response.PlayerResponse
import io.github.aedev.flow.innertube.models.response.VideoHeatmap
import io.github.aedev.flow.player.stream.StoryboardLevel
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamSegment
import org.schabi.newpipe.extractor.stream.VideoStream

data class VideoPlayerUiState(
    val cachedVideo: Video? = null,
    val relatedVideos: List<Video> = emptyList(),
    val videoStream: VideoStream? = null,
    val audioStream: AudioStream? = null,
    val availableQualities: List<VideoQuality> = emptyList(),
    val selectedQuality: VideoQuality = VideoQuality.AUTO,
    val isLoading: Boolean = false,
    val error: String? = null,
    /** Optional secondary hint shown below the primary error in the player's error panel. */
    val errorHint: String? = null,
    /** The error is a bot wall, which a fresh YouTube session may get past. */
    val errorOffersSessionReset: Boolean = false,
    val savedPosition: Long? = null,
    val isAdaptiveMode: Boolean = false,
    val isSubscribed: Boolean = false,
    val isNotificationsEnabled: Boolean = false,
    val likeState: String? = null,
    /** How far the viewer's own like has moved since this video loaded: the parsed count
     * pairs with the at-load state, so the pill adds this to it. Reset with the video. */
    val likeCountDelta: Int = 0,
    /** Same, for the dislike pill: the dislike count has no vote of its own to lean on, so
     * the tap-time snapshot drives this exactly like [likeCountDelta]. Reset with the video. */
    val dislikeCountDelta: Int = 0,
    val channelSubscriberCount: Long? = null,
    val channelAvatarUrl: String? = null,
    val chapters: List<StreamSegment> = emptyList(),
    val storyboard: List<StoryboardLevel> = emptyList(),
    /** A livestream that has ended: its date reads "Streamed …" rather than a plain date. */
    val isArchivedLivestream: Boolean = false,
    val heatmap: VideoHeatmap? = null,
    val autoplayEnabled: Boolean = true,
    val streamSizes: Map<String, Long> = emptyMap(),
    val localFilePath: String? = null,
    val localFileVideoId: String? = null,
    val dislikeCount: Long? = null,
    val queueTitle: String? = null,
    val hlsUrl: String? = null,
    val shouldDismissPlayer: Boolean = false,
    val isBackgroundPlaybackMode: Boolean = false,
    val isRestoredSession: Boolean = false,
    val resumedInMiniPlayer: Boolean = false,
    val isUpcoming: Boolean = false,
    val upcomingReleaseTimeMs: Long? = null,
    val isUpcomingReminderSet: Boolean = false,
    /** SponsorBlock segments loaded from local DB for offline playback. Null when streaming online. */
    val offlineSponsorBlockSegments: List<SponsorBlockSegment>? = null,
    val innerTubeVideoFormats: List<PlayerResponse.StreamingData.Format> = emptyList(),
    val innerTubeAudioFormats: List<PlayerResponse.StreamingData.Format> = emptyList(),
    val isLive: Boolean = false,
    val isLiveChatAvailable: Boolean = false,
    val liveChatMessages: List<LiveChatMessage> = emptyList(),
    val isLiveChatLoading: Boolean = false,
    /** The playback session the server-history beacons belong to: one cpn, the
     * watchtime URL of the response that produced the streams, and its client.
     * Null when no session is armed. Reset with the video, kept on same-video
     * reloads like [likeState]. */
    val serverWatchSession: ServerWatchSession? = null,
) {
    /**
     * The state to show while [video] is being armed: its metadata, and every field that describes
     * the video that was playing before it cleared. Sheet flags, queue title and the live-chat
     * transcript are left alone — a caller that owns one of those re-applies it with `copy`.
     *
     * The subscription and like state survive a reload of the same channel or video: their collector
     * only emits on a change, so a value cleared here under a pair it is already watching never came
     * back (#1160).
     */
    fun resetForVideo(video: Video): VideoPlayerUiState =
        copy(
            cachedVideo = video,
            isRestoredSession = false,
            isLoading = true,
            error = null,
            errorHint = null,
            errorOffersSessionReset = false,
            videoStream = null,
            audioStream = null,
            streamSizes = emptyMap(),
            savedPosition = null,
            relatedVideos = emptyList(),
            isSubscribed = isSubscribed && video.channelId.isNotBlank() && video.channelId == cachedVideo?.channelId,
            likeState = likeState.takeIf { video.id == cachedVideo?.id },
            likeCountDelta = if (video.id == cachedVideo?.id) likeCountDelta else 0,
            dislikeCountDelta = if (video.id == cachedVideo?.id) dislikeCountDelta else 0,
            hlsUrl = null,
            localFilePath = null,
            localFileVideoId = null,
            isUpcoming = false,
            upcomingReleaseTimeMs = null,
            // A video without these sends nothing, so they are cleared here or stay from the last one (#1111).
            chapters = emptyList(),
            heatmap = null,
            storyboard = emptyList(),
            serverWatchSession = serverWatchSession.takeIf { video.id == cachedVideo?.id },
        )
}

/** One playback's server-history session: the watchtime URL, the cpn its
 * playback beacon and every watchtime ping share, and the client that owns
 * the player response. */
data class ServerWatchSession(
    val videoId: String,
    val watchtimeUrl: String,
    val cpn: String,
    val client: YouTubeClient,
)
