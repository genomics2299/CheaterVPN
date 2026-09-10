package com.cheatervpnapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class DisconnectReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != VpnNotification.ACTION_DISCONNECT) return

        AwgManager.get(context).stopTunnel()
        runCatching {
            context.startService(
                Intent(context, XrayVpnService::class.java).setAction(XrayVpnService.ACTION_STOP)
            )
        }
        context.stopService(Intent(context, XrayVpnService::class.java))
        VpnNotification.cancel(context)

        val appIntent = Intent(context, MainActivity::class.java).apply {
            action = VpnNotification.ACTION_DISCONNECTED
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        context.startActivity(appIntent)
    }
}
