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
 * The in-app privacy policy (privacy_policy_body) and the hosted one
 * (docs/privacy-policy.md, the Play listing's privacy URL) must describe the
 * same app. Their wording is formatted differently, so this pins the part
 * that drifts first: the list of sections, in order.
 */
class PrivacyPolicyDriftTest {

    /** Unit tests run from the module directory; the others are fallbacks. */
    private fun find(vararg candidates: String): File =
        candidates.map(::File).firstOrNull { it.isFile }
            ?: run {
                fail("none of ${candidates.toList()} found from ${File(".").absolutePath}")
                throw IllegalStateException()
            }

    private fun inAppPolicy(): String {
        val file = find(
            "src/main/res/values/strings.xml",
            "app/src/main/res/values/strings.xml",
            "android/app/src/main/res/values/strings.xml"
        )
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(file).getElementsByTagName("string")
        val body = (0 until nodes.length).map { nodes.item(it) as Element }
            .single { it.getAttribute("name") == "privacy_policy_body" }
            .textContent
        return body.replace("\\n", "\n").replace("\\'", "'").replace("\\\"", "\"")
    }

    private fun hostedPolicy(): String = find(
        "../../docs/privacy-policy.md",
        "../docs/privacy-policy.md",
        "docs/privacy-policy.md"
    ).readText()

    @Test
    fun bothPoliciesHaveTheSameSections() {
        val inApp = inAppPolicy().lines()
            .map { it.trim() }
            .filter { it.length > 2 && it == it.uppercase() && it.any(Char::isLetter) }
        val hosted = hostedPolicy().lines()
            .filter { it.startsWith("## ") }
            .map { it.removePrefix("## ").trim().uppercase() }
        assertTrue(inApp.isNotEmpty())
        assertEquals(hosted, inApp)
    }

    @Test
    fun inAppPolicyIsNotTheTemplateAndNamesItsContact() {
        val body = inAppPolicy()
        assertFalse(body.contains("starting-point", ignoreCase = true))
        assertFalse(body.contains("adapt it", ignoreCase = true))
        // PrivacyActivity fills these with support_email and the effective date.
        assertTrue(body.contains("%1\$s"))
        assertTrue(body.contains("%2\$s"))
    }
}
