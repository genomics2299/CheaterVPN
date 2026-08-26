package com.cheatervpnapp

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.amnezia.awg.crypto.Key
import org.amnezia.awg.crypto.KeyPair
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

object WarpConfigGenerator {

    private const val TAG = "WarpConfig"
    private const val API_URL = "https://api.cloudflareclient.com/v0a1922/reg"
    private const val USER_AGENT = "okhttp/3.12.1"
    private const val CLIENT_VERSION = "a-6.3-1922"
    private const val FALLBACK_FILENAME = "warp.conf"

    class WarpResult(
        val config: String,
        val host: String,
        val port: Int,
    )

    suspend fun generate(context: Context? = null): Result<WarpResult> = withContext(Dispatchers.IO) {
        runCatching {
            val keyPair = generateKeyPair()
            Log.d(TAG, "Key generated: pub=${keyPair.publicKey.toBase64().take(8)}...")
            val body = register(keyPair.publicKey.toBase64())
            Log.d(TAG, "API success, parsing config")
            val config = body.optJSONObject("result")?.optJSONObject("config")
                ?: body.optJSONObject("config")
                ?: throw RuntimeException("No config in response")
            val peer = config.getJSONArray("peers").getJSONObject(0)
            val peerPub = peer.getString("public_key")
            val endpoint = peer.getJSONObject("endpoint")
            val hostPort = endpoint.optString("host").ifEmpty {
                endpoint.optString("v4")
            }
            val (host, port) = splitEndpoint(hostPort)
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
        }.onFailure { Log.e(TAG, "API generate failed, trying fallback", it) }
            .recoverCatching {
                Log.d(TAG, "Attempting fallback read...")
                readFallbackFile(context!!)
            }.onFailure { Log.e(TAG, "Fallback also failed", it) }
    }

    private fun readFallbackFile(context: Context): WarpResult {
        val text = readExternalFilesDir(context)
            ?: queryMediaStoreFile(context)
            ?: readDirectFile()
                ?: throw RuntimeException("API unavailable and no $FALLBACK_FILENAME found")
        Log.d(TAG, "Read fallback config, generating new identity")
        val newKeyPair = generateKeyPair()
        val newPrivKey = newKeyPair.privateKey.toBase64()
        val newPubKey = newKeyPair.publicKey.toBase64()
        val newV4 = generateNewIPv4(text)
        val newV6 = generateNewIPv6(text)
        val peerPub = extractPeerPublicKey(text)
        val host = parseEndpoint(text)
        val port = parsePort(text)
        val config = formatConfig(
            privateKey = newPrivKey,
            addressV4 = newV4,
            addressV6 = newV6,
            peerPublicKey = peerPub,
            host = host,
            port = port,
        )
        return WarpResult(config, host, port)
    }

    private fun extractPeerPublicKey(config: String): String {
        for (line in config.lines()) {
            if (line.trimStart().startsWith("PublicKey")) {
                return line.substringAfter("=", "").trim()
            }
        }
        return "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="
    }

    private fun generateNewIPv4(config: String): String {
        val v4Regex = Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})""")
        val match = v4Regex.find(config) ?: return "172.16.0.2"
        val parts = (1..4).map { match.groupValues[it].toIntOrNull() ?: 0 }
        val random = SecureRandom()
        val newLast = 2 + random.nextInt(252)
        return "${parts[0]}.${parts[1]}.${parts[2]}.$newLast"
    }

    private fun generateNewIPv6(config: String): String {
        val v6Regex = Regex("""([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}""")
        val match = v6Regex.find(config) ?: return ""
        val random = SecureRandom()
        val bytes = ByteArray(8)
        random.nextBytes(bytes)
        val newSuffix = bytes.joinToString(":") { "%04x".format(it) }
        return newSuffix
    }

    private fun readExternalFilesDir(context: Context): String? {
        return try {
            val dir = context.getExternalFilesDir(null) ?: return null
            val file = java.io.File(dir, FALLBACK_FILENAME)
            if (file.exists()) {
                Log.d(TAG, "Read from externalFilesDir: ${file.absolutePath}")
                file.readText()
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "externalFilesDir read failed", e)
            null
        }
    }

    private fun readDirectFile(): String? {
        val paths = listOf(
            "/sdcard/Download/warp.conf",
            "/storage/emulated/0/Download/warp.conf",
        )
        for (p in paths) {
            try {
                val f = java.io.File(p)
                if (f.exists() && f.canRead()) {
                    Log.d(TAG, "Read direct file: $p")
                    return f.readText()
                }
            } catch (_: Exception) {}
        }
        return null
    }

    private fun queryMediaStoreFile(context: Context): String? {
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
        )
        val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(FALLBACK_FILENAME)
        val sortOrder = "${MediaStore.Downloads.DATE_ADDED} DESC"

        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection, selection, selectionArgs, sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(idCol)
                val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    return stream.bufferedReader().readText()
                }
            }
        }
        return null
    }

    private fun parseEndpoint(config: String): String {
        for (line in config.lines()) {
            if (line.trimStart().startsWith("Endpoint")) {
                val value = line.substringAfter("=", "").trim()
                return value.substringBeforeLast(":")
            }
        }
        return ""
    }

    private fun parsePort(config: String): Int {
        for (line in config.lines()) {
            if (line.trimStart().startsWith("Endpoint")) {
                val value = line.substringAfter("=", "").trim()
                return value.substringAfterLast(":").toIntOrNull() ?: 2408
            }
        }
        return 2408
    }

    private fun generateKeyPair(): KeyPair {
        return try {
            KeyPair()
        } catch (_: Exception) {
            val random = SecureRandom()
            val privateKeyBytes = ByteArray(32)
            random.nextBytes(privateKeyBytes)
            KeyPair(Key.fromBytes(privateKeyBytes))
        }
    }

    private fun register(publicKeyBase64: String): JSONObject {
        val body = JSONObject()
            .put("fcm_token", "")
            .put("install_id", UUID.randomUUID().toString())
            .put("key", publicKeyBase64)
            .put("locale", "en_US")
            .put("model", "Pixel 9")
            .put("tos", Instant.now().toString())
            .put("type", "Android")

        val conn = URL(API_URL).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("CF-Client-Version", CLIENT_VERSION)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(response) }.getOrNull()
            val success = json?.optBoolean("success", false) ?: false
            if (code !in 200..299 || !success) {
                val errMsg = json?.optJSONArray("errors")?.optJSONObject(0)?.optString("message")
                    ?: "HTTP $code"
                throw RuntimeException(errMsg)
            }
            return json
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
        sb.append("S1 = 0\n")
        sb.append("S2 = 0\n")
        sb.append("S3 = 0\n")
        sb.append("S4 = 0\n")
        sb.append('\n')
        sb.append("[Peer]\n")
        sb.append("PublicKey = ").append(peerPublicKey).append('\n')
        sb.append("AllowedIPs = 0.0.0.0/0, ::/0\n")
        sb.append("Endpoint = ").append(host).append(':').append(port).append('\n')
        return sb.toString()
    }
}
