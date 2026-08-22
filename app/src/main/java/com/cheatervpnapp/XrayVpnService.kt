package com.cheatervpnapp

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.coroutines.coroutineContext

class XrayVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tunFd: Int = -1
    private lateinit var xrayManager: XrayManager
    private lateinit var killSwitchStore: KillSwitchStore

    override fun onCreate() {
        super.onCreate()
        xrayManager = XrayManager.get(this)
        killSwitchStore = KillSwitchStore(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        userStopRequested = false
        startAsForeground()
        scope.launch {
            val server = ServerStore(applicationContext).selectedServer()
            if (server == null || !server.isXray) {
                stopSelfSafely()
                return@launch
            }
            if (isActiveFlag) {
                return@launch
            }
            startTunnel(server)
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        val server = ServerStore(this).selectedServer()
        val notification = VpnNotification.buildForeground(this, server)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                FOREGROUND_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(FOREGROUND_ID, notification)
        }
    }

    private suspend fun startTunnel(server: Server) {
        if (!HevTunnel.available) {
            fail(getString(R.string.xray_start_failed))
            return
        }

        if (!xrayManager.startProcess(server)) {
            fail(getString(R.string.xray_start_failed))
            return
        }

        val cfgFile = writeHevConfig()
        val pfd = runCatching { establishTun() }.getOrNull()
        if (pfd == null) {
            xrayManager.stopProcess()
            fail(getString(R.string.err_unable_start_vpn))
            return
        }
        tunFd = pfd.detachFd()

        val started = HevTunnel.TProxyStartService(cfgFile.absolutePath, tunFd)
        if (!started) {
            closeTunFd()
            xrayManager.stopProcess()
            fail(getString(R.string.xray_start_failed))
            return
        }

        isActiveFlag = true
        SessionTracker.start(server, 0L, 0L)

        if (killSwitchStore.isEnabled()) {
            killSwitchStore.setActive(true)
            VpnNotification.cancelKillSwitchAlert(this)
        }

        VpnNotification.showConnected(this, server)
        VpnWidgetProvider.updateAllWidgets(this)
        VpnTileService.requestUpdate(this)

        monitorLoop(server)
    }

    private suspend fun monitorLoop(server: Server) {
        var restartAttempts = 0
        while (coroutineContext.isActive && isActiveFlag && !userStopRequested) {
            delay(MONITOR_INTERVAL_MS)
            val coreAlive = xrayManager.isAlive() && HevTunnel.TProxyIsRunning()
            if (coreAlive) continue

            if (killSwitchStore.isEnabled()) {
                VpnNotification.showKillSwitchAlert(this)
                Toast.makeText(
                    this,
                    getString(R.string.kill_switch_reconnecting),
                    Toast.LENGTH_LONG,
                ).show()
                restartAttempts++
                if (restartAttempts <= MAX_RESTART_ATTEMPTS && xrayManager.startProcess(server)) {
                    continue
                }
            }
            break
        }
        if (!userStopRequested && isActiveFlag) {
            isActiveFlag = false
            cleanupCore()
            notifyDisconnected()
        }
        stopSelfSafely()
    }

    private fun establishTun(): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession("CheaterVPN/Xray")
            .setMtu(TUN_MTU)
            .addAddress("198.18.0.1", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(XrayManager.DNS_FAKE_IP)
            .addDisallowedApplication(packageName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.addAddress("fc00::1", 128)
            builder.addRoute("::", 0)
        }
        return builder.establish()
    }

    private fun writeHevConfig(): File {
        val dir = File(filesDir, "xray").apply { mkdirs() }
        val file = File(dir, "hev.yml")
        val logFile = File(dir, "hev.log")
        file.writeText(
            """
            tunnel:
              name: tun0
              mtu: $TUN_MTU
              multi-queue: false
              ipv4: 198.18.0.1
              ipv6: 'fc00::1'
              icmp: 'off'
            socks5:
              port: ${XrayManager.SOCKS_PORT}
              address: 127.0.0.1
              udp: 'udp'
            mapdns:
              address: ${XrayManager.DNS_FAKE_IP}
              port: 53
              network: 198.18.0.0
              netmask: 255.192.0.0
              cache-size: 10000
            misc:
              log-file: '${logFile.absolutePath}'
              log-level: warn
            """.trimIndent(),
        )
        return file
    }

    private fun fail(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        stopSelfSafely()
    }

    private fun notifyDisconnected() {
        VpnNotification.cancel(this)
        VpnWidgetProvider.updateAllWidgets(this)
        VpnTileService.requestUpdate(this)
        val appIntent = Intent(this, MainActivity::class.java).apply {
            action = VpnNotification.ACTION_DISCONNECTED
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(appIntent)
    }

    private fun closeTunFd() {
        if (tunFd != -1) {
            runCatching { ParcelFileDescriptor.adoptFd(tunFd).close() }
            tunFd = -1
        }
    }

    private fun cleanupCore() {
        runCatching { HevTunnel.TProxyStopService() }
        xrayManager.stopProcess()
        closeTunFd()
        val stats = runCatching { HevTunnel.TProxyGetStats() }.getOrNull()
        val rx = stats?.getOrNull(3) ?: 0L
        val tx = stats?.getOrNull(1) ?: 0L
        SessionTracker.finish(this, rx, tx)
    }

    private fun stopSelfSafely() {
        runCatching { stopSelf() }
    }

    override fun onDestroy() {
        userStopRequested = true
        scope.cancel()
        if (isActiveFlag) {
            isActiveFlag = false
            cleanupCore()
        } else {
            runCatching { HevTunnel.TProxyStopService() }
            xrayManager.stopProcess()
            closeTunFd()
        }
        super.onDestroy()
    }

    companion object {
        const val FOREGROUND_ID = 1003
        const val EXTRA_SERVER_ID = "com.cheatervpnapp.EXTRA_SERVER_ID"
        private const val MONITOR_INTERVAL_MS = 3000L
        private const val MAX_RESTART_ATTEMPTS = 3
        private const val TUN_MTU = 8500

        @Volatile
        var isActiveFlag: Boolean = false
            private set

        @Volatile
        private var userStopRequested: Boolean = false

        fun isActive(): Boolean = isActiveFlag

        fun requestStart(context: Context) {
            userStopRequested = false
            context.startForegroundService(Intent(context, XrayVpnService::class.java))
        }

        fun requestStop(context: Context) {
            userStopRequested = true
            context.stopService(Intent(context, XrayVpnService::class.java))
        }

        fun liveStats(): LiveStats? {
            if (!isActiveFlag) return null
            val stats = runCatching { HevTunnel.TProxyGetStats() }.getOrNull() ?: return null
            return SessionTracker.snapshot(stats.getOrNull(3) ?: 0L, stats.getOrNull(1) ?: 0L)
        }
    }
}
