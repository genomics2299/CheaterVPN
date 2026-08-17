package com.cheatervpnapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object VpnNotification {

    const val CHANNEL_ID = "vpn_status"
    const val KILL_SWITCH_CHANNEL_ID = "kill_switch_alert"
    const val NOTIFICATION_ID = 1001
    const val KILL_SWITCH_NOTIFICATION_ID = 1002
    const val ACTION_DISCONNECT = "com.cheatervpnapp.ACTION_DISCONNECT"
    const val ACTION_DISCONNECTED = "com.cheatervpnapp.ACTION_DISCONNECTED"

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.notif_channel_desc)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
        if (manager.getNotificationChannel(KILL_SWITCH_CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                KILL_SWITCH_CHANNEL_ID,
                context.getString(R.string.kill_switch_channel),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.kill_switch_channel_desc)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    fun showConnected(context: Context, server: Server) {
        ensureChannel(context)
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return

        val disconnectIntent = Intent(context, DisconnectReceiver::class.java)
            .setAction(ACTION_DISCONNECT)
        val disconnectPending = PendingIntent.getBroadcast(
            context,
            0,
            disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_title))
            .setContentText("${server.country.ifEmpty { server.name }} — ${server.host}")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, context.getString(R.string.notif_disconnect_action), disconnectPending)
            .build()

        nm.notify(NOTIFICATION_ID, notification)
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    fun showKillSwitchAlert(context: Context) {
        ensureChannel(context)
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return

        val notification = NotificationCompat.Builder(context, KILL_SWITCH_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.kill_switch_title))
            .setContentText(context.getString(R.string.kill_switch_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        nm.notify(KILL_SWITCH_NOTIFICATION_ID, notification)
    }

    fun cancelKillSwitchAlert(context: Context) {
        NotificationManagerCompat.from(context).cancel(KILL_SWITCH_NOTIFICATION_ID)
    }
}
