package com.cheatervpnapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerTest {

    @Test
    fun `parseEndpoint parses hostname and port`() {
        assertEquals("vpn.example.com" to 51820, Server.parseEndpoint("Endpoint = vpn.example.com:51820"))
    }

    @Test
    fun `parseEndpoint parses ipv4`() {
        assertEquals("203.0.113.10" to 51820, Server.parseEndpoint("Endpoint = 203.0.113.10:51820"))
    }

    @Test
    fun `parseEndpoint parses bracketed ipv6`() {
        assertEquals("2001:db8::1" to 443, Server.parseEndpoint("Endpoint = [2001:db8::1]:443"))
    }

    @Test
    fun `parseEndpoint returns null when absent or malformed`() {
        assertNull(Server.parseEndpoint("PrivateKey = abc"))
        assertNull(Server.parseEndpoint("Endpoint = bad"))
        assertNull(Server.parseEndpoint(""))
    }

    @Test
    fun `flagEmoji returns globe for invalid country code`() {
        assertEquals("\uD83C\uDF10", Server.flagEmoji(""))
        assertEquals("\uD83C\uDF10", Server.flagEmoji("USA"))
        assertEquals("\uD83C\uDF10", Server.flagEmoji("R"))
    }

    @Test
    fun `flagEmoji converts country code`() {
        assertEquals("\uD83C\uDDF7\uD83C\uDDFA", Server.flagEmoji("ru"))
        assertEquals("\uD83C\uDDFA\uD83C\uDDF8", Server.flagEmoji("us"))
    }
}
