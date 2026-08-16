package com.cheatervpnapp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

object CountryResolver {

    suspend fun resolveCountry(host: String): Pair<String, String>? {
        if (host.isEmpty()) return null
        return withContext(Dispatchers.IO) {
            val ip = resolveToIp(host) ?: return@withContext null
            tryIpApi(ip) ?: tryIpWhoIs(ip)
        }
    }

    private fun resolveToIp(host: String): String? = try {
        if (isIp(host)) host
        else InetAddress.getAllByName(host).firstOrNull()?.hostAddress
    } catch (_: Exception) {
        null
    }

    private fun isIp(host: String): Boolean {
        val a = host.split(".")
        if (a.size == 4 && a.all { it.isNotEmpty() && it.all(Char::isDigit) }) return true
        return host.contains(":") && host.split(":").all { it.length <= 4 }
    }

    private fun tryIpApi(ip: String): Pair<String, String>? = runCatching {
        val url = URL("https://ip-api.com/json/$ip?fields=status,country,countryCode")
        val body = fetch(url)
        val json = JSONObject(body)
        if (json.optString("status") == "success") {
            json.optString("country") to json.optString("countryCode")
        } else null
    }.getOrNull()

    private fun tryIpWhoIs(ip: String): Pair<String, String>? = runCatching {
        val url = URL("https://ipwho.is/$ip")
        val body = fetch(url)
        val json = JSONObject(body)
        if (json.optBoolean("success", true)) {
            json.optString("country") to json.optString("country_code")
        } else null
    }.getOrNull()

    private fun fetch(url: URL): String {
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.setRequestProperty("User-Agent", "VPN-App/1.0")
        return conn.inputStream.bufferedReader().use { it.readText() }
    }
}
