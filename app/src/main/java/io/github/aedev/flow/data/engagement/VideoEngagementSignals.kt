package io.github.aedev.flow.data.engagement

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.engagement.InteractionType
import io.github.aedev.flow.data.repository.YouTubeRepository
import javax.inject.Inject

/**
 * The learning signals an engagement action used to feed the recommendation engine. The engine
 * is gone; these stay as no-ops so every engagement caller keeps its shape and ordering.
 */
class VideoEngagementSignals
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val repository: YouTubeRepository,
    ) {
        suspend fun channelSubscriptionChanged(
            channelId: String,
            channelName: String,
            subscribed: Boolean,
        ) = Unit

        suspend fun videoInteraction(
            video: Video,
            interactionType: InteractionType,
        ) = Unit

        /** A newly subscribed channel's declared keyword tags used to teach the engine. */
        suspend fun channelTagsLearned(channelId: String) = Unit
    }
