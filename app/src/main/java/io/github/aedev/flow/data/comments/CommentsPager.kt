package io.github.aedev.flow.data.comments

import io.github.aedev.flow.utils.ShivLog
import io.github.aedev.flow.utils.formatLikeCount

import android.util.Log
import io.github.aedev.flow.data.model.Comment
import io.github.aedev.flow.data.model.distinctByNonBlankKey
import io.github.aedev.flow.data.model.mergeDistinctByNonBlankKey
import io.github.aedev.flow.data.repository.PostReplyResult
import io.github.aedev.flow.data.repository.YouTubeRepository
import io.github.aedev.flow.innertube.pages.VideoCommentSort
import io.github.aedev.flow.player.PlaybackStartupPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "CommentsPager"

/** How long a comment fetch waits for playback to stop competing with it before giving up. */
private const val STARTUP_GATE_TIMEOUT_MS = 20_000L

/**
 * Pages fetched behind the first one, so a filter that reads the whole loaded set has something to
 * read. Bounded and sequential: each page waits for the one before it, and a user-driven load more
 * or a sort change cancels what is left.
 */
private const val DEFAULT_PREFETCH_PAGES = 3

/** What the pager needs to know about the surface's playback before it spends the network on comments. */
internal data class CommentsPlaybackState(
    val isPlaybackLoading: Boolean,
    val currentVideoId: String?,
) {
    companion object {
        /** For a surface whose playback never competes with the comment fetch. */
        val READY = CommentsPlaybackState(isPlaybackLoading = false, currentVideoId = null)
    }
}

/**
 * The comment list for one video: first page, further pages, and a comment's replies.
 *
 * The player and Shorts each had their own copy of this and drifted — only one of them deduplicated
 * by comment id, and neither guarded against a second request landing while the first was still in
 * flight, so re-opening the sheet fetched page one twice.
 *
 * One request per (video, page) is in flight at a time. A request for a different video supersedes
 * the one before it; a repeat of the request already running is dropped.
 *
 * [isCurrentVideo] is the owner's decision about whether the answer still belongs on screen, and it
 * is asked both before and after the fetch, because the fetch outlives a fast swipe to the next
 * video. [fetchTimeoutMs] bounds the fetch itself for a surface that wants one.
 */
internal class CommentsPager(
    private val repository: YouTubeRepository,
    private val scope: CoroutineScope,
    private val playbackState: Flow<CommentsPlaybackState> = flowOf(CommentsPlaybackState.READY),
    private val isCurrentVideo: (String) -> Boolean = { true },
    private val fetchTimeoutMs: Long? = null,
    private val prefetchPages: Int = DEFAULT_PREFETCH_PAGES,
) {
    private val _comments = MutableStateFlow<List<Comment>>(emptyList())
    val comments: StateFlow<List<Comment>> = _comments.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _hasMore = MutableStateFlow(false)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _sortOptions = MutableStateFlow<List<VideoCommentSort>>(emptyList())
    val sortOptions: StateFlow<List<VideoCommentSort>> = _sortOptions.asStateFlow()

    private val _totalText = MutableStateFlow<String?>(null)
    val totalText: StateFlow<String?> = _totalText.asStateFlow()

    private val _totalCount = MutableStateFlow<Long?>(null)
    val totalCount: StateFlow<Long?> = _totalCount.asStateFlow()

    private val _createCommentParams = MutableStateFlow<String?>(null)

    /** The create box token of the loaded section; non-null only when posting is possible. */
    val createCommentParams: StateFlow<String?> = _createCommentParams.asStateFlow()

    /** Every create token the section carried; posting tries them in order. */
    private val _createCommentParamsCandidates = MutableStateFlow<List<String>>(emptyList())

    private var next: CommentsPageResult = CommentsPageResult.EMPTY
    private var loadJob: Job? = null
    private var prefetchJob: Job? = null
    private var loadingVideoId: String? = null
    private var activeSortToken: String? = null
    private val repliesInFlight = mutableSetOf<String>()

    /** Empties the list, for a video with no comments to fetch and for a player being torn down. */
    fun clear() {
        loadJob?.cancel()
        prefetchJob?.cancel()
        loadJob = null
        prefetchJob = null
        loadingVideoId = null
        activeSortToken = null
        next = CommentsPageResult.EMPTY
        _comments.value = emptyList()
        _isLoading.value = false
        _hasMore.value = false
        _isLoadingMore.value = false
        _sortOptions.value = emptyList()
        _totalText.value = null
        _totalCount.value = null
        _createCommentParams.value = null
        _createCommentParamsCandidates.value = emptyList()
    }

    fun load(videoId: String) {
        if (loadingVideoId == videoId && activeSortToken == null && loadJob?.isActive == true) return
        startLoad(videoId, sortToken = null)
    }

    /** Reloads the section in the order [sort] names, which is a different continuation entirely. */
    fun selectSort(
        videoId: String,
        sort: VideoCommentSort,
    ) {
        if (activeSortToken == sort.token && loadJob?.isActive == true) return
        startLoad(videoId, sortToken = sort.token)
    }

    /** Posts [text] as a new top-level comment, then reloads the section so it shows up. */
    fun postComment(
        text: String,
        onResult: (Boolean) -> Unit,
    ) {
        val params = _createCommentParams.value
        val videoId = loadingVideoId
        if (params.isNullOrBlank() || videoId == null || text.isBlank()) {
            onResult(false)
            return
        }
        scope.launch {
            val posted =
                try {
                    val candidates =
                        _createCommentParamsCandidates.value
                            .ifEmpty { listOf(params) }
                    repository.postVideoComment(candidates, text.trim(), videoId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Error posting comment", e)
                    false
                }
            onResult(posted)
            if (posted && isCurrentVideo(videoId)) {
                startLoad(videoId, sortToken = activeSortToken)
            }
        }
    }

    /**
     * Posts [text] as a reply to [parent]. On success the reply is inserted into the thread
     * locally at once: reloading the thread from the server right after posting kept losing
     * it (the fetched first page does not carry a just-posted reply), so the reply "posted,
     * then vanished" (device evidence, 2026-10-24). The inserted row borrows its author
     * identity from one of the viewer's own comments already on screen; the next full
     * reload replaces it with the server's own copy. Without an identity to borrow, the
     * old thread reload runs instead.
     */
    fun postReply(
        parent: Comment,
        text: String,
        onResult: (Boolean) -> Unit,
    ) {
        val params = parent.replyParams
        val videoId = loadingVideoId
        if (params.isNullOrBlank() || videoId == null || text.isBlank()) {
            onResult(false)
            return
        }
        scope.launch {
            // A nested reply (replying to a reply) re-reads its parent's token fresh,
            // for the same reason edits do: stored thread tokens proved unusable later.
            // Top-level parents keep their proven stored token untouched.
            var targetParent = parent
            val replyAncestor = topLevelAncestorOf(parent.id)
            if (videoId != null && replyAncestor != null && replyAncestor.id != parent.id) {
                val fresh = freshReplyRow(videoId, parent)
                ShivLog.i(
                    "tokRefresh kind=reply old=${parent.replyParams?.take(8)} " +
                        "new=${fresh?.replyParams?.take(8)} same=${fresh?.replyParams == parent.replyParams}",
                )
                if (fresh?.replyParams != null) targetParent = fresh
            }
            if (videoId != null && (replyAncestor == null || replyAncestor.id == parent.id)) {
                val fresh = freshTopLevelRow(videoId, parent.id)
                ShivLog.i(
                    "tokRefresh kind=replyTop old=${parent.replyParams?.take(8)} " +
                        "new=${fresh?.replyParams?.take(8)} same=${fresh?.replyParams == parent.replyParams}",
                )
                if (fresh?.replyParams != null) targetParent = fresh
            }
            val result =
                try {
                    repository.postCommentReply(targetParent, text.trim())
                } catch (e: CancellationException) {
                    // The waiting composer must be answered even on cancellation, or it
                    // stays in its posting state forever.
                    onResult(false)
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Error posting reply", e)
                    PostReplyResult(posted = false, serverReply = null)
                }
            val posted = result.posted
            onResult(posted)
            if (posted && isCurrentVideo(videoId)) {
                val ancestor = topLevelAncestorOf(parent.id)
                // The answer itself carries the reply's real row (real id, timestamp),
                // but proven on 0.3.26 its batch holds no surface tokens — so the row
                // still needs its actions, fetched once propagation lands them.
                if (result.serverReply != null && ancestor != null) {
                    _comments.value =
                        _comments.value.map { current ->
                            if (current.id != ancestor.id) {
                                current
                            } else {
                                current.copy(
                                    replies = current.replies + result.serverReply,
                                    replyCount = current.replyCount + 1,
                                )
                            }
                        }
                    ShivLog.i("replyEntity inserted=true")
                    if (result.serverReply.likeAction == null &&
                        result.serverReply.editParams == null &&
                        result.serverReply.deleteAction == null
                    ) {
                        scheduleTokenRetries(videoId, ancestor.id, result.serverReply.id, text.trim())
                    }
                    return@launch
                }
                val identity = ownIdentity()
                ShivLog.i(
                    "replyLocal ancestor=${ancestor != null} identity=${identity != null}",
                )
                if (ancestor != null && identity != null) {
                    val localReply = appendOwnReply(ancestor, text.trim(), identity)
                    val localId = localReply.id
                    // The local row is bare (no vote/edit/delete tokens): swap it for the
                    // server row as soon as the thread fetch returns it, so the reply
                    // gains its like/menu/Reply actions instead of staying untouchable.
                    // A thread can be fetched either way (continuation or legacy page);
                    // 0.3.23 only tried the continuation, so threads without one never
                    // swapped at all.
                    val continuation = ancestor.continuationToken
                    val legacyPage = ancestor.repliesPage
                    if (continuation == null && legacyPage == null) {
                        // A first reply has no thread fetch at all, and an immediate
                        // section reload does not gain one either (proven 0.3.25:
                        // replyRefresh thread=false) — the list response only reflects
                        // the new reply after propagation. Retry later instead; the
                        // bare row stays put until a full row swaps it.
                        ShivLog.i("replySwap skipped=no-thread-fetch")
                        scheduleTokenRetries(videoId, ancestor.id, localId, text.trim())
                    } else {
                        scope.launch {
                            val serverRow =
                                try {
                                    val rows =
                                        if (continuation != null) {
                                            repository
                                                .getVideoCommentReplies(videoId, continuation)
                                                .comments
                                        } else {
                                            val url = "https://www.youtube.com/watch?v=$videoId"
                                            repository
                                                .getCommentReplies(url, requireNotNull(legacyPage))
                                                .first
                                        }
                                    rows.firstOrNull { it.isOwn && it.text.trim() == text.trim() }
                                } catch (e: Exception) {
                                    null
                                }
                            ShivLog.i("replySwap found=${serverRow != null}")
                            if (serverRow != null) {
                                _comments.value =
                                    _comments.value.map { current ->
                                        if (current.id != ancestor.id) {
                                            current
                                        } else {
                                            current.copy(
                                                replies =
                                                    current.replies.map { r ->
                                                        if (r.id == localId) serverRow else r
                                                    },
                                            )
                                        }
                                    }
                            } else {
                                // Not on the thread page yet: propagation, not absence.
                                scheduleTokenRetries(videoId, ancestor.id, localId, text.trim())
                            }
                        }
                    }
                } else {
                    ancestor?.let { loadReplies(videoId, it) }
                }
            }
        }
    }

    /**
     * Retries fetching a just-posted reply's full row (with its vote/menu/Reply tokens)
     * after propagation lands it in the thread. Never replaces the list: the pending row
     * is swapped only when a matching full row is actually found, so nothing vanishes.
     * The first attempt runs after 3s, not 20s: the write response carries no tokens, so
     * until the swap lands the just-posted row shows no Like/Reply options at all, and
     * a 20s wait for them is visibly worse than the official app. The 20s/60s attempts
     * stay as fallbacks for slow propagation.
     */
    private fun scheduleTokenRetries(
        videoId: String,
        ancestorId: String,
        rowId: String,
        text: String,
    ) {
        scope.launch {
            for ((attempt, delayMs) in listOf(1 to 3_000L, 2 to 20_000L, 3 to 60_000L)) {
                delay(delayMs)
                if (!isCurrentVideo(videoId)) return@launch
                val page =
                    try {
                        fetch { repository.getVideoComments(videoId, activeSortToken) }
                    } catch (e: Exception) {
                        null
                    }
                if (page == null) {
                    ShivLog.i("replyRetry attempt=$attempt thread=false swapped=false")
                    continue
                }
                val freshAncestor = page.comments.firstOrNull { it.id == ancestorId }
                val continuation = freshAncestor?.continuationToken
                val legacyPage = freshAncestor?.repliesPage
                if (continuation == null && legacyPage == null) {
                    ShivLog.i("replyRetry attempt=$attempt thread=false swapped=false")
                    continue
                }
                val serverRow =
                    try {
                        val rows =
                            if (continuation != null) {
                                repository
                                    .getVideoCommentReplies(videoId, continuation)
                                    .comments
                            } else {
                                val url = "https://www.youtube.com/watch?v=$videoId"
                                repository
                                    .getCommentReplies(url, requireNotNull(legacyPage))
                                    .first
                            }
                        rows.firstOrNull { it.isOwn && it.text.trim() == text.trim() }
                    } catch (e: Exception) {
                        null
                    }
                ShivLog.i(
                    "replyRetry attempt=$attempt thread=true swapped=${serverRow != null} " +
                        "editTok=${serverRow?.editParams?.take(8)} editField=${serverRow?.editParamsField} " +
                        "editPath=${serverRow?.editPath} replyTok=${serverRow?.replyParams?.take(8)}",
                )
                if (serverRow != null) {
                    _comments.value =
                        _comments.value.map { current ->
                            if (current.id != ancestorId) {
                                current
                            } else {
                                // Keep the continuation that just fetched this thread on
                                // the ancestor itself. Without it, later action-time
                                // refreshes must re-derive it from a page reload — and
                                // once the ancestor drops off that page (sort change,
                                // ranking), every refresh misses and actions fall back to
                                // tokens an edit has already invalidated (device
                                // evidence 2026-10-29: 404 right after an edit).
                                current.copy(
                                    continuationToken =
                                        freshAncestor?.continuationToken
                                            ?: current.continuationToken,
                                    replies =
                                        current.replies.map { r ->
                                            if (r.id == rowId) serverRow else r
                                        },
                                )
                            }
                        }
                    return@launch
                }
            }
        }
    }

    /** The viewer's own author identity, borrowed from one of their comments on screen. */
    private fun ownIdentity(): Comment? =
        _comments.value.firstNotNullOfOrNull { top ->
            when {
                top.isOwn -> top
                else -> top.replies.firstOrNull { it.isOwn }
            }
        }

    /** Appends a just-posted own reply under its top-level ancestor, count included. */
    private fun appendOwnReply(
        ancestor: Comment,
        text: String,
        identity: Comment,
    ): Comment {
        val localId = "local-${java.util.UUID.randomUUID()}"
        val reply =
            Comment(
                id = localId,
                author = identity.author,
                authorThumbnail = identity.authorThumbnail,
                text = text,
                likeCount = 0,
                publishedTime = "",
                authorChannelId = identity.authorChannelId,
                isOwn = true,
            )
        _comments.value =
            _comments.value.map { current ->
                if (current.id != ancestor.id) {
                    current
                } else {
                    current.copy(
                        replies = current.replies + reply,
                        replyCount = current.replyCount + 1,
                    )
                }
            }
        return reply
    }

    /**
     * A reply row's action tokens re-read from a fresh thread fetch, right before acting
     * on it. Thread-fetched dialog tokens proved unusable when replayed later (device
     * evidence 2026-10-28: a reply's edit and nested reply 404'd both 81 seconds and 2.5
     * hours after the swap, while the same row's delete and page-fetched tokens worked),
     * so an action never trusts tokens stored from an earlier fetch. Returns null for
     * top-level comments (their stored tokens are proven) and when no fresh row is found,
     * in which case the caller falls back to the stored tokens.
     */
    private suspend fun freshReplyRow(videoId: String, comment: Comment): Comment? {
        val ancestor = topLevelAncestorOf(comment.id) ?: return null
        if (ancestor.id == comment.id) return null
        val direct = ancestor.continuationToken
        if (direct != null) {
            try {
                repository.getVideoCommentReplies(videoId, direct).comments
                    .firstOrNull { it.id == comment.id }
                    ?.let { return it }
            } catch (e: Exception) {
                // Fall through to the page-reload chain below.
            }
        }
        // The list's ancestor may predate the thread (no continuation yet: the thread
        // only existed after the user posted into it), which made every 0.3.32 refresh
        // miss. Reload the page like the token retry does, then fetch the thread from
        // the fresh ancestor. This also covers an edited reply, whose stored tokens the
        // edit invalidated (device evidence 2026-10-29: 12x 404 under an edited reply).
        return try {
            val page = repository.getVideoComments(videoId, activeSortToken)
            val freshAncestor = page.comments.firstOrNull { it.id == ancestor.id } ?: return null
            val continuation = freshAncestor.continuationToken ?: return null
            repository.getVideoCommentReplies(videoId, continuation).comments
                .firstOrNull { it.id == comment.id }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * A top-level row re-read from a fresh page fetch, by id. Top-level rows have no
     * thread to re-fetch, but their tokens go stale the same way reply tokens do (an
     * edit invalidates them), and 0.3.34's refresh covers replies only — top-level
     * edits and replies under top-level comments 404'd on stale tokens in __23__0.
     * Returns null when the row is not on the freshly fetched page (ranking), in
     * which case the caller falls back to the stored tokens.
     */
    private suspend fun freshTopLevelRow(videoId: String, commentId: String): Comment? {
        return try {
            repository.getVideoComments(videoId, activeSortToken).comments
                .firstOrNull { it.id == commentId }
        } catch (e: Exception) {
            null
        }
    }

    /** Edits one of the viewer's own comments; the new text replaces the old on success. */
    fun editComment(
        comment: Comment,
        text: String,
        onResult: (Boolean) -> Unit,
    ) {
        if (text.isBlank()) {
            onResult(false)
            return
        }
        ShivLog.i("editTap id=${comment.id.take(12)} oldLen=${comment.text.length} newLen=${text.trim().length}")
        scope.launch {
            val videoId = loadingVideoId
            var target = comment
            val ancestor = topLevelAncestorOf(comment.id)
            if (videoId != null && ancestor != null && ancestor.id != comment.id) {
                val fresh = freshReplyRow(videoId, comment)
                ShivLog.i(
                    "tokRefresh kind=edit old=${comment.editParams?.take(8)} " +
                        "new=${fresh?.editParams?.take(8)} same=${fresh?.editParams == comment.editParams}",
                )
                if (fresh?.editParams != null) target = fresh
            }
            if (videoId != null && (ancestor == null || ancestor.id == comment.id)) {
                val fresh = freshTopLevelRow(videoId, comment.id)
                ShivLog.i(
                    "tokRefresh kind=editTop old=${comment.editParams?.take(8)} " +
                        "new=${fresh?.editParams?.take(8)} same=${fresh?.editParams == comment.editParams}",
                )
                if (fresh?.editParams != null) target = fresh
            }
            val ok =
                try {
                    repository.editComment(target, text.trim())
                } catch (e: CancellationException) {
                    // The waiting composer must be answered even on cancellation, or it
                    // stays in its posting state forever.
                    ShivLog.i("editDone id=${comment.id.take(12)} cancelled")
                    onResult(false)
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Error editing comment", e)
                    false
                }
            ShivLog.i("editDone id=${comment.id.take(12)} ok=$ok")
            // The edited text is plain: drop the parsed rich text so the row renders it as-is.
            // The target row carries the refreshed tokens when a refresh ran, so they stay
            // fresh for the next action instead of reverting to the stale stored ones.
            if (ok) {
                replaceComment(target.copy(text = text.trim(), richText = null))
                val applied =
                    _comments.value
                        .flatMap { listOf(it) + it.replies }
                        .firstOrNull { it.id == comment.id }
                        ?.text == text.trim()
                ShivLog.i("editLocal id=${comment.id.take(12)} applied=$applied")
            }
            onResult(ok)
        }
    }

    /** Deletes one of the viewer's own comments; it leaves the list only on success. */
    fun deleteComment(
        comment: Comment,
        onResult: (Boolean) -> Unit,
    ) {
        ShivLog.i("deleteTap id=${comment.id.take(12)}")
        scope.launch {
            val ok =
                try {
                    repository.deleteComment(comment)
                } catch (e: CancellationException) {
                    // The waiting dialog must be answered even on cancellation, or it
                    // stays in its deleting state forever — the reported "hang".
                    ShivLog.i("deleteDone id=${comment.id.take(12)} cancelled")
                    onResult(false)
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Error deleting comment", e)
                    false
                }
            ShivLog.i("deleteDone id=${comment.id.take(12)} ok=$ok")
            if (ok) _comments.value = _comments.value.removeCommentById(comment.id)
            onResult(ok)
        }
    }

    /** Replies sit in one flat list under their top-level comment, however deep they nest. */
    private fun topLevelAncestorOf(commentId: String): Comment? =
        _comments.value.firstOrNull { top ->
            top.id == commentId || top.replies.any { it.id == commentId }
        }

    private val votesInFlight = mutableSetOf<String>()

    /** Likes [comment], or withdraws the like when it is already liked. */
    fun toggleLike(comment: Comment) = vote(comment, like = true)

    /** Dislikes [comment], or withdraws the dislike when it is already disliked. */
    fun toggleDislike(comment: Comment) = vote(comment, like = false)

    private fun vote(
        comment: Comment,
        like: Boolean,
    ) {
        if (!votesInFlight.add(comment.id)) return
        val endpoint =
            when {
                like && comment.isLiked -> comment.unlikeAction
                like -> comment.likeAction
                comment.isDisliked -> comment.undislikeAction
                else -> comment.dislikeAction
            }
        if (endpoint.isNullOrBlank()) {
            votesInFlight.remove(comment.id)
            return
        }
        // The count must move with the icon: the display prefers the server's liked
        // text, and when the server did not send one it falls back to the not-liked
        // text, so flipping only the vote state left the number visibly frozen.
        val optimistic =
            when {
                like && comment.isLiked -> comment.copy(
                    isLiked = false,
                    likeCount = (comment.likeCount - 1).coerceAtLeast(0),
                )
                like -> comment.copy(
                    isLiked = true,
                    isDisliked = false,
                    likeCount = comment.likeCount + 1,
                    likeCountLikedText = comment.likeCountLikedText.ifBlank {
                        formatLikeCount(comment.likeCount + 1)
                    },
                )
                comment.isDisliked -> comment.copy(isDisliked = false)
                else -> comment.copy(
                    isDisliked = true,
                    isLiked = false,
                    likeCount =
                        if (comment.isLiked) {
                            (comment.likeCount - 1).coerceAtLeast(0)
                        } else {
                            comment.likeCount
                        },
                )
            }
        replaceComment(optimistic)
        scope.launch {
            val ok =
                try {
                    repository.performCommentAction(endpoint)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Error voting on comment", e)
                    false
                }
            // A refused vote must not stay on screen: put the parsed state back.
            if (!ok) replaceComment(comment)
            votesInFlight.remove(comment.id)
        }
    }

    private fun replaceComment(updated: Comment) {
        _comments.value = _comments.value.replaceCommentById(updated)
    }

    private fun startLoad(
        videoId: String,
        sortToken: String?,
    ) {
        loadJob?.cancel()
        prefetchJob?.cancel()
        // A re-sort keeps the header; a page without a count must not inherit the last video's (#1111).
        if (videoId != loadingVideoId) {
            _sortOptions.value = emptyList()
            _totalText.value = null
            _totalCount.value = null
            _createCommentParams.value = null
            _createCommentParamsCandidates.value = emptyList()
        }
        loadingVideoId = videoId
        activeSortToken = sortToken
        _comments.value = emptyList()
        next = CommentsPageResult.EMPTY
        _hasMore.value = false
        _isLoadingMore.value = false
        _isLoading.value = true
        loadJob =
            scope.launch {
                try {
                    withTimeoutOrNull(STARTUP_GATE_TIMEOUT_MS) {
                        playbackState.first { state ->
                            !PlaybackStartupPolicy.shouldDelaySecondaryContent(
                                isPlaybackLoading = state.isPlaybackLoading,
                                currentVideoId = state.currentVideoId,
                                requestedVideoId = videoId,
                            )
                        }
                    }
                    if (!isCurrentVideo(videoId)) return@launch
                    val page = fetch { repository.getVideoComments(videoId, sortToken) } ?: return@launch
                    if (!isCurrentVideo(videoId)) return@launch
                    _comments.value = page.comments.distinctByNonBlankKey(Comment::id)
                    // The restart question (an own comment present in-session, absent after a
                    // cold start) is decided by these two numbers on the post-restart load.
                    ShivLog.i("commentsLoaded video=$videoId count=${_comments.value.size} " +
                            "own=${_comments.value.count { it.isOwn }}",
                    )
                    next = page
                    _hasMore.value = page.hasMore
                    page.createCommentParams?.let { _createCommentParams.value = it }
                    _createCommentParamsCandidates.value = page.createCommentParamsCandidates
                    if (page.sortOptions.isNotEmpty()) _sortOptions.value = page.sortOptions
                    page.totalText?.let { _totalText.value = it }
                    page.totalCount?.let { _totalCount.value = it }
                    prefetchFollowingPages(videoId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading comments", e)
                } finally {
                    if (loadingVideoId == videoId) _isLoading.value = false
                }
            }
    }

    fun loadMore(videoId: String) {
        if (!next.hasMore) return
        if (_isLoadingMore.value) return
        prefetchJob?.cancel()
        scope.launch {
            _isLoadingMore.value = true
            try {
                appendNextPage(videoId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error loading more comments", e)
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    /**
     * Walks a bounded number of further pages in the background once the first one has landed, so
     * a filter over the loaded set is not deciding from twenty rows.
     */
    private fun prefetchFollowingPages(videoId: String) {
        if (prefetchPages <= 0) return
        prefetchJob?.cancel()
        prefetchJob =
            scope.launch {
                repeat(prefetchPages) {
                    if (!next.hasMore || !isCurrentVideo(videoId) || _isLoadingMore.value) return@launch
                    try {
                        appendNextPage(videoId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.d(TAG, "Comment prefetch stopped for $videoId: ${e.message}")
                        return@launch
                    }
                }
            }
    }

    private suspend fun appendNextPage(videoId: String) {
        val continuation = next.continuation
        val legacyPage = next.legacyPage
        val page =
            when {
                continuation != null -> {
                    repository.getMoreVideoComments(videoId, continuation)
                }

                legacyPage != null -> {
                    val (comments, nextLegacy) = repository.getMoreComments(videoId, legacyPage)
                    CommentsPageResult(comments = comments, legacyPage = nextLegacy)
                }

                else -> {
                    return
                }
            }
        if (!isCurrentVideo(videoId)) return
        _comments.value = _comments.value.mergeDistinctByNonBlankKey(page.comments, Comment::id)
        next = page
        _hasMore.value = page.hasMore
    }

    fun loadReplies(
        videoId: String,
        comment: Comment,
    ) = loadReplies(videoId, comment, append = false)

    fun loadMoreReplies(
        videoId: String,
        comment: Comment,
    ) = loadReplies(videoId, comment, append = true)

    private fun loadReplies(
        videoId: String,
        comment: Comment,
        append: Boolean,
    ) {
        val continuation = comment.continuationToken
        val repliesPage = comment.repliesPage
        if (continuation == null && repliesPage == null) return
        if (!repliesInFlight.add(comment.id)) return
        scope.launch {
            try {
                val (replies, nextContinuation, nextLegacyPage) =
                    if (continuation != null) {
                        val page = repository.getVideoCommentReplies(videoId, continuation)
                        Triple(page.comments, page.continuation, null)
                    } else {
                        val url = "https://www.youtube.com/watch?v=$videoId"
                        val (items, nextPage) = repository.getCommentReplies(url, requireNotNull(repliesPage))
                        Triple(items, null, nextPage)
                    }
                _comments.value =
                    _comments.value.map { current ->
                        if (current.id != comment.id) {
                            current
                        } else {
                            current.copy(
                                replies =
                                    if (append) {
                                        current.replies.mergeDistinctByNonBlankKey(replies, Comment::id)
                                    } else {
                                        replies.distinctByNonBlankKey(Comment::id)
                                    },
                                continuationToken = nextContinuation,
                                repliesPage = nextLegacyPage,
                            )
                        }
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error loading replies", e)
            } finally {
                repliesInFlight.remove(comment.id)
            }
        }
    }

    private suspend fun fetch(block: suspend () -> CommentsPageResult): CommentsPageResult? =
        if (fetchTimeoutMs == null) block() else withTimeoutOrNull(fetchTimeoutMs) { block() }
}

/** Replaces the comment with [updated]'s id, wherever it sits in the thread tree. */
private fun List<Comment>.replaceCommentById(updated: Comment): List<Comment> =
    map { comment ->
        when {
            comment.id == updated.id -> updated
            comment.replies.isNotEmpty() -> comment.copy(replies = comment.replies.replaceCommentById(updated))
            else -> comment
        }
    }

/** Removes the comment with [id], wherever it sits in the thread tree. */
private fun List<Comment>.removeCommentById(id: String): List<Comment> =
    mapNotNull { comment ->
        when {
            comment.id == id -> null
            comment.replies.isNotEmpty() -> comment.copy(replies = comment.replies.removeCommentById(id))
            else -> comment
        }
    }
