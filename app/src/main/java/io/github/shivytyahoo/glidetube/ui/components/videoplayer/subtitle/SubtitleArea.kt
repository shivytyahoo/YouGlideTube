package io.github.shivytyahoo.glidetube.ui.components.videoplayer.subtitle

/** The part of the player, in dp, where the picture is drawn and captions are laid out. */
internal data class SubtitleArea(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

/**
 * Where the picture sits in a [width] by [height] player. Only the fit modes leave bars; zoom to
 * fill crops the picture past the edges, so it uses the whole player, which keeps captions on
 * screen. The 2x mode zooms on the fit frame, so its captions stay on the fit picture.
 *
 * @param resizeMode the player's mode: 0 fit, 1 zoom to fill, 2 two-x.
 */
internal fun subtitleArea(
    width: Float,
    height: Float,
    videoAspectRatio: Float?,
    resizeMode: Int,
): SubtitleArea {
    val whole = SubtitleArea(0f, 0f, width, height)
    if (resizeMode == RESIZE_MODE_ZOOM_FILL || videoAspectRatio == null || videoAspectRatio <= 0f ||
        width <= 0f || height <= 0f
    ) {
        return whole
    }
    return if (videoAspectRatio > width / height) {
        val pictureHeight = width / videoAspectRatio
        SubtitleArea(0f, (height - pictureHeight) / 2f, width, pictureHeight)
    } else {
        val pictureWidth = height * videoAspectRatio
        SubtitleArea((width - pictureWidth) / 2f, 0f, pictureWidth, height)
    }
}

private const val RESIZE_MODE_ZOOM_FILL = 1
