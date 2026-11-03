package io.github.aedev.flow.ui.screens.channel

import android.util.Log
import io.github.aedev.flow.data.model.Comment
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.innertube.pages.parsePostedReply
import io.github.aedev.flow.innertube.pages.renderer.CommunityPost
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class ChannelCommunityUiState(
    val posts: List<CommunityPost> = emptyList(),
    val postsContinuation: String? = null,
    val postsLoaded: Boolean = false,
    val isLoadingPosts: Boolean = false,
    val isLoadingMorePosts: Boolean = false,
    val postsErrorLog: String? = null,
    val activePost: CommunityPost? = null,
    val comments: List<Comment> = emptyList(),
    val commentsContinuation: String? = null,
    val isLoadingComments: Boolean = false,
    val isLoadingMoreComments: Boolean = false,
    val commentCreateParams: List<String> = emptyList(),
)

internal class ChannelCommunityController(
    private val scope: CoroutineScope,
) {
    private data class ChannelContext(
        val id: String,
        val name: String,
        val avatarUrl: String,
    )

    private val _state = MutableStateFlow(ChannelCommunityUiState())
    val state: StateFlow<ChannelCommunityUiState> = _state.asStateFlow()

    private var channel: ChannelContext? = null

    fun reset(
        channelId: String,
        channelName: String,
        avatarUrl: String,
    ) {
        channel = ChannelContext(channelId, channelName, avatarUrl)
        _state.value = ChannelCommunityUiState()
    }

    fun ensurePostsLoaded() {
        val channelSnapshot = channel ?: return
        val stateSnapshot = _state.value
        if (stateSnapshot.postsLoaded || stateSnapshot.isLoadingPosts) return

        scope.launch(PerformanceDispatcher.networkIO) {
            _state.update { it.copy(isLoadingPosts = true, postsErrorLog = null) }
            YouTube
                .communityPosts(
                    channelSnapshot.id,
                    channelSnapshot.name,
                    channelSnapshot.avatarUrl,
                ).fold(
                    onSuccess = { page ->
                        if (channel?.id != channelSnapshot.id) return@fold
                        _state.update {
                            it.copy(
                                posts = page.posts,
                                postsContinuation = page.continuation,
                                postsLoaded = true,
                                isLoadingPosts = false,
                            )
                        }
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Failed to load community posts", error)
                        if (channel?.id == channelSnapshot.id) {
                            _state.update {
                                it.copy(
                                    postsLoaded = true,
                                    isLoadingPosts = false,
                                    postsErrorLog =
                                        buildChannelRequestErrorLog(
                                            operation = "community_posts",
                                            channelId = channelSnapshot.id,
                                            error = error,
                                        ),
                                )
                            }
                        }
                    },
                )
        }
    }

    fun retryPosts() {
        _state.update { it.copy(postsLoaded = false, postsErrorLog = null) }
        ensurePostsLoaded()
    }

    fun loadMorePosts() {
        val channelSnapshot = channel ?: return
        val continuation = _state.value.postsContinuation ?: return
        if (_state.value.isLoadingMorePosts) return

        scope.launch(PerformanceDispatcher.networkIO) {
            _state.update { it.copy(isLoadingMorePosts = true) }
            YouTube
                .communityPostsContinuation(
                    continuation,
                    channelSnapshot.name,
                    channelSnapshot.avatarUrl,
                ).fold(
                    onSuccess = { page ->
                        if (channel?.id != channelSnapshot.id) return@fold
                        _state.update {
                            it.copy(
                                posts = (it.posts + page.posts).distinctBy(CommunityPost::id),
                                postsContinuation = page.continuation,
                                isLoadingMorePosts = false,
                            )
                        }
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Failed to load more community posts", error)
                        _state.update { it.copy(isLoadingMorePosts = false) }
                    },
                )
        }
    }

    fun openComments(post: CommunityPost) {
        _state.update {
            it.copy(
                activePost = post,
                comments = emptyList(),
                commentsContinuation = null,
                isLoadingComments = true,
                isLoadingMoreComments = false,
                commentCreateParams = emptyList(),
            )
        }
        scope.launch(PerformanceDispatcher.networkIO) {
            YouTube.communityPostComments(post.id, post.commentEndpointParams).fold(
                onSuccess = { page ->
                    if (_state.value.activePost?.id != post.id) return@fold
                    val candidates =
                        page.createCommentParamsCandidates.ifEmpty {
                            page.createCommentParams?.let(::listOf).orEmpty()
                        }
                    Log.i(TAG, "postComments createParams=${candidates.size}")
                    _state.update {
                        it.copy(
                            comments = page.comments,
                            commentsContinuation = page.continuation,
                            isLoadingComments = false,
                            commentCreateParams = candidates,
                        )
                    }
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to load community post comments", error)
                    if (_state.value.activePost?.id == post.id) {
                        _state.update { it.copy(isLoadingComments = false) }
                    }
                },
            )
        }
    }

    fun closeComments() {
        _state.update {
            it.copy(
                activePost = null,
                comments = emptyList(),
                commentsContinuation = null,
                isLoadingComments = false,
                isLoadingMoreComments = false,
                commentCreateParams = emptyList(),
            )
        }
    }

    private val votesInFlight = mutableSetOf<String>()

    fun togglePostLike(post: CommunityPost) {
        val endpoint = if (post.isLiked) post.unlikeAction else post.likeAction
        if (endpoint.isNullOrBlank() || !votesInFlight.add("post:${post.id}")) return
        val optimistic = post.copy(isLiked = !post.isLiked)
        _state.update { state ->
            state.copy(posts = state.posts.map { if (it.id == post.id) optimistic else it })
        }
        scope.launch(PerformanceDispatcher.networkIO) {
            val ok = YouTube.likePost(endpoint).isSuccess
            if (!ok) {
                Log.e(TAG, "Failed to like community post")
                _state.update { state ->
                    state.copy(posts = state.posts.map { if (it.id == post.id) post else it })
                }
            }
            votesInFlight.remove("post:${post.id}")
        }
    }

    fun postPostComment(
        text: String,
        onResult: (Boolean) -> Unit,
    ) {
        val candidates = _state.value.commentCreateParams
        val post = _state.value.activePost
        if (candidates.isEmpty() || post == null || text.isBlank()) {
            onResult(false)
            return
        }
        scope.launch(PerformanceDispatcher.networkIO) {
            var posted = false
            for (token in candidates) {
                if (token.isBlank()) continue
                if (YouTube.createComment(token, text.trim()).isSuccess) {
                    posted = true
                    break
                }
            }
            onResult(posted)
            if (posted) openComments(post)
        }
    }

    fun togglePostCommentVote(
        comment: Comment,
        like: Boolean,
    ) {
        if (!votesInFlight.add("comment:${comment.id}")) return
        val endpoint =
            when {
                like && comment.isLiked -> comment.unlikeAction
                like -> comment.likeAction
                comment.isDisliked -> comment.undislikeAction
                else -> comment.dislikeAction
            }
        if (endpoint.isNullOrBlank()) {
            votesInFlight.remove("comment:${comment.id}")
            return
        }
        val optimistic =
            when {
                like && comment.isLiked ->
                    comment.copy(
                        isLiked = false,
                        likeCount = (comment.likeCount - 1).coerceAtLeast(0),
                    )
                like ->
                    comment.copy(
                        isLiked = true,
                        isDisliked = false,
                        likeCount = comment.likeCount + 1,
                    )
                comment.isDisliked -> comment.copy(isDisliked = false)
                else ->
                    comment.copy(
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
        replacePostComment(optimistic)
        scope.launch(PerformanceDispatcher.networkIO) {
            val ok = YouTube.performCommentAction(endpoint).isSuccess
            if (!ok) {
                Log.e(TAG, "Failed to vote on community post comment")
                replacePostComment(comment)
            }
            votesInFlight.remove("comment:${comment.id}")
        }
    }

    fun postPostCommentReply(
        comment: Comment,
        text: String,
        onResult: (Boolean) -> Unit,
    ) {
        val params = comment.replyParams
        val post = _state.value.activePost
        if (params.isNullOrBlank() || post == null || text.isBlank()) {
            onResult(false)
            return
        }
        scope.launch(PerformanceDispatcher.networkIO) {
            val body =
                YouTube.commentWrite(
                    path = comment.replyPath?.takeIf(String::isNotBlank) ?: "comment/create_comment_reply",
                    paramsField = "createReplyParams",
                    params = params,
                    textField = "commentText",
                    text = text.trim(),
                ).getOrNull()
            val serverReply = body?.let { runCatching { parsePostedReply(it, text.trim()) }.getOrNull() }
            val posted = body != null
            onResult(posted)
            if (!posted) return@launch
            if (serverReply != null) {
                _state.update { state ->
                    state.copy(comments = state.comments.insertReply(comment.id, serverReply))
                }
            } else {
                openComments(post)
            }
        }
    }

    fun editPostComment(
        comment: Comment,
        text: String,
        onResult: (Boolean) -> Unit,
    ) {
        val params = comment.editParams
        val path = comment.editPath
        val paramsField = comment.editParamsField
        val post = _state.value.activePost
        if (params.isNullOrBlank() || path.isNullOrBlank() || paramsField.isNullOrBlank() ||
            post == null || text.isBlank()
        ) {
            onResult(false)
            return
        }
        scope.launch(PerformanceDispatcher.networkIO) {
            val posted =
                YouTube.commentWrite(
                    path = path,
                    paramsField = paramsField,
                    params = params,
                    textField = comment.editTextField ?: "commentText",
                    text = text.trim(),
                ).isSuccess
            onResult(posted)
            if (posted) openComments(post)
        }
    }

    fun deletePostComment(
        comment: Comment,
        onResult: (Boolean) -> Unit,
    ) {
        val action = comment.deleteAction
        val post = _state.value.activePost
        if (action.isNullOrBlank() || post == null) {
            onResult(false)
            return
        }
        scope.launch(PerformanceDispatcher.networkIO) {
            val deleted = YouTube.performCommentAction(action).isSuccess
            onResult(deleted)
            if (deleted) openComments(post)
        }
    }

    private fun replacePostComment(updated: Comment) {
        _state.update { state ->
            state.copy(comments = state.comments.replaceById(updated))
        }
    }

    private fun List<Comment>.replaceById(updated: Comment): List<Comment> =
        map { current ->
            when {
                current.id == updated.id -> updated
                current.replies.isNotEmpty() -> current.copy(replies = current.replies.replaceById(updated))
                else -> current
            }
        }

    private fun List<Comment>.insertReply(parentId: String, reply: Comment): List<Comment> =
        map { current ->
            when {
                current.id == parentId ->
                    current.copy(
                        replies = current.replies + reply,
                        replyCount = current.replyCount + 1,
                    )
                current.replies.isNotEmpty() -> current.copy(replies = current.replies.insertReply(parentId, reply))
                else -> current
            }
        }

    fun loadMoreComments() {
        val continuation = _state.value.commentsContinuation ?: return
        val postId = _state.value.activePost?.id ?: return
        if (_state.value.isLoadingMoreComments) return

        scope.launch(PerformanceDispatcher.networkIO) {
            _state.update { it.copy(isLoadingMoreComments = true) }
            YouTube.communityPostCommentsContinuation(continuation).fold(
                onSuccess = { page ->
                    if (_state.value.activePost?.id != postId) return@fold
                    _state.update {
                        it.copy(
                            comments = (it.comments + page.comments).distinctBy(Comment::id),
                            commentsContinuation = page.continuation,
                            isLoadingMoreComments = false,
                        )
                    }
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to load more community post comments", error)
                    _state.update { it.copy(isLoadingMoreComments = false) }
                },
            )
        }
    }

    fun loadReplies(
        comment: Comment,
        append: Boolean,
    ) {
        val continuation = comment.continuationToken ?: return
        scope.launch(PerformanceDispatcher.networkIO) {
            YouTube.communityPostCommentsContinuation(continuation).fold(
                onSuccess = { page ->
                    _state.update { state ->
                        state.copy(
                            comments =
                                state.comments.map { current ->
                                    if (current.id != comment.id) return@map current
                                    val replies =
                                        if (append) {
                                            (current.replies + page.comments).distinctBy(Comment::id)
                                        } else {
                                            page.comments
                                        }
                                    current.copy(
                                        replies = replies,
                                        continuationToken = page.continuation,
                                        replyCount = maxOf(current.replyCount, replies.size),
                                    )
                                },
                        )
                    }
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to load community comment replies", error)
                },
            )
        }
    }

    private companion object {
        const val TAG = "ChannelCommunity"
    }
}
