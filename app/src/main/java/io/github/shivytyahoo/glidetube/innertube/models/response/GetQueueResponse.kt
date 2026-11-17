package io.github.shivytyahoo.glidetube.innertube.models.response

import io.github.shivytyahoo.glidetube.innertube.models.PlaylistPanelRenderer
import kotlinx.serialization.Serializable

@Serializable
data class GetQueueResponse(
    val queueDatas: List<QueueData>,
) {
    @Serializable
    data class QueueData(
        val content: PlaylistPanelRenderer.Content,
    )
}
