package io.github.shivytyahoo.glidetube.player.sabr.integration

import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class SabrSegmentBuffer {
    private val queue = LinkedBlockingQueue<ByteArray>()
    private var currentChunk: ByteArray? = null
    private var currentOffset = 0
    private val closed = AtomicBoolean(false)
    private val endOfStream = AtomicBoolean(false)

    // Bytes the session appended vs bytes the player read: the difference is what the fetch
    // has run ahead of consumption, and what the byte allow-gate measures.
    private val bytesAppended = AtomicLong(0)
    private val bytesRead = AtomicLong(0)

    fun appendSegment(data: ByteArray) {
        if (closed.get()) return
        if (data.isNotEmpty()) {
            bytesAppended.addAndGet(data.size.toLong())
            queue.put(data)
        }
    }

    /** Bytes appended but not yet read: how far the fetch has run ahead of the player. */
    fun bytesBuffered(): Long = bytesAppended.get() - bytesRead.get()

    fun signalEndOfStream() {
        endOfStream.set(true)
        queue.put(ByteArray(0))
    }

    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (closed.get()) return -1
        if (length == 0) return 0

        var totalRead = 0
        while (totalRead < length) {
            if (currentChunk == null || currentOffset >= currentChunk!!.size) {
                val next = if (totalRead > 0) {
                    queue.poll()
                } else {
                    var polled: ByteArray? = null
                    while (polled == null && !closed.get()) {
                        if (endOfStream.get() && queue.isEmpty()) break
                        polled = try {
                            queue.poll(250, TimeUnit.MILLISECONDS)
                        } catch (error: InterruptedException) {
                            Thread.currentThread().interrupt()
                            throw IOException("Interrupted while waiting for SABR media", error)
                        }
                    }
                    polled
                }

                if (next == null) {
                    if (closed.get() || (endOfStream.get() && queue.isEmpty())) {
                        return if (totalRead > 0) totalRead else -1
                    }
                    continue
                }

                if (next.isEmpty()) {
                    return if (totalRead > 0) totalRead else -1
                }

                currentChunk = next
                currentOffset = 0
            }

            val chunk = currentChunk!!
            val available = chunk.size - currentOffset
            val toRead = minOf(available, length - totalRead)
            System.arraycopy(chunk, currentOffset, buffer, offset + totalRead, toRead)
            currentOffset += toRead
            totalRead += toRead
            bytesRead.addAndGet(toRead.toLong())
        }
        return totalRead
    }

    fun close() {
        closed.set(true)
        queue.clear()
        currentChunk = null
        currentOffset = 0
    }

    fun reset() {
        queue.clear()
        currentChunk = null
        currentOffset = 0
        closed.set(false)
        endOfStream.set(false)
        bytesAppended.set(0)
        bytesRead.set(0)
    }
}
