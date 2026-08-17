package com.cheatervpnapp

import android.content.Context
import android.content.SharedPreferences

class KillSwitchStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun isActive(): Boolean = prefs.getBoolean(KEY_ACTIVE, false)

    fun setActive(active: Boolean) {
        prefs.edit().putBoolean(KEY_ACTIVE, active).apply()
    }

    companion object {
        private const val PREFS_NAME = "kill_switch"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_ACTIVE = "active"
    }
}
