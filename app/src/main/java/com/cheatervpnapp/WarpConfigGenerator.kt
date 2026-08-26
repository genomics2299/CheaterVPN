package com.cheatervpnapp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.amnezia.awg.crypto.KeyPair
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

object WarpConfigGenerator {

    private const val API_URL = "https://api.cloudflareclient.com/v0a2158/reg"
    private const val USER_AGENT = "okhttp/3.28.4"

    class WarpResult(
        val config: String,
        val host: String,
        val port: Int,
    )

    suspend fun generate(): Result<WarpResult> = withContext(Dispatchers.IO) {
        runCatching {
            val keyPair = KeyPair()
            val reg = register(keyPair.publicKey.toBase64())
            val config = reg.getJSONObject("config")
            val peer = config.getJSONArray("peers").getJSONObject(0)
            val peerPub = peer.getString("public_key")
            val endpoint = peer.getJSONObject("endpoint")
            val epRaw = endpoint.optString("v4").ifEmpty { endpoint.optString("v6") }
            val (host, port) = splitEndpoint(epRaw)
            val addresses = config.getJSONObject("interface").getJSONObject("addresses")
            val v4 = addresses.optString("v4")
            val v6 = addresses.optString("v6")
            val text = formatConfig(
                privateKey = keyPair.privateKey.toBase64(),
                addressV4 = v4,
                addressV6 = v6,
                peerPublicKey = peerPub,
                host = host,
                port = port,
            )
            WarpResult(text, host, port)
        }
    }

    private fun register(publicKeyBase64: String): JSONObject {
        val body = JSONObject()
            .put("fcm_token", "")
            .put("install_id", "")
            .put("key", publicKeyBase64)
            .put("locale", "en_US")
            .put("model", "Android")
            .put("tos", Instant.now().toString())
            .put("type", "Android")

        val conn = URL(API_URL).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw RuntimeException("HTTP $code: ${response.take(200)}")
            }
            return JSONObject(response)
        } finally {
            conn.disconnect()
        }
    }

    private fun splitEndpoint(endpoint: String): Pair<String, Int> {
        val idx = endpoint.lastIndexOf(':')
        require(idx > 0 && idx < endpoint.length - 1) { "Bad endpoint: $endpoint" }
        var host = endpoint.substring(0, idx)
        if (host.startsWith("[")) host = host.removeSurrounding("[", "]")
        val port = endpoint.substring(idx + 1).toIntOrNull() ?: throw RuntimeException("Bad port: $endpoint")
        return host to port
    }

    internal fun formatConfig(
        privateKey: String,
        addressV4: String,
        addressV6: String,
        peerPublicKey: String,
        host: String,
        port: Int,
    ): String {
        val sb = StringBuilder()
        sb.append("[Interface]\n")
        sb.append("PrivateKey = ").append(privateKey).append('\n')
        val addresses = mutableListOf<String>()
        if (addressV4.isNotEmpty()) addresses.add("$addressV4/32")
        if (addressV6.isNotEmpty()) addresses.add("$addressV6/128")
        sb.append("Address = ").append(addresses.joinToString(", ")).append('\n')
        sb.append("DNS = 1.1.1.1, 1.0.0.1\n")
        sb.append("Jc = 4\n")
        sb.append("Jmin = 40\n")
        sb.append("Jmax = 70\n")
        sb.append('\n')
        sb.append("[Peer]\n")
        sb.append("PublicKey = ").append(peerPublicKey).append('\n')
        sb.append("AllowedIPs = 0.0.0.0/0, ::/0\n")
        sb.append("Endpoint = ").append(host).append(':').append(port).append('\n')
        return sb.toString()
    }
}
