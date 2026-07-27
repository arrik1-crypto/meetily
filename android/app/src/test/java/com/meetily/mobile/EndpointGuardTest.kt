package com.meetily.mobile

import com.meetily.mobile.security.EndpointGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Host classification and endpoint rules. All hosts here are IP literals or
 * "localhost", so nothing touches DNS.
 */
class EndpointGuardTest {

    @Test
    fun privateRangesAreRecognized() {
        assertTrue(EndpointGuard.isPrivateHost("localhost"))
        assertTrue(EndpointGuard.isPrivateHost("127.0.0.1"))
        assertTrue(EndpointGuard.isPrivateHost("10.0.0.5"))
        assertTrue(EndpointGuard.isPrivateHost("172.16.0.1"))
        assertTrue(EndpointGuard.isPrivateHost("172.31.255.254"))
        assertTrue(EndpointGuard.isPrivateHost("192.168.1.100"))
        assertTrue(EndpointGuard.isPrivateHost("169.254.1.1"))
        assertTrue(EndpointGuard.isPrivateHost("::1"))
        assertTrue(EndpointGuard.isPrivateHost("fd00::1"))
        assertTrue(EndpointGuard.isPrivateHost("fe80::1"))
    }

    @Test
    fun cgnatTailscaleRangeIsPrivate() {
        assertTrue(EndpointGuard.isPrivateHost("100.64.0.1"))
        assertTrue(EndpointGuard.isPrivateHost("100.100.20.30"))
        assertTrue(EndpointGuard.isPrivateHost("100.127.255.255"))
        assertFalse(EndpointGuard.isPrivateHost("100.63.255.255"))
        assertFalse(EndpointGuard.isPrivateHost("100.128.0.1"))
    }

    @Test
    fun publicAddressesAreNotPrivate() {
        assertFalse(EndpointGuard.isPrivateHost("8.8.8.8"))
        assertFalse(EndpointGuard.isPrivateHost("172.32.0.1"))
        assertFalse(EndpointGuard.isPrivateHost("11.0.0.1"))
        assertFalse(EndpointGuard.isPrivateHost("2607:f8b0::1"))
    }

    @Test
    fun localOnlyBlocksPublicHttps() {
        expectBlocked("https://8.8.8.8/v1", localOnly = true)
        EndpointGuard.check("https://8.8.8.8/v1", localOnly = false) // allowed
        EndpointGuard.check("https://192.168.1.4/v1", localOnly = true)
    }

    @Test
    fun cleartextIsAlwaysPrivateOnly() {
        // Even with local-only off, plain http may not leave the LAN.
        expectBlocked("http://8.8.8.8:11434/v1", localOnly = false)
        EndpointGuard.check("http://192.168.1.100:11434/v1", localOnly = true)
        EndpointGuard.check("http://localhost:11434/v1", localOnly = true)
        EndpointGuard.check("http://100.101.1.2:11434/v1", localOnly = true)
    }

    @Test
    fun malformedUrlsAreBlocked() {
        expectBlocked("not a url", localOnly = false)
        expectBlocked("ftp://192.168.1.1/v1", localOnly = false)
        expectBlocked("http://", localOnly = false)
    }

    // --- Address pinning policy ------------------------------------------
    //
    // The socket wiring cannot be exercised off-device, but the decision it
    // acts on can: given what a host resolved to, which addresses may be used.

    @Test
    fun pinsOnlyWhenThePolicyConstrainsTheAddress() {
        // Local-only https, and any http: the address matters.
        assertTrue(EndpointGuard.isConstrained("https", localOnly = true))
        assertTrue(EndpointGuard.isConstrained("http", localOnly = true))
        assertTrue(EndpointGuard.isConstrained("http", localOnly = false))
        // Cloud endpoint, local-only off: no opinion, so no pinning — freezing
        // one DNS answer there would break failover for no security gain.
        assertFalse(EndpointGuard.isConstrained("https", localOnly = false))
        assertTrue(
            EndpointGuard.allowed(
                "https", listOf(addr("8.8.8.8")), localOnly = false
            ).isEmpty()
        )
    }

    @Test
    fun everyAnswerMustBePrivate() {
        val privateOnly = listOf(addr("192.168.1.50"), addr("10.0.0.9"))
        assertEquals(
            privateOnly,
            EndpointGuard.allowed("https", privateOnly, localOnly = true)
        )
        // The rebinding shape: one LAN answer, one public. All-or-nothing.
        val mixed = listOf(addr("192.168.1.50"), addr("93.184.216.34"))
        assertTrue(EndpointGuard.allowed("https", mixed, localOnly = true).isEmpty())
        // Nothing resolved at all is not an approval either.
        assertTrue(
            EndpointGuard.allowed("https", emptyList(), localOnly = true).isEmpty()
        )
    }

    @Test
    fun tailscaleAndLoopbackAnswersArePinnable() {
        val tailscale = listOf(addr("100.101.1.2"))
        assertEquals(
            tailscale,
            EndpointGuard.allowed("http", tailscale, localOnly = true)
        )
        val loopback = listOf(addr("127.0.0.1"))
        assertEquals(
            loopback,
            EndpointGuard.allowed("http", loopback, localOnly = false)
        )
    }

    /** IP literal — InetAddress.getByName does not resolve these. */
    private fun addr(literal: String): java.net.InetAddress =
        java.net.InetAddress.getByName(literal)

    private fun expectBlocked(url: String, localOnly: Boolean) {
        try {
            EndpointGuard.check(url, localOnly)
            fail("expected $url to be blocked")
        } catch (e: EndpointGuard.BlockedEndpointException) {
            // expected
        }
    }
}
