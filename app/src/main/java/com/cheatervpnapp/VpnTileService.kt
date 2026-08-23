package com.cheatervpnapp

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class VpnTileService : TileService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onStartListening() {
        updateTile()
    }

    override fun onTileAdded() {
        updateTile()
        TileService.requestListeningState(this, ComponentName(this, VpnTileService::class.java))
    }

    override fun onTileRemoved() {
        TileService.requestListeningState(this, ComponentName(this, VpnTileService::class.java))
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        scope.launch {
            val awg = AwgManager.get(this@VpnTileService)

            if (awg.isRunning) {
                awg.stopTunnel()
                VpnNotification.cancel(this@VpnTileService)
            } else {
                val server = ServerStore(this@VpnTileService).selectedServer()
                if (server == null) {
                    openApp(getString(R.string.select_server_first))
                    return@launch
                }
                val config = runCatching { awg.parseConfigFile(awg.buildConfigForServer(server)) }.getOrNull()
                if (config == null) {
                    openApp(getString(R.string.invalid_config))
                    return@launch
                }
                if (VpnService.prepare(this@VpnTileService) != null) {
                    openApp(getString(R.string.vpn_permission_required))
                    return@launch
                }
                val started = runCatching {
                    awg.startTunnel(config)
                    VpnNotification.showConnected(this@VpnTileService, server)
                }.isSuccess
                if (!started) {
                    openApp(getString(R.string.connection_failed_generic))
                    return@launch
                }
            }
            updateTile()
            VpnWidgetProvider.updateAllWidgets(this@VpnTileService)
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val running = AwgManager.get(this).isRunning
        tile.label = getString(R.string.tile_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_notification)
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = if (running) getString(R.string.connected) else getString(R.string.disconnected)
        tile.updateTile()
    }

    private fun openApp(reason: String) {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?.apply {
                putExtra(VpnWidgetProvider.EXTRA_WIDGET_MESSAGE, reason)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            } ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val pending = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        fun requestUpdate(context: Context) {
            TileService.requestListeningState(
                context,
                ComponentName(context, VpnTileService::class.java)
            )
        }
    }
}
