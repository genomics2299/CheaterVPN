package com.cheatervpnapp

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log

class XrayVpnService : VpnService() {

    companion object {
        const val EXTRA_CONFIG = "extra_xray_config"
        const val ACTION_STOP = "com.cheatervpnapp.ACTION_XRAY_STOP"

        @Volatile
        private var lastConfigJson: String? = null
    }

    private var tunnel: ParcelFileDescriptor? = null

    override fun onCreate() {
        super.onCreate()
        XrayManager.get(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopXray()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val configJson = intent?.getStringExtra(EXTRA_CONFIG)
        if (configJson != null) lastConfigJson = configJson
        val currentConfig = lastConfigJson
        if (currentConfig.isNullOrBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val started = startXray(currentConfig)
        if (!started) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    @Suppress("VpnServicePolicy")
    private fun startXray(configJson: String): Boolean {
        if (prepare(this) != null) {
            Log.e("XrayVpnService", "VPN permission not granted")
            return false
        }

        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(1500)
            .addAddress("10.7.0.2", 30)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("1.0.0.1")

        runCatching { builder.addDisallowedApplication(packageName) }
        runCatching { builder.addDisallowedApplication("org.amnezia.awg.backend") }

        val fd = try {
            builder.establish()
        } catch (e: Exception) {
            Log.e("XrayVpnService", "establish failed", e)
            null
        } ?: return false
        tunnel = fd

        val ok = XrayManager.get(this).startTunnel(configJson, fd.fd)
        if (!ok) {
            runCatching { fd.close() }
            tunnel = null
            return false
        }
        return true
    }

    private fun stopXray() {
        XrayManager.get(this).stopTunnel()
        runCatching { tunnel?.close() }
        tunnel = null
    }

    override fun onRevoke() {
        stopXray()
        notifyDisconnected()
        super.onRevoke()
    }

override fun onDestroy() {
    val hadTunnel = tunnel != null
    stopXray()
    if (hadTunnel) notifyDisconnected()
    super.onDestroy()
}

    private fun notifyDisconnected() {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = VpnNotification.ACTION_DISCONNECTED
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        runCatching { startActivity(intent) }
    }
}