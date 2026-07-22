package com.meetily.mobile.security

import java.net.InetAddress
import java.net.URI

/**
 * Gatekeeper for the single place transcript text can leave the device: the
 * OpenAI-compatible LLM endpoint. Two rules, checked before every request:
 *
 *  1. Cleartext http:// is only ever allowed to loopback / private-network
 *     addresses, regardless of settings — an http endpoint on the public
 *     internet would ship transcripts unencrypted.
 *  2. When local-only mode is on (the default), https endpoints must also be
 *     loopback / private-network, so no cloud provider can receive meeting
 *     content unless the user has explicitly opted out in Settings.
 *
 * "Private" means loopback, link-local, RFC 1918 (10/8, 172.16/12,
 * 192.168/16), IPv6 unique-local (fc00::/7), or CGNAT 100.64/10 — the last
 * so a home Ollama box reached over Tailscale/WireGuard still counts as
 * local. Hostnames must resolve to private addresses on every A/AAAA record.
 */
object EndpointGuard {

    class BlockedEndpointException(message: String) : Exception(message)

    fun check(baseUrl: String, localOnly: Boolean) {
        val uri = try {
            URI(baseUrl.trim())
        } catch (e: Exception) {
            throw BlockedEndpointException("AI endpoint URL is invalid: $baseUrl")
        }
        val host = uri.host
            ?: throw BlockedEndpointException("AI endpoint URL has no host: $baseUrl")
        when (uri.scheme?.lowercase()) {
            "https" -> {
                if (localOnly && !isPrivateHost(host)) {
                    throw BlockedEndpointException(
                        "Local-only AI is on, and $host is not on your private " +
                            "network. Use a local endpoint, or turn off Local-only " +
                            "AI in Settings to allow cloud providers."
                    )
                }
            }
            "http" -> {
                if (!isPrivateHost(host)) {
                    throw BlockedEndpointException(
                        "Plain http is only allowed to private-network endpoints. " +
                            "Use https for $host."
                    )
                }
            }
            else -> throw BlockedEndpointException(
                "AI endpoint must start with http:// or https://"
            )
        }
    }

    /**
     * True when every address the host resolves to is private. IP literals
     * never touch DNS; a resolution failure counts as not private.
     */
    fun isPrivateHost(host: String): Boolean {
        if (host.equals("localhost", ignoreCase = true)) return true
        return try {
            InetAddress.getAllByName(host).let { addrs ->
                addrs.isNotEmpty() && addrs.all(::isPrivateAddress)
            }
        } catch (e: Exception) {
            false
        }
    }

    fun isPrivateAddress(addr: InetAddress): Boolean {
        if (addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isSiteLocalAddress) {
            return true
        }
        val bytes = addr.address
        // IPv6 unique-local, fc00::/7.
        if (bytes.size == 16 && (bytes[0].toInt() and 0xFE) == 0xFC) return true
        // CGNAT 100.64.0.0/10 (Tailscale and friends).
        if (bytes.size == 4 &&
            (bytes[0].toInt() and 0xFF) == 100 &&
            (bytes[1].toInt() and 0xC0) == 0x40
        ) {
            return true
        }
        return false
    }
}
