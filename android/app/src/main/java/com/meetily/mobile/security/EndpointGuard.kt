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
 *
 * That last sentence used to be a claim rather than a guarantee: the guard
 * resolved the name to decide, and then the connection resolved it again on
 * its way to the socket, so the address approved and the address that got the
 * transcript did not have to be the same one. [vet] closes that for https by
 * handing the approved addresses to the caller to pin (see
 * PinnedAddressSocketFactory). For cleartext http the platform offers no
 * socket hook, so the second lookup still happens — the protection there is
 * that [vet] re-resolves and refuses unless every answer is private, which
 * narrows the window to the moment between check and connect rather than
 * removing it. Cleartext is confined to the private network by rule 1 either
 * way, and local setups are usually IP literals, which never touch DNS.
 */
object EndpointGuard {

    class BlockedEndpointException(message: String) : Exception(message)

    /**
     * An endpoint that passed [check], together with the addresses the policy
     * actually approved.
     *
     * [pinned] is empty when the policy places no constraint on the address —
     * a cloud endpoint with local-only off. In that case the caller must NOT
     * pin: DNS failover and load balancing are normal there, and freezing the
     * first answer would break setups the guard has no opinion about.
     */
    class Vetted(val scheme: String, val host: String, val pinned: List<InetAddress>)

    /**
     * The policy decision on its own: given the addresses a host resolved to,
     * which ones may be connected to.
     *
     * Split out from resolution so it is unit-testable without DNS — the
     * classification is the part that has to be right.
     */
    fun allowed(
        scheme: String,
        addresses: List<InetAddress>,
        localOnly: Boolean
    ): List<InetAddress> {
        if (!isConstrained(scheme, localOnly)) return emptyList()
        // All or nothing, matching isPrivateHost: a host answering with one
        // private and one public address is the shape of a rebinding attack,
        // not a configuration to accommodate.
        if (addresses.isEmpty() || !addresses.all(::isPrivateAddress)) return emptyList()
        return addresses
    }

    /** True when the policy restricts which address may be connected to. */
    fun isConstrained(scheme: String, localOnly: Boolean): Boolean =
        scheme == "http" || (scheme == "https" && localOnly)

    /**
     * Resolves [baseUrl] ONCE and returns the addresses the policy approves.
     *
     * [check] answers "may this URL be used", and then the connection resolves
     * the name a second time on its way to the socket — so the address that
     * was approved and the address that receives the transcript need not be
     * the same one. The guard's own doc promises that hostnames "must resolve
     * to private addresses on every A/AAAA record"; nothing was enforcing that
     * at the moment it mattered. Callers pass [Vetted.pinned] down to the
     * socket so the address used IS the address checked.
     */
    fun vet(baseUrl: String, localOnly: Boolean): Vetted {
        EndpointGuard.check(baseUrl, localOnly)
        val uri = URI(baseUrl.trim())
        val scheme = uri.scheme!!.lowercase()
        val host = uri.host!!
        if (!isConstrained(scheme, localOnly)) {
            // The policy has no opinion on the address here — a cloud endpoint
            // with local-only off. Pinning would freeze the first DNS answer
            // and break failover and load balancing for no security gain.
            return Vetted(scheme, host, emptyList())
        }
        val resolved = try {
            InetAddress.getAllByName(host).toList()
        } catch (e: Exception) {
            emptyList()
        }
        val pinned = allowed(scheme, resolved, localOnly)
        if (pinned.isEmpty()) {
            // Constrained, and nothing came back that the policy allows. Either
            // the name stopped resolving between the two lookups, or it now
            // answers with an address outside the private ranges — which is
            // precisely the case this exists to catch. Refuse rather than let
            // the connection do its own lookup and go wherever that leads.
            throw BlockedEndpointException(
                "$host no longer resolves to an address on your private " +
                    "network. Nothing was sent."
            )
        }
        return Vetted(scheme, host, pinned)
    }

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
