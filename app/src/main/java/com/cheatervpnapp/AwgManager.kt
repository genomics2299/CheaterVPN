package com.cheatervpnapp

import android.content.Context
import android.util.Log
import org.amnezia.awg.backend.Backend
import org.amnezia.awg.backend.GoBackend
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.config.Config
import java.io.ByteArrayInputStream

class AwgManager(context: Context) {

    private val appContext = context.applicationContext
    private val backend: Backend = GoBackend(appContext)

    private var currentTunnel: Tunnel? = null
    private var currentConfig: Config? = null
    private var userRequestedStop = false
    private var tunnelStateListener: TunnelStateListener? = null

    private val tunnelName = "vpnapp-tun"

    fun interface TunnelStateListener {
        fun onUnexpectedDisconnect()
    }

    fun setTunnelStateListener(listener: TunnelStateListener?) {
        tunnelStateListener = listener
    }

    fun parseConfigFile(content: String): Config {
        val cleaned = content.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .filterNot { Regex("^\\w+\\s*=\\s*$").matches(it) }
            .joinToString("\n")
        return Config.parse(ByteArrayInputStream(cleaned.toByteArray()))
    }

    fun startTunnel(config: Config) {
        if (isRunning) stopTunnel()
        bringUpTunnel(config)
    }

    fun restartTunnelKeepingBlocking(config: Config) {
        // Kill-switch aware reconnect: never tear down a still-running tunnel here,
        // otherwise the blocking barrier (setBlocking(true) + default route) is removed
        // and traffic can leak on the raw interface. If the tunnel dropped, bring it
        // back up so the barrier is restored immediately.
        if (!isRunning) bringUpTunnel(config)
    }

    private fun bringUpTunnel(config: Config) {
        userRequestedStop = false
        val configStr = config.toAwgQuickString()
        Log.d("AwgManager", "Config toGo: $configStr")
        val tunnel = object : Tunnel {
            override fun getName(): String = tunnelName
            override fun onStateChange(newState: Tunnel.State) {
                if (newState == Tunnel.State.DOWN && !userRequestedStop) {
                    tunnelStateListener?.onUnexpectedDisconnect()
                }
            }
        }
        backend.setState(tunnel, Tunnel.State.UP, config)
        currentTunnel = tunnel
        currentConfig = config
        val (rx, tx) = readTraffic()
        SessionTracker.start(ServerStore(appContext).selectedServer(), rx, tx)
    }

    fun stopTunnel() {
        userRequestedStop = true
        val (rx, tx) = readTraffic()
        SessionTracker.finish(appContext, rx, tx)
        currentTunnel?.let {
            backend.setState(it, Tunnel.State.DOWN, null)
        }
        currentTunnel = null
        currentConfig = null
    }

    fun liveStats(): LiveStats? {
        val t = currentTunnel ?: return null
        if (!SessionTracker.isActive) return null
        val (rx, tx) = readTraffic()
        return SessionTracker.snapshot(rx, tx)
    }

    fun buildConfigForServer(server: Server): String {
        val store = SplitTunnelStore(appContext)
        val apps = store.apps()
        if (apps.isEmpty()) return server.config
        return when (store.mode()) {
            SplitTunnelStore.Mode.EXCLUDE -> applySplitTunnel(server.config, apps, emptySet())
            SplitTunnelStore.Mode.INCLUDE -> applySplitTunnel(server.config, emptySet(), apps)
        }
    }

    private fun readTraffic(): Pair<Long, Long> {
        val t = currentTunnel ?: return 0L to 0L
        return runCatching {
            val s = backend.getStatistics(t)
            s.totalRx() to s.totalTx()
        }.getOrDefault(0L to 0L)
    }

    fun getVersion(): String = backend.version

    val isRunning: Boolean
        get() {
            val t = currentTunnel ?: return false
            return try {
                backend.getState(t) == Tunnel.State.UP
            } catch (_: Exception) {
                false
            }
        }

    companion object {
        @Volatile
        private var instance: AwgManager? = null

        fun get(context: Context): AwgManager =
            instance ?: synchronized(this) {
                instance ?: AwgManager(context.applicationContext).also { instance = it }
            }

        fun applySplitTunnel(content: String, excluded: Set<String>, included: Set<String>): String {
            if (excluded.isEmpty() && included.isEmpty()) return content
            val idx = content.lines().indexOfFirst { it.trim().equals("[interface]", ignoreCase = true) }
            if (idx == -1) return content
            val extra = buildList {
                if (excluded.isNotEmpty()) add("ExcludedApplications = ${excluded.sorted().joinToString(", ")}")
                if (included.isNotEmpty()) add("IncludedApplications = ${included.sorted().joinToString(", ")}")
            }
            val lines = content.lines().toMutableList()
            lines.addAll(idx + 1, extra)
            return lines.joinToString("\n")
        }
    }
}
