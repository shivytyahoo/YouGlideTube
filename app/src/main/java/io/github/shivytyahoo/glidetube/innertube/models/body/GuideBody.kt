package io.github.shivytyahoo.glidetube.innertube.models.body

import io.github.shivytyahoo.glidetube.innertube.models.Context
import kotlinx.serialization.Serializable

@Serializable
data class GuideBody(
    val context: Context,
)
