package io.github.shivytyahoo.glidetube.player

import io.github.shivytyahoo.glidetube.data.engagement.FeedExclusions

/** What the viewer hid, so autoplay picked outside the player screen skips it too. */
fun interface FeedExclusionsSource {
    suspend fun current(): FeedExclusions
}
