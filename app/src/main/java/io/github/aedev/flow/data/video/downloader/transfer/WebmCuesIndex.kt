package io.github.aedev.flow.data.video.downloader.transfer

/**
 * WebM/Matroska Cues index: the segment map for a WebM DASH stream, the counterpart of
 * [SidxIndex] for MP4. A WebM stream has no sidx; its index is the Cues element the format's
 * `indexRange` points at, and its timescale lives in the init segment's Info element.
 *
 * Recipe from the yt-dlp workspace's media-seek crate (src/video/webm.rs, 0.4.0), which is the
 * same code yt-dlp-rs uses for `--download-sections` on WebM: each CuePoint gives a CueTime (in
 * TimestampScale units) and a CueClusterPosition (segment-relative byte offset of a Cluster);
 * a segment runs from one cluster position to the next cluster's position minus one, and the
 * last cluster runs to the file's total size. The init segment is bytes [0, first cluster).
 *
 * EBML element IDs: EBML 0x1A45DFA3, Segment 0x18538067, Info 0x1549A966, TimestampScale
 * 0x2AD7B1 (default 1_000_000 ns), Cues 0x1C53BB6B, CuePoint 0xBB, CueTime 0xB3,
 * CueTrackPositions 0xB7, CueClusterPosition 0xF1. A VINT's width is its first byte's leading
 * zeros plus one; a VINT with all data bits set is the unknown-size sentinel.
 *
 * Pure function, no Android or network use: null on any malformed input, so the caller falls
 * back to the full download instead of fetching wrong bytes.
 */
object WebmCuesIndex {
    /** Internal like [SidxIndex]: the segment type is the app's internal download-transfer type. */
    internal fun parse(
        initBytes: ByteArray,
        indexBytes: ByteArray,
        contentLength: Long?,
    ): List<SidxIndex.Segment>? =
        runCatching {
            val segmentDataStart = segmentDataStart(initBytes) ?: return null
            val timescaleNs = timestampScaleNs(initBytes, segmentDataStart) ?: return null
            val cues = cuePoints(indexBytes) ?: return null
            if (cues.isEmpty()) return null
            val total = contentLength?.takeIf { it > 0 } ?: return null
            // DASH WebM is single-track, but dedupe by cluster position anyway: duplicate
            // CuePoints (one per track) would otherwise split one cluster into zero-length parts.
            val distinct =
                cues
                    .groupBy { it.second }
                    .map { (position, points) -> points.minOf { it.first } to position }
                    .sortedBy { it.first }
            val segments = ArrayList<SidxIndex.Segment>(distinct.size)
            for (i in distinct.indices) {
                val (cueTime, clusterPos) = distinct[i]
                val startUs = cueTime * timescaleNs / 1_000L
                val byteStart = segmentDataStart + clusterPos
                if (i == 0 && byteStart < initBytes.size) return null
                if (i + 1 < distinct.size) {
                    val (nextTime, nextPos) = distinct[i + 1]
                    val byteEnd = segmentDataStart + nextPos - 1
                    if (byteEnd < byteStart) return null
                    segments += SidxIndex.Segment(startUs, nextTime * timescaleNs / 1_000L, byteStart, byteEnd)
                } else {
                    // The last cluster runs to the end of the file; its end time is unbounded —
                    // the overlap filter selects it whenever the clip reaches it, and the remux
                    // trims the exact end, so no duration estimate is needed here.
                    if (total - 1 < byteStart) return null
                    segments += SidxIndex.Segment(startUs, Long.MAX_VALUE, byteStart, total - 1)
                }
            }
            segments
        }.getOrNull()

    /** Byte offset where the Segment body's first child starts, parsed from the init bytes. */
    private fun segmentDataStart(initBytes: ByteArray): Long? {
        var pos = 0
        val (ebmlId, ebmlIdLen) = readElemId(initBytes, pos) ?: return null
        if (ebmlId != ID_EBML) return null
        pos += ebmlIdLen
        val (ebmlSize, ebmlSizeLen) = readVint(initBytes, pos) ?: return null
        pos += ebmlSizeLen + ebmlSize.toInt()
        if (pos >= initBytes.size) return null
        val (segmentId, segmentIdLen) = readElemId(initBytes, pos) ?: return null
        if (segmentId != ID_SEGMENT) return null
        pos += segmentIdLen
        val (_, segmentSizeLen) = readVint(initBytes, pos) ?: return null
        return (pos + segmentSizeLen).toLong()
    }

    /** TimestampScale in nanoseconds, from the init bytes' Info element; default 1_000_000. */
    private fun timestampScaleNs(
        initBytes: ByteArray,
        segmentDataStart: Long,
    ): Long? {
        var pos = segmentDataStart.toInt()
        while (pos < initBytes.size) {
            val (id, idLen) = readElemId(initBytes, pos) ?: return null
            pos += idLen
            val (size, sizeLen) = readVint(initBytes, pos) ?: return null
            pos += sizeLen
            val end = (pos + size).toInt().coerceAtMost(initBytes.size)
            if (id == ID_INFO) {
                var inner = pos
                while (inner < end) {
                    val (fieldId, fieldIdLen) = readElemId(initBytes, inner) ?: break
                    inner += fieldIdLen
                    val (fieldSize, fieldSizeLen) = readVint(initBytes, inner) ?: break
                    inner += fieldSizeLen
                    val fieldEnd = (inner + fieldSize).toInt().coerceAtMost(initBytes.size)
                    if (fieldId == ID_TIMESTAMP_SCALE) {
                        val scale = readUint(initBytes, inner, fieldSize.toInt()) ?: return null
                        if (scale == 0L) return null
                        return scale
                    }
                    inner = fieldEnd
                }
                return DEFAULT_TIMESTAMP_SCALE_NS
            }
            // Unknown-size child elements run to the end of the init segment.
            pos = if (isVintUnknown(size, sizeLen)) initBytes.size else end
        }
        return DEFAULT_TIMESTAMP_SCALE_NS
    }

    /** (CueTime, CueClusterPosition) pairs from the Cues element bytes. */
    private fun cuePoints(indexBytes: ByteArray): List<Pair<Long, Long>>? {
        var pos = 0
        val (cuesId, cuesIdLen) = readElemId(indexBytes, pos) ?: return null
        if (cuesId != ID_CUES) return null
        pos += cuesIdLen
        val (cuesSize, cuesSizeLen) = readVint(indexBytes, pos) ?: return null
        pos += cuesSizeLen
        val cuesEnd =
            if (isVintUnknown(cuesSize, cuesSizeLen)) {
                indexBytes.size
            } else {
                (pos + cuesSize).toInt().coerceAtMost(indexBytes.size)
            }
        val points = ArrayList<Pair<Long, Long>>()
        while (pos < cuesEnd) {
            val (id, idLen) = readElemId(indexBytes, pos) ?: break
            pos += idLen
            val (size, sizeLen) = readVint(indexBytes, pos) ?: break
            pos += sizeLen
            val end = (pos + size).toInt().coerceAtMost(cuesEnd)
            if (id == ID_CUE_POINT) {
                var cueTime: Long? = null
                var clusterPos: Long? = null
                var inner = pos
                while (inner < end) {
                    val (fieldId, fieldIdLen) = readElemId(indexBytes, inner) ?: break
                    inner += fieldIdLen
                    val (fieldSize, fieldSizeLen) = readVint(indexBytes, inner) ?: break
                    inner += fieldSizeLen
                    val fieldEnd = (inner + fieldSize).toInt().coerceAtMost(end)
                    when (fieldId) {
                        ID_CUE_TIME -> cueTime = readUint(indexBytes, inner, fieldSize.toInt())
                        ID_CUE_TRACK_POSITIONS -> {
                            var nested = inner
                            while (nested < fieldEnd) {
                                val (nestedId, nestedIdLen) = readElemId(indexBytes, nested) ?: break
                                nested += nestedIdLen
                                val (nestedSize, nestedSizeLen) = readVint(indexBytes, nested) ?: break
                                nested += nestedSizeLen
                                val nestedEnd = (nested + nestedSize).toInt().coerceAtMost(fieldEnd)
                                if (nestedId == ID_CUE_CLUSTER_POSITION) {
                                    clusterPos = readUint(indexBytes, nested, nestedSize.toInt())
                                }
                                nested = nestedEnd
                            }
                        }
                    }
                    inner = fieldEnd
                }
                val time = cueTime
                val position = clusterPos
                if (time != null && position != null) points += time to position
            }
            pos = end
        }
        return points
    }

    /** Reads a variable-length EBML integer: (value, bytes consumed), or null when truncated. */
    private fun readVint(
        data: ByteArray,
        pos: Int,
    ): Pair<Long, Int>? {
        if (pos >= data.size) return null
        val first = data[pos].toInt() and 0xFF
        val width = Integer.numberOfLeadingZeros(first) - 24 + 1
        if (width > 8 || pos + width > data.size) return null
        val mask = (1L shl (8 - width)) - 1
        var value = (first.toLong() and mask)
        for (i in 1 until width) {
            value = (value shl 8) or (data[pos + i].toLong() and 0xFF)
        }
        return value to width
    }

    /** Reads an EBML element ID (up to 4 bytes): (id, bytes consumed), or null when truncated. */
    private fun readElemId(
        data: ByteArray,
        pos: Int,
    ): Pair<Int, Int>? {
        if (pos >= data.size) return null
        val first = data[pos].toInt() and 0xFF
        val width = Integer.numberOfLeadingZeros(first) - 24 + 1
        if (width > 4 || pos + width > data.size) return null
        var id = first
        for (i in 1 until width) {
            id = (id shl 8) or (data[pos + i].toInt() and 0xFF)
        }
        return id to width
    }

    /** Reads a big-endian unsigned integer of `size` bytes, or null when truncated. */
    private fun readUint(
        data: ByteArray,
        pos: Int,
        size: Int,
    ): Long? {
        if (size <= 0 || size > 8 || pos + size > data.size) return null
        var value = 0L
        for (i in 0 until size) {
            value = (value shl 8) or (data[pos + i].toLong() and 0xFF)
        }
        return value
    }

    /** A VINT whose data bits are all 1 is the EBML unknown-size sentinel for its width. */
    private fun isVintUnknown(
        value: Long,
        width: Int,
    ): Boolean {
        if (width !in 1..8) return false
        return value == (1L shl (7 * width)) - 1
    }

    private const val ID_EBML = 0x1A45DFA3
    private const val ID_SEGMENT = 0x18538067
    private const val ID_INFO = 0x1549A966
    private const val ID_TIMESTAMP_SCALE = 0x2AD7B1
    private const val ID_CUES = 0x1C53BB6B
    private const val ID_CUE_POINT = 0xBB
    private const val ID_CUE_TIME = 0xB3
    private const val ID_CUE_TRACK_POSITIONS = 0xB7
    private const val ID_CUE_CLUSTER_POSITION = 0xF1
    private const val DEFAULT_TIMESTAMP_SCALE_NS = 1_000_000L
}
