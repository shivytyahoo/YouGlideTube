package io.github.aedev.flow.data.video.downloader.work

import java.io.File

/**
 * The working files of one download in app-private staging. Every name is keyed by video id, so two
 * downloads can never write to each other's files, whatever their titles.
 */
internal class DownloadStaging(
    directory: File,
    videoId: String,
) {
    private val stem = File(directory, videoId.replace(UNSAFE, "_")).path

    val videoPart = File("$stem.video.part")
    val audioPart = File("$stem.audio.part")
    val state = File("$stem.state.json")

    /**
     * One part file per audio track in a multi-audio download: index 0 is the primary
     * [audioPart]; the rest are `$stem.audio1.part`, `$stem.audio2.part`, … — so every
     * ticked language has its own file and the remux can write one track per language.
     */
    fun audioPart(index: Int): File = if (index == 0) audioPart else File("$stem.audio$index.part")

    fun audioParts(count: Int): List<File> = (0 until count).map { audioPart(it) }

    /** Every audio part file this download (or an earlier attempt) left behind. */
    private fun audioPartFiles(): List<File> {
        val dir = File(stem).parentFile ?: return listOf(audioPart)
        val prefix = File(stem).name + ".audio"
        return dir.listFiles { file -> file.name.startsWith(prefix) }?.toList().orEmpty().ifEmpty { listOf(audioPart) }
    }

    fun output(extension: String) = File("$stem.out.$extension")

    /** Everything this download wrote to staging, including a half-made output. */
    fun clear() {
        (listOf(videoPart, state, File("${state.path}.tmp"), output("mp4"), output("m4a"), output("webm")) + audioPartFiles())
            .forEach { it.delete() }
    }

    /** A half-made output, keeping the fetched parts for a retry. */
    fun clearOutputs() {
        listOf(output("mp4"), output("m4a"), output("webm")).forEach { it.delete() }
    }

    /** The part files only, for a transfer that has to start over rather than resume. */
    fun clearParts() {
        (listOf(videoPart, state) + audioPartFiles()).forEach { it.delete() }
    }

    private companion object {
        val UNSAFE = Regex("[^A-Za-z0-9_-]")
    }
}
