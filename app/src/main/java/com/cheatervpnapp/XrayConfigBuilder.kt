package com.cheatervpnapp

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

object XrayConfigBuilder {

    data class VlessParams(
        val uuid: String,
        val host: String,
        val port: Int,
        val flow: String,
        val security: String,
        val sni: String,
        val publicKey: String,
        val shortId: String,
        val fingerprint: String,
        val remark: String,
        val network: String,
        val encryption: String,
        val spx: String,
    )

    class VlessParseException(message: String) : Exception(message)

    data class Hysteria2Params(
        val auth: String,
        val host: String,
        val port: Int,
        val sni: String,
        val insecure: Boolean,
        val alpn: List<String>,
        val pinSHA256: String,
        val remark: String,
    )

    class Hysteria2ParseException(message: String) : Exception(message)

    private fun parseHostPort(holder: String, description: String): Pair<String, Int> {
        val h = holder.trim()
        if (h.startsWith("[")) {
            val close = h.indexOf(']')
            if (close == -1) throw VlessParseException("Invalid $description")
            val host = h.substring(1, close)
            val port = h.substring(close + 1).removePrefix(":").trim().trimEnd('/').toIntOrNull()
                ?: throw VlessParseException("Invalid $description port")
            if (host.isBlank()) throw VlessParseException("Missing host in $description")
            return host to port
        }
        val idx = h.lastIndexOf(':')
        if (idx <= 0) throw VlessParseException("Invalid $description")
        val host = h.substring(0, idx).trim()
        val port = h.substring(idx + 1).trim().trimEnd('/').toIntOrNull()
            ?: throw VlessParseException("Invalid $description port")
        if (host.isBlank()) throw VlessParseException("Missing host in $description")
        return host to port
    }

    private fun parseQuery(query: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        query.split('&').forEach { pair ->
            val eq = pair.indexOf('=')
            if (eq > 0) {
                params[pair.substring(0, eq)] = Uri.decode(pair.substring(eq + 1))
            }
        }
        return params
    }

    private fun splitLink(link: String): Triple<String, String, String> {
        val trimmed = link.trim()
        val hashIdx = trimmed.indexOf('#')
        val remark = if (hashIdx >= 0) {
            Uri.decode(trimmed.substring(hashIdx + 1)).trim()
        } else ""
        val rest = if (hashIdx >= 0) trimmed.substring(0, hashIdx) else trimmed
        val qIdx = rest.indexOf('?')
        val addrPart = if (qIdx >= 0) rest.substring(0, qIdx) else rest
        val query = if (qIdx >= 0) rest.substring(qIdx + 1) else ""
        return Triple(addrPart, query, remark)
    }

    fun parseVlessLink(link: String): VlessParams {
        val trimmed = link.trim()
        if (!trimmed.startsWith("vless://")) throw VlessParseException("Not a VLESS link")
        val afterScheme = trimmed.removePrefix("vless://")
        val (addrPart0, query0, remark0) = splitLink(afterScheme)
        val addrPart = addrPart0
        val atIdx0 = addrPart.lastIndexOf('@')
        if (atIdx0 <= 0) throw VlessParseException("Invalid address part")
        val uuid = addrPart.substring(0, atIdx0)
        val (host, port) = parseHostPort(addrPart.substring(atIdx0 + 1), "host:port")

        if (uuid.isBlank()) throw VlessParseException("Missing uuid or host")

        val params = parseQuery(query0)

        return VlessParams(
            uuid = uuid,
            host = host,
            port = port,
            flow = params["flow"] ?: "",
            security = params["security"] ?: "none",
            sni = params["sni"] ?: "",
            publicKey = params["pbk"] ?: "",
            shortId = params["sid"] ?: "",
            fingerprint = params["fp"] ?: "",
            remark = remark0,
            network = params["type"] ?: "tcp",
            encryption = params["encryption"] ?: "none",
            spx = params["spx"] ?: "",
        )
    }

    fun parseHysteria2Link(link: String): Hysteria2Params {
        val trimmed = link.trim()
        if (!trimmed.startsWith("hysteria2://")) throw Hysteria2ParseException("Not a Hysteria2 link")
        val afterScheme = trimmed.removePrefix("hysteria2://")
        val (addrPart, query, remark) = splitLink(afterScheme)

        val atIdx = addrPart.lastIndexOf('@')
        if (atIdx <= 0) throw Hysteria2ParseException("Invalid address part")
        val auth = Uri.decode(addrPart.substring(0, atIdx)).trim()
        val (host, port) = parseHostPort(addrPart.substring(atIdx + 1), "host:port")

        if (auth.isBlank() || host.isBlank()) throw Hysteria2ParseException("Missing auth or host")

        val params = parseQuery(query)
        val insecureValue = params["insecure"] ?: params["allowInsecure"] ?: ""
        val insecure = insecureValue == "1" || insecureValue.equals("true", ignoreCase = true)
        val alpn = (params["alpn"] ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }

        return Hysteria2Params(
            auth = auth,
            host = host,
            port = port,
            sni = params["sni"] ?: "",
            insecure = insecure,
            alpn = alpn,
            pinSHA256 = params["pinSHA256"] ?: "",
            remark = remark,
        )
    }

    fun buildConfig(params: VlessParams): String {
        val streamSettings = JSONObject()
            .put("network", "tcp")
            .put("security", if (params.security.isNotBlank()) params.security else "none")

        if (params.security == "reality") {
            val reality = JSONObject()
            if (params.sni.isNotBlank()) reality.put("serverName", params.sni)
            if (params.fingerprint.isNotBlank()) reality.put("fingerprint", params.fingerprint)
            if (params.publicKey.isNotBlank()) reality.put("publicKey", params.publicKey)
            if (params.shortId.isNotBlank()) reality.put("shortId", params.shortId)
            streamSettings.put("realitySettings", reality)
        } else if (params.security == "tls") {
            val tls = JSONObject()
            if (params.sni.isNotBlank()) tls.put("serverName", params.sni)
            streamSettings.put("tlsSettings", tls)
        }

        val user = JSONObject()
            .put("id", params.uuid)
            .put("encryption", "none")
            .put("level", 8)
        if (params.flow.isNotBlank()) user.put("flow", params.flow)

        val proxyOutbound = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "vless")
            .put(
                "settings",
                JSONObject().put(
                    "vnext",
                    JSONArray().put(
                        JSONObject()
                            .put("address", params.host)
                            .put("port", params.port)
                            .put("users", JSONArray().put(user))
                    )
                )
            )
            .put("streamSettings", streamSettings)

        return baseConfig(proxyOutbound)
    }

    fun buildHysteria2Config(params: Hysteria2Params): String {
        val tlsSettings = JSONObject()
            .put("serverName", if (params.sni.isNotBlank()) params.sni else params.host)
            .put("allowInsecure", params.insecure)
        if (params.alpn.isNotEmpty()) {
            tlsSettings.put("alpn", JSONArray().apply { params.alpn.forEach { put(it) } })
        }
        if (params.pinSHA256.isNotBlank()) {
            tlsSettings.put("pinnedPeerCertificateChainSha256", JSONArray().put(params.pinSHA256))
        }

        val streamSettings = JSONObject()
            .put("network", "hysteria")
            .put("security", "tls")
            .put("tlsSettings", tlsSettings)
            .put(
                "hysteriaSettings",
                JSONObject()
                    .put("version", 2)
                    .put("auth", params.auth)
            )

        val proxyOutbound = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "hysteria2")
            .put(
                "settings",
                JSONObject()
                    .put("version", 2)
                    .put("address", params.host)
                    .put("port", params.port)
            )
            .put("streamSettings", streamSettings)

        return baseConfig(proxyOutbound)
    }

    private fun baseConfig(proxyOutbound: JSONObject): String {
        val directOutbound = JSONObject()
            .put("protocol", "freedom")
            .put(
                "streamSettings",
                JSONObject().put("sockopt", JSONObject().put("domainStrategy", "UseIP"))
            )
            .put("tag", "direct")

        val blockOutbound = JSONObject()
            .put("protocol", "blackhole")
            .put("tag", "block")
            .put("settings", JSONObject().put("response", JSONObject().put("type", "http")))

        val tunInbound = JSONObject()
            .put("tag", "tun")
            .put("protocol", "tun")
            .put(
                "settings",
                JSONObject()
                    .put("name", "xray0")
                    .put("MTU", 1500)
                    .put("userLevel", 8)
            )
            .put(
                "sniffing",
                JSONObject()
                    .put("enabled", true)
                    .put("destOverride", JSONArray().put("http").put("tls").put("quic"))
            )

        val config = JSONObject()
            .put("stats", JSONObject())
            .put("log", JSONObject().put("loglevel", "warning"))
            .put(
                "policy",
                JSONObject()
                    .put(
                        "levels",
                        JSONObject().put(
                            "8",
                            JSONObject()
                                .put("handshake", 4)
                                .put("connIdle", 300)
                                .put("uplinkOnly", 1)
                                .put("downlinkOnly", 1)
                        )
                    )
                    .put(
                        "system",
                        JSONObject()
                            .put("statsOutboundUplink", true)
                            .put("statsOutboundDownlink", true)
                    )
            )
            .put("inbounds", JSONArray().put(tunInbound))
            .put("outbounds", JSONArray().put(proxyOutbound).put(directOutbound).put(blockOutbound))
            .put("routing", JSONObject().put("domainStrategy", "AsIs").put("rules", JSONArray()))
            .put("dns", JSONObject().put("servers", JSONArray()))

        return config.toString()
    }
}