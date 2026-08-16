package com.cheatervpnapp

data class SessionRecord(
    val id: Long,
    val serverLabel: String,
    val serverHost: String,
    val startTime: Long,
    val endTime: Long,
    val durationSec: Long,
    val rxBytes: Long,
    val txBytes: Long,
)

data class LiveStats(
    val elapsedSec: Long,
    val rxBytes: Long,
    val txBytes: Long,
)

data class Totals(
    val totalRx: Long,
    val totalTx: Long,
    val totalSessions: Long,
    val totalDurationSec: Long,
)
