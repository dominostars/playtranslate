package com.playtranslate.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CustomEndpointPolicy]: https to any host; http only to loopback/private/LAN.
 * Guards against a typo'd or pasted public http URL leaking the OpenAI key in
 * cleartext, while keeping self-hosted LAN servers usable.
 */
class CustomEndpointPolicyTest {

    @Test fun `https is accepted for any host`() {
        assertTrue(CustomEndpointPolicy.isAcceptable("https://api.openai.com/v1"))
        assertTrue(CustomEndpointPolicy.isAcceptable("https://my-proxy.example.com:8443/v1"))
        assertTrue(CustomEndpointPolicy.isAcceptable("https://192.168.1.50:1234/v1"))
    }

    @Test fun `http to a public host is rejected`() {
        assertFalse(CustomEndpointPolicy.isAcceptable("http://api.openai.com/v1"))
        assertFalse(CustomEndpointPolicy.isAcceptable("http://example.com/v1"))
        assertFalse(CustomEndpointPolicy.isAcceptable("http://8.8.8.8:1234/v1"))
        assertFalse(CustomEndpointPolicy.isAcceptable("http://172.32.0.1/v1")) // 172.32 is public
    }

    @Test fun `http to loopback and LAN addresses is accepted`() {
        assertTrue(CustomEndpointPolicy.isAcceptable("http://localhost:1234/v1"))
        assertTrue(CustomEndpointPolicy.isAcceptable("http://127.0.0.1:1234/v1"))
        assertTrue(CustomEndpointPolicy.isAcceptable("http://10.0.0.5:1234/v1"))
        assertTrue(CustomEndpointPolicy.isAcceptable("http://192.168.1.50:1234/v1"))
        assertTrue(CustomEndpointPolicy.isAcceptable("http://172.16.0.9:1234/v1"))
        assertTrue(CustomEndpointPolicy.isAcceptable("http://mybox.local:1234/v1"))
    }

    @Test fun `blank, malformed, and non-http schemes are rejected`() {
        assertFalse(CustomEndpointPolicy.isAcceptable(""))
        assertFalse(CustomEndpointPolicy.isAcceptable("   "))
        assertFalse(CustomEndpointPolicy.isAcceptable("not a url"))
        assertFalse(CustomEndpointPolicy.isAcceptable("ftp://localhost/v1"))
    }

    @Test fun `public hostnames with ipv6-private prefixes are rejected`() {
        // Regression: a DNS name that merely starts with fc/fd/fe is NOT an
        // IPv6 unique-local/link-local address (it has no ':').
        assertFalse(CustomEndpointPolicy.isAcceptable("http://fcdn.example.com/v1"))
        assertFalse(CustomEndpointPolicy.isAcceptable("http://fdexample.com/v1"))
        assertFalse(CustomEndpointPolicy.isAcceptable("http://fe80example.com/v1"))
        assertFalse(CustomEndpointPolicy.isAcceptable("http://fc.evil.com/v1"))
    }

    @Test fun `ipv6 literals are classified, not prefix-matched`() {
        assertTrue(CustomEndpointPolicy.isAcceptable("http://[::1]:1234/v1"))
        assertTrue(CustomEndpointPolicy.isAcceptable("http://[fc00::1]:1234/v1"))
        assertTrue(CustomEndpointPolicy.isAcceptable("http://[fd12:3456::1]:1234/v1"))
        assertTrue(CustomEndpointPolicy.isAcceptable("http://[fe80::1]:1234/v1"))
        assertFalse(CustomEndpointPolicy.isAcceptable("http://[2001:db8::1]/v1"))
    }
}
