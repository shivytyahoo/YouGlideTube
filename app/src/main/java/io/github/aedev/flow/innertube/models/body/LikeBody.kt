package io.github.aedev.flow.innertube.models.body

import io.github.aedev.flow.innertube.models.Context
import kotlinx.serialization.Serializable

@Serializable
data class LikeBody(
    val context: Context,
    val target: Target,
    // youtubei.js LikeEndpoint sends the params the server issued with the
    // button (likeParams / removeLikeParams); without them the server 403s.
    // explicitNulls = false omits this when no params are known.
    val params: String? = null,
) {
    @Serializable
    sealed class Target {
        @Serializable
        data class VideoTarget(val videoId: String) : Target()
        @Serializable
        data class PlaylistTarget(val playlistId: String) : Target()
    }
}
