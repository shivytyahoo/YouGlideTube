package io.github.shivytyahoo.glidetube.player.config

import io.github.shivytyahoo.glidetube.player.stream.VideoCodecUtils

object PlayerConfig {
    const val TAG = "EnhancedPlayerManager"

    val PREFERRED_VIDEO_MIME_TYPES = VideoCodecUtils.preferredVideoMimeTypes()

    // ===== Cache Configuration =====

    /** Maximum cache size in bytes (500 MB — default) */
    const val CACHE_SIZE_BYTES = 500L * 1024L * 1024L

    /** The video and Shorts cache folder; it held every player's data before songs got their own. */
    const val CACHE_DIR_NAME = "exoplayer"

    /** The song and animated artwork cache folder. */
    const val MUSIC_CACHE_DIR_NAME = "exoplayer_music"

    // ===== Buffer Configuration =====

    /** Allocator buffer size (64 KB optimal for DASH segments) */
    const val ALLOCATOR_BUFFER_SIZE = 64 * 1024

    /** Back buffer duration in milliseconds (30 seconds for instant rewind) */
    const val BACK_BUFFER_DURATION_MS = 30_000

    /** Back buffer duration for low-memory devices. */
    const val LOW_MEMORY_BACK_BUFFER_DURATION_MS = 0

    /** Hard runtime cap for main-player max buffer */
    const val MAX_SAFE_MAIN_BUFFER_MS = 300_000

    /** Hard runtime cap for main-player min buffer so low-RAM devices do not over-retain media. */
    const val MAX_SAFE_MAIN_MIN_BUFFER_MS = 60_000

    /** Runtime caps used when Android reports a small app heap. */
    const val LOW_MEMORY_MAX_SAFE_MAIN_BUFFER_MS = 18_000
    const val LOW_MEMORY_MAX_SAFE_MAIN_MIN_BUFFER_MS = 8_000

    /**
     * Byte budget for the main player's read-ahead. The loader stops above the min buffer once
     * this is allocated, so at 2160p it capped the buffer near the 60 s min instead of letting
     * it reach the 300 s window. 144 MB lines up with the size Media3 derives for a muxed
     * stream when no explicit budget is set (~137 MB), and lets the buffer reach ~80 s at
     * 2160p. The low-memory tiers below are untouched.
     */
    const val MAIN_TARGET_BUFFER_BYTES = 144 * 1024 * 1024

    /** Smaller target buffer budgets for devices with 256-384 MB app heaps. */
    const val LOW_MEMORY_MAIN_TARGET_BUFFER_BYTES = 4 * 1024 * 1024
    const val MID_MEMORY_MAIN_TARGET_BUFFER_BYTES = 12 * 1024 * 1024

    /** Explicit target buffer budget per shorts player in the pooled shorts stack. */
    const val SHORTS_TARGET_BUFFER_BYTES = 8 * 1024 * 1024

    /**
     * Shorts buffer window. Deliberately below the floors in `BufferDurations` — a swipe has to
     * start the next clip immediately, and three pooled players each holding a long window would
     * cost more memory than the feed is worth.
     */
    const val SHORTS_MIN_BUFFER_MS = 3_000
    const val SHORTS_MAX_BUFFER_MS = 15_000
    const val SHORTS_BUFFER_FOR_PLAYBACK_MS = 250
    const val SHORTS_BUFFER_FOR_REBUFFER_MS = 750
    const val SHORTS_BACK_BUFFER_MS = 2_000

    /** Music buffer window. Audio-only, so a long window is cheap and a low start threshold is safe. */
    const val MUSIC_MIN_BUFFER_MS = 2_500
    const val MUSIC_MAX_BUFFER_MS = 30_000
    const val MUSIC_BUFFER_FOR_PLAYBACK_MS = 1_000
    const val MUSIC_BUFFER_FOR_REBUFFER_MS = 1_500

    /** Preferred delay from the true live edge. Keeps YouTube live playback stable. */
    const val LIVE_EDGE_GAP_MS = 10_000L

    /**
     * Live edge per the stream's latency class: an ultra-low stream is only a few seconds
     * behind live, so holding the normal 10 s back would leave the viewer a segment behind
     * the chat; a normal-latency stream needs the room. Unknown classes fall back to the
     * normal values. The class arrives prefixed on some clients (MWEB_LATENCY_CLASS_LOW),
     * so the match is on the suffix.
     */
    fun liveTargetOffsetMs(latencyClass: String?): Long =
        when {
            latencyClass?.endsWith("ULTRA_LOW") == true -> 3_000L
            latencyClass?.endsWith("LOW") == true -> 5_000L
            else -> 8_000L
        }

    /** Minimum live offset for [liveTargetOffsetMs]: the player may not creep closer than this. */
    fun liveMinOffsetMs(latencyClass: String?): Long =
        when {
            latencyClass?.endsWith("ULTRA_LOW") == true -> 2_000L
            latencyClass?.endsWith("LOW") == true -> 3_000L
            else -> 5_000L
        }

    /** How far the player may speed up or slow down to hold the live edge. */
    const val LIVE_MIN_PLAYBACK_SPEED = 0.97f
    const val LIVE_MAX_PLAYBACK_SPEED = 1.03f

    /** Maximum DVR window requested for live streams where the manifest supports it. */
    const val LIVE_DVR_MAX_OFFSET_MS = 2 * 60 * 60 * 1000L

    // ===== Bandwidth Thresholds =====

    /**
     * Initial bandwidth estimate in bits per second, used only when no measured estimate has
     * been persisted yet. Media3's own default (1 Mbps): the meter corrects upward within
     * seconds on a fast network, but an optimistic start stalls the first seconds on a slow
     * one — and a stall is what this build exists to remove. Once a real estimate exists it
     * is persisted on release and seeded on the next start instead.
     */
    const val INITIAL_BANDWIDTH_ESTIMATE = 1_000_000L

    /**
     * The persisted estimate is from the last session's network, and a stale-high seed starts the
     * next session at 4K on a link that cannot carry it — the first segments take a minute to arrive
     * and the video stalls before it has played a frame. Cap the seed so playback always starts at
     * a modest quality and climbs on the live measurement instead; climbing costs a few seconds of
     * lower quality, stalling costs the video.
     */
    const val MAX_INITIAL_BANDWIDTH_ESTIMATE_BPS = 3_000_000L

    /**
     * Cap on the estimate the initial pick trusts. The live estimate is sampled per transfer end
     * and a chunk's burst phase inflates it far above the sustained link (39–252 Mbps seen on a
     * ~0.3–0.5 Mbps link), so an uncapped pick starts every video at the top rung and stalls its
     * way down. At this cap the budget (× [AUTO_BANDWIDTH_FRACTION]) lands on 480p on a normal
     * ladder, and the ladder climbs from there only on sustained evidence.
     */
    const val INITIAL_PICK_BANDWIDTH_CAP_BPS = 1_000_000L

    // ===== Quality Adaptation =====

    /** Share of the bandwidth estimate a stream may use, for Auto's own picks and Media3's ladder alike. */
    const val AUTO_BANDWIDTH_FRACTION = 0.7f

    /**
     * Buffer that must be ahead before Auto climbs a rung — Media3's upgrade gate and our own
     * ceiling-raise guard share this value. At the 10 s default the climb fired ~5 s after a tap
     * on a burst-inflated estimate (199–206 Mbps on a link sustaining a few), while the buffer
     * was barely past the gate; the first segment at the new rung then took tens of seconds to
     * arrive and the shallow buffer drained to zero. 30 s makes the climb follow demonstrated
     * throughput: the buffer builds fast at the start rung, so a healthy link still climbs
     * within seconds, while a slow one stays low until it has actually banked buffer.
     */
    const val ABR_MIN_BUFFER_FOR_QUALITY_INCREASE_MS = 30_000

    /**
     * Media3 holds the current quality while more than this is buffered. At its 25 s default a full
     * 300 s buffer drains for 275 s before a collapsed network steps down; the midpoint of the main
     * buffer window cuts that to 120 s.
     */
    const val ABR_MAX_BUFFER_FOR_QUALITY_DECREASE_MS = (MAX_SAFE_MAIN_MIN_BUFFER_MS + MAX_SAFE_MAIN_BUFFER_MS) / 2

    /** Interval for bandwidth checks during playback (5 seconds) */
    const val BANDWIDTH_CHECK_INTERVAL_MS = 5000L

    /** Upgrade threshold - need 30% more bandwidth to upgrade */
    const val QUALITY_UPGRADE_THRESHOLD = 1.3f

    /** Maximum stream errors before quality downgrade */
    const val MAX_STREAM_ERRORS = 2

    /** Buffering count threshold before quality downgrade */
    const val BUFFERING_COUNT_THRESHOLD = 3

    // How many bytes the loader reads before it re-checks whether to keep loading. ExoPlayer's
    // default is 1 MiB, which makes the first seconds of a YouTube stream feel stuck on slow or
    // throttled links; NewPipe (#7919) fixed long initial buffering by shrinking it to 16 KiB.
    const val PROGRESSIVE_LOAD_INTERVAL_BYTES = 32 * 1024

    // ===== Network Configuration =====

    /** Maximum concurrent requests per host for adaptive streaming */
    const val MAX_REQUESTS_PER_HOST = 20

    /** Maximum total concurrent requests */
    const val MAX_REQUESTS = 40

    /** Connection pool size */
    const val CONNECTION_POOL_SIZE = 15

    /** Connection pool keep-alive duration in minutes */
    const val CONNECTION_POOL_KEEP_ALIVE_MINUTES = 5L

    /** Connect timeout in seconds */
    const val CONNECT_TIMEOUT_SECONDS = 15L

    /** Read timeout in seconds */
    const val READ_TIMEOUT_SECONDS = 30L

    /** Write timeout in seconds */
    const val WRITE_TIMEOUT_SECONDS = 15L

    // ===== Position Tracking =====

    /** Position tracker polling interval in milliseconds */
    const val POSITION_TRACKER_INTERVAL_MS = 1_000L

    /** Auto-save interval in milliseconds (30 seconds) */
    const val AUTO_SAVE_INTERVAL_MS = 30_000L

    /** Stuck detection threshold (number of checks with no position change) */
    const val STUCK_DETECTION_THRESHOLD = 2

    // ===== Surface Configuration =====

    /** Default surface ready timeout in milliseconds */
    const val DEFAULT_SURFACE_TIMEOUT_MS = 500L

    // ===== Error Recovery =====

    /** Delay before retry after error in milliseconds */
    const val ERROR_RETRY_DELAY_MS = 1000L

    // ===== Renderer Scheduling (experiment) =====

    const val ENABLE_DYNAMIC_SCHEDULING = false

    // ===== Video Size Constraints =====
    const val MAX_VIDEO_WIDTH = 3840

    const val MAX_VIDEO_HEIGHT = 2160

    /** 8K: only offered to Auto when a decoder on the device actually plays it (see VideoSizeCap). */
    const val MAX_VIDEO_WIDTH_8K = 7680

    const val MAX_VIDEO_HEIGHT_8K = 4320

    // ===== Long-buffer mode ("Buffer further ahead") =====

    /** Duration window a long buffer holds ahead, in milliseconds (1200 s). */
    const val LONG_BUFFER_MAX_MS = 1_200_000

    /** Byte budget a long buffer holds ahead on phones. */
    const val LONG_BUFFER_TARGET_BUFFER_BYTES = 400 * 1024 * 1024

    /** Byte budget a long buffer holds ahead on TV, where the heap is shared with the leanback UI. */
    const val LONG_BUFFER_TARGET_BUFFER_BYTES_TV = 150 * 1024 * 1024
}
