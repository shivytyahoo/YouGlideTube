package io.github.aedev.flow.data.video.downloader.transfer

import io.github.aedev.flow.data.video.downloader.tags.Mp4Boxes
import java.nio.ByteBuffer

/**
 * The segments of one DASH stream, read from its `sidx` (segment index) box: each segment's time
 * range and its byte range in the source file.
 *
 * A `sidx` lists its subsegments in order: each reference carries the subsegment's byte size and
 * its duration in the box's timescale, and the first subsegment starts `first_offset` bytes after
 * the END of the `sidx` box itself (ISO 14496-12 §8.16.3 — not after the box start, which the
 * first version of this parser assumed and the device log proved wrong). A stream whose index
 * holds a nested `sidx` (reference_type 1) or an unknown version has no usable list here.
 */
internal object SidxIndex {
    /** One media segment: its time range and its inclusive byte range in the source file. */
    data class Segment(
        val startUs: Long,
        val endUs: Long,
        val byteStart: Long,
        val byteEnd: Long,
    )

    /**
     * Parses the `sidx` box inside [indexBytes] (the stream's indexRange). [sidxFileOffset] is the
     * file offset the index bytes were fetched from, so the segment byte ranges come out absolute.
     * Null when the bytes hold no usable sidx. Any malformed index falls back the same way: the
     * caller downloads the whole stream instead, so a bad index never fails a download.
     */
    fun parse(
        indexBytes: ByteArray,
        sidxFileOffset: Long,
    ): List<Segment>? =
        runCatching {
            val sidx =
                Mp4Boxes
                    .children(indexBytes, 0, indexBytes.size)
                    .firstOrNull { it.type == "sidx" } ?: return null
            val payload = Mp4Boxes.childPayload(indexBytes, sidx)
            if (payload.size < 4) return null
            val version = payload[0].toInt() and 0xFF
            if (version != 0 && version != 1) return null
            val headerSize = if (version == 0) 24 else 32
            if (payload.size < headerSize) return null
            val buffer = ByteBuffer.wrap(payload)
            buffer.position(8) // version + flags, then reference_ID
            val timescale = buffer.int.toLong() and 0xFFFFFFFFL
            if (timescale == 0L) return null
            val earliest: Long
            val firstOffset: Long
            if (version == 0) {
                earliest = buffer.int.toLong() and 0xFFFFFFFFL
                firstOffset = buffer.int.toLong() and 0xFFFFFFFFL
            } else {
                earliest = buffer.long
                firstOffset = buffer.long
            }
            parseReferences(buffer, timescale, earliest, sidxFileOffset + sidx.end + firstOffset)
        }.getOrNull()

    private fun parseReferences(
        buffer: ByteBuffer,
        timescale: Long,
        earliestPresentationTime: Long,
        firstSegmentByte: Long,
    ): List<Segment>? {
        buffer.short // reserved
        val referenceCount = buffer.short.toInt() and 0xFFFF
        if (referenceCount == 0) return null
        if (buffer.remaining() < referenceCount * 12) return null
        var timeUs = earliestPresentationTime * 1_000_000L / timescale
        var byteCursor = firstSegmentByte
        val segments = ArrayList<Segment>(referenceCount)
        repeat(referenceCount) {
            val rawSize = buffer.int.toLong() and 0xFFFFFFFFL
            val isIndex = rawSize ushr 31 == 1L
            val size = rawSize and 0x7FFFFFFFL
            val duration = buffer.int.toLong() and 0xFFFFFFFFL
            buffer.int // starts_with_SAP, SAP_type, SAP_delta_time
            if (isIndex || size == 0L) return null
            val durationUs = duration * 1_000_000L / timescale
            val startUs = timeUs
            timeUs += durationUs
            val byteStart = byteCursor
            byteCursor += size
            segments += Segment(startUs, timeUs, byteStart, byteCursor - 1)
        }
        return segments
    }
}
