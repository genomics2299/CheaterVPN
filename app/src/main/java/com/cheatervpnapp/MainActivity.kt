package com.cheatervpnapp

import android.Manifest
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.cheatervpnapp.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.amnezia.awg.backend.BackendException
import org.amnezia.awg.config.Config
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Inflater

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var awgManager: AwgManager
    private lateinit var serverStore: ServerStore
    private lateinit var killSwitchStore: KillSwitchStore
    private lateinit var adapter: ServerAdapter

    private var servers = emptyList<Server>()
    private var selectedServer: Server? = null
    private var isConnected = false
    private var pingJobs = mutableMapOf<String, Job>()
    private var pingLoop: Job? = null
    private var restartJob: Job? = null
    private var killSwitchReconnectJob: Job? = null
    private lateinit var connectivityManager: ConnectivityManager

    private fun uriToFile(uri: Uri): File? {
        return try {
            if (uri.scheme == "file") {
                File(uri.path!!)
            } else {
                contentResolver.openInputStream(uri)?.use { input ->
                    val file = File(cacheDir, "update.apk")
                    file.outputStream().use { output -> input.copyTo(output) }
                    file
                }
            }
        } catch (_: Exception) {
            null
        }
    }
    private var lastNetworkKey: Long? = null
    private var lastRestartAt = 0L
    private lateinit var updateChecker: UpdateChecker
    private var downloadId: Long = -1L

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id != downloadId) return
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val query = DownloadManager.Query().setFilterById(id)
            val cursor: Cursor? = dm.query(query)
            cursor?.use {
                if (it.moveToFirst()) {
                    val localUri = it.getString(it.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                    if (localUri != null) {
                        val file = uriToFile(Uri.parse(localUri))
                        if (file != null && file.exists()) {
                            updateChecker.installApk(file)
                        }
                    }
                }
            }
        }
    }

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

    private fun exceptionDetail(e: Throwable?): String {
        var current = e
        var depth = 0
        while (current != null && depth < 6) {
            val msg = current.message
            if (!msg.isNullOrBlank()) return msg
            current = current.cause
            depth++
        }
        return e?.toString() ?: "null"
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

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { contents ->
            val config = decodeQrConfig(contents)
            if (config != null) {
                lifecycleScope.launch { importConfigText(config, null) }
            } else {
                Toast.makeText(this, getString(R.string.invalid_qr_config), Toast.LENGTH_LONG).show()
            }
        } ?: Toast.makeText(this, getString(R.string.qr_scan_cancelled), Toast.LENGTH_SHORT).show()
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
        killSwitchStore = KillSwitchStore(this)
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        updateChecker = UpdateChecker(this)

        registerReceiver(downloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), RECEIVER_NOT_EXPORTED)

        adapter = ServerAdapter(
            onClick = { server -> selectServer(server) },
            onLongClick = { server -> deleteServer(server) },
            onRename = { server -> showRenameDialog(server) },
        )
        binding.rvServers.layoutManager = LinearLayoutManager(this)
        binding.rvServers.adapter = adapter
        binding.rvServers.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))

        binding.btnImportConfig.setOnClickListener {
            configPickerLauncher.launch(arrayOf("*/*"))
        }

        binding.btnWarp.setOnClickListener { generateWarpConfig() }

        binding.btnScanQr.setOnClickListener {
            val options = ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(getString(R.string.scan_qr_prompt))
                .setBeepEnabled(false)
            scanLauncher.launch(options)
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
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

        awgManager.setTunnelStateListener {
            lifecycleScope.launch(Dispatchers.Main) {
                if (killSwitchStore.isEnabled()) {
                    VpnNotification.showKillSwitchAlert(this@MainActivity)
                    Toast.makeText(this@MainActivity, getString(R.string.kill_switch_reconnecting), Toast.LENGTH_LONG).show()
                    scheduleKillSwitchReconnect()
                }
            }
        }

        warmUpVpnService()

        lifecycleScope.launch {
            val update = runCatching { updateChecker.checkForUpdate() }.getOrNull()
            if (update != null) {
                val notes = update.releaseNotes.ifBlank { null }
                val message = if (notes != null) {
                    getString(R.string.update_available, update.versionName) + "\n\n" +
                        getString(R.string.update_notes) + "\n" + notes
                } else {
                    getString(R.string.update_available, update.versionName)
                }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(getString(R.string.update_confirm))
                    .setMessage(message)
                    .setPositiveButton(getString(R.string.update_confirm)) { _, _ ->
                        Toast.makeText(this@MainActivity, getString(R.string.update_downloading), Toast.LENGTH_SHORT).show()
                        downloadId = updateChecker.downloadAndInstall(update)
                    }
                    .setNegativeButton(getString(R.string.update_later), null)
                    .show()
            }
        }

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
                    if (killSwitchStore.isEnabled()) {
                        // Preserve the blocking barrier: never stop a still-running tunnel.
                        awgManager.restartTunnelKeepingBlocking(config)
                    } else {
                        awgManager.stopTunnel()
                        awgManager.startTunnel(config)
                    }
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

    private fun scheduleKillSwitchReconnect() {
        killSwitchReconnectJob?.cancel()
        killSwitchReconnectJob = lifecycleScope.launch {
            while (isActive && killSwitchStore.isEnabled()) {
                if (!isConnected) return@launch
                val server = selectedServer ?: return@launch
                val config = runCatching { awgManager.parseConfigFile(splitTunnelConfig(server)) }.getOrNull()
                    ?: return@launch
                val started = withContext(Dispatchers.IO) {
                    runCatching {
                        awgManager.restartTunnelKeepingBlocking(config)
                        awgManager.isRunning
                    }.getOrDefault(false)
                }
                if (started) {
                    anchorCurrentNetwork()
                    lastRestartAt = SystemClock.elapsedRealtime()
                    withContext(Dispatchers.Main) {
                        if (killSwitchStore.isEnabled()) {
                            killSwitchStore.setActive(true)
                            VpnNotification.cancelKillSwitchAlert(this@MainActivity)
                        }
                    }
                    return@launch
                }
                delay(1000)
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

            importConfigText(text, fileDisplayName(uri))
        }
    }

    private suspend fun importConfigText(text: String, displayName: String?) {
        if (text.isBlank()) {
            Toast.makeText(this, getString(R.string.empty_config), Toast.LENGTH_SHORT).show()
            return
        }

        val parseResult = runCatching { awgManager.parseConfigFile(text) }
        if (parseResult.isFailure) {
            Toast.makeText(this, getString(R.string.invalid_config_detail, exceptionDetail(parseResult.exceptionOrNull())), Toast.LENGTH_LONG).show()
            return
        }

        val endpoint = Server.parseEndpoint(text)
        var name = displayName?.substringBeforeLast('.')?.ifEmpty { null } ?: "Server"
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
            Toast.makeText(this, getString(R.string.config_imported), Toast.LENGTH_SHORT).show()
            return
        }

        servers = servers + server
        serverStore.save(servers)
        adapter.submitList(servers)
        startPing(server)
        Toast.makeText(this, getString(R.string.server_added), Toast.LENGTH_SHORT).show()
    }

    private fun generateWarpConfig() {
        binding.btnWarp.isEnabled = false
        lifecycleScope.launch {
            val result = WarpConfigGenerator.generate(this@MainActivity)
            result.onSuccess { warpResult ->
                val server = Server(
                    id = System.currentTimeMillis().toString(),
                    name = "Cloudflare WARP",
                    country = "",
                    countryCode = "",
                    host = warpResult.host,
                    port = warpResult.port,
                    config = warpResult.config,
                )
                servers = servers + server
                serverStore.save(servers)
                adapter.submitList(servers)
                startPing(server)
                selectServer(server)
                Toast.makeText(this@MainActivity, getString(R.string.warp_added), Toast.LENGTH_SHORT).show()
            }.onFailure { e ->
                Toast.makeText(this@MainActivity, getString(R.string.warp_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
            }
            binding.btnWarp.isEnabled = !isConnected
        }
    }

    private fun decodeQrConfig(contents: String): String? {
        val trimmed = contents.trim()
        configFromText(trimmed)?.let { return sanitizeConfig(it) }

        val body = Regex("""^[\w+.-]+://(.+)$""").find(trimmed)?.groupValues?.get(1) ?: trimmed
        val bytes = base64Decode(body) ?: return null

        configFromText(String(bytes, Charsets.UTF_8))?.let { return sanitizeConfig(it) }

        listOf(false, true).forEach { skip4 ->
            inflate(bytes, skip4, raw = false)?.let { configFromText(it)?.let { cfg -> return sanitizeConfig(cfg) } }
            inflate(bytes, skip4, raw = true)?.let { configFromText(it)?.let { cfg -> return sanitizeConfig(cfg) } }
        }
        return null
    }

    private fun sanitizeConfig(text: String): String {
        val lower = text.lowercase()
        val ifIdx = lower.indexOf("[interface]")
        val peerIdx = lower.indexOf("[peer]")
        val idx = when {
            ifIdx == -1 && peerIdx == -1 -> return text
            ifIdx == -1 -> peerIdx
            peerIdx == -1 -> ifIdx
            else -> minOf(ifIdx, peerIdx)
        }
        return if (idx > 0) text.substring(idx) else text
    }

    private fun configFromText(text: String): String? {
        if (looksLikeConfig(text)) return text
        return extractAmneziaConfig(text)?.takeIf { looksLikeConfig(it) }
    }

    private fun looksLikeConfig(text: String): Boolean {
        return Regex("""(?im)^\s*\[(interface|peer)\]""").containsMatchIn(text)
    }

    private fun base64Decode(encoded: String): ByteArray? {
        val cleaned = encoded.filterNot { it.isWhitespace() }
        if (cleaned.isEmpty()) return null
        val padded = cleaned + "=".repeat((4 - cleaned.length % 4) % 4)
        listOf(
            android.util.Base64.NO_WRAP,
            android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE,
        ).forEach { flags ->
            runCatching { android.util.Base64.decode(padded, flags) }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun inflate(data: ByteArray, skip4: Boolean, raw: Boolean): String? {
        return runCatching {
            val offset = if (skip4 && data.size > 4) 4 else 0
            val inflater = Inflater(raw)
            inflater.setInput(data, offset, data.size - offset)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (!inflater.finished()) {
                val count = inflater.inflate(buf)
                if (count == 0) break
                out.write(buf, 0, count)
            }
            inflater.end()
            out.toString(Charsets.UTF_8.name())
        }.getOrNull()
    }

    private fun extractAmneziaConfig(text: String): String? {
        if (!text.trimStart().startsWith("{")) return null
        return runCatching {
            val root = JSONObject(text)
            val containers = root.optJSONArray("containers")
            if (containers != null) {
                for (i in 0 until containers.length()) {
                    val container = containers.optJSONObject(i) ?: continue
                    for (proto in listOf("awg", "wg")) {
                        val protoObj = container.optJSONObject(proto) ?: continue
                        val lastConfig = protoObj.optString("last_config")
                        if (lastConfig.isNotEmpty()) {
                            val inner = JSONObject(lastConfig)
                            val config = inner.optString("config")
                            if (config.isNotEmpty()) return config
                        }
                        val directConfig = protoObj.optString("config")
                        if (directConfig.isNotEmpty()) return directConfig
                    }
                }
            }
            root.optString("config").takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun fileDisplayName(uri: android.net.Uri): String {
        return runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx != -1 && cursor.moveToFirst()) cursor.getString(idx) else ""
            }
        }.getOrDefault("") ?: ""
    }

    private fun showRenameDialog(server: Server) {
        val input = EditText(this).apply {
            hint = getString(R.string.rename_hint)
            setText(server.country.ifEmpty { server.name })
            inputType = InputType.TYPE_CLASS_TEXT
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.rename_server))
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    val updated = server.copy(
                        country = newName,
                        name = newName.ifEmpty { server.name },
                    )
                    servers = servers.map { if (it.id == server.id) updated else it }
                    serverStore.save(servers)
                    adapter.submitList(servers)
                    adapter.setSelected(selectedServer?.id)
                    if (selectedServer?.id == server.id) selectedServer = updated
                    Toast.makeText(this, getString(R.string.rename_saved), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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

    private fun splitTunnelConfig(server: Server): String = awgManager.buildConfigForServer(server)

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
                    if (killSwitchStore.isEnabled()) {
                        killSwitchStore.setActive(true)
                        VpnNotification.cancelKillSwitchAlert(this@MainActivity)
                    }
                    updateUI()
                    VpnWidgetProvider.updateAllWidgets(this@MainActivity)
                    VpnTileService.requestUpdate(this@MainActivity)
                    selectedServer?.let { VpnNotification.showConnected(this@MainActivity, it) }
                    Toast.makeText(this@MainActivity, getString(R.string.vpn_connected), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                if (e is BackendException) {
                    Log.e("MainActivity", "BackendException reason=${e.reason} format=${e.format?.contentToString()} cause=${e.cause}", e)
                }
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
        killSwitchReconnectJob?.cancel()
        lastNetworkKey = null
        lastRestartAt = SystemClock.elapsedRealtime()
        killSwitchStore.setActive(false)
        VpnNotification.cancelKillSwitchAlert(this)
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
        binding.btnScanQr.isEnabled = !isConnected
        binding.btnWarp.isEnabled = !isConnected
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(downloadReceiver) }
        runCatching { connectivityManager.unregisterNetworkCallback(connectivityCallback) }
        awgManager.setTunnelStateListener(null)
        pingLoop?.cancel()
        restartJob?.cancel()
        killSwitchReconnectJob?.cancel()
        pingJobs.values.forEach { it.cancel() }
        if (isConnected) {
            runCatching { awgManager.stopTunnel() }
        }
        super.onDestroy()
    }
}
