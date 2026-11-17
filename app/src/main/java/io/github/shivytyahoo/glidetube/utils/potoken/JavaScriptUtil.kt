package io.github.shivytyahoo.glidetube.utils.potoken

import io.github.shivytyahoo.glidetube.network.AppProxyManager
import java.util.regex.Pattern
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

/**
 * This is based and ported from Metrolist,
 * see https://github.com/MetrolistGroup/Metrolist for the original code and license.
 * 
 * Parses the raw challenge data obtained from the Create endpoint and returns an object that can be
 * embedded in a JavaScript snippet.
 */
fun parseChallengeData(rawChallengeData: String): String {
    val scrambled = Json.parseToJsonElement(rawChallengeData).jsonArray

    val challengeData = if (scrambled.size > 1 && scrambled[1].jsonPrimitive.isString) {
        val descrambled = descramble(scrambled[1].jsonPrimitive.content)
        Json.parseToJsonElement(descrambled).jsonArray
    } else {
        scrambled[0].jsonArray
    }

    val messageId = challengeData[0].jsonPrimitive.content
    val interpreterHash = challengeData[3].jsonPrimitive.content
    val program = challengeData[4].jsonPrimitive.content
    val globalName = challengeData[5].jsonPrimitive.content
    val clientExperimentsStateBlob = challengeData[7].jsonPrimitive.content

    val privateDoNotAccessOrElseSafeScriptWrappedValue = challengeData[1]
        .takeIf { it !is JsonNull }
        ?.jsonArray
        ?.find { it.jsonPrimitive.isString }
    val privateDoNotAccessOrElseTrustedResourceUrlWrappedValue = challengeData[2]
        .takeIf { it !is JsonNull }
        ?.jsonArray
        ?.find { it.jsonPrimitive.isString }

    return Json.encodeToString(
        JsonObject.serializer(), JsonObject(
            mapOf(
                "messageId" to JsonPrimitive(messageId),
                "interpreterJavascript" to JsonObject(
                    mapOf(
                        "privateDoNotAccessOrElseSafeScriptWrappedValue" to (privateDoNotAccessOrElseSafeScriptWrappedValue
                            ?: JsonNull),
                        "privateDoNotAccessOrElseTrustedResourceUrlWrappedValue" to (privateDoNotAccessOrElseTrustedResourceUrlWrappedValue
                            ?: JsonNull)
                    )
                ),
                "interpreterHash" to JsonPrimitive(interpreterHash),
                "program" to JsonPrimitive(program),
                "globalName" to JsonPrimitive(globalName),
                "clientExperimentsStateBlob" to JsonPrimitive(clientExperimentsStateBlob)
            )
        )
    )
}

/**
 * Parses the raw integrity token data obtained from the GenerateIT endpoint to a JavaScript
 * `Uint8Array` that can be embedded directly in JavaScript code, and a [Long] representing the
 * duration of this token in seconds.
 */
fun parseIntegrityTokenData(rawIntegrityTokenData: String): Pair<String, Long> {
    val integrityTokenData = Json.parseToJsonElement(rawIntegrityTokenData).jsonArray
    if (integrityTokenData.size < 2) {
        throw PoTokenException("GenerateIT returned no integrity token")
    }
    val token = integrityTokenData[0].jsonPrimitive.content
    val lifetimeSeconds = integrityTokenData[1].jsonPrimitive.long
    if (token.isBlank() || lifetimeSeconds <= 0L) {
        throw PoTokenException("GenerateIT returned a degraded integrity response")
    }
    return base64ToU8(token) to lifetimeSeconds
}

/**
 * Converts a string (usually the identifier used as input to `obtainPoToken`) to a JavaScript
 * `Uint8Array` that can be embedded directly in JavaScript code.
 */
fun stringToU8(identifier: String): String {
    return newUint8Array(identifier.toByteArray())
}

/**
 * Takes a poToken encoded as a sequence of bytes represented as integers separated by commas
 * (e.g. "97,98,99" would be "abc"), which is the output of `Uint8Array::toString()` in JavaScript,
 * and converts it to the specific base64 representation for poTokens.
 */
fun u8ToBase64(poToken: String): String {
    return poToken.split(",")
        .map { it.toUByte().toByte() }
        .toByteArray()
        .toByteString()
        .base64()
        .replace("+", "-")
        .replace("/", "_")
}

/**
 * Takes the scrambled challenge, decodes it from base64, adds 97 to each byte.
 */
private fun descramble(scrambledChallenge: String): String {
    return base64ToByteString(scrambledChallenge)
        .map { (it + 97).toByte() }
        .toByteArray()
        .decodeToString()
}

/**
 * Decodes a base64 string encoded in the specific base64 representation used by YouTube, and
 * returns a JavaScript `Uint8Array` that can be embedded directly in JavaScript code.
 */
private fun base64ToU8(base64: String): String {
    return newUint8Array(base64ToByteString(base64))
}

private fun newUint8Array(contents: ByteArray): String {
    return "new Uint8Array([" + contents.joinToString(separator = ",") { it.toUByte().toString() } + "])"
}

/**
 * Decodes a base64 string encoded in the specific base64 representation used by YouTube.
 */
private fun base64ToByteString(base64: String): ByteArray {
    val base64Mod = base64
        .replace('-', '+')
        .replace('_', '/')
        .replace('.', '=')

    return (base64Mod.decodeBase64() ?: throw PoTokenException("Cannot base64 decode"))
        .toByteArray()
}

/**
 * Parses the descrambled challenge data obtained from the homepage's `ytAtN` payload and returns
 * an object that can be embedded in a JavaScript snippet. Downloads the interpreter JS the
 * challenge points at.
 */
fun parseDescrambledChallengeData(rawChallengeData: String): String {
    val root = Json.parseToJsonElement(rawChallengeData).jsonObject
    val bgChallenge = root.getValue("bgChallenge").jsonObject

    val interpreterHash = bgChallenge.getValue("interpreterHash").jsonPrimitive.content
    val program = bgChallenge.getValue("program").jsonPrimitive.content
    val globalName = bgChallenge.getValue("globalName").jsonPrimitive.content
    val clientExperimentsStateBlob = bgChallenge.getValue("clientExperimentsStateBlob").jsonPrimitive.content

    val trustedUrlValue = bgChallenge.getValue("interpreterUrl").jsonObject
        .getValue("privateDoNotAccessOrElseTrustedResourceUrlWrappedValue").jsonPrimitive.content
    val safeScriptValue = downloadInterpreter("https:$trustedUrlValue")

    return Json.encodeToString(
        JsonObject.serializer(), JsonObject(
            mapOf(
                "interpreterJavascript" to JsonObject(
                    mapOf(
                        "privateDoNotAccessOrElseSafeScriptWrappedValue" to JsonPrimitive(safeScriptValue),
                        "privateDoNotAccessOrElseTrustedResourceUrlWrappedValue" to JsonPrimitive(trustedUrlValue)
                    )
                ),
                "interpreterHash" to JsonPrimitive(interpreterHash),
                "program" to JsonPrimitive(program),
                "globalName" to JsonPrimitive(globalName),
                "clientExperimentsStateBlob" to JsonPrimitive(clientExperimentsStateBlob)
            )
        )
    )
}

// The interpreter JS is fetched through the same proxy client as the homepage, so the challenge
// and the downloads leave from the same IP.
private val interpreterHttpClient: OkHttpClient
    get() = AppProxyManager.applyTo(OkHttpClient.Builder()).build()

private fun downloadInterpreter(url: String): String {
    val request = Request.Builder().url(url).get().build()
    interpreterHttpClient.newCall(request).execute().use { response ->
        if (response.code != 200) throw PoTokenException("Interpreter download failed: ${response.code}")
        return response.body?.string() ?: throw PoTokenException("Empty response body")
    }
}

private val HEX_ESCAPE_PATTERN = Pattern.compile("""\\x([0-9A-Fa-f]{2})""")
private val TRAILING_COMMA_PATTERN = Pattern.compile(""",\s*([\]}])""")
private val UNQUOTED_KEY_PATTERN = Pattern.compile("""([{,]\s*)([a-zA-Z0-9_$]+)\s*:""")

/**
 * Parses the `ytAtN` payload, which is JS-object-literal-ish rather than strict JSON. Ported from
 * LuanRT/BgUtils v4.0.3 (MIT): `\xNN` escapes are decoded AFTER parsing, inside string values, and
 * object/array values come back as JSON text with their nested strings normalized the way
 * BgUtils' normalizeValue does.
 */
fun parseLooseJSON(looseJson: String): Map<String, String> {
    var jsonStr = TRAILING_COMMA_PATTERN.matcher(looseJson).replaceAll("$1")

    jsonStr = quoteSingleQuotedStrings(jsonStr)

    // just in case (BgUtils)
    jsonStr = UNQUOTED_KEY_PATTERN.matcher(jsonStr).replaceAll("""$1"$2":""")

    val parsedData = Json.parseToJsonElement(jsonStr).jsonObject
    val result = LinkedHashMap<String, String>()

    for ((key, value) in parsedData) {
        result[key] = when (value) {
            is JsonNull -> "null"
            // The real R: returned as the decoded text, which is JSON for the next parser.
            is JsonPrimitive -> if (value.isString) decodeHexEscapes(value.content) else value.toString()
            is JsonObject, is JsonArray -> Json.encodeToString(JsonElement.serializer(), normalizeLooseValue(value))
        }
    }

    return result
}

/**
 * BgUtils' normalizeValue for nested values: strings are hex-decoded, and a decoded string that
 * holds JSON becomes that JSON (normalized in turn), so an object-literal `R` with escaped strings
 * inside comes out the way BgUtils would see it.
 */
private fun normalizeLooseString(value: String): JsonElement {
    val decoded = decodeHexEscapes(value)
    val trimmed = decoded.trim()

    if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
        try {
            return normalizeLooseValue(Json.parseToJsonElement(decoded))
        } catch (e: Exception) {
            // not JSON after all: keep the text, as BgUtils does
        }
    }

    return JsonPrimitive(decoded)
}

private fun normalizeLooseValue(value: JsonElement): JsonElement = when (value) {
    is JsonPrimitive -> if (value.isString) normalizeLooseString(value.content) else value
    is JsonArray -> JsonArray(value.map { normalizeLooseValue(it) })
    is JsonObject -> JsonObject(value.mapValues { normalizeLooseValue(it.value) })
    is JsonNull -> value
}

/**
 * BgUtils' `/'((?:[^'\\]|\\[\s\S])*)'/g` replace (each single-quoted literal becomes a JSON
 * string, `\'` unescaped), as a scanner rather than that regex. OpenJDK's engine recurses once
 * per character through a repeated alternation, and the real `R` literal is ~40 KB (a
 * StackOverflowError in JVM unit tests); Android's ICU engine keeps its backtrack stack on the
 * heap, which is why the regex works on devices. Same matches: a quote with no closing quote is
 * left as it is and the scan goes on from the next character.
 */
private fun quoteSingleQuotedStrings(source: String): String {
    val out = StringBuilder(source.length + 16)
    var i = 0

    while (i < source.length) {
        val char = source[i]

        if (char == '\'') {
            val end = findClosingSingleQuote(source, i + 1)

            if (end >= 0) {
                out.append(quoteJson(source.substring(i + 1, end).replace("""\'""", "'")))
                i = end + 1
                continue
            }
        }

        out.append(char)
        i++
    }

    return out.toString()
}

/**
 * Index of the `'` closing a literal whose body starts at [from], stepping over `\` + any char;
 * -1 when there is none.
 */
private fun findClosingSingleQuote(source: String, from: Int): Int {
    var j = from

    while (j < source.length) {
        when (source[j]) {
            '\'' -> return j
            '\\' -> j += 2
            else -> j++
        }
    }

    return -1
}

/**
 * `\x41` -> `A`, as BgUtils' decodeHexEscapes.
 */
private fun decodeHexEscapes(value: String): String {
    if (!value.contains("\\x")) {
        return value
    }

    val hexMatcher = HEX_ESCAPE_PATTERN.matcher(value)

    return buildString {
        var lastEnd = 0

        while (hexMatcher.find()) {
            append(value, lastEnd, hexMatcher.start())
            append(hexMatcher.group(1)!!.toInt(16).toChar())
            lastEnd = hexMatcher.end()
        }

        append(value, lastEnd, value.length)
    }
}

private fun quoteJson(value: String): String =
    buildString {
        append('"')
        for (char in value) {
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (char.code < 0x20) {
                        append("\\u00")
                        // Fast hex conversion for values 0-31 without allocations
                        append("0123456789abcdef"[char.code ushr 4])
                        append("0123456789abcdef"[char.code and 0x0F])
                    } else {
                        append(char)
                    }
                }
            }
        }
        append('"')
    }
