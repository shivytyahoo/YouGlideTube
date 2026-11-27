package io.github.shivytyahoo.glidetube.player.sabr

import com.google.common.truth.Truth.assertThat
import io.github.shivytyahoo.glidetube.player.sabr.integration.SabrByteAllowGate
import org.junit.Test

class SabrByteAllowGateTest {
    @Test
    fun `stays open below 24 MB ahead and engages at it`() {
        val gate = SabrByteAllowGate()

        assertThat(gate.update(24L * 1024L * 1024L - 1)).isFalse()
        assertThat(gate.update(24L * 1024L * 1024L)).isTrue()
        assertThat(gate.engaged).isTrue()
    }

    @Test
    fun `stays engaged until the lead drains to 18 MB`() {
        val gate = SabrByteAllowGate()
        gate.update(30L * 1024L * 1024L)

        assertThat(gate.update(20L * 1024L * 1024L)).isTrue()
        assertThat(gate.update(18L * 1024L * 1024L)).isTrue()
        assertThat(gate.update(18L * 1024L * 1024L - 1)).isFalse()
    }

    @Test
    fun `reset clears the gate`() {
        val gate = SabrByteAllowGate()
        gate.update(30L * 1024L * 1024L)

        gate.reset()

        assertThat(gate.engaged).isFalse()
        assertThat(gate.update(0L)).isFalse()
    }
}
