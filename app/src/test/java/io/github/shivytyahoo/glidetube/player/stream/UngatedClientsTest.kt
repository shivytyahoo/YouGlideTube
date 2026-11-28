package io.github.shivytyahoo.glidetube.player.stream

import com.google.common.truth.Truth.assertThat
import io.github.shivytyahoo.glidetube.innertube.models.YouTubeClient
import org.junit.Test

class UngatedClientsTest {
    @Test
    fun `a gated client is never offered to the ladder`() {
        // Gating ANDROID_VR must not trigger a visitor re-roll (it is not in
        // VISITOR_WALLED_CLIENTS), so this test cannot touch the session. The gate is
        // cleared at the end so no other test can observe it.
        val clients = listOf(YouTubeClient.ANDROID_VR_1_65_10, YouTubeClient.VISIONOS, YouTubeClient.IOS_PRODUCER)
        try {
            ClientGateTracker.reportGated("ANDROID_VR")

            assertThat(ClientGateTracker.isGated("ANDROID_VR")).isTrue()
            assertThat(clients.ungated()).containsExactly(YouTubeClient.VISIONOS, YouTubeClient.IOS_PRODUCER)
        } finally {
            ClientGateTracker.clear()
        }
    }
}
