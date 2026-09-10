package com.cheatervpnapp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger

class XrayBridge(context: Context) {

    private val appContext = context.applicationContext
    private var bound = false
    private var serviceMessenger: Messenger? = null

    @Volatile
    var isVpnRunning = false
        private set

    @Volatile
    var onStateChanged: ((Boolean) -> Unit)? = null

    @Volatile
    private var rx = 0L

    @Volatile
    private var tx = 0L

    private val responseHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                XrayVpnService.MSG_STATE_RESPONSE -> {
                    val running = msg.data.getBoolean(XrayVpnService.KEY_RUNNING)
                    isVpnRunning = running
                    onStateChanged?.invoke(running)
                }
                XrayVpnService.MSG_STATS_RESPONSE -> {
                    rx = msg.data.getLong(XrayVpnService.KEY_RX)
                    tx = msg.data.getLong(XrayVpnService.KEY_TX)
                }
            }
        }
    }
    private val responseMessenger = Messenger(responseHandler)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            serviceMessenger = Messenger(binder)
            bound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            serviceMessenger = null
            if (isVpnRunning) {
                isVpnRunning = false
                onStateChanged?.invoke(false)
            }
        }
    }

    fun bind() {
        if (bound) return
        runCatching {
            appContext.bindService(
                Intent(appContext, XrayVpnService::class.java).setAction(XrayVpnService.ACTION_BIND),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        }
    }

    fun unbind() {
        if (!bound) return
        bound = false
        runCatching { appContext.unbindService(connection) }
        serviceMessenger = null
    }

    fun queryState() = send(XrayVpnService.MSG_QUERY_STATE)

    fun queryStats() = send(XrayVpnService.MSG_QUERY_STATS)

    fun trafficStats(): Pair<Long, Long> = rx to tx

    fun stop() {
        unbind()
        runCatching {
            appContext.startService(
                Intent(appContext, XrayVpnService::class.java).setAction(XrayVpnService.ACTION_STOP)
            )
        }
        runCatching { appContext.stopService(Intent(appContext, XrayVpnService::class.java)) }
    }

    private fun send(what: Int) {
        val messenger = serviceMessenger ?: return
        runCatching {
            val msg = Message.obtain(null, what)
            msg.replyTo = responseMessenger
            messenger.send(msg)
        }
    }
}