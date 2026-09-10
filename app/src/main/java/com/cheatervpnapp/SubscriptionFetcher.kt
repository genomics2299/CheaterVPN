package com.cheatervpnapp

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class SubscriptionInfo(
    val title: String = "",
    val expireAt: Long = 0L,
    val links: List<String> = emptyList(),
)

object SubscriptionFetcher {

    private const val USER_AGENT = "Happ/1.0"

    fun fetch(url: String): SubscriptionInfo {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.requestMethod = "GET"
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")

            val headers = conn.headerFields
            val userInfo = parseUserInfo(headerValue(headers, "Subscription-Userinfo"))
            val body = readBody(conn)
            val bodyMeta = parseBodyMeta(body)

            return SubscriptionInfo(
                title = headerValue(headers, "Profile-Title").ifEmpty { bodyMeta ?.title ?: "" },
                expireAt = (userInfo["expire"] ?: bodyMeta?.expireAt ?: 0L),
                links = parseLinks(body),
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun headerValue(headers: Map<String, MutableList<String>>, name: String): String {
        for ((key, values) in headers) {
            if (key != null && key.equals(name, ignoreCase = true) && values.isNotEmpty()) {
                return values[0].trim()
            }
        }
        return ""
    }

    private fun readBody(conn: HttpURLConnection): String {
        val out = ByteArrayOutputStream()
        conn.inputStream.use { input ->
            input.copyTo(out)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun parseLinks(body: String): List<String> {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return emptyList()
        val text = tryBase64(trimmed) ?: trimmed
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("vless://") }
            .distinct()
            .toList()
    }

    private fun tryBase64(text: String): String? {
        val candidate = text.replace(Regex("\\s+"), "")
        if (!Regex("^[A-Za-z0-9+/=]+$").matches(candidate)) return null
        return runCatching {
            val decoded = java.util.Base64.getDecoder().decode(candidate)
            val s = String(decoded, Charsets.UTF_8)
            if (s.contains("://")) s else null
        }.getOrNull()
    }

    private data class BodyMeta(val title: String = "", val expireAt: Long = 0L)

    private fun parseBodyMeta(body: String): BodyMeta? {
        val text = tryBase64(body.trim()) ?: body
        var title = ""
        var expire = 0L
        var found = false
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (!line.startsWith("#")) return@forEach
            found = true
            val idx = line.indexOf(':')
            if (idx <= 0) return@forEach
            val key = line.substring(1, idx).trim().lowercase()
            val value = line.substring(idx + 1).trim()
            when (key) {
                "profile-title" -> title = decodeMetaValue(value)
                "subscription-userinfo" -> expire = parseUserInfo(value)["expire"] ?: 0L
            }
        }
        return if (found) BodyMeta(title, expire) else null
    }

    private fun decodeMetaValue(value: String): String {
        val v = value.trim()
        return if (v.startsWith("base64:")) {
            runCatching {
                String(java.util.Base64.getDecoder().decode(v.removePrefix("base64:")), Charsets.UTF_8)
            }.getOrDefault(v)
        } else v
    }

    fun parseUserInfo(value: String): Map<String, Long> {
        val out = mutableMapOf<String, Long>()
        value.split(';').forEach { part ->
            val idx = part.indexOf('=')
            if (idx > 0) {
                val key = part.substring(0, idx).trim()
                val v = part.substring(idx + 1).trim().toLongOrNull()
                if (key.isNotEmpty() && v != null && v >= 0) out[key] = v
            }
        }
        return out
    }
}