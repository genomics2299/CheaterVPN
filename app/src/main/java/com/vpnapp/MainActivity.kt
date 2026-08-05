package com.vpnapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.vpnapp.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.amnezia.awg.backend.BackendException
import org.amnezia.awg.config.Config

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var awgManager: AwgManager
    private lateinit var serverStore: ServerStore
    private lateinit var adapter: ServerAdapter

    private var servers = emptyList<Server>()
    private var selectedServer: Server? = null
    private var isConnected = false
    private var pingJobs = mutableMapOf<String, Job>()
    private var pingLoop: Job? = null
    private var restartJob: Job? = null
    private lateinit var connectivityManager: ConnectivityManager
    private var lastNetworkKey: Long? = null
    private var lastRestartAt = 0L

    private companion object {
        const val RESTART_DEBOUNCE_MS = 3000L
        const val RESTART_COOLDOWN_MS = 8000L
    }

    private fun exceptionMessage(e: Exception): String {
        if (e is BackendException) {
            val code = e.format?.firstOrNull()?.toString()
            val base = when (e.reason) {
                BackendException.Reason.VPN_NOT_AUTHORIZED -> R.string.err_vpn_not_authorized
                BackendException.Reason.TUNNEL_MISSING_CONFIG -> R.string.err_tunnel_missing_config
                BackendException.Reason.UNABLE_TO_START_VPN -> R.string.err_unable_start_vpn
                BackendException.Reason.TUN_CREATION_ERROR -> R.string.err_tun_creation
                BackendException.Reason.GO_ACTIVATION_ERROR_CODE -> R.string.err_go_activation
                BackendException.Reason.DNS_RESOLUTION_FAILURE -> R.string.err_dns_resolution
                BackendException.Reason.SERVICE_NOT_RUNNING -> R.string.err_service_not_running
                else -> R.string.err_generic
            }
            val text = getString(base)
            return if (e.reason == BackendException.Reason.GO_ACTIVATION_ERROR_CODE && !code.isNullOrEmpty()) {
                "$text ($code)"
            } else {
                text
            }
        }
        val msg = e.cause?.message ?: e.message
        return if (msg.isNullOrBlank()) e.javaClass.simpleName else msg
    }

    private fun logError(tag: String, message: String, e: Exception) {
        Log.e(tag, message, e)
    }

    private val connectivityCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
            val handle = network.getNetworkHandle()
            val usable = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            if (handle == lastNetworkKey) {
                if (!usable) {
                    lastNetworkKey = null
                    scheduleVpnRestart()
                }
                return
            }
            if (usable && lastNetworkKey == null) {
                lastNetworkKey = handle
                scheduleVpnRestart()
            }
        }

        override fun onLost(network: Network) {
            if (network.getNetworkHandle() == lastNetworkKey) {
                lastNetworkKey = null
                scheduleVpnRestart()
            }
        }
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            connectVpn()
        } else {
            Toast.makeText(this, getString(R.string.vpn_permission_denied), Toast.LENGTH_SHORT).show()
        }
    }

    private val configPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { importConfig(it) }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        awgManager = AwgManager.get(this)
        serverStore = ServerStore(this)
        connectivityManager = getSystemService(ConnectivityManager::class.java)

        adapter = ServerAdapter(
            onClick = { server -> selectServer(server) },
            onLongClick = { server -> deleteServer(server) },
        )
        binding.rvServers.layoutManager = LinearLayoutManager(this)
        binding.rvServers.adapter = adapter
        binding.rvServers.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))

        binding.btnImportConfig.setOnClickListener {
            configPickerLauncher.launch(arrayOf("*/*"))
        }

        binding.btnSplitTunnel.setOnClickListener {
            startActivity(Intent(this, SplitTunnelActivity::class.java))
        }

        binding.btnToggle.setOnClickListener {
            if (isConnected) {
                disconnectVpn()
            } else {
                connectVpn()
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        loadServers()
        val request = NetworkRequest.Builder().build()
        connectivityManager.registerNetworkCallback(request, connectivityCallback)

        warmUpVpnService()

        intent?.getStringExtra(VpnWidgetProvider.EXTRA_WIDGET_MESSAGE)?.let {
            Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
        }
    }

    private fun warmUpVpnService() {
        try {
            val intent = Intent().setClassName(this, "org.amnezia.awg.backend.AbstractBackend\$VpnService")
            startService(intent)
        } catch (_: Exception) {
        }
    }

    override fun onResume() {
        super.onResume()
        isConnected = awgManager.isRunning
        updateUI()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(VpnWidgetProvider.EXTRA_WIDGET_MESSAGE)?.let {
            Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
        }
        if (intent.action == VpnNotification.ACTION_DISCONNECTED) {
            isConnected = false
            updateUI()
        }
    }

    private fun hasUsableUnderlyingNetwork(): Boolean {
        return connectivityManager.allNetworks.any { network ->
            val caps = runCatching { connectivityManager.getNetworkCapabilities(network) }.getOrNull()
            caps != null &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }

    private fun anchorCurrentNetwork() {
        lastNetworkKey = connectivityManager.allNetworks
            .firstOrNull { network ->
                val caps = runCatching { connectivityManager.getNetworkCapabilities(network) }.getOrNull()
                caps != null &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
            ?.getNetworkHandle()
    }

    private fun scheduleVpnRestart() {
        if (!isConnected) return
        restartJob?.cancel()
        restartJob = lifecycleScope.launch {
            delay(RESTART_DEBOUNCE_MS)
            if (!isConnected) return@launch
            if (SystemClock.elapsedRealtime() - lastRestartAt < RESTART_COOLDOWN_MS) return@launch
            if (!hasUsableUnderlyingNetwork()) return@launch
            lastRestartAt = SystemClock.elapsedRealtime()
            val server = selectedServer ?: return@launch
            val config = runCatching { awgManager.parseConfigFile(splitTunnelConfig(server)) }.getOrElse { return@launch }
            withContext(Dispatchers.IO) {
                try {
                    awgManager.stopTunnel()
                    awgManager.startTunnel(config)
                    anchorCurrentNetwork()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, getString(R.string.vpn_restarted_network), Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    logError("MainActivity", "Auto-restart failed", e)
                    val msg = exceptionMessage(e)
                    withContext(Dispatchers.Main) {
                        isConnected = false
                        updateUI()
                        Toast.makeText(this@MainActivity, getString(R.string.vpn_restart_failed, msg), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun loadServers() {
        servers = serverStore.load()
        adapter.submitList(servers)
        val savedId = serverStore.loadSelectedId()
        selectedServer = servers.firstOrNull { it.id == savedId }
        adapter.setSelected(selectedServer?.id)
        updateUI()
        startAllPings()
        startPingLoop()
    }

    private fun startPingLoop() {
        pingLoop?.cancel()
        pingLoop = lifecycleScope.launch {
            while (isActive) {
                delay(20000)
                if (servers.isNotEmpty()) startAllPings()
            }
        }
    }

    private fun startAllPings() {
        servers.forEach { server -> startPing(server) }
    }

    private fun startPing(server: Server) {
        pingJobs[server.id]?.cancel()
        pingJobs[server.id] = lifecycleScope.launch {
            val ping = PingChecker.ping(server.host, server.port)
            adapter.setPing(server.id, ping)
        }
    }

    private fun importConfig(uri: android.net.Uri) {
        lifecycleScope.launch {
            val text = runCatching {
                val inputStream = contentResolver.openInputStream(uri)
                inputStream?.bufferedReader()?.use { it.readText() } ?: ""
            }.getOrElse {
                Toast.makeText(this@MainActivity, getString(R.string.failed_read_config), Toast.LENGTH_SHORT).show()
                return@launch
            }

            if (text.isBlank()) {
                Toast.makeText(this@MainActivity, getString(R.string.empty_config), Toast.LENGTH_SHORT).show()
                return@launch
            }

            val parseResult = runCatching { awgManager.parseConfigFile(text) }
            if (parseResult.isFailure) {
                Toast.makeText(this@MainActivity, getString(R.string.invalid_config_detail, parseResult.exceptionOrNull()?.message), Toast.LENGTH_LONG).show()
                return@launch
            }

            val endpoint = Server.parseEndpoint(text)
            var name = fileDisplayName(uri).substringBeforeLast('.').ifEmpty { "Server" }
            var country = ""
            var countryCode = ""
            if (endpoint != null) {
                CountryResolver.resolveCountry(endpoint.first)?.let { (c, code) ->
                    country = c
                    countryCode = code
                    name = c
                }
            }

            val server = Server(
                id = System.currentTimeMillis().toString(),
                name = name,
                country = country,
                countryCode = countryCode,
                host = endpoint?.first.orEmpty(),
                port = endpoint?.second ?: 0,
                config = text,
            )

            if (servers.any { it.config == text }) {
                Toast.makeText(this@MainActivity, getString(R.string.config_imported), Toast.LENGTH_SHORT).show()
                return@launch
            }

            servers = servers + server
            serverStore.save(servers)
            adapter.submitList(servers)
            startPing(server)
            Toast.makeText(this@MainActivity, getString(R.string.server_added), Toast.LENGTH_SHORT).show()
        }
    }

    private fun fileDisplayName(uri: android.net.Uri): String {
        return runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx != -1 && cursor.moveToFirst()) cursor.getString(idx) else ""
            }
        }.getOrDefault("") ?: ""
    }

    private fun selectServer(server: Server) {
        if (isConnected) {
            Toast.makeText(this, getString(R.string.disconnect_first), Toast.LENGTH_SHORT).show()
            return
        }
        selectedServer = server
        serverStore.saveSelectedId(server.id)
        adapter.setSelected(server.id)
        Toast.makeText(this, getString(R.string.selected_server, server.country.ifEmpty { server.name }), Toast.LENGTH_SHORT).show()
    }

    private fun deleteServer(server: Server) {
        if (isConnected) {
            Toast.makeText(this, getString(R.string.disconnect_first), Toast.LENGTH_SHORT).show()
            return
        }
        pingJobs[server.id]?.cancel()
        servers = servers.filterNot { it.id == server.id }
        serverStore.save(servers)
        if (selectedServer?.id == server.id) {
            selectedServer = null
            serverStore.saveSelectedId(null)
        }
        adapter.submitList(servers)
        adapter.setSelected(selectedServer?.id)
        Toast.makeText(this, getString(R.string.server_deleted), Toast.LENGTH_SHORT).show()
    }

    private fun splitTunnelConfig(server: Server): String {
        val store = SplitTunnelStore(this)
        val apps = store.apps()
        if (apps.isEmpty()) return server.config
        return when (store.mode()) {
            SplitTunnelStore.Mode.EXCLUDE -> AwgManager.applySplitTunnel(server.config, apps, emptySet())
            SplitTunnelStore.Mode.INCLUDE -> AwgManager.applySplitTunnel(server.config, emptySet(), apps)
        }
    }

    private fun connectVpn() {
        val server = selectedServer ?: run {
            Toast.makeText(this, getString(R.string.select_server_first), Toast.LENGTH_SHORT).show()
            return
        }

        val config = runCatching { awgManager.parseConfigFile(splitTunnelConfig(server)) }.getOrElse {
            Toast.makeText(this, getString(R.string.invalid_config), Toast.LENGTH_SHORT).show()
            return
        }

        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            startTunnel(config)
        }
    }

    private fun startTunnel(config: Config) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                awgManager.startTunnel(config)
                anchorCurrentNetwork()
                lastRestartAt = SystemClock.elapsedRealtime()
                withContext(Dispatchers.Main) {
                    isConnected = true
                    updateUI()
                    VpnWidgetProvider.updateAllWidgets(this@MainActivity)
                    VpnTileService.requestUpdate(this@MainActivity)
                    selectedServer?.let { VpnNotification.showConnected(this@MainActivity, it) }
                    Toast.makeText(this@MainActivity, getString(R.string.vpn_connected), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                logError("MainActivity", "Connect failed", e)
                val msg = exceptionMessage(e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, getString(R.string.connection_failed, msg), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun disconnectVpn() {
        restartJob?.cancel()
        lastNetworkKey = null
        lastRestartAt = SystemClock.elapsedRealtime()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                awgManager.stopTunnel()
                withContext(Dispatchers.Main) {
                    isConnected = false
                    VpnNotification.cancel(this@MainActivity)
                    VpnWidgetProvider.updateAllWidgets(this@MainActivity)
                    VpnTileService.requestUpdate(this@MainActivity)
                    updateUI()
                    Toast.makeText(this@MainActivity, getString(R.string.vpn_disconnected), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                logError("MainActivity", "Disconnect failed", e)
                val msg = exceptionMessage(e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, getString(R.string.error_generic, msg), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateUI() {
        binding.btnToggle.text = if (isConnected) getString(R.string.disconnect) else getString(R.string.connect)
        binding.tvStatus.text = if (isConnected) getString(R.string.connected) else getString(R.string.disconnected)
        binding.tvStatus.setTextColor(
            if (isConnected) getColor(android.R.color.holo_green_dark)
            else getColor(android.R.color.darker_gray)
        )
        binding.btnImportConfig.isEnabled = !isConnected
    }

    override fun onDestroy() {
        runCatching { connectivityManager.unregisterNetworkCallback(connectivityCallback) }
        pingLoop?.cancel()
        restartJob?.cancel()
        pingJobs.values.forEach { it.cancel() }
        if (isConnected) {
            runCatching { awgManager.stopTunnel() }
        }
        super.onDestroy()
    }
}
