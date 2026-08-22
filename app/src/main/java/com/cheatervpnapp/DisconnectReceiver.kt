package com.cheatervpnapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class DisconnectReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != VpnNotification.ACTION_DISCONNECT) return

        if (XrayVpnService.isActive()) {
            XrayVpnService.requestStop(context)
        }
        AwgManager.get(context).stopTunnel()
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
