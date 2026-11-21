package com.meetily.mobile

import com.meetily.mobile.llm.LocalLlmModels
import com.meetily.mobile.whisper.DiarizationModels
import com.meetily.mobile.whisper.NemoModels
import com.meetily.mobile.whisper.WhisperModels
import java.io.File
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * Pins the cleartext policy. The base config permits cleartext because a
 * user's Ollama box on the LAN is plain http and a network security config
 * cannot name IP ranges; EndpointGuard is what confines http to private
 * addresses (EndpointGuardTest). Everything the app downloads by itself must
 * stay https-only here as well as in code.
 */
class NetworkSecurityConfigTest {

    private val androidNs = "http://schemas.android.com/apk/res/android"

    /** Unit tests run from the module directory; the others are fallbacks. */
    private fun source(path: String): File {
        val candidates = listOf("src/main/$path", "app/src/main/$path", "android/app/src/main/$path")
        return candidates.map(::File).firstOrNull { it.isFile }
            ?: run {
                fail("$path not found from ${File(".").absolutePath}")
                throw IllegalStateException()
            }
    }

    private fun parse(file: File): Document =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(file)

    private fun Element.children(tag: String): List<Element> {
        val nodes = getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    @Test
    fun manifestUsesTheConfigInsteadOfAppWideCleartext() {
        val app = parse(source("AndroidManifest.xml")).documentElement
            .children("application").single()
        assertEquals(
            "@xml/network_security_config",
            app.getAttributeNS(androidNs, "networkSecurityConfig")
        )
        assertFalse(app.hasAttributeNS(androidNs, "usesCleartextTraffic"))
    }

    @Test
    fun baseConfigTrustsOnlyTheSystemStore() {
        val root = parse(source("res/xml/network_security_config.xml")).documentElement
        val base = root.children("base-config").single()
        // Deliberately true: see the class comment and EndpointGuard.
        assertEquals("true", base.getAttribute("cleartextTrafficPermitted"))
        val sources = base.children("certificates").map { it.getAttribute("src") }
        assertEquals(listOf("system"), sources)
        assertTrue(root.children("debug-overrides").isEmpty())
    }

    @Test
    fun everyCatalogueDownloadHostIsHttpsOnly() {
        val root = parse(source("res/xml/network_security_config.xml")).documentElement
        val httpsOnly = root.children("domain-config")
            .filter { it.getAttribute("cleartextTrafficPermitted") == "false" }
            .flatMap { it.children("domain") }
        assertTrue(httpsOnly.all { it.getAttribute("includeSubdomains") == "true" })
        val domains = httpsOnly.map { it.textContent.trim().lowercase() }
        val urls = LocalLlmModels.ALL.map { it.downloadUrl } +
            WhisperModels.ALL.map { it.url } +
            NemoModels.ALL.flatMap { m -> m.files.map { m.urlFor(it) } } +
            DiarizationModels.ALL.map { it.url }
        assertTrue(urls.isNotEmpty())
        for (url in urls) {
            val host = URL(url).host.lowercase()
            assertTrue(
                "$host is not https-only in network_security_config.xml",
                domains.any { host == it || host.endsWith(".$it") }
            )
        }
    }
}
