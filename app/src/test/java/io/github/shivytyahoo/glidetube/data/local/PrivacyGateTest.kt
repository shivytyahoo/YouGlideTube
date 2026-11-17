package io.github.shivytyahoo.glidetube.data.local

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PrivacyGateTest {
    private fun gate(
        deepGlide: Boolean,
        historyPaused: Boolean = false,
        scrobbleDuringDeepGlide: Boolean = false,
    ) = PrivacyGate(
        deepGlideActive = { deepGlide },
        watchHistoryPaused = { historyPaused },
        scrobbleDuringDeepGlide = { scrobbleDuringDeepGlide },
    )

    @Test
    fun `scrobbling runs while Deep Flow is off`() =
        runTest {
            assertThat(gate(deepGlide = false).allowsScrobbling()).isTrue()
        }

    @Test
    fun `Deep Flow stops scrobbling by default`() =
        runTest {
            assertThat(gate(deepGlide = true).allowsScrobbling()).isFalse()
        }

    @Test
    fun `Deep Flow scrobbles when the user chose to`() =
        runTest {
            assertThat(gate(deepGlide = true, scrobbleDuringDeepGlide = true).allowsScrobbling()).isTrue()
        }

    @Test
    fun `the gate reports both settings as they are`() =
        runTest {
            val gate = gate(deepGlide = true, historyPaused = true)

            assertThat(gate.isDeepGlideActive()).isTrue()
            assertThat(gate.isWatchHistoryPaused()).isTrue()
        }
}
