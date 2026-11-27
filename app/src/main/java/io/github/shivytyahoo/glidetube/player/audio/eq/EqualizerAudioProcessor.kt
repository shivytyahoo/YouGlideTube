package io.github.shivytyahoo.glidetube.player.audio.eq

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import io.github.shivytyahoo.glidetube.data.audio.eq.EqFilterMath
import io.github.shivytyahoo.glidetube.data.audio.eq.EqLimits
import io.github.shivytyahoo.glidetube.data.audio.eq.EqProcessingSpec
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.tanh

/**
 * The parametric equalizer inside a player's audio sink: a cascade of biquads, then preamp, then a
 * soft knee near full scale.
 *
 * [setSpec] may be called from any thread. The audio thread picks the newest spec up at the start
 * of a buffer and glides the coefficients to it over [RAMP_SECONDS], keeping each filter's history
 * so a change never clicks. Nothing is allocated per buffer once the design is sized.
 *
 * One instance per player: a processor holds that player's filter history and must never be shared
 * between two players that are live at the same time.
 */
@UnstableApi
class EqualizerAudioProcessor : BaseAudioProcessor() {
    @Volatile
    private var pendingSpec: EqProcessingSpec = EqProcessingSpec.OFF
    private var appliedSpec: EqProcessingSpec? = null

    private var sampleRate = 0
    private var channelCount = 0
    private var encoding = C.ENCODING_INVALID

    private var sections: Array<BiquadSection> = emptyArray()
    private var gain = 1.0
    private var targetGain = 1.0
    private var gainStep = 0.0
    private var rampFramesLeft = 0
    private var idleAfterRamp = false
    private var processing = false

    // Stereo stage: width (mid/side) and balance (per-channel gains) glide to their targets over
    // the same ramp as the filters, so a dragged slider never clicks; night mode is a compressor
    // whose own attack/release smooths it. The stage is skipped entirely at neutral settings.
    private var frameSamples = DoubleArray(0)
    private var spatialActive = false
    private var widthCurrent = 1.0
    private var widthTarget = 1.0
    private var widthStep = 0.0
    private var balanceLeftCurrent = 1.0
    private var balanceLeftTarget = 1.0
    private var balanceLeftStep = 0.0
    private var balanceRightCurrent = 1.0
    private var balanceRightTarget = 1.0
    private var balanceRightStep = 0.0
    private var spatialGlideFramesLeft = 0
    private var nightOn = false
    private var compEnvelope = 0.0
    private var compGain = 1.0
    private var attackCoef = 0.0
    private var releaseCoef = 0.0

    fun setSpec(spec: EqProcessingSpec) {
        pendingSpec = spec
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
            inputAudioFormat
        } else {
            AudioProcessor.AudioFormat.NOT_SET
        }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        val format = inputAudioFormat
        if (format.sampleRate != sampleRate || format.channelCount != channelCount || format.encoding != encoding) {
            sampleRate = format.sampleRate
            channelCount = format.channelCount
            encoding = format.encoding
            appliedSpec = null
            attackCoef = 1.0 - exp(-1.0 / (sampleRate.coerceAtLeast(1) * NIGHT_ATTACK_SECONDS))
            releaseCoef = 1.0 - exp(-1.0 / (sampleRate.coerceAtLeast(1) * NIGHT_RELEASE_SECONDS))
        }
        sections.forEach { it.clearHistory() }
        // A seek restarts the sound from silence: snap the stereo glide and the compressor there.
        widthCurrent = widthTarget
        balanceLeftCurrent = balanceLeftTarget
        balanceRightCurrent = balanceRightTarget
        spatialGlideFramesLeft = 0
        compEnvelope = 0.0
        compGain = 1.0
    }

    override fun onReset() {
        sampleRate = 0
        channelCount = 0
        encoding = C.ENCODING_INVALID
        appliedSpec = null
        sections = emptyArray()
        rampFramesLeft = 0
        idleAfterRamp = false
        processing = false
        frameSamples = DoubleArray(0)
        spatialActive = false
        widthCurrent = 1.0
        widthTarget = 1.0
        balanceLeftCurrent = 1.0
        balanceLeftTarget = 1.0
        balanceRightCurrent = 1.0
        balanceRightTarget = 1.0
        spatialGlideFramesLeft = 0
        nightOn = false
        compEnvelope = 0.0
        compGain = 1.0
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        refreshDesign()
        val output = replaceOutputBuffer(remaining)
        when {
            !processing -> output.put(inputBuffer)
            encoding == C.ENCODING_PCM_16BIT -> process16Bit(inputBuffer, output)
            else -> processFloat(inputBuffer, output)
        }
        output.flip()
    }

    private fun refreshDesign() {
        val spec = pendingSpec
        if (spec === appliedSpec || sampleRate <= 0 || channelCount <= 0) return
        val firstDesign = appliedSpec == null
        appliedSpec = spec
        if (frameSamples.size != channelCount) frameSamples = DoubleArray(channelCount)

        val bands = if (spec.enabled) spec.bands.filterNot { EqFilterMath.isBypassed(it, sampleRate) } else emptyList()
        val newGain = if (spec.enabled) 10.0.pow(spec.preampDb / 20.0) else 1.0
        updateSpatialTargets(spec, firstDesign)

        val design = DoubleArray(bands.size * EqFilterMath.COEFFICIENTS_PER_SECTION)
        bands.forEachIndexed { index, band ->
            EqFilterMath.design(band, sampleRate, design, index * EqFilterMath.COEFFICIENTS_PER_SECTION)
        }

        when {
            firstDesign || (!processing && bands.isEmpty()) -> {
                install(design)
                gain = newGain
                processing = bands.isNotEmpty() || newGain != 1.0 || spatialActive
            }

            bands.size == sections.size -> {
                rampTo(design, newGain)
            }

            sections.isEmpty() -> {
                install(identity(bands.size))
                rampTo(design, newGain)
            }

            bands.isEmpty() -> {
                rampTo(identity(sections.size), newGain)
                idleAfterRamp = true
            }

            else -> {
                install(design)
                gain = newGain
                processing = true
            }
        }
    }

    /**
     * The stereo stage's targets from [spec]. Width and balance glide there over the ramp; night
     * mode's own attack/release does its smoothing. The stage stays live while the compressor is
     * still recovering to unity after night mode turns off, so that switch-off never clicks either.
     */
    private fun updateSpatialTargets(
        spec: EqProcessingSpec,
        snap: Boolean,
    ) {
        val width = if (spec.enabled) (spec.widthPercent / EqLimits.NEUTRAL_STEREO_WIDTH).coerceIn(0.0, 2.0) else 1.0
        val balance = if (spec.enabled) spec.balance.coerceIn(EqLimits.MIN_BALANCE, EqLimits.MAX_BALANCE) else 0.0
        val left = if (balance > 0) 1.0 - balance / EqLimits.MAX_BALANCE else 1.0
        val right = if (balance < 0) 1.0 + balance / EqLimits.MAX_BALANCE else 1.0
        nightOn = spec.enabled && spec.nightMode
        val offNeutral = spec.widthPercent != EqLimits.NEUTRAL_STEREO_WIDTH || spec.balance != 0.0 || spec.nightMode
        spatialActive = (spec.enabled && offNeutral) || compGain != 1.0
        if (snap) {
            widthCurrent = width
            balanceLeftCurrent = left
            balanceRightCurrent = right
            spatialGlideFramesLeft = 0
        } else if (width != widthTarget || left != balanceLeftTarget || right != balanceRightTarget) {
            val frames = (sampleRate * RAMP_SECONDS).toInt().coerceAtLeast(1)
            widthStep = (width - widthCurrent) / frames
            balanceLeftStep = (left - balanceLeftCurrent) / frames
            balanceRightStep = (right - balanceRightCurrent) / frames
            spatialGlideFramesLeft = frames
        }
        widthTarget = width
        balanceLeftTarget = left
        balanceRightTarget = right
    }

    private fun stepSpatialGlide() {
        spatialGlideFramesLeft--
        if (spatialGlideFramesLeft == 0) {
            widthCurrent = widthTarget
            balanceLeftCurrent = balanceLeftTarget
            balanceRightCurrent = balanceRightTarget
        } else {
            widthCurrent += widthStep
            balanceLeftCurrent += balanceLeftStep
            balanceRightCurrent += balanceRightStep
        }
    }

    /** Switches to [design] at once with fresh history; only for a change in the number of bands. */
    private fun install(design: DoubleArray) {
        val count = design.size / EqFilterMath.COEFFICIENTS_PER_SECTION
        sections =
            Array(count) { index ->
                BiquadSection(channelCount).also { it.set(design, index * EqFilterMath.COEFFICIENTS_PER_SECTION) }
            }
        targetGain = gain
        rampFramesLeft = 0
        idleAfterRamp = false
    }

    /**
     * Glides from the current coefficients to [design]. Interpolating towards or away from a pass-through
     * section keeps every intermediate filter stable, which is how the equalizer turns on and off.
     */
    private fun rampTo(
        design: DoubleArray,
        newGain: Double,
    ) {
        val frames = (sampleRate * RAMP_SECONDS).toInt().coerceAtLeast(1)
        sections.forEachIndexed { index, section -> section.rampTo(design, index * EqFilterMath.COEFFICIENTS_PER_SECTION, frames) }
        targetGain = newGain
        gainStep = (newGain - gain) / frames
        rampFramesLeft = frames
        idleAfterRamp = false
        processing = true
    }

    private fun identity(count: Int): DoubleArray =
        DoubleArray(count * EqFilterMath.COEFFICIENTS_PER_SECTION).also { design ->
            for (section in 0 until count) design[section * EqFilterMath.COEFFICIENTS_PER_SECTION] = 1.0
        }

    private fun stepRamp() {
        rampFramesLeft--
        if (rampFramesLeft == 0) {
            sections.forEach { it.finishRamp() }
            gain = targetGain
            if (idleAfterRamp) {
                idleAfterRamp = false
                sections = emptyArray()
                processing = gain != 1.0 || spatialActive
            }
        } else {
            sections.forEach { it.stepRamp() }
            gain += gainStep
        }
    }

    private fun process16Bit(
        input: ByteBuffer,
        output: ByteBuffer,
    ) {
        val channels = channelCount
        val frames = input.remaining() / (2 * channels)
        val samples = frameSamples
        for (i in 0 until frames) {
            if (rampFramesLeft > 0) stepRamp()
            if (spatialGlideFramesLeft > 0) stepSpatialGlide()
            val stages = sections
            val g = gain
            for (channel in 0 until channels) {
                var x = input.getShort() / SHORT_SCALE
                for (stage in stages) x = stage.process(x, channel)
                samples[channel] = x * g
            }
            applySpatial(samples, channels)
            for (channel in 0 until channels) output.putShort(toShort(softClip(samples[channel])))
        }
    }

    private fun processFloat(
        input: ByteBuffer,
        output: ByteBuffer,
    ) {
        val channels = channelCount
        val frames = input.remaining() / (4 * channels)
        val samples = frameSamples
        for (i in 0 until frames) {
            if (rampFramesLeft > 0) stepRamp()
            if (spatialGlideFramesLeft > 0) stepSpatialGlide()
            val stages = sections
            val g = gain
            for (channel in 0 until channels) {
                var x = input.getFloat().toDouble()
                for (stage in stages) x = stage.process(x, channel)
                samples[channel] = x * g
            }
            applySpatial(samples, channels)
            for (channel in 0 until channels) output.putFloat(softClip(samples[channel]).toFloat())
        }
    }

    /**
     * Stereo width (mid/side), balance (per-channel gains) and night mode (compressor) on one
     * frame, after the filters and preamp. Mono hears only the compressor; surround keeps its
     * extra channels untouched. At neutral settings this is skipped outright by [spatialActive].
     */
    private fun applySpatial(
        samples: DoubleArray,
        channels: Int,
    ) {
        if (!spatialActive) return
        if (channels >= 2) {
            val mid = (samples[0] + samples[1]) / 2.0
            val side = (samples[0] - samples[1]) / 2.0 * widthCurrent
            samples[0] = (mid + side) * balanceLeftCurrent
            samples[1] = (mid - side) * balanceRightCurrent
        }
        val level =
            if (channels >= 2) maxOf(abs(samples[0]), abs(samples[1])) else abs(samples[0])
        val envelopeCoef = if (level > compEnvelope) attackCoef else releaseCoef
        compEnvelope += (level - compEnvelope) * envelopeCoef
        val target =
            if (nightOn) {
                compressorTargetGain(compEnvelope)
            } else {
                1.0
            }
        val gainCoef = if (target < compGain) attackCoef else releaseCoef
        compGain += (target - compGain) * gainCoef
        if (abs(compGain - 1.0) < COMP_GAIN_EPSILON) compGain = 1.0
        if (compGain != 1.0) {
            for (channel in 0 until channels) samples[channel] *= compGain
        }
    }

    /** Night mode's static curve: unity below the threshold, 4:1 above it, plus 7 dB make-up. */
    private fun compressorTargetGain(envelope: Double): Double {
        if (envelope <= 0.0) return NIGHT_MAKEUP_GAIN
        val envelopeDb = 20.0 * log10(envelope)
        if (envelopeDb <= NIGHT_THRESHOLD_DB) return NIGHT_MAKEUP_GAIN
        val reductionDb = -(1.0 - 1.0 / NIGHT_RATIO) * (envelopeDb - NIGHT_THRESHOLD_DB)
        return 10.0.pow((reductionDb + NIGHT_MAKEUP_DB) / 20.0)
    }

    private companion object {
        const val RAMP_SECONDS = 0.02
        const val SHORT_SCALE = 32768.0

        /** Night mode: a 5 ms attack so loud peaks are caught, a 250 ms release so it breathes. */
        const val NIGHT_ATTACK_SECONDS = 0.005
        const val NIGHT_RELEASE_SECONDS = 0.25
        const val NIGHT_THRESHOLD_DB = -28.0
        const val NIGHT_RATIO = 4.0
        const val NIGHT_MAKEUP_DB = 7.0
        val NIGHT_MAKEUP_GAIN = 10.0.pow(NIGHT_MAKEUP_DB / 20.0)
        const val COMP_GAIN_EPSILON = 0.001

        /** About −0.45 dBFS: below it samples pass untouched; above it they bend towards full scale instead of clipping. */
        const val KNEE = 0.95

        fun softClip(sample: Double): Double {
            val magnitude = abs(sample)
            if (magnitude <= KNEE) return sample
            val shaped = KNEE + (1.0 - KNEE) * tanh((magnitude - KNEE) / (1.0 - KNEE))
            return if (sample < 0) -shaped else shaped
        }

        fun toShort(sample: Double): Short =
            (sample * SHORT_SCALE).coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble()).toInt().toShort()
    }
}

/**
 * One biquad in direct form I. Coefficients and the first two channels' history live in fields,
 * which the JIT keeps in registers; the further channels of a surround stream use [more].
 */
private class BiquadSection(
    channels: Int,
) {
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0

    private val target = DoubleArray(EqFilterMath.COEFFICIENTS_PER_SECTION)
    private val step = DoubleArray(EqFilterMath.COEFFICIENTS_PER_SECTION)

    private var x1l = 0.0
    private var x2l = 0.0
    private var y1l = 0.0
    private var y2l = 0.0
    private var x1r = 0.0
    private var x2r = 0.0
    private var y1r = 0.0
    private var y2r = 0.0
    private val more = DoubleArray((channels - 2).coerceAtLeast(0) * 4)

    fun set(
        design: DoubleArray,
        offset: Int,
    ) {
        b0 = design[offset]
        b1 = design[offset + 1]
        b2 = design[offset + 2]
        a1 = design[offset + 3]
        a2 = design[offset + 4]
    }

    fun rampTo(
        design: DoubleArray,
        offset: Int,
        frames: Int,
    ) {
        design.copyInto(target, 0, offset, offset + target.size)
        step[0] = (target[0] - b0) / frames
        step[1] = (target[1] - b1) / frames
        step[2] = (target[2] - b2) / frames
        step[3] = (target[3] - a1) / frames
        step[4] = (target[4] - a2) / frames
    }

    fun stepRamp() {
        b0 += step[0]
        b1 += step[1]
        b2 += step[2]
        a1 += step[3]
        a2 += step[4]
    }

    fun finishRamp() = set(target, 0)

    fun clearHistory() {
        x1l = 0.0
        x2l = 0.0
        y1l = 0.0
        y2l = 0.0
        x1r = 0.0
        x2r = 0.0
        y1r = 0.0
        y2r = 0.0
        more.fill(0.0)
    }

    fun process(
        x: Double,
        channel: Int,
    ): Double =
        when (channel) {
            0 -> {
                val y = b0 * x + b1 * x1l + b2 * x2l - a1 * y1l - a2 * y2l
                x2l = x1l
                x1l = x
                y2l = y1l
                y1l = y
                y
            }

            1 -> {
                val y = b0 * x + b1 * x1r + b2 * x2r - a1 * y1r - a2 * y2r
                x2r = x1r
                x1r = x
                y2r = y1r
                y1r = y
                y
            }

            else -> {
                val s = (channel - 2) * 4
                val h = more
                val y = b0 * x + b1 * h[s] + b2 * h[s + 1] - a1 * h[s + 2] - a2 * h[s + 3]
                h[s + 1] = h[s]
                h[s] = x
                h[s + 3] = h[s + 2]
                h[s + 2] = y
                y
            }
        }
}
