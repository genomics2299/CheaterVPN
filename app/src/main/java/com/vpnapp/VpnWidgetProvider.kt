package com.vpnapp

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.widget.RemoteViews

class VpnWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { id -> updateWidget(context, appWidgetManager, id) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TOGGLE) {
            handleToggle(context)
        }
    }

    private fun handleToggle(context: Context) {
        val awg = AwgManager.get(context)

        if (awg.isRunning) {
            awg.stopTunnel()
            VpnNotification.cancel(context)
        } else {
            val server = ServerStore(context).selectedServer()
            if (server == null) {
                openApp(context, context.getString(R.string.select_server_first))
                return
            }
            val config = runCatching { awg.parseConfigFile(server.config) }.getOrNull()
            if (config == null) {
                openApp(context, context.getString(R.string.invalid_config))
                return
            }
            if (VpnService.prepare(context) != null) {
                openApp(context, context.getString(R.string.vpn_permission_required))
                return
            }
            val started = runCatching {
                awg.startTunnel(config)
                VpnNotification.showConnected(context, server)
            }.isSuccess
            if (!started) openApp(context, context.getString(R.string.connection_failed_generic))
        }
        updateAllWidgets(context)
    }

    private fun openApp(context: Context, reason: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.apply { putExtra(EXTRA_WIDGET_MESSAGE, reason) }
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
    }

    companion object {

        const val ACTION_TOGGLE = "com.vpnapp.ACTION_WIDGET_TOGGLE"
        const val EXTRA_WIDGET_MESSAGE = "com.vpnapp.EXTRA_WIDGET_MESSAGE"

        fun updateAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, VpnWidgetProvider::class.java)
            )
            ids.forEach { id -> updateWidget(context, manager, id) }
        }

        private fun updateWidget(
            context: Context,
            manager: AppWidgetManager,
            appWidgetId: Int,
        ) {
            val running = AwgManager.get(context).isRunning
            val views = RemoteViews(context.packageName, R.layout.widget_vpn)
            views.setTextViewText(
                R.id.widget_status,
                if (running) context.getString(R.string.connected) else context.getString(R.string.disconnected)
            )
            views.setTextViewText(
                R.id.widget_toggle,
                if (running) context.getString(R.string.disconnect) else context.getString(R.string.connect)
            )

            val toggleIntent = Intent(context, VpnWidgetProvider::class.java)
                .setAction(ACTION_TOGGLE)
            val pending = PendingIntent.getBroadcast(
                context,
                appWidgetId,
                toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_toggle, pending)
            views.setOnClickPendingIntent(R.id.widget_status, pending)

            manager.updateAppWidget(appWidgetId, views)
        }
    }
}
