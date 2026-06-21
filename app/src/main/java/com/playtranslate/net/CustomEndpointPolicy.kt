package com.playtranslate.net

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Policy for the OpenAI custom base URL — the one user-configurable endpoint.
 *
 * https is allowed to any host. http is allowed ONLY to a loopback / private-LAN
 * / link-local address, so a self-hosted LLM server on the user's machine works
 * (those almost always speak plain http on a LAN IP) while a typo'd or pasted
 * PUBLIC http URL is rejected — otherwise the OpenAI Bearer key would travel in
 * cleartext over the internet. The manifest permits cleartext app-wide (it can't
 * express IP ranges), so THIS code check is what scopes http to the LAN.
 *
 * Pure (OkHttp URL parsing only, no Android/DNS) so it's unit-testable directly.
 */
object CustomEndpointPolicy {

    /** Parse-and-check form for UI input (rejects malformed / non-http(s)). */
    fun isAcceptable(raw: String): Boolean =
        raw.trim().toHttpUrlOrNull()?.let { isAcceptable(it) } ?: false

    /** Request-boundary form (used by the network interceptor): https to any
     *  host, or http only to a loopback / private-LAN / link-local address. */
    fun isAcceptable(url: HttpUrl): Boolean =
        url.isHttps || isPrivateOrLoopbackHost(url.host)

    private fun isPrivateOrLoopbackHost(host: String): Boolean {
        val h = host.lowercase().removeSurrounding("[", "]")
        if (h == "localhost") return true
        if (h.endsWith(".local")) return true          // mDNS, LAN-scoped
        // A ':' means an IPv6 literal (OkHttp strips the port + brackets from
        // the host), so the IPv6 range checks apply ONLY to real literals — a
        // public DNS name like "fdexample.com" / "fcdn.example.com" can never
        // match the fc/fd rule (it has no ':' and isn't four numeric octets).
        return if (h.contains(':')) isLoopbackOrPrivateIpv6(h) else isPrivateIpv4(h)
    }

    /** [h] is a validated IPv6 literal (lowercased, brackets already stripped). */
    private fun isLoopbackOrPrivateIpv6(h: String): Boolean =
        h == "::1" ||                                       // loopback ::1
            h.startsWith("fc") || h.startsWith("fd") ||      // unique-local fc00::/7
            h.startsWith("fe8") || h.startsWith("fe9") ||    // link-local fe80::/10
            h.startsWith("fea") || h.startsWith("feb")

    private fun isPrivateIpv4(h: String): Boolean {
        val parts = h.split(".")
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() }
        if (octets.any { it == null || it < 0 || it > 255 }) return false
        val a = octets[0]!!
        val b = octets[1]!!
        return a == 127 ||                         // loopback 127.0.0.0/8
            a == 10 ||                             // private 10.0.0.0/8
            (a == 192 && b == 168) ||              // private 192.168.0.0/16
            (a == 172 && b in 16..31) ||           // private 172.16.0.0/12
            (a == 169 && b == 254)                 // link-local 169.254.0.0/16
    }
}
