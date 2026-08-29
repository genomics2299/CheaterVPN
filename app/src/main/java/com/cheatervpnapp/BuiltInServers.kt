package com.cheatervpnapp

import android.content.Context

object BuiltInServers {

    private data class Spec(
        val id: String,
        val asset: String,
        val country: String,
        val countryCode: String,
    )

    private val specs = listOf(
        Spec("builtin-us", "us.conf", "United States", "US"),
    )

    fun load(context: Context): List<Server> {
        return specs.mapNotNull { spec ->
            val text = runCatching {
                context.assets.open("configs/${spec.asset}").bufferedReader().use { it.readText() }
            }.getOrNull() ?: return@mapNotNull null
            val endpoint = Server.parseEndpoint(text)
            Server(
                id = spec.id,
                name = spec.country,
                country = spec.country,
                countryCode = spec.countryCode,
                host = endpoint?.first.orEmpty(),
                port = endpoint?.second ?: 0,
                config = text,
            )
        }
    }
}
