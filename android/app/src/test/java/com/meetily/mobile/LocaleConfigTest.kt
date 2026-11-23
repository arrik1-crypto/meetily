package com.meetily.mobile

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The per-app language setup: AGP generates the locale list
 * (androidResources.generateLocaleConfig) from res/resources.properties and
 * the values-xx folders, limited by localeFilters so the libraries'
 * translations do not offer languages the app is not written in.
 */
class LocaleConfigTest {

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

    /** The resource qualifiers localeFilters keeps, as written in build.gradle.kts. */
    private fun localeFilters(): Set<String> {
        val match = Regex("""localeFilters\s*\+=\s*listOf\(([^)]*)\)""").find(gradle())
            ?: run {
                fail("no localeFilters in build.gradle.kts")
                throw IllegalStateException()
            }
        return Regex(""""([^"]+)"""").findAll(match.groupValues[1]).map { it.groupValues[1] }.toSet()
    }

    @Test
    fun theUnqualifiedStringsAreUsEnglish() {
        val lines = moduleFile("src/main/res/resources.properties").readLines()
            .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        assertEquals(listOf("unqualifiedResLocale=en-US"), lines)
    }

    @Test
    fun agpGeneratesTheLocaleConfig() {
        assertTrue(Regex("""generateLocaleConfig\s*=\s*true""").containsMatchIn(gradle()))
        // AGP refuses to build with a hand-written one alongside.
        val manifest = moduleFile("src/main/AndroidManifest.xml").readText()
        assertFalse(manifest.contains("android:localeConfig"))
        assertFalse(File(moduleFile("src/main/res"), "xml/locale_config.xml").exists())
        // The default locale, in resource-qualifier form (aapt2's -c).
        assertTrue(localeFilters().contains("en-rUS"))
    }

    /**
     * Every translation the app carries is in localeFilters (or the language
     * list would drop it), and nothing else is: no library's languages.
     */
    @Test
    fun theFilterListsExactlyTheAppsLanguages() {
        val uiModes = setOf("car", "desk")
        val languages = moduleFile("src/main/res").listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("values-") }
            .mapNotNull { dir ->
                val qualifier = dir.name.removePrefix("values-")
                val match = Regex("""^(b\+[A-Za-z0-9+]+|[a-z]{2,3}(-r[A-Z]{2})?)(-.*)?$""")
                    .find(qualifier) ?: return@mapNotNull null
                match.groupValues[1].takeUnless { it in uiModes }
            }
            .toSet()
        assertEquals(languages + "en-rUS", localeFilters())
    }
}
