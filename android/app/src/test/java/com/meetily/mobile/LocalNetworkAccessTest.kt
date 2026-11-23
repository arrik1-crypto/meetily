package com.meetily.mobile

import com.meetily.mobile.security.EndpointUnreachableException
import com.meetily.mobile.security.LocalNetworkAccess
import com.meetily.mobile.security.LocalNetworkAccess.Hint
import com.meetily.mobile.security.LocalNetworkAccess.Kind
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Android 17's local network permission (LocalNetworkAccess). Every host
 * here is a literal, "localhost" or a ".local" name, so nothing touches DNS.
 */
class LocalNetworkAccessTest {

    private fun kind(host: String): Kind = LocalNetworkAccess.hostKind(host)

    private fun v4(a: Int, b: Int, c: Int, d: Int): ByteArray =
        byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte())

    @Test
    fun rfc1918RangesAndTheirEdges() {
        assertEquals(Kind.OTHER, kind("9.255.255.255"))
        assertEquals(Kind.LOCAL_NETWORK, kind("10.0.0.0"))
        assertEquals(Kind.LOCAL_NETWORK, kind("10.255.255.255"))
        assertEquals(Kind.OTHER, kind("11.0.0.0"))

        assertEquals(Kind.OTHER, kind("172.15.255.255"))
        assertEquals(Kind.LOCAL_NETWORK, kind("172.16.0.0"))
        assertEquals(Kind.LOCAL_NETWORK, kind("172.31.255.255"))
        assertEquals(Kind.OTHER, kind("172.32.0.0"))

        assertEquals(Kind.OTHER, kind("192.167.255.255"))
        assertEquals(Kind.LOCAL_NETWORK, kind("192.168.0.0"))
        assertEquals(Kind.LOCAL_NETWORK, kind("192.168.255.255"))
        assertEquals(Kind.OTHER, kind("192.169.0.0"))
    }

    @Test
    fun sharedAddressSpaceAndLinkLocal() {
        // 100.64.0.0/10, which the platform lists as local (Tailscale lives here).
        assertEquals(Kind.OTHER, kind("100.63.255.255"))
        // Local to the platform on Wi-Fi, but Tailscale's range too.
        assertEquals(Kind.LOCAL_OR_VPN, kind("100.64.0.0"))
        assertEquals(Kind.LOCAL_OR_VPN, kind("100.101.102.103"))
        assertEquals(Kind.LOCAL_OR_VPN, kind("100.127.255.255"))
        assertEquals(Kind.OTHER, kind("100.128.0.0"))

        assertEquals(Kind.OTHER, kind("169.253.255.255"))
        assertEquals(Kind.LOCAL_NETWORK, kind("169.254.0.0"))
        assertEquals(Kind.LOCAL_NETWORK, kind("169.254.255.255"))
        assertEquals(Kind.OTHER, kind("169.255.0.0"))
    }

    @Test
    fun multicastAndBroadcastAreLocal() {
        assertEquals(Kind.OTHER, kind("223.255.255.255"))
        assertEquals(Kind.LOCAL_NETWORK, kind("224.0.0.0"))
        assertEquals(Kind.LOCAL_NETWORK, kind("239.255.255.255"))
        assertEquals(Kind.OTHER, kind("240.0.0.0"))
        assertEquals(Kind.OTHER, kind("255.255.255.254"))
        assertEquals(Kind.LOCAL_NETWORK, kind("255.255.255.255"))
    }

    @Test
    fun loopbackAndPublicAreNotLocalNetwork() {
        assertEquals(Kind.LOOPBACK, kind("127.0.0.1"))
        assertEquals(Kind.LOOPBACK, kind("127.255.255.255"))
        assertEquals(Kind.OTHER, kind("126.255.255.255"))
        assertEquals(Kind.OTHER, kind("128.0.0.0"))
        assertEquals(Kind.LOOPBACK, kind("localhost"))
        assertEquals(Kind.LOOPBACK, kind("LocalHost."))
        assertEquals(Kind.LOOPBACK, kind("::1"))
        assertEquals(Kind.LOOPBACK, kind("[::1]"))
        assertEquals(Kind.OTHER, kind("8.8.8.8"))
        assertEquals(Kind.OTHER, kind("1.1.1.1"))
        assertEquals(Kind.OTHER, kind("2001:4860:4860::8888"))
        assertEquals(Kind.OTHER, kind("0.0.0.0"))
        assertEquals(Kind.OTHER, kind("::"))
    }

    @Test
    fun ipv6RangesAndTheirEdges() {
        // Unique local fc00::/7.
        assertEquals(Kind.OTHER, kind("fbff:ffff:ffff:ffff:ffff:ffff:ffff:ffff"))
        // Unique local: a home prefix, or a VPN's.
        assertEquals(Kind.LOCAL_OR_VPN, kind("fc00::"))
        assertEquals(Kind.LOCAL_OR_VPN, kind("fd12:3456:789a::1"))
        assertEquals(Kind.LOCAL_OR_VPN, kind("fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff"))
        // Tailscale's own IPv6 range.
        assertEquals(Kind.LOCAL_OR_VPN, kind("fd7a:115c:a1e0::1"))
        assertEquals(Kind.LOCAL_OR_VPN, kind("[fd7a:115c:a1e0:ab12:4843:cd96:6258:b240]"))
        assertEquals(Kind.OTHER, kind("fe00::"))
        // Link-local fe80::/10.
        assertEquals(Kind.OTHER, kind("fe7f:ffff:ffff:ffff:ffff:ffff:ffff:ffff"))
        assertEquals(Kind.LOCAL_NETWORK, kind("fe80::"))
        assertEquals(Kind.LOCAL_NETWORK, kind("fe80::1"))
        assertEquals(Kind.LOCAL_NETWORK, kind("FEBF:FFFF:FFFF:FFFF:FFFF:FFFF:FFFF:FFFF"))
        // The deprecated site-local fec0::/10 is not on the platform's list.
        assertEquals(Kind.OTHER, kind("fec0::1"))
        // Multicast ff00::/8.
        assertEquals(Kind.LOCAL_NETWORK, kind("ff02::1"))
        assertEquals(Kind.LOCAL_NETWORK, kind("ff00::"))
    }

    @Test
    fun urlHostFormsOfIpv6() {
        assertEquals(Kind.LOCAL_NETWORK, kind("[fe80::1]"))
        // A zone, as a URL may carry one for a link-local server.
        assertEquals(Kind.LOCAL_NETWORK, kind("[fe80::1%wlan0]"))
        assertEquals(Kind.LOCAL_NETWORK, kind("fe80::1%25wlan0"))
        assertEquals(Kind.LOCAL_OR_VPN, kind("fd00:0:0:0:0:0:0:1"))
        assertEquals(Kind.LOCAL_OR_VPN, kind("[FD00::ABCD]"))
    }

    @Test
    fun ipv4MappedIpv6IsClassifiedByItsIpv4() {
        assertEquals(Kind.LOCAL_NETWORK, kind("::ffff:192.168.1.20"))
        assertEquals(Kind.LOCAL_NETWORK, kind("[::ffff:10.0.0.1]"))
        assertEquals(Kind.LOCAL_NETWORK, kind("::ffff:c0a8:0114"))
        assertEquals(Kind.LOOPBACK, kind("::ffff:127.0.0.1"))
        assertEquals(Kind.LOCAL_OR_VPN, kind("::ffff:100.64.0.1"))
        assertEquals(Kind.OTHER, kind("::ffff:100.128.0.1"))
        assertEquals(Kind.OTHER, kind("::ffff:8.8.8.8"))
        // The raw 16 bytes, as a resolver may hand them over.
        val mapped = ByteArray(16).also {
            it[10] = 0xFF.toByte(); it[11] = 0xFF.toByte()
            v4(172, 16, 5, 4).copyInto(it, 12)
        }
        assertEquals(Kind.LOCAL_NETWORK, LocalNetworkAccess.kindOf(mapped))
        // ::a.b.c.d (IPv4-compatible) is not a mapped address.
        assertEquals(Kind.OTHER, kind("::192.168.1.1"))
    }

    @Test
    fun literalsParseWithoutLookingAnythingUp() {
        assertEquals(InetAddress.getByAddress(v4(192, 168, 1, 20)), LocalNetworkAccess.literal("192.168.1.20"))
        assertNotNull(LocalNetworkAccess.literal("1:2:3:4:5:6:7:8"))
        assertNotNull(LocalNetworkAccess.literal("1::"))
        assertNotNull(LocalNetworkAccess.literal("::"))
        for (notLiteral in listOf(
            "", "nas", "nas.lan", "192.168.1", "192.168.1.256", "192.168.1.1.1", "1.2.3.-4",
            "1.2.3.4a", "fe80::1::2", ":1:2:3:4:5:6:7", "1:2:3:4:5:6:7:8:9", "1:2:3:4:5:6:7",
            "12345::1", "g::1", "1:::2", "abc:def", "ollama.local", "[nas]"
        )) {
            assertNull(notLiteral, LocalNetworkAccess.literal(notLiteral))
        }
    }

    @Test
    fun namesGoByMdnsOrByTheirAddresses() {
        assertEquals(Kind.LOCAL_NETWORK, kind("ollama.local"))
        assertEquals(Kind.LOCAL_NETWORK, kind("Ollama.LOCAL."))
        assertTrue(LocalNetworkAccess.isMdnsName("box.local"))
        assertFalse(LocalNetworkAccess.isMdnsName(".local"))
        assertFalse(LocalNetworkAccess.isMdnsName("local"))
        assertFalse(LocalNetworkAccess.isMdnsName("notlocal"))
        assertFalse(LocalNetworkAccess.isMdnsName("box.localhost"))
        // Any other name: unknown until it is resolved.
        assertEquals(Kind.OTHER, kind("nas.lan"))
        val lan = InetAddress.getByAddress(v4(192, 168, 1, 20))
        val public = InetAddress.getByAddress(v4(93, 184, 216, 34))
        val loop = InetAddress.getByAddress(v4(127, 0, 0, 1))
        assertEquals(Kind.LOCAL_NETWORK, LocalNetworkAccess.hostKind("nas.lan", listOf(lan)))
        // Any local answer counts: the connection may take that one.
        assertEquals(Kind.LOCAL_NETWORK, LocalNetworkAccess.hostKind("mixed.example", listOf(public, lan)))
        assertEquals(Kind.OTHER, LocalNetworkAccess.hostKind("example.com", listOf(public)))
        assertEquals(Kind.LOOPBACK, LocalNetworkAccess.hostKind("me.internal", listOf(loop)))
        assertEquals(Kind.OTHER, LocalNetworkAccess.hostKind("mixed.internal", listOf(loop, public)))
        // A MagicDNS name resolving to a Tailscale address.
        val tailnet = InetAddress.getByAddress(v4(100, 101, 102, 103))
        assertEquals(Kind.LOCAL_OR_VPN, LocalNetworkAccess.hostKind("box.tail1234.ts.net", listOf(tailnet)))
        // Plainly local ahead of maybe-VPN, whatever the order.
        assertEquals(Kind.LOCAL_NETWORK, LocalNetworkAccess.hostKind("both.example", listOf(tailnet, lan)))
        assertEquals(Kind.LOCAL_OR_VPN, LocalNetworkAccess.hostKind("vpn.example", listOf(public, tailnet)))
        // A literal is its own answer, whatever a resolver said.
        assertEquals(Kind.OTHER, LocalNetworkAccess.hostKind("8.8.8.8", listOf(lan)))
    }

    @Test
    fun theSwitchNeedsAndroid17AndATarget37Build() {
        assertFalse(LocalNetworkAccess.applies(sdkInt = 36, targetSdk = 36))
        assertFalse(LocalNetworkAccess.applies(sdkInt = 37, targetSdk = 36))
        assertFalse(LocalNetworkAccess.applies(sdkInt = 36, targetSdk = 37))
        assertFalse(LocalNetworkAccess.applies(sdkInt = 26, targetSdk = 36))
        assertTrue(LocalNetworkAccess.applies(sdkInt = 37, targetSdk = 37))
        assertTrue(LocalNetworkAccess.applies(sdkInt = 38, targetSdk = 37))
        assertEquals("android.permission.ACCESS_LOCAL_NETWORK", LocalNetworkAccess.PERMISSION)
    }

    @Test
    fun askOnlyForALocalHostWhenItApplies() {
        assertTrue(LocalNetworkAccess.shouldRequest(true, false, Kind.LOCAL_NETWORK))
        // Erring towards local: a maybe-VPN host is asked for too.
        assertTrue(LocalNetworkAccess.shouldRequest(true, false, Kind.LOCAL_OR_VPN))
        assertFalse(LocalNetworkAccess.shouldRequest(true, true, Kind.LOCAL_OR_VPN))
        assertFalse(LocalNetworkAccess.shouldRequest(false, false, Kind.LOCAL_OR_VPN))
        assertFalse(LocalNetworkAccess.shouldRequest(true, true, Kind.LOCAL_NETWORK))
        assertFalse(LocalNetworkAccess.shouldRequest(false, false, Kind.LOCAL_NETWORK))
        assertFalse(LocalNetworkAccess.shouldRequest(true, false, Kind.LOOPBACK))
        assertFalse(LocalNetworkAccess.shouldRequest(true, false, Kind.OTHER))
    }

    @Test
    fun reachabilityFailuresAreToldFromOthers() {
        assertTrue(LocalNetworkAccess.isReachabilityFailure(SocketTimeoutException("failed to connect")))
        assertTrue(LocalNetworkAccess.isReachabilityFailure(ConnectException("ECONNREFUSED")))
        assertTrue(LocalNetworkAccess.isReachabilityFailure(NoRouteToHostException("EHOSTUNREACH")))
        assertTrue(
            LocalNetworkAccess.isReachabilityFailure(
                SocketException("connect failed: EPERM (Operation not permitted)")
            )
        )
        assertTrue(
            LocalNetworkAccess.isReachabilityFailure(
                IOException("wrapped", SocketTimeoutException("after 20000ms"))
            )
        )
        assertFalse(LocalNetworkAccess.isReachabilityFailure(SSLHandshakeException("bad certificate")))
        assertFalse(
            LocalNetworkAccess.isReachabilityFailure(
                SSLHandshakeException("handshake").apply { initCause(SocketTimeoutException("read")) }
            )
        )
        assertFalse(LocalNetworkAccess.isReachabilityFailure(IOException("HTTP 500")))
        assertFalse(LocalNetworkAccess.isReachabilityFailure(RuntimeException("no choices")))
    }

    @Test
    fun failuresMapToHints() {
        val cause = SocketTimeoutException("failed to connect to /192.168.1.20 (port 11434) after 20000ms")
        val lan = EndpointUnreachableException("192.168.1.20", Kind.LOCAL_NETWORK, cause)
        // The original message is kept, so screens that show it read as before.
        assertEquals(cause.message, lan.message)
        assertSame(cause, lan.cause)

        assertEquals(Hint.CHECK_NETWORK, LocalNetworkAccess.hintFor(lan, applies = false, granted = false))
        assertEquals(Hint.CHECK_NETWORK, LocalNetworkAccess.hintFor(lan, applies = true, granted = true))
        assertEquals(Hint.ALLOW_ACCESS, LocalNetworkAccess.hintFor(lan, applies = true, granted = false))
        // Wrapped further up the way (a summary run's own exception).
        val wrapped = RuntimeException("summary failed", lan)
        assertEquals(Hint.ALLOW_ACCESS, LocalNetworkAccess.hintFor(wrapped, applies = true, granted = false))
        assertSame(lan, LocalNetworkAccess.unreachableIn(wrapped))

        // A Tailscale or ULA host: the network hint whatever the permission,
        // never the permission dialog (a VPN is not what it covers).
        val vpn = EndpointUnreachableException("100.101.102.103", Kind.LOCAL_OR_VPN, cause)
        assertEquals(Hint.CHECK_NETWORK, LocalNetworkAccess.hintFor(vpn, applies = false, granted = false))
        assertEquals(Hint.CHECK_NETWORK, LocalNetworkAccess.hintFor(vpn, applies = true, granted = false))
        assertEquals(Hint.CHECK_NETWORK, LocalNetworkAccess.hintFor(vpn, applies = true, granted = true))
        val ulaHost = "[fd7a:115c:a1e0::1]"
        val ula = EndpointUnreachableException(ulaHost, LocalNetworkAccess.hostKind(ulaHost), cause)
        assertEquals(Hint.CHECK_NETWORK, LocalNetworkAccess.hintFor(ula, applies = true, granted = false))

        val cloud = EndpointUnreachableException("api.example.com", Kind.OTHER, cause)
        assertEquals(Hint.NONE, LocalNetworkAccess.hintFor(cloud, applies = true, granted = false))
        val loopback = EndpointUnreachableException("localhost", Kind.LOOPBACK, cause)
        assertEquals(Hint.NONE, LocalNetworkAccess.hintFor(loopback, applies = true, granted = false))
        // Not a connect failure at all.
        assertEquals(Hint.NONE, LocalNetworkAccess.hintFor(cause, applies = true, granted = false))
        assertEquals(
            Hint.NONE,
            LocalNetworkAccess.hintFor(RuntimeException("LLM endpoint returned HTTP 500"), true, false)
        )
    }

    /** Unit tests run from the module directory; the others are fallbacks. */
    private fun moduleFile(path: String): File {
        val candidates = listOf(path, "app/$path", "android/app/$path")
        return candidates.map(::File).firstOrNull { it.isFile }
            ?: run {
                fail("$path not found from ${File(".").absolutePath}")
                throw IllegalStateException()
            }
    }

    /**
     * The other half of the switch. The docs say an app targeting 36 or lower
     * must not declare ACCESS_LOCAL_NETWORK, and one targeting 37 must; the
     * day build.gradle.kts moves to 37, this fails until the manifest line
     * is added.
     */
    @Test
    fun manifestDeclarationFollowsTheTargetSdk() {
        val gradle = moduleFile("build.gradle.kts").readText()
        val target = Regex("""targetSdk\s*=\s*(\d+)""").find(gradle)?.groupValues?.get(1)?.toInt()
            ?: run {
                fail("no targetSdk in build.gradle.kts")
                return
            }
        val manifest = moduleFile("src/main/AndroidManifest.xml").readText()
        val declared = Regex(
            """<uses-permission[^>]*android:name="${Regex.escape(LocalNetworkAccess.PERMISSION)}""""
        ).containsMatchIn(manifest)
        if (target >= LocalNetworkAccess.FIRST_API) {
            assertTrue("targetSdk $target: declare ${LocalNetworkAccess.PERMISSION}", declared)
        } else {
            assertFalse("targetSdk $target: must not declare ${LocalNetworkAccess.PERMISSION}", declared)
        }
    }
}
