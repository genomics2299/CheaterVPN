package com.cheatervpnapp

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Formatters {

    private val timeFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

    fun bytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(Locale.ROOT, kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(Locale.ROOT, mb)
        return "%.2f GB".format(Locale.ROOT, mb / 1024.0)
    }

    fun durationClock(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    fun durationShort(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return buildString {
            if (h > 0) append("${h}h ")
            if (m > 0) append("${m}m ")
            if (s > 0 || (h == 0L && m == 0L)) append("${s}s")
        }.trim().ifEmpty { "0s" }
    }

    fun time(epochMillis: Long): String = timeFormat.format(Date(epochMillis))
}
