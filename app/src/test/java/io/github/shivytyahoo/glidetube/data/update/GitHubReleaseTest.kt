package io.github.shivytyahoo.glidetube.data.update

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant

class GitHubReleaseTest {
    private fun asset(
        name: String,
        size: Long = 1,
        digest: String? = null,
    ) = GitHubAsset(name, "https://example.test/$name", size, digest)

    @Test
    fun `the universal apk is selected regardless of device abi`() {
        val assets = listOf(asset("YouGlideTube-arm64-v8a.apk"), asset("YouGlideTube.apk"))
        assertThat(selectApk(assets)?.name).isEqualTo("YouGlideTube.apk")
    }

    @Test
    fun `no universal apk means no update is offered`() {
        assertThat(selectApk(listOf(asset("YouGlideTube-arm64-v8a.apk")))).isNull()
    }

    @Test
    fun `versions compare numerically and ignore the prefix and suffix`() {
        assertThat(AppVersions.isNewer("v2.10.0", "2.9.9")).isTrue()
        assertThat(AppVersions.isNewer("v2.2.5", "2.2.5-debug")).isFalse()
        assertThat(AppVersions.isNewer("2.2", "2.2.0")).isFalse()
        assertThat(AppVersions.isNewer("v2.2.1", "2.3.0")).isFalse()
    }

    @Test
    fun `a newer release carries its apk size, digest and date`() {
        val json =
            """
            {"tag_name":"v2.3.0","body":"## New features","html_url":"https://github.com/shivytyahoo/YouGlideTube/releases/tag/v2.3.0",
             "published_at":"2026-09-28T10:00:00Z","draft":false,
             "assets":[{"name":"YouGlideTube.apk","browser_download_url":"https://example.test/a","size":17613193,
                        "digest":"sha256:F900AB"}]}
            """.trimIndent()

        val release = parseGitHubRelease(json).toAppReleaseIfNewer("2.2.5")

        assertThat(release?.version).isEqualTo("2.3.0")
        assertThat(release?.publishedAt).isEqualTo(Instant.parse("2026-09-28T10:00:00Z"))
        assertThat(release?.apk).isEqualTo(ReleaseApk("YouGlideTube.apk", "https://example.test/a", 17613193, "f900ab"))
    }

    @Test
    fun `the running version is not an update`() {
        val release = GitHubRelease(tagName = "v2.2.5").toAppReleaseIfNewer("2.2.5")

        assertThat(release).isNull()
    }
}
