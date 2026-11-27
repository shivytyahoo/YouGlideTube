package io.github.shivytyahoo.glidetube.player.audio.eq

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.google.common.truth.Truth.assertThat
import io.github.shivytyahoo.glidetube.data.audio.eq.EqBand
import io.github.shivytyahoo.glidetube.data.audio.eq.EqProcessingSpec
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class EqualizerAudioProcessorTest {
    private val rate = 48_000

    private fun processor(
        channels: Int = 2,
        encoding: Int = C.ENCODING_PCM_16BIT,
        spec: EqProcessingSpec? = null,
    ) = EqualizerAudioProcessor().apply {
        spec?.let(::setSpec)
        configure(AudioProcessor.AudioFormat(rate, channels, encoding))
        flush(AudioProcessor.StreamMetadata.DEFAULT)
    }

    private fun sine(
        frequency: Double,
        frames: Int,
        channels: Int = 2,
        amplitude: Double = 0.1,
        start: Int = 0,
    ): ShortArray =
        ShortArray(frames * channels) { i ->
            val frame = start + i / channels
            (amplitude * 32767 * sin(2 * PI * frequency * frame / rate)).toInt().toShort()
        }

    private fun EqualizerAudioProcessor.run(samples: ShortArray): ShortArray {
        val input = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder())
        samples.forEach(input::putShort)
        input.flip()
        queueInput(input)
        val output = getOutput()
        return ShortArray(output.remaining() / 2) { output.getShort() }
    }

    private fun rms(samples: ShortArray): Double = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)

    private fun gainDb(
        input: ShortArray,
        output: ShortArray,
    ): Double = 20 * log10(rms(output) / rms(input))

    @Test
    fun `off passes audio through untouched`() {
        val input = sine(440.0, 1_024)
        assertThat(processor(spec = EqProcessingSpec.OFF).run(input)).isEqualTo(input)
    }

    @Test
    fun `preamp scales the signal`() {
        val eq = processor(spec = EqProcessingSpec(enabled = true, preampDb = -6.0206, bands = emptyList()))
        val input = sine(440.0, 4_800)
        assertThat(gainDb(input, eq.run(input))).isWithin(0.05).of(-6.02)
    }

    @Test
    fun `a peak boosts its own frequency and leaves others alone`() {
        val spec = EqProcessingSpec(enabled = true, preampDb = 0.0, bands = listOf(EqBand(1_000.0, 6.0, 1.0)))
        val eq = processor(spec = spec)
        eq.run(sine(1_000.0, 4_800))
        val settled = sine(1_000.0, 4_800, start = 4_800)
        assertThat(gainDb(settled, eq.run(settled))).isWithin(0.1).of(6.0)

        val far = processor(spec = spec)
        far.run(sine(60.0, 9_600))
        val low = sine(60.0, 9_600, start = 9_600)
        assertThat(gainDb(low, far.run(low))).isWithin(0.1).of(0.0)
    }

    @Test
    fun `surround audio is equalized instead of bypassed`() {
        val spec = EqProcessingSpec(enabled = true, preampDb = 0.0, bands = listOf(EqBand(1_000.0, 6.0, 1.0)))
        val eq = processor(channels = 6, spec = spec)
        assertThat(eq.isActive).isTrue()
        eq.run(sine(1_000.0, 4_800, channels = 6))
        val settled = sine(1_000.0, 4_800, channels = 6, start = 4_800)
        assertThat(gainDb(settled, eq.run(settled))).isWithin(0.1).of(6.0)
    }

    @Test
    fun `float audio is equalized too`() {
        val eq =
            processor(
                encoding = C.ENCODING_PCM_FLOAT,
                spec = EqProcessingSpec(enabled = true, preampDb = -6.0206, bands = emptyList()),
            )
        val input = ByteBuffer.allocateDirect(8 * 100).order(ByteOrder.nativeOrder())
        repeat(200) { input.putFloat(0.5f) }
        input.flip()
        eq.queueInput(input)
        val output = eq.getOutput()
        assertThat(output.getFloat().toDouble()).isWithin(0.001).of(0.25)
    }

    @Test
    fun `unsupported encodings are left to the sink`() {
        val eq = EqualizerAudioProcessor()
        assertThat(eq.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_24BIT))).isEqualTo(AudioProcessor.AudioFormat.NOT_SET)
        assertThat(eq.isActive).isFalse()
    }

    @Test
    fun `changing the curve mid-stream glides instead of clicking`() {
        val boosted = EqProcessingSpec(enabled = true, preampDb = 0.0, bands = listOf(EqBand(1_000.0, 3.0, 1.0)))
        val eq = processor(channels = 1, spec = boosted)
        val warm = eq.run(sine(1_000.0, 4_800, channels = 1))
        val steadyStep = warm.toList().zipWithNext { a, b -> abs(b - a) }.max()

        eq.setSpec(boosted.copy(bands = listOf(EqBand(1_000.0, 9.0, 1.0))))
        val across = eq.run(sine(1_000.0, 480, channels = 1, start = 4_800))
        val worstStep = across.toList().zipWithNext { a, b -> abs(b - a) }.max()

        assertThat(worstStep.toDouble()).isLessThan(steadyStep * 2.2)
    }

    @Test
    fun `turning the equalizer off fades rather than cutting`() {
        val boosted = EqProcessingSpec(enabled = true, preampDb = 0.0, bands = listOf(EqBand(1_000.0, 9.0, 1.0)))
        val eq = processor(channels = 1, spec = boosted)
        eq.run(sine(1_000.0, 4_800, channels = 1))
        eq.setSpec(EqProcessingSpec.OFF)
        val fading = eq.run(sine(1_000.0, 1_200, channels = 1, start = 4_800))
        val after = sine(1_000.0, 4_800, channels = 1, start = 6_000)

        assertThat(fading).isNotEqualTo(sine(1_000.0, 1_200, channels = 1, start = 4_800))
        assertThat(eq.run(after)).isEqualTo(after)
    }

    @Test
    fun `a slight over is bent under full scale instead of clipped flat`() {
        val over = EqProcessingSpec(enabled = true, preampDb = 1.0, bands = emptyList())
        val eq = processor(channels = 1, spec = over)
        val output = eq.run(sine(100.0, 4_800, channels = 1, amplitude = 0.95))
        assertThat(output.count { it == Short.MAX_VALUE || it == Short.MIN_VALUE }).isEqualTo(0)
        assertThat(output.max().toInt()).isGreaterThan((0.95 * 32767).toInt())
    }

    @Test
    fun `neutral width, balance and night pass audio through untouched`() {
        val neutral =
            EqProcessingSpec(
                enabled = true,
                preampDb = 0.0,
                bands = emptyList(),
                widthPercent = 100.0,
                balance = 0.0,
                nightMode = false,
            )
        val input = sine(440.0, 1_024)
        assertThat(processor(spec = neutral).run(input)).isEqualTo(input)
    }

    @Test
    fun `width zero folds stereo to mono and width two doubles the difference`() {
        val input = oneSidedSine(440.0, 4_800)
        val mono = processor(spec = EqProcessingSpec(enabled = true, preampDb = 0.0, bands = emptyList(), widthPercent = 0.0)).run(input)
        val left = mono.filterIndexed { i, _ -> i % 2 == 0 }
        val right = mono.filterIndexed { i, _ -> i % 2 == 1 }
        assertThat(left).containsExactlyElementsIn(right)
        assertThat(rms(mono)).isWithin(0.02).of(rms(input) / sqrt(2.0))

        val wide = processor(spec = EqProcessingSpec(enabled = true, preampDb = 0.0, bands = emptyList(), widthPercent = 200.0)).run(input)
        val wideSpread = spreadRms(wide)
        assertThat(wideSpread).isWithin(0.02).of(2.0 * spreadRms(input))
    }

    @Test
    fun `balance fully left silences the right channel`() {
        val input = sine(440.0, 4_800)
        val output = processor(spec = EqProcessingSpec(enabled = true, preampDb = 0.0, bands = emptyList(), balance = -100.0)).run(input)
        val left = output.filterIndexed { i, _ -> i % 2 == 0 }.toShortArray()
        val right = output.filterIndexed { i, _ -> i % 2 == 1 }.toShortArray()
        assertThat(right.maxOf { abs(it.toInt()) }).isEqualTo(0)
        assertThat(gainDb(input.filterIndexed { i, _ -> i % 2 == 0 }.toShortArray(), left)).isWithin(0.05).of(0.0)
    }

    @Test
    fun `night mode pulls loud peaks down and quiet parts up`() {
        val night = EqProcessingSpec(enabled = true, preampDb = 0.0, bands = emptyList(), nightMode = true)
        val eq = processor(spec = night)
        val loud = sine(440.0, 4_800, amplitude = 0.9)
        val loudOut = eq.run(loud)
        assertThat(gainDb(loud.takeLast(2_400).toShortArray(), loudOut.takeLast(2_400).toShortArray())).isLessThan(-6.0)

        val quiet = sine(440.0, 96_000, amplitude = 0.05, start = 4_800)
        val quietOut = eq.run(quiet)
        assertThat(gainDb(quiet.takeLast(9_600).toShortArray(), quietOut.takeLast(9_600).toShortArray())).isGreaterThan(3.0)
    }

    /** A sine on the left channel only, so width and balance have something to work on. */
    private fun oneSidedSine(
        frequency: Double,
        frames: Int,
        amplitude: Double = 0.1,
    ): ShortArray {
        val one = sine(frequency, frames, channels = 1, amplitude = amplitude)
        return ShortArray(frames * 2) { i -> if (i % 2 == 0) one[i / 2] else 0 }
    }

    /** RMS of the left-minus-right difference, i.e. how wide the stereo image is. */
    private fun spreadRms(samples: ShortArray): Double {
        val diff = ShortArray(samples.size / 2) { i -> (samples[2 * i] - samples[2 * i + 1]).toShort() }
        return rms(diff)
    }
}
