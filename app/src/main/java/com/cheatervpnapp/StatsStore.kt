package com.cheatervpnapp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object StatsStore {

    private const val PREFS = "stats"
    private const val KEY_HISTORY = "history"
    private const val KEY_TOTAL_RX = "total_rx"
    private const val KEY_TOTAL_TX = "total_tx"
    private const val KEY_TOTAL_SESSIONS = "total_sessions"
    private const val KEY_TOTAL_DURATION = "total_duration"
    private const val MAX_HISTORY = 100

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun saveSession(context: Context, record: SessionRecord) {
        val p = prefs(context)
        val history = loadHistory(context).toMutableList()
        history.add(0, record)
        while (history.size > MAX_HISTORY) history.removeAt(history.size - 1)
        val arr = JSONArray()
        history.forEach { arr.put(it.toJson()) }
        p.edit()
            .putString(KEY_HISTORY, arr.toString())
            .putLong(KEY_TOTAL_RX, p.getLong(KEY_TOTAL_RX, 0) + record.rxBytes)
            .putLong(KEY_TOTAL_TX, p.getLong(KEY_TOTAL_TX, 0) + record.txBytes)
            .putLong(KEY_TOTAL_SESSIONS, p.getLong(KEY_TOTAL_SESSIONS, 0) + 1)
            .putLong(KEY_TOTAL_DURATION, p.getLong(KEY_TOTAL_DURATION, 0) + record.durationSec)
            .apply()
    }

    fun loadHistory(context: Context): List<SessionRecord> {
        val json = prefs(context).getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            buildList {
                for (i in 0 until arr.length()) {
                    add(arr.getJSONObject(i).toSession())
                }
            }
        }.getOrDefault(emptyList())
    }

    fun totals(context: Context): Totals {
        val p = prefs(context)
        return Totals(
            totalRx = p.getLong(KEY_TOTAL_RX, 0),
            totalTx = p.getLong(KEY_TOTAL_TX, 0),
            totalSessions = p.getLong(KEY_TOTAL_SESSIONS, 0),
            totalDurationSec = p.getLong(KEY_TOTAL_DURATION, 0),
        )
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun SessionRecord.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("serverLabel", serverLabel)
        put("serverHost", serverHost)
        put("startTime", startTime)
        put("endTime", endTime)
        put("durationSec", durationSec)
        put("rxBytes", rxBytes)
        put("txBytes", txBytes)
    }

    private fun JSONObject.toSession(): SessionRecord = SessionRecord(
        id = getLong("id"),
        serverLabel = optString("serverLabel"),
        serverHost = optString("serverHost"),
        startTime = getLong("startTime"),
        endTime = getLong("endTime"),
        durationSec = getLong("durationSec"),
        rxBytes = getLong("rxBytes"),
        txBytes = getLong("txBytes"),
    )
}
