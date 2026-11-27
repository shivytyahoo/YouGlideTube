package io.github.shivytyahoo.glidetube.data.audio.eq

import kotlinx.serialization.Serializable

@Serializable
enum class EqFilterType(
    val apoCode: String,
) {
    PEAK("PK"),
    LOW_SHELF("LSC"),
    HIGH_SHELF("HSC"),
    LOW_PASS("LPQ"),
    HIGH_PASS("HPQ"),
    ;

    val hasGain: Boolean
        get() = this == PEAK || this == LOW_SHELF || this == HIGH_SHELF
}

@Serializable
data class EqBand(
    val frequency: Double,
    val gain: Double = 0.0,
    val q: Double = EqLimits.DEFAULT_PEAK_Q,
    val type: EqFilterType = EqFilterType.PEAK,
    val enabled: Boolean = true,
)

@Serializable
data class EqCurve(
    val preamp: Double = 0.0,
    val bands: List<EqBand> = emptyList(),
)

@Serializable
enum class EqMode {
    PARAMETRIC,
    GRAPHIC,
}

@Serializable
data class EqPreset(
    val id: String,
    val name: String,
    val curve: EqCurve,
    val mode: EqMode = EqMode.PARAMETRIC,
    val imported: Boolean = false,
)

/** The curve a mode is playing, and the preset it started from; null once that preset is gone. */
@Serializable
data class EqWorkingCopy(
    val presetId: String?,
    val curve: EqCurve,
)

@Serializable
data class EqState(
    val version: Int = CURRENT_VERSION,
    val enabled: Boolean = true,
    val mode: EqMode = EqMode.PARAMETRIC,
    val parametric: EqWorkingCopy = EqWorkingCopy(BuiltInEqPresets.FLAT_ID, EqCurve()),
    val graphic: EqWorkingCopy = EqWorkingCopy(null, GraphicEq.flatCurve()),
    val autoPreamp: Boolean = true,
    val bassBoost: Double = 0.0,
    /** Stereo width in percent: 100 is untouched, 0 is mono, 200 is twice as wide. */
    val stereoWidth: Double = EqLimits.NEUTRAL_STEREO_WIDTH,
    /** Balance in percent: 0 is centred, −100 is fully left, 100 is fully right. */
    val balance: Double = 0.0,
    /** Night mode: a gentle compressor that evens out loud and quiet parts. */
    val nightMode: Boolean = false,
    val userPresets: List<EqPreset> = emptyList(),
) {
    val active: EqWorkingCopy
        get() = if (mode == EqMode.PARAMETRIC) parametric else graphic

    companion object {
        const val CURRENT_VERSION = 1
    }
}

/** What the audio processors run: already resolved for bypass, bass boost and auto preamp. */
data class EqProcessingSpec(
    val enabled: Boolean,
    val preampDb: Double,
    val bands: List<EqBand>,
    val widthPercent: Double = EqLimits.NEUTRAL_STEREO_WIDTH,
    val balance: Double = 0.0,
    val nightMode: Boolean = false,
) {
    companion object {
        val OFF = EqProcessingSpec(enabled = false, preampDb = 0.0, bands = emptyList())
    }
}
