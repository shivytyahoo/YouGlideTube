package io.github.shivytyahoo.glidetube.player.sabr

import com.google.common.truth.Truth.assertThat
import io.github.shivytyahoo.glidetube.player.sabr.core.SabrIndex
import org.junit.Test
import java.io.ByteArrayOutputStream

class SabrIndexTest {
    @Test
    fun `mp4 sidx maps bytes to times exactly`() {
        val index = SabrIndex.parse(sidxBox())!!

        assertThat(index.size).isEqualTo(2)
        assertThat(index.offsets.toList()).containsExactly(156L, 1156L).inOrder()
        assertThat(index.startsMs.toList()).containsExactly(2000L, 4000L).inOrder()
        assertThat(index.segmentAt(156L)).isEqualTo(0)
        assertThat(index.segmentAt(1155L)).isEqualTo(0)
        assertThat(index.segmentAt(1156L)).isEqualTo(1)
        assertThat(index.segmentAt(100L)).isEqualTo(-1)
        assertThat(index.timeOf(2000L)).isEqualTo(4000L)
        assertThat(index.startAtOrBefore(5000L)).isEqualTo(4000L)
        assertThat(index.startAtOrBefore(1500L)).isEqualTo(0L)
    }

    @Test
    fun `webm cues map positions to times exactly`() {
        val index = SabrIndex.parse(webmHead())!!

        assertThat(index.size).isEqualTo(1)
        assertThat(index.offsets[0]).isEqualTo(110L)
        assertThat(index.startsMs[0]).isEqualTo(5L)
        assertThat(index.timeOf(110L)).isEqualTo(5L)
    }

    @Test
    fun `garbage and empty headers have no index`() {
        assertThat(SabrIndex.parse(ByteArray(0))).isNull()
        assertThat(SabrIndex.parse(ByteArray(64) { 0x41 })).isNull()
        assertThat(SabrIndex.parse("not a media header at all".toByteArray())).isNull()
    }

    /** One sidx box, version 0: timescale 1000, first presentation 2000 ms, first offset 100. */
    private fun sidxBox(): ByteArray {
        val out = ByteArrayOutputStream()

        fun u32(value: Long) {
            out.write((value shr 24).toInt() and 0xFF)
            out.write((value shr 16).toInt() and 0xFF)
            out.write((value shr 8).toInt() and 0xFF)
            out.write(value.toInt() and 0xFF)
        }

        fun u16(value: Int) {
            out.write(value shr 8 and 0xFF)
            out.write(value and 0xFF)
        }
        u32(56)
        out.write("sidx".toByteArray())
        u32(0) // version + flags
        u32(1) // reference id
        u32(1000) // timescale
        u32(2000) // earliest presentation time
        u32(100) // first offset
        u16(0) // reserved
        u16(2) // reference count
        u32(1000)
        u32(2000)
        u32(0) // SAP
        u32(3000)
        u32(4000)
        u32(0) // SAP
        return out.toByteArray()
    }

    /** EBML head, a Segment with a timescale of 1,000,000 and one cue at 5 ms, byte 100. */
    private fun webmHead(): ByteArray {
        val out = ByteArrayOutputStream()

        fun element(
            id: ByteArray,
            content: ByteArray,
        ): ByteArray {
            val encoded = ByteArrayOutputStream()
            encoded.write(id)
            encoded.write(0x80 or content.size)
            encoded.write(content)
            return encoded.toByteArray()
        }

        val timescale = element(byteArrayOf(0x2A, 0xD7.toByte(), 0xB1.toByte()), byteArrayOf(0x0F, 0x42, 0x40))
        val info = element(byteArrayOf(0x15, 0x49, 0xA9.toByte(), 0x66), timescale)
        val cueTime = element(byteArrayOf(0xB3.toByte()), byteArrayOf(0x05))
        val cuePosition = element(byteArrayOf(0xF1.toByte()), byteArrayOf(0x64))
        val cueTrackPositions = element(byteArrayOf(0xB7.toByte()), cuePosition)
        val cuePoint = element(byteArrayOf(0xBB.toByte()), cueTime + cueTrackPositions)
        val cues = element(byteArrayOf(0x1C, 0x53, 0xBB.toByte(), 0x6B), cuePoint)
        val segmentContent = info + cues
        out.write(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()))
        out.write(0x80)
        out.write(byteArrayOf(0x18, 0x53, 0x80.toByte(), 0x67))
        out.write(0x80 or segmentContent.size)
        out.write(segmentContent)
        return out.toByteArray()
    }
}
