package io.github.shivytyahoo.glidetube.platform

import com.google.common.truth.Truth.assertThat
import io.github.shivytyahoo.glidetube.util.AppIcons
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The launcher aliases must match [AppIcons] and point to resources in the single app source set.
 */
class LauncherAliasManifestTest {
    private val androidNs = "http://schemas.android.com/apk/res/android"

    private val aliases: List<Element> =
        DocumentBuilderFactory
            .newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(File("src/main/AndroidManifest.xml"))
            .getElementsByTagName("activity-alias")
            .let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }

    private fun Element.android(name: String) = getAttributeNS(androidNs, name)

    @Test
    fun `the manifest declares exactly the registered aliases in the same order`() {
        assertThat(aliases.map { it.android("name") }).containsExactlyElementsIn(AppIcons.ALL_SUFFIXES).inOrder()
    }

    @Test
    fun `only the default alias is enabled out of the box`() {
        val enabled = aliases.filter { it.android("enabled") != "false" }.map { it.android("name") }

        assertThat(enabled).containsExactly(AppIcons.DEFAULT_SUFFIX)
    }

    @Test
    fun `every alias icon exists in the main resources`() {
        val mainMipmaps = File("src/main/res/mipmap-anydpi-v26")
        val icons = aliases.flatMap { listOf(it.android("icon"), it.android("roundIcon")) }

        val missing = icons.map { it.removePrefix("@mipmap/") }.filterNot { File(mainMipmaps, "$it.xml").isFile }

        assertThat(missing).isEmpty()
    }
}
