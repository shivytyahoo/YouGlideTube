package io.github.shivytyahoo.glidetube.player.sabr.core

/**
 * A format's table of contents, from the first piece of it: the header carries the index with it
 * (an mp4 sidx box, a WebM Cues element), so where every segment starts, in bytes and in time,
 * is known exactly. A byte is then a segment, and a segment a time to ask from: nothing is guessed.
 *
 * Parsed from the init data the server sends with the format, not from a fetched file: the index
 * lives in the header piece, so it is available before any media arrives.
 */
class SabrIndex(
    val offsets: LongArray,
    val startsMs: LongArray,
) {
    val size: Int get() = offsets.size

    /** The segment holding byte [n] (-1 before the first). */
    fun segmentAt(n: Long): Int {
        var low = 0
        var high = offsets.size - 1
        var answer = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (offsets[mid] <= n) {
                answer = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return answer
    }

    /** The start time of the segment holding byte [n]. */
    fun timeOf(n: Long): Long = segmentAt(n).let { if (it < 0) 0 else startsMs[it] }

    /** The latest segment start at or before [timeMs], or 0 when none is. */
    fun startAtOrBefore(timeMs: Long): Long {
        var answer = 0L
        for (start in startsMs) {
            if (start <= timeMs) answer = start else break
        }
        return answer
    }

    companion object {
        /** The index in [head] (the header piece, from byte 0); null when it has none this reads. */
        fun parse(head: ByteArray): SabrIndex? = runCatching { mp4(head) ?: webm(head) }.getOrNull()?.takeIf { it.size > 0 }

        private fun u32(
            bytes: ByteArray,
            position: Int,
        ): Long =
            ((bytes[position].toLong() and 0xFF) shl 24) or
                ((bytes[position + 1].toLong() and 0xFF) shl 16) or
                ((bytes[position + 2].toLong() and 0xFF) shl 8) or
                (bytes[position + 3].toLong() and 0xFF)

        private fun mp4(bytes: ByteArray): SabrIndex? {
            var offset = 0
            while (offset + 8 <= bytes.size) {
                val size = u32(bytes, offset).toInt()
                if (size < 8 || offset + size > bytes.size) return null
                val type = bytes.decodeToString(offset + 4, offset + 8)
                if (type == "sidx") {
                    var position = offset + 8
                    if (position + 4 > bytes.size) return null
                    val version = bytes[position].toInt()
                    position += 4 // version + flags
                    position += 4 // reference id
                    if (position + 4 > bytes.size) return null
                    val timescale = u32(bytes, position)
                    if (timescale == 0L) return null
                    position += 4
                    var time: Long
                    val firstOffset: Long
                    if (version == 0) {
                        if (position + 8 > bytes.size) return null
                        time = u32(bytes, position)
                        firstOffset = u32(bytes, position + 4)
                        position += 8
                    } else {
                        if (position + 16 > bytes.size) return null
                        time = (u32(bytes, position) shl 32) or u32(bytes, position + 4)
                        firstOffset = (u32(bytes, position + 8) shl 32) or u32(bytes, position + 12)
                        position += 16
                    }
                    position += 2 // reserved
                    if (position + 2 > bytes.size) return null
                    val count = ((bytes[position].toInt() and 0xFF) shl 8) or (bytes[position + 1].toInt() and 0xFF)
                    position += 2
                    if (position + count * 12 > bytes.size) return null
                    // Offsets count from the end of the sidx box.
                    var at = offset.toLong() + size + firstOffset
                    val offsets = LongArray(count)
                    val startsMs = LongArray(count)
                    for (i in 0 until count) {
                        val referencedSize = u32(bytes, position) and 0x7FFFFFFFL
                        val duration = u32(bytes, position + 4)
                        position += 12
                        offsets[i] = at
                        startsMs[i] = time * 1000 / timescale
                        at += referencedSize
                        time += duration
                    }
                    return SabrIndex(offsets, startsMs)
                }
                offset += size
            }
            return null
        }

        // ------------------------------------------------------------------ WebM (EBML)

        private class Element(
            val id: Long,
            val data: Int,
            val end: Int,
        )

        private fun vint(
            bytes: ByteArray,
            position: Int,
            keepMarker: Boolean,
        ): Pair<Long, Int> {
            val first = bytes[position].toInt() and 0xFF
            var length = 1
            while (length <= 8 && first and (0x80 shr (length - 1)) == 0) length++
            if (length > 8 || position + length > bytes.size) return 0L to length
            var value = (if (keepMarker) first else first and (0xFF shr length)).toLong()
            for (i in 1 until length) value = (value shl 8) or (bytes[position + i].toLong() and 0xFF)
            return value to length
        }

        private fun element(
            bytes: ByteArray,
            position: Int,
        ): Element? {
            if (position >= bytes.size) return null
            val (id, idLength) = vint(bytes, position, true)
            if (position + idLength >= bytes.size) return null
            val (size, sizeLength) = vint(bytes, position + idLength, false)
            val data = position + idLength + sizeLength
            if (data > bytes.size) return null
            val unknownSize = size == (1L shl (7 * sizeLength)) - 1
            return Element(id, data, if (unknownSize) bytes.size else minOf(bytes.size.toLong(), data + size).toInt())
        }

        private fun uint(
            bytes: ByteArray,
            element: Element,
        ): Long {
            var value = 0L
            for (i in element.data until element.end) value = (value shl 8) or (bytes[i].toLong() and 0xFF)
            return value
        }

        private fun children(
            bytes: ByteArray,
            from: Int,
            to: Int,
        ): Sequence<Element> =
            sequence {
                var position = from
                while (position < to) {
                    val child = element(bytes, position) ?: break
                    yield(child)
                    if (child.end <= position) break
                    position = child.end
                }
            }

        private fun webm(bytes: ByteArray): SabrIndex? {
            val ebml = element(bytes, 0) ?: return null
            if (ebml.id != 0x1A45DFA3L) return null
            val segment = element(bytes, ebml.end) ?: return null
            if (segment.id != 0x18538067L) return null
            var timescale = 1_000_000L
            val offsets = ArrayList<Long>()
            val startsMs = ArrayList<Long>()
            for (child in children(bytes, segment.data, bytes.size)) {
                when (child.id) {
                    0x1549A966L -> {
                        children(bytes, child.data, child.end)
                            .firstOrNull { it.id == 0x2AD7B1L }
                            ?.let { timescale = uint(bytes, it) }
                    }

                    0x1C53BB6BL -> {
                        for (cuePoint in children(bytes, child.data, child.end)) {
                            if (cuePoint.id != 0xBBL) continue
                            var time = -1L
                            var position = -1L
                            for (entry in children(bytes, cuePoint.data, cuePoint.end)) {
                                if (entry.id == 0xB3L) time = uint(bytes, entry)
                                if (entry.id == 0xB7L) {
                                    children(bytes, entry.data, entry.end)
                                        .firstOrNull { it.id == 0xF1L }
                                        ?.let { position = uint(bytes, it) }
                                }
                            }
                            if (time >= 0 && position >= 0) {
                                offsets.add(segment.data + position)
                                startsMs.add(time * timescale / 1_000_000)
                            }
                        }
                    }
                }
            }
            return SabrIndex(offsets.toLongArray(), startsMs.toLongArray())
        }
    }
}
