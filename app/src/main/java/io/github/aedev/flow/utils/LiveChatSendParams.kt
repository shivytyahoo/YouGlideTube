package io.github.aedev.flow.utils

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.net.URLEncoder

/**
 * The `params` value for `live_chat/send_message`, per YouTube.js `LiveChat.sendMessage`
 * (protos/misc/params.proto: `LiveMessageParams`):
 *
 * ```
 * LiveMessageParams {
 *   params = 1: Params { ids = 5: Ids { channel_id = 1, video_id = 2 } },
 *   number_0 = 2: 1,
 *   number_1 = 3: 4,
 * }
 * ```
 *
 * The proto bytes are standard-base64'd, percent-encoded, then base64'd again — exactly the
 * `btoa(encodeURIComponent(u8ToBase64(...)))` chain in the reference implementation.
 */
fun liveChatSendParams(
    videoId: String,
    channelId: String,
): String {
    val ids =
        ByteArrayOutputStream().apply {
            writeLengthDelimited(1, channelId.toByteArray(Charsets.UTF_8))
            writeLengthDelimited(2, videoId.toByteArray(Charsets.UTF_8))
        }.toByteArray()
    val params =
        ByteArrayOutputStream().apply {
            writeLengthDelimited(5, ids)
        }.toByteArray()
    val proto =
        ByteArrayOutputStream().apply {
            writeLengthDelimited(1, params)
            writeVarintField(2, 1)
            writeVarintField(3, 4)
        }.toByteArray()
    val inner = Base64.encodeToString(proto, Base64.NO_WRAP)
    val encoded = URLEncoder.encode(inner, Charsets.UTF_8.name())
    return Base64.encodeToString(encoded.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
}

private fun ByteArrayOutputStream.writeVarint(value: Int) {
    var remaining = value
    while (true) {
        if (remaining and 0x7F.inv() == 0) {
            write(remaining)
            return
        }
        write(remaining and 0x7F or 0x80)
        remaining = remaining ushr 7
    }
}

private fun ByteArrayOutputStream.writeVarintField(
    field: Int,
    value: Int,
) {
    writeVarint(field shl 3)
    writeVarint(value)
}

private fun ByteArrayOutputStream.writeLengthDelimited(
    field: Int,
    bytes: ByteArray,
) {
    writeVarint(field shl 3 or 2)
    writeVarint(bytes.size)
    write(bytes)
}
