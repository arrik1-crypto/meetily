package com.meetily.mobile

import com.meetily.mobile.security.EndpointGuard
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

    private fun expectBlocked(url: String, localOnly: Boolean) {
        try {
            EndpointGuard.check(url, localOnly)
            fail("expected $url to be blocked")
        } catch (e: EndpointGuard.BlockedEndpointException) {
            // expected
        }
    }
}
