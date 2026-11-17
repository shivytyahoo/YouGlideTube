package io.github.shivytyahoo.glidetube.data.stats

import com.google.common.truth.Truth.assertThat
import io.github.shivytyahoo.glidetube.data.local.PrivacyGate
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RecapEntryTest {
    private fun gate(
        deepGlide: Boolean,
        historyPaused: Boolean,
    ) = PrivacyGate(
        deepGlideActive = { deepGlide },
        watchHistoryPaused = { historyPaused },
        scrobbleDuringDeepGlide = { false },
    )

    @Test
    fun `everything is recorded when nothing is paused`() =
        runTest {
            RecapEntry.entries.forEach { assertThat(it.allowed(gate(deepGlide = false, historyPaused = false))).isTrue() }
        }

    @Test
    fun `Deep Flow keeps searches, actions and views out`() =
        runTest {
            val gate = gate(deepGlide = true, historyPaused = false)

            assertThat(RecapEntry.ACTIVITY.allowed(gate)).isFalse()
            assertThat(RecapEntry.VIEW.allowed(gate)).isFalse()
        }

    @Test
    fun `paused watch history keeps views out but still counts searches`() =
        runTest {
            val gate = gate(deepGlide = false, historyPaused = true)

            assertThat(RecapEntry.VIEW.allowed(gate)).isFalse()
            assertThat(RecapEntry.ACTIVITY.allowed(gate)).isTrue()
        }

    @Test
    fun `forgetting stored data always goes through`() =
        runTest {
            assertThat(RecapEntry.FORGET.allowed(gate(deepGlide = true, historyPaused = true))).isTrue()
        }
}
