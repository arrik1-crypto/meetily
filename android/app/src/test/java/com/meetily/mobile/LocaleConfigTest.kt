package com.meetily.mobile

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Element

/**
 * The per-app language setup: a hand-written res/xml/locale_config.xml,
 * referenced from the manifest, listing exactly the languages the app is
 * written in, with no android:defaultLocale.
 *
 * Not AGP's generated list (androidResources.generateLocaleConfig): from
 * AGP 8.8 it writes android:defaultLocale from res/resources.properties, and
 * that attribute crashes Android 15 and some Android 16 builds at every
 * activity launch for a user whose system languages are all missing from
 * the list (issuetracker b/399131926). AGP stops writing it in
 * 9.0.0-beta04, so generation stays off below that.
 */
class LocaleConfigTest {

    private val androidNs = "http://schemas.android.com/apk/res/android"

    /** Unit tests run from the module directory; the others are fallbacks. */
    private fun moduleFile(path: String): File {
        val candidates = listOf(path, "app/$path", "android/app/$path")
        return candidates.map(::File).firstOrNull { it.exists() }
            ?: run {
                fail("$path not found from ${File(".").absolutePath}")
                throw IllegalStateException()
            }
    }

    private fun gradle(): String = moduleFile("build.gradle.kts").readText()

    /** The root build file, beside the module directory. */
    private fun rootGradle(): String =
        File(moduleFile("build.gradle.kts").absoluteFile.parentFile.parentFile, "build.gradle.kts")
            .readText()

    private fun localeConfig(): Element =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(moduleFile("src/main/res/xml/locale_config.xml"))
            .documentElement

    /** The android:name of every <locale>, in file order. */
    private fun listedLocales(): List<String> {
        val nodes = localeConfig().getElementsByTagName("locale")
        return (0 until nodes.length).map { (nodes.item(it) as Element).getAttributeNS(androidNs, "name") }
    }

    /**
     * "9.0.0-beta04" as comparable parts: the numbers, then the pre-release
     * rank (alpha 0, beta 1, rc 2, a release 3) and its number.
     */
    private fun agpVersionKey(version: String): List<Int> {
        val numbers = version.substringBefore('-').split('.').map { it.toInt() }
        val pre = version.substringAfter('-', "")
        val match = Regex("""^(alpha|beta|rc)(\d+)$""").find(pre)
        val rank = when (match?.groupValues?.get(1)) {
            "alpha" -> 0
            "beta" -> 1
            "rc" -> 2
            else -> 3
        }
        return numbers + listOf(rank, match?.groupValues?.get(2)?.toInt() ?: 0)
    }

    private fun atLeast(version: String, floor: String): Boolean {
        val a = agpVersionKey(version)
        val b = agpVersionKey(floor)
        for (i in a.indices) {
            if (a[i] != b[i]) return a[i] > b[i]
        }
        return true
    }

    @Test
    fun agpVersionsCompareWithTheirPreReleases() {
        assertFalse(atLeast("8.10.1", "9.0.0-beta04"))
        assertFalse(atLeast("9.0.0-beta03", "9.0.0-beta04"))
        assertFalse(atLeast("9.0.0-alpha12", "9.0.0-beta04"))
        assertTrue(atLeast("9.0.0-beta04", "9.0.0-beta04"))
        assertTrue(atLeast("9.0.0-rc01", "9.0.0-beta04"))
        assertTrue(atLeast("9.0.0", "9.0.0-beta04"))
        assertTrue(atLeast("9.1.0", "9.0.0-beta04"))
    }

    @Test
    fun theUnqualifiedStringsAreUsEnglish() {
        val lines = moduleFile("src/main/res/resources.properties").readLines()
            .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        assertEquals(listOf("unqualifiedResLocale=en-US"), lines)
    }

    /** AGP's generated list stays off while it would write android:defaultLocale. */
    @Test
    fun noGeneratedLocaleConfigBeforeAgp9Beta04() {
        val agp = Regex("""id\("com\.android\.application"\)\s+version\s+"([^"]+)"""")
            .find(rootGradle())?.groupValues?.get(1)
            ?: run {
                fail("no com.android.application version in the root build.gradle.kts")
                return
            }
        if (Regex("""generateLocaleConfig\s*=\s*true""").containsMatchIn(gradle())) {
            assertTrue(
                "AGP $agp writes android:defaultLocale; keep generateLocaleConfig off",
                atLeast(agp, "9.0.0-beta04")
            )
            // AGP refuses to build with a hand-written list alongside.
            fail("generateLocaleConfig replaces res/xml/locale_config.xml; remove one")
        }
    }

    @Test
    fun theManifestPointsAtTheHandWrittenList() {
        val manifest = moduleFile("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("""android:localeConfig="@xml/locale_config""""))
        val root = localeConfig()
        assertEquals("locale-config", root.tagName)
        // The attribute that triggers the platform crash, on no element.
        val elements = root.getElementsByTagName("*")
        for (element in listOf(root) + (0 until elements.length).map { elements.item(it) as Element }) {
            val attributes = element.attributes
            for (i in 0 until attributes.length) {
                val name = attributes.item(i).localName ?: attributes.item(i).nodeName
                assertFalse(element.tagName, name.endsWith("defaultLocale"))
            }
        }
        // The unqualified strings' locale comes first.
        assertEquals("en-US", listedLocales().first())
    }

    /**
     * Every translation the app carries is listed (or the language settings
     * would not offer it), and nothing else is: not a library's languages.
     */
    @Test
    fun theListNamesExactlyTheAppsLanguages() {
        val uiModes = setOf("car", "desk")
        val languages = moduleFile("src/main/res").listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("values-") }
            .mapNotNull { dir ->
                val qualifier = dir.name.removePrefix("values-")
                val match = Regex("""^(b\+[A-Za-z0-9+]+|[a-z]{2,3}(-r[A-Z]{2})?)(-.*)?$""")
                    .find(qualifier) ?: return@mapNotNull null
                match.groupValues[1].takeUnless { it in uiModes }
            }
            // Resource qualifiers as BCP 47 tags: pt-rBR is pt-BR, b+sr+Latn is sr-Latn.
            .map { it.removePrefix("b+").replace('+', '-').replace("-r", "-") }
            .toSet()
        val listed = listedLocales()
        assertEquals(listed.size, listed.toSet().size)
        assertEquals(languages + "en-US", listed.toSet())
    }
}
