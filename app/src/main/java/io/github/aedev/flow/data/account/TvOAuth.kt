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
    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    private const val GRANT_REFRESH = "refresh_token"

    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient(OkHttp)

    data class Tokens(
        val accessToken: String,
        val refreshToken: String?,
        val expiresInSeconds: Long,
    )

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
