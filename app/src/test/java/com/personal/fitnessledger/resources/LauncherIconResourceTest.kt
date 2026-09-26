package com.personal.fitnessledger.resources

import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element

class LauncherIconResourceTest {
    @Test
    fun `android 13 launcher icons share the complete themed icon contract`() {
        listOf("ic_launcher.xml", "ic_launcher_round.xml").forEach { fileName ->
            val root = parse(resourcePath("mipmap-anydpi-v26", fileName)).documentElement

            assertEquals("adaptive-icon", root.tagName)
            assertLayer(root, "background", "@color/launcher_background")
            assertLayer(root, "foreground", "@drawable/ic_launcher_foreground")
            assertLayer(root, "monochrome", "@drawable/ic_launcher_monochrome")
        }
    }

    @Test
    fun `monochrome launcher layer is a non-empty vector alpha mask`() {
        val root = parse(resourcePath("drawable", "ic_launcher_monochrome.xml")).documentElement

        assertEquals("vector", root.tagName)
        assertEquals("108", root.androidAttribute("viewportWidth"))
        assertEquals("108", root.androidAttribute("viewportHeight"))
        val paths = root.getElementsByTagName("path")
        assertTrue("Monochrome vector must contain a visible path", paths.length > 0)
        for (index in 0 until paths.length) {
            val path = paths.item(index) as Element
            assertTrue(
                "Every monochrome path must provide geometry",
                path.androidAttribute("pathData").isNotBlank(),
            )
            assertTrue(
                "Every monochrome path must provide an alpha-bearing fill",
                path.androidAttribute("fillColor").isNotBlank(),
            )
        }
    }

    private fun assertLayer(root: Element, tagName: String, expectedDrawable: String) {
        val elements = root.getElementsByTagName(tagName)
        assertEquals("Expected exactly one <$tagName> layer", 1, elements.length)
        val layer = elements.item(0) as Element
        assertEquals(expectedDrawable, layer.androidAttribute("drawable"))
    }

    private fun Element.androidAttribute(name: String): String =
        getAttributeNS(ANDROID_NAMESPACE, name)

    private fun parse(path: Path): Document {
        assertTrue("Missing launcher resource: $path", Files.isRegularFile(path))
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
            setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
        }
        return Files.newInputStream(path).use { factory.newDocumentBuilder().parse(it) }
    }

    private fun resourcePath(directory: String, fileName: String): Path =
        resourceRoot.resolve(directory).resolve(fileName)

    private val resourceRoot: Path by lazy {
        val workingDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        val candidates = generateSequence(workingDirectory) { it.parent }
            .flatMap { directory -> sequenceOf(directory, directory.resolve("app")) }
        val root = candidates.firstOrNull { candidate ->
            Files.isRegularFile(candidate.resolve("build.gradle.kts")) &&
                Files.isDirectory(candidate.resolve("src/main/res"))
        }
        assertNotNull("Could not locate the app module from $workingDirectory", root)
        requireNotNull(root).resolve("src/main/res")
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
