package com.cheatervpnapp

data class Server(
    val id: String,
    val name: String,
    val country: String,
    val countryCode: String,
    val host: String,
    val port: Int,
    val config: String,
    val protocol: String = PROTOCOL_AWG,
    val subscriptionUrl: String = "",
    val subExpireAt: Long = 0L,
) {
    fun flag(): String = flagEmoji(countryCode)

    fun endpointLabel(): String = if (host.isEmpty()) "—" else "$host:$port"

    val isVless: Boolean get() = protocol == PROTOCOL_VLESS

    companion object {
        const val PROTOCOL_AWG = "awg"
        const val PROTOCOL_VLESS = "vless"

        fun parseEndpoint(configText: String): Pair<String, Int>? {
            val match = Regex("""(?im)^\s*Endpoint\s*=\s*(.+)$""").find(configText) ?: return null
            val ep = match.groupValues[1].trim()
            return when {
                ep.startsWith("[") -> {
                    val close = ep.indexOf(']')
                    if (close == -1) null
                    else {
                        val host = ep.substring(1, close)
                        val port = ep.substring(close + 1).removePrefix(":").trim().toIntOrNull()
                        if (port == null) null else host to port
                    }
                }
                else -> {
                    val idx = ep.lastIndexOf(':')
                    if (idx <= 0) null
                    else {
                        val host = ep.substring(0, idx).trim()
                        val port = ep.substring(idx + 1).trim().toIntOrNull()
                        if (port == null) null else host to port
                    }
                }
            }
        }

        fun flagEmoji(countryCode: String): String {
            if (countryCode.length != 2) return "\uD83C\uDF10"
            val base = 0x1F1E6 - 'A'.code
            return countryCode.uppercase().map { c ->
                String(Character.toChars(c.code + base))
            }.joinToString("")
        }
    }
}
