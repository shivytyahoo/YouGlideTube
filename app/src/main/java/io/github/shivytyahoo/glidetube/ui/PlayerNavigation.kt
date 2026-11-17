package io.github.shivytyahoo.glidetube.ui

import androidx.navigation.NavHostController
import io.github.shivytyahoo.glidetube.player.preview.FeedPreviewController
import io.github.shivytyahoo.glidetube.player.stream.PlaybackPrefetcher

/**
 * Opens the player for [videoId], warming stream extraction before navigating.
 *
 * The extraction started here is joined — not duplicated — by the player's own load path, so the
 * only effect is that navigation, composition and player setup overlap with the network work
 * instead of queuing behind it. Use this for every user-initiated open of the player screen.
 *
 * When the tapped video is the live feed preview, its position rides along as a start offset,
 * so the full player continues where the preview left off instead of restarting. Sub-second
 * positions are ignored: they are a tap that landed on a preview that had barely started.
 */
internal fun NavHostController.navigateToPlayer(videoId: String) {
    PlaybackPrefetcher.prefetch(videoId)
    val previewMs = FeedPreviewController.positionIfPreviewing(videoId)
    if (previewMs > 1000L) {
        navigate("player/$videoId?startMs=$previewMs")
    } else {
        navigate("player/$videoId")
    }
}
