package com.cheatervpnapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class XrayConfigTest {

    @Test
    fun `isXrayLink detects supported schemes`() {
        assertTrue(XrayConfig.isXrayLink("vmess://eyJhZGQiOiIxLjIuMy40In0="))
        assertTrue(XrayConfig.isXrayLink("vless://uuid@example.com:443"))
        assertTrue(XrayConfig.isXrayLink("trojan://pass@example.com:443"))
        assertTrue(XrayConfig.isXrayLink("ss://YWVzLTI1Ni1nY206cGFzcw=="))
        assertFalse(XrayConfig.isXrayLink("https://example.com"))
        assertFalse(XrayConfig.isXrayLink(""))
    }

    @Test
    fun `parse vmess base64 link`() {
        val json = JSONObject()
            .put("add", "vm.example.com")
            .put("port", 443)
            .put("id", "b831381d-6324-4d53-ad4f-8cda48b30811")
            .put("aid", 0)
            .put("net", "ws")
            .put("host", "vm.example.com")
            .put("path", "/ray")
            .put("tls", "tls")
            .put("ps", "Test VMess")
        val encoded = java.util.Base64.getEncoder()
            .encodeToString(json.toString().toByteArray(Charsets.UTF_8))
        val parsed = XrayConfig.parse("vmess://$encoded")

        assertNotNull(parsed)
        parsed!!
        assertEquals("vm.example.com", parsed.host)
        assertEquals(443, parsed.port)
        assertEquals("Test VMess", parsed.name)
        assertEquals("vmess", parsed.protocol)

        val config = JSONObject(parsed.fullConfig)
        val inbound = config.getJSONArray("inbounds").getJSONObject(0)
        assertEquals("socks", inbound.getString("protocol"))
        assertEquals(XrayManager.SOCKS_PORT, inbound.getInt("port"))

        val outbound = config.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("vmess", outbound.getString("protocol"))
        val stream = outbound.getJSONObject("streamSettings")
        assertEquals("ws", stream.getString("network"))
        assertEquals("tls", stream.getString("security"))
    }

    @Test
    fun `parse vless reality link`() {
        val link = "vless://b831381d-6324-4d53-ad4f-8cda48b30811@vl.example.com:443" +
            "?type=tcp&security=reality&pbk=SbVKOEMjK0sIlbwg4akyBg5mL5KZwwB-ed4eEE7YnRc" +
            "&sid=6ba85179&sni=www.microsoft.com&fp=chrome#My%20VLESS"
        val parsed = XrayConfig.parse(link)

        assertNotNull(parsed)
        parsed!!
        assertEquals("vl.example.com", parsed.host)
        assertEquals(443, parsed.port)
        assertEquals("My VLESS", parsed.name)
        assertEquals("vless", parsed.protocol)

        val config = JSONObject(parsed.fullConfig)
        val outbound = config.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("vless", outbound.getString("protocol"))
        val stream = outbound.getJSONObject("streamSettings")
        assertEquals("reality", stream.getString("security"))
        assertEquals(
            "SbVKOEMjK0sIlbwg4akyBg5mL5KZwwB-ed4eEE7YnRc",
            stream.getJSONObject("realitySettings").getString("publicKey"),
        )
    }

    @Test
    fun `parse trojan link defaults to tls`() {
        val link = "trojan://secret-password@tj.example.com:8443?sni=tj.example.com#TrojanNode"
        val parsed = XrayConfig.parse(link)

        assertNotNull(parsed)
        parsed!!
        assertEquals("tj.example.com", parsed.host)
        assertEquals(8443, parsed.port)

        val config = JSONObject(parsed.fullConfig)
        val outbound = config.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("trojan", outbound.getString("protocol"))
        assertEquals(
            "secret-password",
            outbound.getJSONObject("settings").getJSONArray("servers").getJSONObject(0).getString("password"),
        )
        val stream = outbound.getJSONObject("streamSettings")
        assertEquals("tls", stream.getString("security"))
        assertEquals("tj.example.com", stream.getJSONObject("tlsSettings").getString("serverName"))
    }

    @Test
    fun `parse shadowsocks sip002 link`() {
        val link = "ss://YWVzLTI1Ni1nY206dGVzdHBhc3N3b3Jk@ss.example.com:8388#SS%20Node"
        val parsed = XrayConfig.parse(link)

        assertNotNull(parsed)
        parsed!!
        assertEquals("ss.example.com", parsed.host)
        assertEquals(8388, parsed.port)
        assertEquals("SS Node", parsed.name)
        assertEquals("shadowsocks", parsed.protocol)

        val config = JSONObject(parsed.fullConfig)
        val outbound = config.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("shadowsocks", outbound.getString("protocol"))
        val server = outbound.getJSONObject("settings").getJSONArray("servers").getJSONObject(0)
        assertEquals("aes-256-gcm", server.getString("method"))
        assertEquals("testpassword", server.getString("password"))
    }

    @Test
    fun `rejects malformed links`() {
        assertNull(XrayConfig.parse("vmess://!!!not-base64!!!"))
        assertNull(XrayConfig.parse("vless://no-host"))
        assertNull(XrayConfig.parse("trojan://pass@bad"))
        assertNull(XrayConfig.parse("ss://"))
        assertNull(XrayConfig.parse("ftp://example.com"))
    }

    @Test
    fun `config includes direct freedom fallback`() {
        val link = "vless://b831381d-6324-4d53-ad4f-8cda48b30811@x.example.com:80"
        val parsed = XrayConfig.parse(link)!!
        val config = JSONObject(parsed.fullConfig)
        assertEquals(2, config.getJSONArray("outbounds").length())
        assertEquals(
            "freedom",
            config.getJSONArray("outbounds").getJSONObject(1).getString("protocol"),
        )
    }
}
