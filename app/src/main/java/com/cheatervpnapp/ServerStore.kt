package com.cheatervpnapp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class ServerStore(context: Context) {

    private val prefs = context.getSharedPreferences("servers", Context.MODE_PRIVATE)

    fun load(): List<Server> {
        val json = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        Server(
                            id = o.getString("id"),
                            name = o.getString("name"),
                            country = o.getString("country"),
                            countryCode = o.getString("countryCode"),
                            host = o.getString("host"),
                            port = o.getInt("port"),
                            config = o.getString("config"),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun save(servers: List<Server>) {
        val arr = JSONArray()
        servers.forEach { s ->
            arr.put(
                JSONObject().apply {
                    put("id", s.id)
                    put("name", s.name)
                    put("country", s.country)
                    put("countryCode", s.countryCode)
                    put("host", s.host)
                    put("port", s.port)
                    put("config", s.config)
                }
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun saveSelectedId(id: String?) {
        prefs.edit().putString(KEY_SELECTED, id).apply()
    }

    fun loadSelectedId(): String? = prefs.getString(KEY_SELECTED, null)

    fun selectedServer(): Server? {
        val id = loadSelectedId() ?: return null
        return load().firstOrNull { it.id == id }
    }

    companion object {
        private const val KEY = "servers_json"
        private const val KEY_SELECTED = "selected_id"
    }
}
