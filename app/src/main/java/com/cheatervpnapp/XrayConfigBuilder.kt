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

    fun parseVlessLink(link: String): VlessParams {
        val trimmed = link.trim()
        if (!trimmed.startsWith("vless://")) throw VlessParseException("Not a VLESS link")
        val afterScheme = trimmed.removePrefix("vless://")
        val hashIdx = afterScheme.indexOf('#')
        val remark = if (hashIdx >= 0) {
            Uri.decode(afterScheme.substring(hashIdx + 1)).trim()
        } else ""
        val rest = if (hashIdx >= 0) afterScheme.substring(0, hashIdx) else afterScheme
        val qIdx = rest.indexOf('?')
        val addrPart = if (qIdx >= 0) rest.substring(0, qIdx) else rest
        val query = if (qIdx >= 0) rest.substring(qIdx + 1) else ""

        val atIdx = addrPart.lastIndexOf('@')
        if (atIdx <= 0) throw VlessParseException("Invalid address part")
        val uuid = addrPart.substring(0, atIdx)
        val hostPort = addrPart.substring(atIdx + 1)
        val idx = hostPort.lastIndexOf(':')
        if (idx <= 0) throw VlessParseException("Invalid host:port")
        val host = hostPort.substring(0, idx).trim()
        val port = hostPort.substring(idx + 1).trim().toIntOrNull()
            ?: throw VlessParseException("Invalid port")

        if (uuid.isBlank() || host.isBlank()) throw VlessParseException("Missing uuid or host")

        val params = mutableMapOf<String, String>()
        query.split('&').forEach { pair ->
            val eq = pair.indexOf('=')
            if (eq > 0) {
                params[pair.substring(0, eq)] = Uri.decode(pair.substring(eq + 1))
            }
        }

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
            remark = remark,
            network = params["type"] ?: "tcp",
            encryption = params["encryption"] ?: "none",
            spx = params["spx"] ?: "",
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