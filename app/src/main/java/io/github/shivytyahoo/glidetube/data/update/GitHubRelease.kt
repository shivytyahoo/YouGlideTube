package io.github.shivytyahoo.glidetube.data.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

private val ReleaseJson = Json { ignoreUnknownKeys = true }
private const val SHA256_PREFIX = "sha256:"
private const val UNIVERSAL_APK = "YouGlideTube.apk"

@Serializable
internal data class GitHubRelease(
    @SerialName("tag_name") val tagName: String = "",
    val body: String? = null,
    @SerialName("html_url") val htmlUrl: String = "",
    @SerialName("published_at") val publishedAt: String? = null,
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
internal data class GitHubAsset(
    val name: String = "",
    @SerialName("browser_download_url") val downloadUrl: String = "",
    val size: Long = 0,
    val digest: String? = null,
)

internal fun parseGitHubRelease(json: String): GitHubRelease = ReleaseJson.decodeFromString(GitHubRelease.serializer(), json)

/**
 * [release] as an [AppRelease] when it is newer than [currentVersion], otherwise null.
 * A same-version rebuild still counts when its asset digest differs from the installed
 * APK's hash: this release line never bumps its version, so the digest is the only
 * signal that the release page carries a newer build.
 */
internal fun GitHubRelease.toAppReleaseIfNewer(
    currentVersion: String,
    installedSha256: String? = null,
): AppRelease? {
    if (tagName.isBlank()) return null
    val apk = selectApk(assets)?.toReleaseApk()
    val release =
        AppRelease(
            version = AppVersions.normalize(tagName),
            tag = tagName,
            notes = body.orEmpty(),
            publishedAt = publishedAt?.let { runCatching { Instant.parse(it) }.getOrNull() },
            pageUrl = htmlUrl,
            apk = apk,
        )
    if (AppVersions.isNewer(tagName, currentVersion)) return release
    if (AppVersions.normalize(tagName) != AppVersions.normalize(currentVersion)) return null
    val digest = apk?.sha256
    if (digest != null && installedSha256 != null && !digest.equals(installedSha256, ignoreCase = true)) {
        return release
    }
    return null
}

/** Selects the single universal YouGlideTube APK published for all Android device architectures. */
internal fun selectApk(assets: List<GitHubAsset>): GitHubAsset? =
    assets.firstOrNull { it.name.equals(UNIVERSAL_APK, ignoreCase = true) }

private fun GitHubAsset.toReleaseApk(): ReleaseApk =
    ReleaseApk(
        name = name,
        url = downloadUrl,
        sizeBytes = size,
        sha256 = digest?.takeIf { it.startsWith(SHA256_PREFIX) }?.removePrefix(SHA256_PREFIX)?.lowercase(),
    )
