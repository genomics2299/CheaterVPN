package com.cheatervpnapp

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.util.Log

class XrayVpnService : VpnService() {

    companion object {
        const val EXTRA_CONFIG = "extra_xray_config"
        const val ACTION_STOP = "com.cheatervpnapp.ACTION_XRAY_STOP"
        const val ACTION_BIND = "com.cheatervpnapp.ACTION_BIND_XRAY"

        const val MSG_QUERY_STATE = 1
        const val MSG_QUERY_STATS = 2
        const val MSG_STATE_RESPONSE = 3
        const val MSG_STATS_RESPONSE = 4

        const val KEY_RUNNING = "xray_running"
        const val KEY_RX = "xray_rx"
        const val KEY_TX = "xray_tx"

        @Volatile
        private var lastConfigJson: String? = null
    }

    private var tunnel: ParcelFileDescriptor? = null

    private val messengerHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            val manager = XrayManager.get(this@XrayVpnService)
            when (msg.what) {
                MSG_QUERY_STATE -> {
                    val out = Message.obtain(null, MSG_STATE_RESPONSE)
                    out.data = Bundle().apply { putBoolean(KEY_RUNNING, manager.isRunning) }
                    msg.replyTo?.send(out)
                }
                MSG_QUERY_STATS -> {
                    val (rx, tx) = manager.trafficStats()
                    val out = Message.obtain(null, MSG_STATS_RESPONSE)
                    out.data = Bundle().apply {
                        putLong(KEY_RX, rx)
                        putLong(KEY_TX, tx)
                    }
                    msg.replyTo?.send(out)
                }
            }
        }
    }
    private val messenger = Messenger(messengerHandler)

    override fun onCreate() {
        super.onCreate()
        XrayManager.get(this)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return if (intent?.action == ACTION_BIND) messenger.binder else super.onBind(intent)
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