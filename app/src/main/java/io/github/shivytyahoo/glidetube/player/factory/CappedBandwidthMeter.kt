package io.github.shivytyahoo.glidetube.player.factory

import android.os.Handler
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import io.github.shivytyahoo.glidetube.player.config.PlayerConfig
import io.github.shivytyahoo.glidetube.player.quality.SustainedBandwidth

/**
 * A [BandwidthMeter] that never reports more than the link has sustained: the delegate meter's
 * own estimate is trusted while few transfers have completed, then capped at the median
 * throughput of the last few transfers with headroom. The delegate already sees every
 * transfer — ExoPlayer registers its meter as the transfer listener — so the cap is fed from
 * the delegate's own bandwidth samples, with no second listener on the data sources.
 */
@UnstableApi
internal class CappedBandwidthMeter(
    private val delegate: DefaultBandwidthMeter,
) : BandwidthMeter {
    private val sustained = SustainedBandwidth()

    init {
        delegate.addEventListener(
            Handler(Looper.getMainLooper()),
            object : BandwidthMeter.EventListener {
                override fun onBandwidthSample(
                    elapsedMs: Int,
                    bytesTransferred: Long,
                    bitrateEstimate: Long,
                ) {
                    sustained.recordSample(bytesTransferred, elapsedMs.toLong())
                }
            },
        )
    }

    override fun getBitrateEstimate(): Long {
        val raw = delegate.bitrateEstimate
        val cap =
            sustained.sustainedBps?.let {
                (it * PlayerConfig.SUSTAINED_BANDWIDTH_HEADROOM)
                    .toLong()
                    .coerceAtLeast(PlayerConfig.SUSTAINED_BANDWIDTH_FLOOR_BPS)
            } ?: return raw
        return minOf(raw, cap)
    }

    override fun getTimeToFirstByteEstimateUs(): Long = delegate.timeToFirstByteEstimateUs

    override fun addEventListener(
        handler: Handler,
        eventListener: BandwidthMeter.EventListener,
    ) {
        delegate.addEventListener(handler, eventListener)
    }

    override fun removeEventListener(eventListener: BandwidthMeter.EventListener) {
        delegate.removeEventListener(eventListener)
    }
}
