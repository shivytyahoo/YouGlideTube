package io.github.shivytyahoo.glidetube.innertube.models.response

import io.github.shivytyahoo.glidetube.innertube.models.NavigationEndpoint
import kotlinx.serialization.Serializable

@Serializable
data class ResolveUrlResponse(
    val endpoint: NavigationEndpoint? = null,
)
