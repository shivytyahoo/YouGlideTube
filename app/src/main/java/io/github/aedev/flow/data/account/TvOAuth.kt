package io.github.aedev.flow.data.account

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Google's OAuth 2.0 device flow (the "TV code" sign-in): the app shows a short code,
 * the person approves it on any signed-in device at the verification URL, and the app
 * polls for tokens. Client credentials are the public YouTube TV client — the same
 * arrangement every open-source YouTube TV client uses; the secret is not confidential
 * for installed apps and is verified against the upstream source.
 */
object TvOAuth {
    private const val CLIENT_ID = "861556708454-d6dlm3lh05idd8npek18k6be8ba3oc68.apps.googleusercontent.com"
    private const val CLIENT_SECRET = "SboVhoG9s0rNafixCSGGKXAT"
    private const val SCOPE = "https://www.googleapis.com/auth/youtube"
    private const val DEVICE_CODE_URL = "https://oauth2.googleapis.com/device/code"
    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    private const val GRANT_DEVICE_CODE = "urn:ietf:params:oauth:grant-type:device_code"
    private const val GRANT_REFRESH = "refresh_token"

    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient(OkHttp)

    data class DeviceSession(
        val userCode: String,
        val verificationUrl: String,
        val deviceCode: String,
        val intervalSeconds: Int,
        val expiresInSeconds: Int,
    )

    data class Tokens(
        val accessToken: String,
        val refreshToken: String?,
        val expiresInSeconds: Long,
    )

    sealed interface PollResult {
        data class Success(
            val tokens: Tokens,
        ) : PollResult

        /** The person has not approved yet — keep polling at the session's interval. */
        data object Pending : PollResult

        /** Polling too fast — back off before the next attempt. */
        data object SlowDown : PollResult

        /** Denied, expired or otherwise finished: this code will never succeed. */
        data class Failed(
            val error: String,
        ) : PollResult
    }

    suspend fun requestDeviceCode(): Result<DeviceSession> =
        runCatching {
            val root =
                postForm(
                    DEVICE_CODE_URL,
                    "client_id" to CLIENT_ID,
                    "scope" to SCOPE,
                )
            DeviceSession(
                userCode = root.string("user_code") ?: error("no user_code"),
                verificationUrl = root.string("verification_url") ?: "https://www.google.com/device",
                deviceCode = root.string("device_code") ?: error("no device_code"),
                intervalSeconds = root.int("interval") ?: 5,
                expiresInSeconds = root.int("expires_in") ?: 900,
            )
        }

    suspend fun pollToken(deviceCode: String): PollResult {
        val root =
            runCatching {
                postForm(
                    TOKEN_URL,
                    "client_id" to CLIENT_ID,
                    "client_secret" to CLIENT_SECRET,
                    "device_code" to deviceCode,
                    "grant_type" to GRANT_DEVICE_CODE,
                )
            }.getOrElse { return PollResult.Failed(it.message ?: "network") }
        root.string("access_token")?.let { access ->
            return PollResult.Success(
                Tokens(
                    accessToken = access,
                    refreshToken = root.string("refresh_token"),
                    expiresInSeconds = root.long("expires_in") ?: 3600L,
                ),
            )
        }
        return when (val error = root.string("error")) {
            "authorization_pending" -> PollResult.Pending
            "slow_down" -> PollResult.SlowDown
            else -> PollResult.Failed(error ?: "unknown")
        }
    }

    suspend fun refreshAccessToken(refreshToken: String): Result<Tokens> =
        runCatching {
            val root =
                postForm(
                    TOKEN_URL,
                    "client_id" to CLIENT_ID,
                    "client_secret" to CLIENT_SECRET,
                    "refresh_token" to refreshToken,
                    "grant_type" to GRANT_REFRESH,
                )
            Tokens(
                accessToken = root.string("access_token") ?: error(root.string("error") ?: "refresh failed"),
                refreshToken = root.string("refresh_token") ?: refreshToken,
                expiresInSeconds = root.long("expires_in") ?: 3600L,
            )
        }

    private suspend fun postForm(
        url: String,
        vararg fields: Pair<String, String>,
    ): JsonObject {
        val text =
            client
                .post(url) {
                    setBody(
                        FormDataContent(
                            Parameters.build {
                                fields.forEach { (name, value) -> append(name, value) }
                            },
                        ),
                    )
                }.bodyAsText()
        return json.parseToJsonElement(text).jsonObject
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.content

    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

    private fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull
}
