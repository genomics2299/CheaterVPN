package com.cheatervpnapp

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.util.Base64

object XrayConfig {

    val SCHEMES = listOf("vmess://", "vless://", "trojan://", "ss://")

    class Parsed(
        val fullConfig: String,
        val host: String,
        val port: Int,
        val name: String,
        val protocol: String,
    )

    fun isXrayLink(text: String): Boolean {
        val t = text.trim()
        return SCHEMES.any { t.startsWith(it) }
    }

    fun parse(text: String): Parsed? {
        return runCatching { parseInternal(text.trim()) }.getOrNull()
    }

    private fun parseInternal(text: String): Parsed? {
        return when {
            text.startsWith("vmess://") -> parseVmess(text)
            text.startsWith("vless://") -> parseVless(text)
            text.startsWith("trojan://") -> parseTrojan(text)
            text.startsWith("ss://") -> parseShadowsocks(text)
            else -> null
        }?.takeIf { it.host.isNotEmpty() && it.port in 1..65535 && it.fullConfig.isNotEmpty() }
    }

    private fun displayName(fragment: String?, fallback: String): String {
        val f = fragment ?: return fallback
        if (f.isEmpty()) return fallback
        return runCatching { URLDecoder.decode(f, "UTF-8") }.getOrDefault(f).ifBlank { fallback }
    }

    private fun base64Decode(encoded: String): ByteArray? {
        val cleaned = encoded.filterNot { it.isWhitespace() }
        if (cleaned.isEmpty()) return null
        val padded = cleaned + "=".repeat((4 - cleaned.length % 4) % 4)
        listOf(Base64.getDecoder(), Base64.getUrlDecoder()).forEach { decoder ->
            runCatching { decoder.decode(padded) }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun base64DecodeString(encoded: String): String? =
        base64Decode(encoded)?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }

    private fun buildConfig(outbound: JSONObject): String {
        val config = JSONObject()
        config.put(
            "log",
            JSONObject().put("loglevel", "warning"),
        )
        val inbound = JSONObject()
            .put("listen", "127.0.0.1")
            .put("port", 10808)
            .put("protocol", "socks")
            .put(
                "settings",
                JSONObject().put("auth", "noauth").put("udp", true),
            )
        val outbounds = JSONArray().put(outbound)
        outbounds.put(JSONObject().put("protocol", "freedom").put("tag", "direct"))
        config.put("inbounds", JSONArray().put(inbound))
        config.put("outbounds", outbounds)
        return config.toString(2)
    }

    private fun streamSettings(net: String, security: String, params: Map<String, String>, tlsHostFallback: String): JSONObject {
        val stream = JSONObject().put("network", net)
        when (security) {
            "tls" -> {
                stream.put("security", "tls")
                val tls = JSONObject()
                params["sni"]?.takeIf { it.isNotEmpty() }?.let { tls.put("serverName", it) }
                    ?: tlsHostFallback.takeIf { it.isNotEmpty() }?.let { tls.put("serverName", it.split(",").first().trim()) }
                params["alpn"]?.takeIf { it.isNotEmpty() }?.let {
                    tls.put("alpn", JSONArray(it.split(",").map { p -> p.trim() }))
                }
                params["fp"]?.takeIf { it.isNotEmpty() }?.let { tls.put("fingerprint", it) }
                stream.put("tlsSettings", tls)
            }
            "reality" -> {
                stream.put("security", "reality")
                val reality = JSONObject()
                params["sni"]?.takeIf { it.isNotEmpty() }?.let { reality.put("serverName", it) }
                    ?: tlsHostFallback.takeIf { it.isNotEmpty() }?.let { reality.put("serverName", it.split(",").first().trim()) }
                params["pbk"]?.takeIf { it.isNotEmpty() }?.let { reality.put("publicKey", it) }
                params["sid"]?.takeIf { it.isNotEmpty() }?.let { reality.put("shortId", it) }
                reality.put("fingerprint", params["fp"]?.takeIf { it.isNotEmpty() } ?: "chrome")
                stream.put("realitySettings", reality)
            }
        }
        when (net) {
            "ws" -> {
                val ws = JSONObject()
                params["path"]?.takeIf { it.isNotEmpty() }?.let { ws.put("path", it) }
                params["host"]?.takeIf { it.isNotEmpty() }?.let {
                    ws.put("headers", JSONObject().put("Host", it))
                }
                stream.put("wsSettings", ws)
            }
            "http", "h2" -> {
                stream.put("network", "http")
                val h2 = JSONObject()
                params["path"]?.takeIf { it.isNotEmpty() }?.let { h2.put("path", it) }
                params["host"]?.takeIf { it.isNotEmpty() }?.let {
                    h2.put("host", JSONArray(it.split(",").map { p -> p.trim() }))
                }
                stream.put("httpSettings", h2)
            }
            "grpc" -> {
                val grpc = JSONObject()
                params["serviceName"]?.takeIf { it.isNotEmpty() }?.let { grpc.put("serviceName", it) }
                params["path"]?.takeIf { it.isNotEmpty() }?.let { grpc.put("serviceName", it) }
                stream.put("grpcSettings", grpc)
            }
            else -> {
                if (net == "tcp" && params["type"] == "http") {
                    val tcp = JSONObject()
                        .put("type", "http")
                        .put(
                            "request",
                            JSONObject().apply {
                                params["host"]?.takeIf { it.isNotEmpty() }?.let {
                                    put("headers", JSONObject().put("Host", JSONArray(it.split(",").map { h -> h.trim() })))
                                }
                                params["path"]?.takeIf { it.isNotEmpty() }?.let { put("path", it) }
                            },
                        )
                    stream.put("tcpSettings", tcp)
                }
            }
        }
        return stream
    }

    private fun splitUserInfo(rest: String): Triple<String, String, Int>? {
        // format: userinfo@host:port
        val at = rest.lastIndexOf('@')
        if (at <= 0) return null
        val userInfo = rest.substring(0, at)
        val hostPort = rest.substring(at + 1)
        val qIdx = hostPort.indexOfFirst { it == '?' || it == '/' }
        val authority = if (qIdx >= 0) hostPort.substring(0, qIdx) else hostPort
        val colon = authority.lastIndexOf(':')
        if (colon <= 0) return null
        val host = authority.substring(0, colon).trim()
        val port = authority.substring(colon + 1).trim().toIntOrNull() ?: return null
        return Triple(userInfo, host, port)
    }

    private fun queryOf(uri: String): Pair<Map<String, String>, String> {
        val qIdx = uri.indexOf('?')
        if (qIdx < 0) return emptyMap<String, String>() to ""
        val rest = uri.substring(qIdx + 1)
        val hash = rest.indexOf('#')
        val query = if (hash >= 0) rest.substring(0, hash) else rest
        val fragment = if (hash >= 0) rest.substring(hash + 1) else ""
        val params = query.split('&')
            .filter { it.contains('=') }
            .associate {
                val i = it.indexOf('=')
                URLDecoder.decode(it.substring(0, i), "UTF-8") to
                    URLDecoder.decode(it.substring(i + 1), "UTF-8")
            }
        return params to fragment
    }

    private fun parseVmess(link: String): Parsed? {
        val body = link.removePrefix("vmess://").trim()
        val jsonText = base64DecodeString(body) ?: return null
        val o = JSONObject(jsonText)
        val add = o.optString("add").trim()
        val port = when (val p = o.opt("port")) {
            is Number -> p.toInt()
            is String -> p.trim().toIntOrNull() ?: return null
            else -> return null
        }
        val id = o.optString("id")
        if (add.isEmpty() || id.isEmpty()) return null
        val net = o.optString("net").ifEmpty { "tcp" }
        val tls = if (o.optString("tls") == "tls") "tls" else ""
        val sni = o.optString("sni").ifEmpty { o.optString("host") }
        val scy = o.optString("scy").ifEmpty { "auto" }

        val settings = JSONObject().put(
            "vnext",
            JSONArray().put(
                JSONObject()
                    .put("address", add)
                    .put("port", port)
                    .put(
                        "users",
                        JSONArray().put(
                            JSONObject()
                                .put("id", id)
                                .put("alterId", o.optInt("aid", 0))
                                .put("security", scy),
                        ),
                    ),
            ),
        )
        val outbound = JSONObject()
            .put("protocol", "vmess")
            .put("settings", settings)
        val params = mapOf(
            "host" to o.optString("host"),
            "path" to o.optString("path"),
            "sni" to sni,
            "fp" to o.optString("fp"),
            "type" to o.optString("type"),
        )
        outbound.put("streamSettings", streamSettings(net, tls, params, o.optString("host")))
        val name = displayName(o.optString("ps"), "VMess")
        return Parsed(buildConfig(outbound), add, port, name, "vmess")
    }

    private fun parseVless(link: String): Parsed? {
        var body = link.removePrefix("vless://").trim()
        val hash = body.indexOf('#')
        val fragment = if (hash >= 0) body.substring(hash + 1) else ""
        if (hash >= 0) body = body.substring(0, hash)
        val parts = splitUserInfo(if (body.contains('?')) body.substringBefore('?') else body) ?: return null
        val (uuid, host, port) = parts
        if (uuid.isEmpty()) return null
        val (params, _) = queryOf(body)

        val flow = params["flow"].orEmpty()
        val users = JSONArray().put(
            JSONObject()
                .put("id", uuid)
                .put("encryption", "none")
                .put("flow", flow),
        )
        if (flow.isNotEmpty()) {
            users.getJSONObject(0).put("flow", flow)
        }
        val settings = JSONObject().put(
            "vnext",
            JSONArray().put(
                JSONObject()
                    .put("address", host)
                    .put("port", port)
                    .put("users", users),
            ),
        )
        val outbound = JSONObject()
            .put("protocol", "vless")
            .put("settings", settings)
        val net = params["type"]?.ifEmpty { null } ?: "tcp"
        val security = params["security"]?.ifEmpty { null } ?: ""
        outbound.put("streamSettings", streamSettings(net, security, params, params["host"].orEmpty()))
        return Parsed(buildConfig(outbound), host, port, displayName(fragment, "VLESS"), "vless")
    }

    private fun parseTrojan(link: String): Parsed? {
        var body = link.removePrefix("trojan://").trim()
        val hash = body.indexOf('#')
        val fragment = if (hash >= 0) body.substring(hash + 1) else ""
        if (hash >= 0) body = body.substring(0, hash)
        val parts = splitUserInfo(if (body.contains('?')) body.substringBefore('?') else body) ?: return null
        val (password, host, port) = parts
        if (password.isEmpty()) return null
        val (params, _) = queryOf(body)

        val settings = JSONObject().put(
            "servers",
            JSONArray().put(
                JSONObject()
                    .put("address", host)
                    .put("port", port)
                    .put("password", password),
            ),
        )
        val outbound = JSONObject()
            .put("protocol", "trojan")
            .put("settings", settings)
        val net = params["type"]?.ifEmpty { null } ?: "tcp"
        outbound.put(
            "streamSettings",
            streamSettings(net, "tls", params, params["sni"].orEmpty()),
        )
        return Parsed(buildConfig(outbound), host, port, displayName(fragment, "Trojan"), "trojan")
    }

    private fun parseShadowsocks(link: String): Parsed? {
        var body = link.removePrefix("ss://").trim()
        val fragment = body.substringAfter('#', "")
        if (body.contains('#')) body = body.substringBefore('#')

        var method: String?
        var password: String?
        var host: String
        var port: Int

        val at = body.lastIndexOf('@')
        if (at > 0) {
            val userInfo = body.substring(0, at)
            val hostPort = body.substring(at + 1)
            val colon = hostPort.lastIndexOf(':')
            if (colon <= 0) return null
            host = hostPort.substring(0, colon).trim()
            port = hostPort.substring(colon + 1).substringBefore('?').substringBefore('/').trim().toIntOrNull() ?: return null
            val decodedUser = base64DecodeString(userInfo) ?: userInfo
            val sep = decodedUser.indexOf(':')
            if (sep <= 0) return null
            method = decodedUser.substring(0, sep)
            password = decodedUser.substring(sep + 1)
        } else {
            val decoded = base64DecodeString(body) ?: return null
            val noQuery = decoded.substringBefore('?')
            val sep = noQuery.lastIndexOf(':')
            val atIdx = noQuery.lastIndexOf('@')
            if (sep <= 0 || atIdx <= sep || atIdx <= 0) return null
            method = noQuery.substring(0, sep)
            val hostPort = noQuery.substring(atIdx + 1)
            val colon = hostPort.lastIndexOf(':')
            if (colon <= 0) return null
            host = hostPort.substring(0, colon).trim()
            port = hostPort.substring(colon + 1).trim().toIntOrNull() ?: return null
            password = noQuery.substring(sep + 1, atIdx)
        }

        if (method.isNullOrEmpty() || password.isNullOrEmpty()) return null

        val pluginParams = body.substringAfter('?', "").split('&')
            .firstOrNull { it.startsWith("plugin=") }
            ?.substringAfter('=')?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
        if (!pluginParams.isNullOrBlank()) return null

        val settings = JSONObject().put(
            "servers",
            JSONArray().put(
                JSONObject()
                    .put("address", host)
                    .put("port", port)
                    .put("method", method)
                    .put("password", password),
            ),
        )
        val outbound = JSONObject()
            .put("protocol", "shadowsocks")
            .put("settings", settings)
        return Parsed(buildConfig(outbound), host, port, displayName(fragment, "Shadowsocks"), "shadowsocks")
    }
}
