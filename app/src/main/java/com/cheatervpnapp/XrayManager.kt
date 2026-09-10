package com.cheatervpnapp

import android.content.Context
import android.provider.Settings
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import go.Seq
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray

class XrayManager(context: Context) {

    private val appContext = context.applicationContext
    private val initialized = AtomicBoolean(false)
    private var controller: CoreController? = null
    private val running = AtomicBoolean(false)

    @Volatile
    var stateListener: (() -> Unit)? = null

    private fun ensureInitialized() {
        if (initialized.compareAndSet(false, true)) {
            Seq.setContext(appContext)
            val assetPath = appContext.filesDir.absolutePath
            val deviceId = runCatching {
                Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
            }.getOrNull() ?: ""
            Libv2ray.initCoreEnv(assetPath, deviceId)
        }
    }

    val version: String
        get() {
            return runCatching {
                ensureInitialized()
                Libv2ray.checkVersionX()
            }.getOrNull() ?: "unknown"
        }

    fun startTunnel(configJson: String, tunFd: Int): Boolean {
        stopTunnel()
        return try {
            ensureInitialized()
            val controller = Libv2ray.newCoreController(object : CoreCallbackHandler {
                override fun startup(): Long = 0L
                override fun shutdown(): Long = 0L
                override fun onEmitStatus(status: Long, msg: String?): Long = 0L
            })
            this.controller = controller
            controller.startLoop(configJson, tunFd)
            if (controller.isRunning) {
                running.set(true)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e("XrayManager", "startLoop failed", e)
            false
        }
    }

    fun stopTunnel() {
        running.set(false)
        val controller = this.controller ?: return
        this.controller = null
        runCatching { controller.stopLoop() }
        stateListener?.invoke()
    }

    val isRunning: Boolean
        get() = running.get() && runCatching { controller?.isRunning == true }.getOrDefault(false)

    fun trafficStats(): Pair<Long, Long> {
        val controller = this.controller ?: return 0L to 0L
        if (!running.get()) return 0L to 0L
        var rx = 0L
        var tx = 0L
        val payload = runCatching { controller.queryAllOutboundTrafficStats() }.getOrNull() ?: ""
        payload.split(';').forEach { entry ->
            val parts = entry.split(',', limit = 3)
            if (parts.size != 3) return@forEach
            val value = parts[2].toLongOrNull() ?: return@forEach
            when (parts[1]) {
                "uplink" -> rx += value
                "downlink" -> tx += value
            }
        }
        return rx to tx
    }

    companion object {
        @Volatile
        private var instance: XrayManager? = null

        fun get(context: Context): XrayManager =
            instance ?: synchronized(this) {
                instance ?: XrayManager(context.applicationContext).also {
                    instance = it
                }
            }
    }
}