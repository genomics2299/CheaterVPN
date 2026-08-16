package com.cheatervpnapp

import android.content.Context
import android.os.SystemClock

object SessionTracker {

    private var active = false
    private var startedAtElapsed = 0L
    private var startedAtEpoch = 0L
    private var baselineRx = 0L
    private var baselineTx = 0L
    private var serverLabel = ""
    private var serverHost = ""

    fun start(server: Server?, rx: Long, tx: Long) {
        active = true
        startedAtElapsed = SystemClock.elapsedRealtime()
        startedAtEpoch = System.currentTimeMillis()
        baselineRx = rx
        baselineTx = tx
        serverLabel = server?.country?.ifEmpty { server.name } ?: server?.name ?: ""
        serverHost = server?.host ?: ""
    }

    fun finish(context: Context, rx: Long, tx: Long) {
        if (!active) return
        active = false
        val durationSec = (SystemClock.elapsedRealtime() - startedAtElapsed) / 1000
        StatsStore.saveSession(
            context,
            SessionRecord(
                id = startedAtEpoch,
                serverLabel = serverLabel,
                serverHost = serverHost,
                startTime = startedAtEpoch,
                endTime = System.currentTimeMillis(),
                durationSec = durationSec,
                rxBytes = maxOf(0L, rx - baselineRx),
                txBytes = maxOf(0L, tx - baselineTx),
            )
        )
    }

    fun snapshot(rx: Long, tx: Long): LiveStats? {
        if (!active) return null
        return LiveStats(
            elapsedSec = (SystemClock.elapsedRealtime() - startedAtElapsed) / 1000,
            rxBytes = maxOf(0L, rx - baselineRx),
            txBytes = maxOf(0L, tx - baselineTx),
        )
    }

    val isActive: Boolean get() = active

    val currentServerLabel: String get() = serverLabel
}
