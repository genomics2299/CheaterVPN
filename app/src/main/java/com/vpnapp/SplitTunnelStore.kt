package com.vpnapp

import android.content.Context

class SplitTunnelStore(context: Context) {

    private val prefs = context.getSharedPreferences("split_tunnel", Context.MODE_PRIVATE)

    enum class Mode { EXCLUDE, INCLUDE }

    fun setMode(mode: Mode) {
        prefs.edit().putString(KEY_MODE, mode.name).apply()
    }

    fun mode(): Mode =
        runCatching { Mode.valueOf(prefs.getString(KEY_MODE, null) ?: "EXCLUDE") }
            .getOrDefault(Mode.EXCLUDE)

    fun setApps(apps: Set<String>) {
        prefs.edit().putStringSet(KEY_APPS, apps).apply()
    }

    fun apps(): Set<String> = prefs.getStringSet(KEY_APPS, emptySet()) ?: emptySet()

    fun isEnabled(): Boolean = apps().isNotEmpty()

    companion object {
        private const val KEY_MODE = "mode"
        private const val KEY_APPS = "apps"
    }
}
