package com.meetily.mobile.security

import java.net.InetAddress
import java.net.Socket
import javax.net.ssl.SSLSocketFactory

/**
 * A TLS socket factory that connects only to an address [EndpointGuard]
 * already approved.
 *
 * The gap this closes: the guard resolved the hostname to decide whether the
 * endpoint was on the private network, and then HttpsURLConnection resolved
 * the same name again on its way to the socket. Two lookups, two answers — so
 * a hostname whose record flips between a LAN address and a public one (split
 * horizon, a short TTL, an operator who simply changes it) could pass the
 * check and then receive the transcript somewhere else entirely, with the
 * "Local-only AI" switch still showing on.
 *
 * This does NOT touch certificate or hostname verification, and must not.
 * The plain socket is connected to the vetted address, then handed to the
 * platform factory together with the real hostname — so SNI carries the
 * hostname, and HttpsURLConnection still verifies the certificate against the
 * hostname from the URL. Pinning the address makes the connection go where the
 * guard looked; TLS still proves the server is who it claims to be. Both
 * checks apply, neither replaces the other.
 */
class PinnedAddressSocketFactory(
    private val delegate: SSLSocketFactory,
    private val allowed: List<InetAddress>,
    private val connectTimeoutMs: Int = 20_000
) : SSLSocketFactory() {

    init {
        require(allowed.isNotEmpty()) { "PinnedAddressSocketFactory needs an address" }
    }

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    /**
     * Connect-and-wrap path, for a client that asks the factory to connect.
     * [host] is the hostname from the URL; it is passed through to the
     * delegate so SNI and the default hostname verifier see the real name,
     * while the bytes go to a vetted address.
     *
     * Android's HttpsURLConnection does NOT take this path: it connects its
     * own raw socket and layers TLS on it through the overload below, so
     * that check is the one enforcing the address there. The caller opens
     * constrained connections with Proxy.NO_PROXY, so that raw socket goes
     * straight to the endpoint rather than to a proxy.
     */
    override fun createSocket(host: String, port: Int): Socket {
        val plain = Socket()
        try {
            plain.connect(java.net.InetSocketAddress(pick(), port), connectTimeoutMs)
        } catch (e: Throwable) {
            try {
                plain.close()
            } catch (_: Exception) {
            }
            throw e
        }
        return delegate.createSocket(plain, host, port, true)
    }

    override fun createSocket(host: InetAddress, port: Int): Socket =
        createSocket(host.hostAddress ?: host.hostName, port)

    override fun createSocket(
        host: String,
        port: Int,
        localHost: InetAddress?,
        localPort: Int
    ): Socket = createSocket(host, port)

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress?,
        localPort: Int
    ): Socket = createSocket(address.hostAddress ?: address.hostName, port)

    /**
     * Layering onto a socket somebody else connected: that socket's address
     * was never vetted, so it is only allowed through if it happens to be one
     * of ours. On Android this is where the pin is enforced (see above).
     */
    override fun createSocket(
        socket: Socket,
        host: String,
        port: Int,
        autoClose: Boolean
    ): Socket {
        val remote = socket.inetAddress
        if (remote == null || allowed.none { it == remote }) {
            // Name both ends: a mismatch is more often a VPN or proxy in the
            // way than an attack, and the user needs to be able to tell.
            val actual = remote?.hostAddress ?: "an unknown address"
            val checked = allowed.joinToString { it.hostAddress ?: it.toString() }
            throw EndpointGuard.BlockedEndpointException(
                "Refusing to send: connected to $actual, not the checked " +
                    "address ($checked). Nothing was sent."
            )
        }
        return delegate.createSocket(socket, host, port, autoClose)
    }

    /**
     * Prefers whichever address family the resolver put first — the same
     * ordering the platform would have used — rather than imposing one.
     */
    private fun pick(): InetAddress = allowed.first()
}
