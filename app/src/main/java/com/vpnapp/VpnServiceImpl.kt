package com.vpnapp

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream

class VpnServiceImpl : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false

    private val localAddress = "10.0.0.2"
    private val localPrefix = 24
    private val dnsServers = listOf("8.8.8.8", "1.1.1.1")
    private val allowedApps: List<String>? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "VPN service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startVPN()
            ACTION_STOP -> stopVPN()
        }
        return START_STICKY
    }

    private fun startVPN() {
        if (isRunning) return

        val builder = Builder().apply {
            setSession("VPN Connection")
            setMtu(1500)

            addAddress(localAddress, localPrefix)
            addRoute("0.0.0.0", 0)

            for (dns in dnsServers) {
                addDnsServer(dns)
            }

            allowedApps?.let { apps ->
                for (app in apps) {
                    addAllowedApplication(app)
                }
            }

            setBlocking(true)
        }

        try {
            vpnInterface = builder.establish()
            isRunning = true

            startForeground()
            notifyState(true)

            vpnInterface?.let { fd ->
                serviceScope.launch {
                    handlePackets(fd)
                }
            }

            Log.d(TAG, "VPN started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN: ${e.message}")
            stopVPN()
        }
    }

    private fun startForeground() {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, App.CHANNEL_ID)
            .setContentTitle("VPN Active")
            .setContentText("VPN connection is running")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(App.NOTIFICATION_ID, notification)
    }

    private suspend fun handlePackets(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buffer = ByteArray(32767)

        while (isRunning) {
            try {
                val length = input.read(buffer)
                if (length > 0) {
                    val packet = buffer.copyOf(length)
                    processPacket(packet, output)
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(TAG, "Packet handling error: ${e.message}")
                }
                break
            }
        }

        try {
            input.close()
            output.close()
        } catch (_: Exception) {}
    }

    private fun processPacket(packet: ByteArray, output: FileOutputStream) {
        try {
            output.write(packet)
            output.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write packet: ${e.message}")
        }
    }

    private fun stopVPN() {
        isRunning = false
        serviceScope.cancel()

        try {
            vpnInterface?.close()
        } catch (_: Exception) {}

        vpnInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        notifyState(false)

        Log.d(TAG, "VPN stopped")
    }

    override fun onDestroy() {
        stopVPN()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopVPN()
    }

    private fun notifyState(connected: Boolean) {
        val intent = Intent(BROADCAST_STATE_CHANGE).apply {
            putExtra(EXTRA_CONNECTED, connected)
        }
        sendBroadcast(intent)
    }

    companion object {
        const val TAG = "VPNApp"
        const val ACTION_START = "com.vpnapp.START"
        const val ACTION_STOP = "com.vpnapp.STOP"
        const val BROADCAST_STATE_CHANGE = "com.vpnapp.STATE_CHANGE"
        const val EXTRA_CONNECTED = "connected"

        fun start(intent: Intent?) = intent?.action == ACTION_START
        fun stop(intent: Intent?) = intent?.action == ACTION_STOP
    }
}
