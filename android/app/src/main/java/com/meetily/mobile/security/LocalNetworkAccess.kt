package com.meetily.mobile.security

import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException

/**
 * Android 17's local network permission, as it bears on the AI endpoint a
 * user may run on their own network (an Ollama box at http://192.168.x.y).
 *
 * What the platform does, per developer.android.com ("Local network
 * permission", "Local Network Definition", Android 17 behaviour changes):
 *
 *  - ACCESS_LOCAL_NETWORK is a runtime permission in the NEARBY_DEVICES
 *    group, defined from API 37 (Android 17). A user who already allowed
 *    another permission of that group is not asked again.
 *  - It is enforced only for apps targeting 37 or higher. An app targeting
 *    36 or lower that holds INTERNET is granted it implicitly, and the docs
 *    say such an app must neither declare it nor request it.
 *  - Without it, an outgoing TCP connection to a local address typically
 *    times out (the packets are dropped) and UDP fails with EPERM.
 *  - "Local" is a broadcast-capable network (Wi-Fi, Ethernet; not cellular
 *    or VPN): 169.254/16, 100.64/10, 10/8, 172.16/12, 192.168/16, IPv6
 *    link-local and the directly connected routes, plus multicast and the
 *    IPv4 broadcast address. Resolving a ".local" name (mDNS) needs it too.
 *    Loopback does not.
 *
 * Recap targets 36, so nothing here changes what the app does today:
 * [applies] is false on every phone, nothing is requested, nothing is
 * declared. [applies] is THE switch for the targetSdk 37 bump. It reads the
 * running app's own target, so it turns on by itself with the bump, and
 * LocalNetworkAccessTest then fails until the manifest declares the
 * permission (and fails now if it is declared early).
 *
 * The classifier errs towards "local": the cost of a wrong yes is one
 * permission prompt, of a wrong no a connection that silently times out.
 * Two ranges are local to the platform only when they are on the Wi-Fi or
 * Ethernet itself: 100.64/10 and fc00::/7 are also what overlay VPNs hand
 * out (Tailscale's 100.x addresses and fd7a:115c:a1e0::/48), and a VPN is
 * not the local network. Those are [Kind.LOCAL_OR_VPN]: asked for like any
 * local address, but a failure to reach one never blames the permission.
 * Pure Kotlin, unit-tested.
 */
object LocalNetworkAccess {

    /** android.Manifest.permission.ACCESS_LOCAL_NETWORK, absent from android-36.jar. */
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    /** Android 17: the first API level that defines and enforces [PERMISSION]. */
    const val FIRST_API = 37

    /**
     * Whether local network access depends on [PERMISSION] for this app on
     * this phone: Android 17 or later AND a build targeting 37 or later.
     */
    fun applies(sdkInt: Int, targetSdk: Int): Boolean =
        sdkInt >= FIRST_API && targetSdk >= FIRST_API

    /** Where a connection to an address goes, as the permission sees it. */
    enum class Kind {
        LOOPBACK,

        /** A local network address, and only that: RFC 1918, link-local, multicast, .local. */
        LOCAL_NETWORK,

        /**
         * Local when it is on the Wi-Fi or Ethernet, but just as often a VPN
         * address (CGNAT 100.64/10 and unique local fc00::/7, the ranges
         * Tailscale and many WireGuard setups use), which the permission
         * does not cover.
         */
        LOCAL_OR_VPN,

        OTHER;

        /** Whether reaching it may need [PERMISSION]. */
        val mayNeedPermission: Boolean
            get() = this == LOCAL_NETWORK || this == LOCAL_OR_VPN
    }

    /** [address] as 4 (IPv4) or 16 (IPv6) bytes in network order. */
    fun kindOf(address: ByteArray): Kind {
        val ipv4 = when {
            address.size == 4 -> address
            address.size == 16 && isMappedIpv4(address) -> address.copyOfRange(12, 16)
            address.size == 16 -> return kindOfIpv6(address)
            else -> return Kind.OTHER
        }
        return kindOfIpv4(ipv4)
    }

    fun kindOf(address: InetAddress): Kind = kindOf(address.address)

    private fun kindOfIpv4(b: ByteArray): Kind {
        val a0 = b[0].toInt() and 0xFF
        val a1 = b[1].toInt() and 0xFF
        val all = b.all { (it.toInt() and 0xFF) == 0xFF }
        return when {
            a0 == 127 -> Kind.LOOPBACK
            a0 == 10 -> Kind.LOCAL_NETWORK
            a0 == 172 && a1 in 16..31 -> Kind.LOCAL_NETWORK
            a0 == 192 && a1 == 168 -> Kind.LOCAL_NETWORK
            a0 == 169 && a1 == 254 -> Kind.LOCAL_NETWORK
            // Shared address space (CGNAT), listed by the platform as local,
            // and Tailscale's own range.
            a0 == 100 && a1 in 64..127 -> Kind.LOCAL_OR_VPN
            // Multicast 224.0.0.0/4.
            a0 in 224..239 -> Kind.LOCAL_NETWORK
            all -> Kind.LOCAL_NETWORK
            else -> Kind.OTHER
        }
    }

    private fun kindOfIpv6(b: ByteArray): Kind {
        val b0 = b[0].toInt() and 0xFF
        val b1 = b[1].toInt() and 0xFF
        val loopback = (0 until 15).all { b[it].toInt() == 0 } && b[15].toInt() == 1
        return when {
            loopback -> Kind.LOOPBACK
            // Link-local fe80::/10.
            b0 == 0xFE && (b1 and 0xC0) == 0x80 -> Kind.LOCAL_NETWORK
            // Unique local fc00::/7: the home network's own prefix (a
            // directly connected route), or a VPN's (Tailscale's fd7a::).
            (b0 and 0xFE) == 0xFC -> Kind.LOCAL_OR_VPN
            // Multicast ff00::/8.
            b0 == 0xFF -> Kind.LOCAL_NETWORK
            else -> Kind.OTHER
        }
    }

    /** ::ffff:a.b.c.d */
    private fun isMappedIpv4(b: ByteArray): Boolean =
        (0 until 10).all { b[it].toInt() == 0 } &&
            (b[10].toInt() and 0xFF) == 0xFF && (b[11].toInt() and 0xFF) == 0xFF

    /** A multicast DNS name, answered only by devices on the local network. */
    fun isMdnsName(host: String): Boolean {
        val name = host.trim().trimEnd('.').lowercase()
        return name.endsWith(".local") && name.length > ".local".length
    }

    /**
     * [host] as an address when it is an IP literal (a URL's host, brackets
     * and an IPv6 zone allowed), else null. Parsed here rather than by the
     * platform, whose parser falls back to a DNS lookup on anything it does
     * not read as a number: nothing can be looked up from here.
     */
    fun literal(host: String): InetAddress? {
        var h = host.trim()
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length - 1)
        h = h.substringBefore('%')
        val bytes = parseIpv4(h) ?: parseIpv6(h) ?: return null
        return InetAddress.getByAddress(bytes)
    }

    private fun parseIpv4(text: String): ByteArray? {
        val parts = text.split('.')
        if (parts.size != 4) return null
        val out = ByteArray(4)
        for ((i, part) in parts.withIndex()) {
            if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return null
            val value = part.toInt()
            if (value > 255) return null
            out[i] = value.toByte()
        }
        return out
    }

    private fun isHex(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    /** RFC 4291 text forms: full, "::"-compressed, and with an IPv4 tail. */
    private fun parseIpv6(text: String): ByteArray? {
        if (text.count { it == ':' } < 2) return null
        var s = text
        var tail: ByteArray? = null
        val lastColon = s.lastIndexOf(':')
        if ('.' in s.substring(lastColon + 1)) {
            tail = parseIpv4(s.substring(lastColon + 1)) ?: return null
            s = s.substring(0, lastColon + 1)
            if (!s.endsWith("::")) s = s.dropLast(1)
        }
        val halves = s.split("::")
        if (halves.size > 2) return null
        val left = hexGroups(halves[0]) ?: return null
        val right = if (halves.size == 2) hexGroups(halves[1]) ?: return null else emptyList()
        val explicit = left.size + right.size + if (tail != null) 2 else 0
        if (halves.size == 1 && explicit != 8) return null
        if (halves.size == 2 && explicit > 7) return null
        val groups = left + List(if (halves.size == 2) 8 - explicit else 0) { 0 } + right
        val out = ByteArray(16)
        for ((i, g) in groups.withIndex()) {
            out[2 * i] = (g shr 8).toByte()
            out[2 * i + 1] = g.toByte()
        }
        tail?.copyInto(out, 12)
        return out
    }

    private fun hexGroups(part: String): List<Int>? {
        if (part.isEmpty()) return emptyList()
        return part.split(':').map { g ->
            if (g.isEmpty() || g.length > 4 || !g.all(::isHex)) return null
            g.toInt(16)
        }
    }

    /**
     * Where [host] leads: "localhost", an IP literal or a ".local" name on
     * their own; any other name by the addresses it [resolved] to, where the
     * caller has them (EndpointGuard resolves constrained endpoints already).
     * Local when ANY address is: the connection may use any of them; plainly
     * local ahead of [Kind.LOCAL_OR_VPN].
     */
    fun hostKind(host: String, resolved: List<InetAddress> = emptyList()): Kind {
        val name = host.trim().trimEnd('.')
        if (name.equals("localhost", ignoreCase = true)) return Kind.LOOPBACK
        literal(name)?.let { return kindOf(it) }
        if (isMdnsName(name)) return Kind.LOCAL_NETWORK
        val kinds = resolved.map(::kindOf)
        return when {
            Kind.LOCAL_NETWORK in kinds -> Kind.LOCAL_NETWORK
            Kind.LOCAL_OR_VPN in kinds -> Kind.LOCAL_OR_VPN
            kinds.isNotEmpty() && kinds.all { it == Kind.LOOPBACK } -> Kind.LOOPBACK
            else -> Kind.OTHER
        }
    }

    /** Whether saving an endpoint on a [kind] host should ask for [PERMISSION] now. */
    fun shouldRequest(applies: Boolean, granted: Boolean, kind: Kind): Boolean =
        applies && !granted && kind.mayNeedPermission

    /**
     * True for a failure to reach the server at all: a connect that timed
     * out, was refused, had no route, or was denied (EPERM, what a blocked
     * local destination can also report). A TLS failure is not one; that
     * server was reached.
     */
    fun isReachabilityFailure(error: Throwable): Boolean {
        var e: Throwable? = error
        var depth = 0
        while (e != null && depth < MAX_CAUSES) {
            when (e) {
                is SSLException -> return false
                is SocketTimeoutException,
                is ConnectException,
                is NoRouteToHostException,
                is PortUnreachableException -> return true
            }
            val message = e.message.orEmpty()
            if (e is IOException && ("EPERM" in message || "Operation not permitted" in message)) {
                return true
            }
            e = e.cause
            depth++
        }
        return false
    }

    /** The [EndpointUnreachableException] in [error]'s cause chain, if any. */
    fun unreachableIn(error: Throwable): EndpointUnreachableException? {
        var e: Throwable? = error
        var depth = 0
        while (e != null && depth < MAX_CAUSES) {
            if (e is EndpointUnreachableException) return e
            e = e.cause
            depth++
        }
        return null
    }

    /** What to tell the user about a failed AI request. */
    enum class Hint {
        /** Nothing about the local network: the failure's own message stands. */
        NONE,

        /**
         * A local or VPN server could not be reached: same Wi-Fi, or VPN
         * connected? Server running?
         */
        CHECK_NETWORK,

        /** A local server, and the permission is what stands in the way. */
        ALLOW_ACCESS
    }

    /**
     * What [error] says about the local network. A [Kind.LOCAL_OR_VPN] host
     * is never [Hint.ALLOW_ACCESS]: over a VPN the permission is not what
     * stopped it, and the network check covers both cases.
     */
    fun hintFor(error: Throwable, applies: Boolean, granted: Boolean): Hint {
        val unreachable = unreachableIn(error) ?: return Hint.NONE
        return when (unreachable.kind) {
            Kind.LOCAL_NETWORK ->
                if (applies && !granted) Hint.ALLOW_ACCESS else Hint.CHECK_NETWORK
            Kind.LOCAL_OR_VPN -> Hint.CHECK_NETWORK
            Kind.LOOPBACK, Kind.OTHER -> Hint.NONE
        }
    }

    private const val MAX_CAUSES = 8
}

/**
 * A request that never reached [host]: the connect itself failed (see
 * [LocalNetworkAccess.isReachabilityFailure]). [kind] is where [host] leads,
 * as far as the caller knew without a new lookup. The message is the
 * original failure's, so every screen that shows it reads as before.
 */
class EndpointUnreachableException(
    val host: String,
    val kind: LocalNetworkAccess.Kind,
    cause: IOException
) : IOException(cause.message, cause)
