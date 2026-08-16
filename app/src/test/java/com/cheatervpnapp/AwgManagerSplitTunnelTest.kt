package com.cheatervpnapp

import org.junit.Assert.assertEquals
import org.junit.Test

class AwgManagerSplitTunnelTest {

    private val config = """
        [Interface]
        PrivateKey = abc
        Address = 10.0.0.2/32
        DNS = 1.1.1.1

        [Peer]
        PublicKey = xyz
        Endpoint = vpn.example.com:51820
        AllowedIPs = 0.0.0.0/0
    """.trimIndent()

    @Test
    fun `excluded applications are injected right after interface header`() {
        val result = AwgManager.applySplitTunnel(config, setOf("com.app.b", "com.app.a"), emptySet())
        val lines = result.lines()
        val interfaceIndex = lines.indexOfFirst { it.trim() == "[Interface]" }
        val excludedLine = lines.first { it.trim().startsWith("ExcludedApplications") }

        assertEquals(interfaceIndex + 1, lines.indexOf(excludedLine))
        assertEquals("ExcludedApplications = com.app.a, com.app.b", excludedLine.trim())
        assert(!lines.any { it.trim().startsWith("IncludedApplications") })
    }

    @Test
    fun `included applications are injected`() {
        val result = AwgManager.applySplitTunnel(config, emptySet(), setOf("com.app.a"))
        val lines = result.lines()
        val includedLine = lines.first { it.trim().startsWith("IncludedApplications") }

        assertEquals("IncludedApplications = com.app.a", includedLine.trim())
        assert(!lines.any { it.trim().startsWith("ExcludedApplications") })
    }

    @Test
    fun `empty lists return the original content`() {
        assertEquals(config, AwgManager.applySplitTunnel(config, emptySet(), emptySet()))
    }

    @Test
    fun `content without interface section is returned unchanged`() {
        assertEquals("hello", AwgManager.applySplitTunnel("hello", setOf("a"), emptySet()))
    }

    @Test
    fun `original keys and peer section are preserved`() {
        val result = AwgManager.applySplitTunnel(config, setOf("com.app.a"), emptySet())
        assert(result.contains("PrivateKey = abc"))
        assert(result.contains("Endpoint = vpn.example.com:51820"))
        assert(result.contains("[Peer]"))
    }
}
