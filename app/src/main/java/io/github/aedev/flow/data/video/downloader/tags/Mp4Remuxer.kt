package io.github.aedev.flow.data.video.downloader.tags

import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.MediaFormatUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.MdtaMetadataEntry
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Mp4Muxer
import androidx.media3.muxer.SeekableMuxerOutput
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import javax.inject.Inject

/**
 * Copies YouTube DASH streams (fragmented MP4 or WebM) into one plain MP4 with the `moov` box at
 * the end, interleaving samples by timestamp and embedding the Flow ids as `mdta` entries.
 * Accepts H.264, HEVC, VP9 and AV1 video and AAC audio only; Opus/Vorbis in MP4 is refused
 * (Media3 mis-reads Opus pre-skip in MP4, androidx/media#3431), so callers must pick AAC.
 */
@OptIn(UnstableApi::class)
class Mp4Remuxer
    @Inject
    constructor() {
        sealed interface Result {
            data class Success(
                val containerMimeType: String,
                val videoMimeType: String?,
                val audioMimeType: String,
                /** Timestamp the output was shifted by: the snapped start for a clip, 0 otherwise. */
                val firstSampleTimeUs: Long = 0L,
            ) : Result

            data class Failure(
                val reason: Reason,
                val message: String,
            ) : Result
        }

        enum class Reason { INPUT_MISSING, NO_TRACK, UNSUPPORTED_AUDIO, UNSUPPORTED_VIDEO, CANCELLED, MUX_FAILED }

        /**
         * One audio track to write: its part file, its timebase shift (its own first
         * segment start for a segment-only clip, 0 for a full download), and its BCP-47
         * language tag (e.g. "hi", "en"), so the player can name the track and switch
         * between the languages in the file.
         */
        data class AudioInput(
            val path: String,
            val baseUs: Long = 0L,
            val language: String? = null,
        )

        private class Input(
            val extractor: MediaExtractor,
            val format: MediaFormat,
            val mime: String,
            val isVideo: Boolean,
        ) {
            var muxerTrack = C.INDEX_UNSET
            var done = false
            var written = 0L
            var readFailures = 0
            /**
             * Absolute time of this input's reported time zero. WebM reports absolute stream
             * times (base 0); the fragmented-MP4 extractor reports times relative to the
             * part's first segment (device report 9: audio firstSampleTime=0 on a clip
             * starting at 44:30). Without the shift, every audio sample falls below the
             * snapped offset and is skipped, leaving a video-only file.
             */
            var baseUs: Long = 0L
            /** BCP-47 language tag for this audio track, written into the file so the player can switch languages. */
            var language: String? = null
            val durationUs: Long =
                if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else C.TIME_UNSET
        }

        private class Rejection(
            val failure: Result.Failure,
        ) : Exception(failure.message)

        fun remux(
            videoPath: String?,
            audioInputs: List<AudioInput>,
            outputPath: String,
            tags: DownloadTags? = null,
            isCancelled: () -> Boolean = { false },
            onProgress: ((Float) -> Unit)? = null,
            clipStartUs: Long = 0L,
            clipEndUs: Long = C.TIME_UNSET,
            /**
             * True when the inputs are segment-only clip parts (init + selected segments). Their
             * indexes describe the original stream, not the part files, so seeking in them lands
             * on the wrong bytes and the merge fails — and the parts already start at the snapped
             * clip start, so the timestamp trim in [copyInterleaved] does the job instead.
             */
            skipSeeks: Boolean = false,
            /**
             * The clip's snapped start in microseconds, straight from the segment index. It is
             * the offset the output is shifted by — preferred over the first sample time, which
             * proved unreadable right after opening a segment-only part (device report 7:
             * trimmedStartMs=0 for a clip starting at 44:30, so the validator expected the
             * clip's end time as its length and rejected a good file).
             */
            snappedStartUs: Long? = null,
            /**
             * Absolute time of the video input's reported time zero, from the segment index:
             * the video's own first-selected segment start when its extractor reports
             * relative times (MP4; 0 for WebM, whose times are already absolute). 0 for full
             * downloads, where the part starts at the stream start and needs no shift. Each
             * audio input carries its own base in [AudioInput.baseUs].
             */
            videoBaseUs: Long = 0L,
        ): Result {
            val inputs = mutableListOf<Input>()
            val output = File(outputPath)
            var completed = false
            var firstSampleTimeUs = 0L
            return try {
                videoPath?.let { inputs += open(it, isVideo = true, skipSeeks) }
                audioInputs.forEach { inputs += open(it.path, isVideo = false, skipSeeks) }
                var audioIndex = 0
                inputs.forEach {
                    if (it.isVideo) {
                        it.baseUs = videoBaseUs
                    } else {
                        val audio = audioInputs.getOrNull(audioIndex++)
                        it.baseUs = audio?.baseUs ?: 0L
                        it.language = audio?.language
                    }
                }
                if (clipStartUs > 0L) {
                    // Video must start on a keyframe, so it snaps to the keyframe at or just
                    // before the requested start; audio starts on its nearest sample. Every
                    // timestamp is shifted by the earliest first sample, so A/V stay in sync.
                    // Skipped for segment-only parts: they already start at the snapped start.
                    if (!skipSeeks) {
                        inputs.forEach {
                            it.extractor.seekTo(
                                clipStartUs,
                                if (it.isVideo) MediaExtractor.SEEK_TO_PREVIOUS_SYNC else MediaExtractor.SEEK_TO_CLOSEST_SYNC,
                            )
                        }
                    }
                    firstSampleTimeUs =
                        snappedStartUs
                            ?: inputs.mapNotNull {
                                val time = it.extractor.sampleTime
                                if (time >= 0L) time + it.baseUs else null
                            }.minOrNull() ?: 0L
                }
                output.parentFile?.mkdirs()
                FileOutputStream(output).use { stream ->
                    val muxer = Mp4Muxer.Builder(SeekableMuxerOutput.of(stream)).setAttemptStreamableOutputEnabled(false).build()
                    var closed = false
                    try {
                        inputs.forEach {
                            val format = MediaFormatUtil.createFormatFromMediaFormat(it.format)
                            it.muxerTrack =
                                muxer.addTrack(
                                    if (!it.isVideo && it.language != null) format.buildUpon().setLanguage(it.language).build() else format,
                                )
                        }
                        tags?.let { FlowTagFields.encode(it) }?.forEach { (field, value) ->
                            muxer.addMetadataEntry(
                                MdtaMetadataEntry(
                                    FlowTagFields.mdtaKey(field),
                                    value.toByteArray(Charsets.UTF_8),
                                    MdtaMetadataEntry.TYPE_INDICATOR_STRING,
                                ),
                            )
                        }
                        val written = copyInterleaved(inputs, muxer, isCancelled, onProgress, clipEndUs, firstSampleTimeUs)
                        var audioNumber = 0
                        Log.w(TAG, "Copied $written samples: ${inputs.joinToString { if (it.isVideo) "video=${it.written} readFailures=${it.readFailures}" else "audio${audioNumber++}=${it.written} readFailures=${it.readFailures}" }}")
                        if (written == 0L) throw Rejection(Result.Failure(Reason.NO_TRACK, "No samples to write"))
                        muxer.close()
                        closed = true
                    } finally {
                        if (!closed) runCatching { muxer.close() }
                    }
                }
                onProgress?.invoke(1f)
                completed = true
                val audioMime = inputs.last().mime
                val videoMime = inputs.firstOrNull { it.isVideo }?.mime
                Result.Success(
                    containerMimeType = if (videoMime == null) MimeTypes.AUDIO_MP4 else MimeTypes.VIDEO_MP4,
                    videoMimeType = videoMime,
                    audioMimeType = audioMime,
                    firstSampleTimeUs = firstSampleTimeUs,
                )
            } catch (rejection: Rejection) {
                rejection.failure
            } catch (e: Exception) {
                Log.e(TAG, "Remux failed", e)
                Result.Failure(Reason.MUX_FAILED, e.message ?: e.javaClass.simpleName)
            } finally {
                inputs.forEach { runCatching { it.extractor.release() } }
                if (!completed) output.delete()
            }
        }

        private fun open(
            path: String,
            isVideo: Boolean,
            skipSeeks: Boolean = false,
        ): Input {
            val file = File(path)
            if (!file.isFile || file.length() == 0L) throw Rejection(Result.Failure(Reason.INPUT_MISSING, "Missing or empty input: $path"))
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(path)
                val prefix = if (isVideo) "video/" else "audio/"
                val index =
                    (0 until extractor.trackCount).firstOrNull {
                        extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true
                    } ?: throw Rejection(Result.Failure(Reason.NO_TRACK, "No $prefix track in $path"))
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (isVideo && mime !in SUPPORTED_VIDEO) {
                    throw Rejection(Result.Failure(Reason.UNSUPPORTED_VIDEO, "Video codec $mime cannot go into MP4"))
                }
                if (!isVideo && mime != MimeTypes.AUDIO_AAC) {
                    throw Rejection(Result.Failure(Reason.UNSUPPORTED_AUDIO, "Audio codec $mime is not AAC"))
                }
                extractor.selectTrack(index)
                // After setDataSource the extractor already sits on the first sample; the seek
                // only positions it, and on a segment-only part it would seek by a stale index.
                // Audio still seeks to 0: the fragmented-MP4 extractor reports its first sample
                // at time 0 (device report 9), so seeking to 0 lands exactly on it. The
                // clip-start seek stays skipped, and the relative timebase is corrected by
                // baseUs in copyInterleaved.
                if (!skipSeeks || !isVideo) extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                if (skipSeeks) Log.w(TAG, "Opened $path mime=$mime firstSampleTime=${extractor.sampleTime}")
                return Input(extractor, format, mime, isVideo)
            } catch (e: Exception) {
                extractor.release()
                throw e
            }
        }

        private fun copyInterleaved(
            inputs: List<Input>,
            muxer: Mp4Muxer,
            isCancelled: () -> Boolean,
            onProgress: ((Float) -> Unit)?,
            clipEndUs: Long = C.TIME_UNSET,
            offsetUs: Long = 0L,
        ): Long {
            val totalUs = inputs.maxOf { it.durationUs }.takeIf { it > 0 }
            val progressTotalUs =
                if (clipEndUs != C.TIME_UNSET) {
                    (clipEndUs - offsetUs).takeIf { it > 0 } ?: totalUs
                } else {
                    totalUs
                }
            var buffer = ByteBuffer.allocateDirect(INITIAL_BUFFER_BYTES)
            var samples = 0L
            var lastReported = -1
            while (true) {
                if (isCancelled()) throw Rejection(Result.Failure(Reason.CANCELLED, "Remux cancelled"))
                var next: Input? = null
                var timeUs = Long.MAX_VALUE
                for (input in inputs) {
                    if (input.done) continue
                    val sampleTimeUs = input.extractor.sampleTime
                    if (sampleTimeUs == END_OF_STREAM) {
                        input.done = true
                    } else {
                        // Shift each input to the absolute timeline before comparing: the
                        // fragmented-MP4 extractor reports times relative to its first segment
                        // (device report 9), so raw times would put every audio sample below
                        // the snapped offset and skip it.
                        val absTimeUs = sampleTimeUs + input.baseUs
                        if (next == null || absTimeUs < timeUs) {
                            next = input
                            timeUs = absTimeUs
                        }
                    }
                }
                if (next == null) return samples
                // Samples arrive in time order, so the first one past the clip end means
                // every remaining sample is past it too.
                if (clipEndUs != C.TIME_UNSET && timeUs >= clipEndUs) return samples
                val extractor = next.extractor
                if (timeUs < offsetUs) {
                    // A sample from before the snapped start (audio sought to closest sync).
                    extractor.advance()
                    continue
                }
                buffer = ensureCapacity(buffer, next)
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) {
                    next.readFailures++
                    next.done = true
                    continue
                }
                buffer.position(0).limit(size)
                val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) C.BUFFER_FLAG_KEY_FRAME else 0
                muxer.writeSampleData(next.muxerTrack, buffer, BufferInfo(timeUs - offsetUs, size, flags))
                samples++
                next.written++
                extractor.advance()
                if (onProgress != null && progressTotalUs != null) {
                    val percent = ((timeUs - offsetUs) * 100 / progressTotalUs).toInt().coerceIn(0, 99)
                    if (percent != lastReported) {
                        lastReported = percent
                        onProgress(percent / 100f)
                    }
                }
            }
        }

        private fun ensureCapacity(
            buffer: ByteBuffer,
            input: Input,
        ): ByteBuffer {
            val needed =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    input.extractor.sampleSize
                } else {
                    MediaFormatUtil.getInteger(input.format, MediaFormat.KEY_MAX_INPUT_SIZE, MAX_LEGACY_SAMPLE_BYTES).toLong()
                }
            if (needed <= buffer.capacity()) return buffer
            return ByteBuffer.allocateDirect((needed + needed / 4).toInt())
        }

        private companion object {
            const val TAG = "Mp4Remuxer"
            const val END_OF_STREAM = -1L
            const val INITIAL_BUFFER_BYTES = 1 shl 20
            const val MAX_LEGACY_SAMPLE_BYTES = 8 shl 20

            val SUPPORTED_VIDEO =
                setOf(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265, MimeTypes.VIDEO_VP9, MimeTypes.VIDEO_AV1)
        }
    }
