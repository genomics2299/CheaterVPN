package com.cheatervpnapp

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
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
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
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
    private lateinit var xrayBridge: XrayBridge
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
    private var speedJob: Job? = null
    private var ringAnimator: Animator? = null
    private var prevRx = 0L
    private var prevTx = 0L
    private var prevTime = 0L

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
        xrayBridge = XrayBridge(this)
        xrayBridge.onStateChanged = { running ->
            if (isConnected != running) {
                isConnected = running
                updateUI()
                VpnWidgetProvider.updateAllWidgets(this)
                VpnTileService.requestUpdate(this)
            }
        }
        serverStore = ServerStore(this)
        killSwitchStore = KillSwitchStore(this)
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        updateChecker = UpdateChecker(this)

        registerReceiver(downloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), RECEIVER_NOT_EXPORTED)

        adapter = ServerAdapter(
            onClick = { server -> selectServer(server) },
            onLongClick = { server -> deleteServer(server) },
        )
        binding.rvServers.layoutManager = LinearLayoutManager(this)
        binding.rvServers.adapter = adapter

        binding.swipeRefresh.setOnRefreshListener {
            startAllPings()
            binding.swipeRefresh.isRefreshing = false
        }

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
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
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
        isConnected = awgManager.isRunning || xrayBridge.isVpnRunning
        if (selectedServer?.isVless == true) {
            xrayBridge.bind()
            xrayBridge.queryState()
            xrayBridge.queryStats()
        }
        updateUI()
        startSpeedLoop()
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
            if (selectedServer?.isVless == true) return@launch
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
                if (selectedServer?.isVless == true) return@launch
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
        updateServersEmpty()
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
        val trimmed = text.trim()

        if (trimmed.startsWith("vless://")) {
            importVlessLink(trimmed, displayName)
            return
        }

        if ((trimmed.startsWith("http://") || trimmed.startsWith("https://")) && !trimmed.contains('\n')) {
            importSubscriptionUrl(trimmed)
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
        updateServersEmpty()
        startPing(server)
        Toast.makeText(this, getString(R.string.server_added), Toast.LENGTH_SHORT).show()
    }

    private suspend fun importVlessLink(text: String, displayName: String?) {
        val cleaned = text.trim()
        val params = runCatching { XrayConfigBuilder.parseVlessLink(cleaned) }.getOrElse {
            Toast.makeText(
                this,
                getString(R.string.invalid_config_detail, exceptionDetail(it)),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        if (servers.any { it.config == cleaned }) {
            Toast.makeText(this, getString(R.string.config_imported), Toast.LENGTH_SHORT).show()
            return
        }

        var name = displayName?.substringBeforeLast('.')?.ifEmpty { null }
            ?: params.remark.ifEmpty { "VLESS" }
        var country = ""
        var countryCode = ""
        CountryResolver.resolveCountry(params.host)?.let { (c, code) ->
            country = c
            countryCode = code
            name = c
        }

        val server = Server(
            id = System.currentTimeMillis().toString(),
            name = name,
            country = country,
            countryCode = countryCode,
            host = params.host,
            port = params.port,
            config = cleaned,
            protocol = Server.PROTOCOL_VLESS,
        )

        servers = servers + server
        serverStore.save(servers)
        adapter.submitList(servers)
        updateServersEmpty()
        startPing(server)
        Toast.makeText(this, getString(R.string.server_added), Toast.LENGTH_SHORT).show()
    }

    private suspend fun importSubscriptionUrl(url: String) {
        val info = withContext(Dispatchers.IO) {
            runCatching {
                val r = SubscriptionFetcher.fetch(url)
                Log.i("Subscription", "fetch ok: links=${r.links.size} expire=${r.expireAt} title=${r.title}")
                r
            }.onFailure { e ->
                Log.e("Subscription", "fetch failed", e)
            }
        }
        info.onFailure { e ->
            Toast.makeText(
                this,
                getString(R.string.subscription_fetch_failed, e.message ?: ""),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val result = info.getOrThrow()
        if (result.links.isEmpty()) {
            Toast.makeText(this, getString(R.string.subscription_no_servers), Toast.LENGTH_LONG).show()
            return
        }
        var added = 0
        var updated = 0
        result.links.forEachIndexed { index, link ->
            val params = runCatching { XrayConfigBuilder.parseVlessLink(link) }.getOrNull() ?: return@forEachIndexed
            var name = params.remark.ifEmpty { "VLESS" }
            var country = ""
            var countryCode = ""
            CountryResolver.resolveCountry(params.host)?.let { (c, code) ->
                country = c
                countryCode = code
                name = c
            }
            val server = Server(
                id = System.currentTimeMillis().toString() + index,
                name = name,
                country = country,
                countryCode = countryCode,
                host = params.host,
                port = params.port,
                config = link,
                protocol = Server.PROTOCOL_VLESS,
                subscriptionUrl = url,
                subExpireAt = result.expireAt,
            )
            val existingIdx = servers.indexOfFirst { it.config == link }
            if (existingIdx >= 0) {
                servers = servers.toMutableList().apply { set(existingIdx, server.copy(id = servers[existingIdx].id)) }
                updated++
            } else {
                servers = servers + server
                added++
            }
        }
        Log.i("Subscription", "import: added=$added updated=$updated expire=${result.expireAt}")
        serverStore.save(servers)
        adapter.submitList(servers)
        updateServersEmpty()
        servers.forEach { startPing(it) }
        selectedServer = servers.firstOrNull { it.id == selectedServer?.id }
        val expireLabel = if (result.expireAt > 0L) formatSubscriptionDate(result.expireAt) else ""
        val toast = when {
            added > 0 -> getString(R.string.subscription_added, added, expireLabel)
            updated > 0 -> getString(R.string.subscription_updated, expireLabel)
            else -> getString(R.string.config_imported)
        }
        updateUI()
        Toast.makeText(this, toast, Toast.LENGTH_SHORT).show()
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
                updateServersEmpty()
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
        if (trimmed.startsWith("vless://")) return trimmed
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
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

    private fun selectServer(server: Server) {
        if (isConnected) {
            Toast.makeText(this, getString(R.string.disconnect_first), Toast.LENGTH_SHORT).show()
            return
        }
        selectedServer = server
        serverStore.saveSelectedId(server.id)
        adapter.setSelected(server.id)
        updateUI()
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
        updateServersEmpty()
        updateUI()
        Toast.makeText(this, getString(R.string.server_deleted), Toast.LENGTH_SHORT).show()
    }

    private fun splitTunnelConfig(server: Server): String = awgManager.buildConfigForServer(server)

    private fun connectVpn() {
        val server = selectedServer ?: run {
            Toast.makeText(this, getString(R.string.select_server_first), Toast.LENGTH_SHORT).show()
            return
        }

        if (server.isVless) {
            val configJson = runCatching {
                XrayConfigBuilder.buildConfig(XrayConfigBuilder.parseVlessLink(server.config))
            }.getOrElse {
                Toast.makeText(this, getString(R.string.invalid_config), Toast.LENGTH_SHORT).show()
                return
            }
            val intent = VpnService.prepare(this)
            if (intent != null) {
                vpnPermissionLauncher.launch(intent)
            } else {
                startVless(configJson, server)
            }
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

    private fun startVless(configJson: String, server: Server) {
        SessionTracker.start(server, 0L, 0L)
        val serviceIntent = Intent(this, XrayVpnService::class.java)
            .putExtra(XrayVpnService.EXTRA_CONFIG, configJson)
        runCatching { startService(serviceIntent) }
        xrayBridge.bind()
        xrayBridge.queryStats()
        isConnected = true
        anchorCurrentNetwork()
        lastRestartAt = SystemClock.elapsedRealtime()
        updateUI()
        VpnWidgetProvider.updateAllWidgets(this)
        VpnTileService.requestUpdate(this)
        VpnNotification.showConnected(this, server)
        Toast.makeText(this, getString(R.string.vpn_connected), Toast.LENGTH_SHORT).show()
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
        val wasVless = selectedServer?.isVless == true
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (wasVless) {
                    val (rx, tx) = xrayBridge.trafficStats()
                    SessionTracker.finish(this@MainActivity, rx, tx)
                    xrayBridge.stop()
                } else {
                    awgManager.stopTunnel()
                }
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
        if (isConnected) {
            binding.btnToggle.setBackgroundResource(R.drawable.bg_power_button_connected)
            binding.tvStatus.text = getString(R.string.connected)
            binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.success))
            startPulse()
        } else {
            binding.btnToggle.setBackgroundResource(R.drawable.bg_power_button)
            binding.tvStatus.text = getString(R.string.disconnected)
            binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            stopPulse()
        }
        val label = selectedServer?.country?.ifEmpty { selectedServer?.name }
        binding.tvSelectedServer.text = if (label.isNullOrEmpty()) {
            getString(R.string.server_empty_title)
        } else {
            getString(R.string.selected_server_name, label)
        }
        binding.btnImportConfig.isEnabled = !isConnected
        binding.btnScanQr.isEnabled = !isConnected
        binding.btnWarp.isEnabled = !isConnected
        updateServerInfo()
    }

    private fun updateServerInfo() {
        val server = selectedServer
        val expireAt = server?.subExpireAt ?: 0L
        binding.cardServerInfo.visibility = if (expireAt > 0L) View.VISIBLE else View.GONE
        if (expireAt <= 0L) return
        binding.tvInfoExpire.text = formatSubscriptionDate(expireAt)
    }

    private fun formatSubscriptionDate(expireAtSeconds: Long): String {
        val date = java.util.Date(expireAtSeconds * 1000L)
        return java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault()).format(date)
    }

    private fun startPulse() {
        stopPulse()
        val ring = binding.vPulseRing
        ring.alpha = 0.15f
        ring.scaleX = 0.9f
        ring.scaleY = 0.9f
        val scale = ObjectAnimator.ofPropertyValuesHolder(
            ring,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 0.9f, 1.12f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.9f, 1.12f),
        ).apply {
            duration = 1300
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
        }
        val fade = ObjectAnimator.ofFloat(ring, View.ALPHA, 0.15f, 0.5f).apply {
            duration = 1300
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
        }
        ringAnimator = AnimatorSet().apply {
            playTogether(scale, fade)
            start()
        }
    }

    private fun stopPulse() {
        ringAnimator?.cancel()
        ringAnimator = null
        binding.vPulseRing.alpha = 0f
    }

    private fun startSpeedLoop() {
        speedJob?.cancel()
        prevRx = 0L
        prevTx = 0L
        prevTime = 0L
        speedJob = lifecycleScope.launch {
            while (isActive) {
                val live = if (selectedServer?.isVless == true && isConnected) {
                    xrayBridge.bind()
                    xrayBridge.queryStats()
                    val (rxBytes, txBytes) = xrayBridge.trafficStats()
                    SessionTracker.snapshot(rxBytes, txBytes)
                } else {
                    awgManager.liveStats()
                }
                if (live != null) {
                    val now = SystemClock.elapsedRealtime()
                    if (prevTime != 0L) {
                        val dtSec = (now - prevTime) / 1000.0
                        if (dtSec > 0) {
                            binding.tvSpeedDown.text = Formatters.speed(((live.rxBytes - prevRx) / dtSec).toLong().coerceAtLeast(0L))
                            binding.tvSpeedUp.text = Formatters.speed(((live.txBytes - prevTx) / dtSec).toLong().coerceAtLeast(0L))
                        }
                    }
                    binding.tvSessionRx.text = Formatters.bytes(live.rxBytes)
                    binding.tvSessionTx.text = Formatters.bytes(live.txBytes)
                    prevRx = live.rxBytes
                    prevTx = live.txBytes
                    prevTime = now
                } else {
                    binding.tvSpeedDown.text = getString(R.string.speed_zero)
                    binding.tvSpeedUp.text = getString(R.string.speed_zero)
                    binding.tvSessionRx.text = Formatters.bytes(0)
                    binding.tvSessionTx.text = Formatters.bytes(0)
                    prevRx = 0L
                    prevTx = 0L
                    prevTime = 0L
                }
                delay(1000)
            }
        }
    }

    private fun updateServersEmpty() {
        binding.layoutServersEmpty.visibility = if (servers.isEmpty()) View.VISIBLE else View.GONE
        binding.tvServersHint.visibility = if (servers.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(downloadReceiver) }
        runCatching { connectivityManager.unregisterNetworkCallback(connectivityCallback) }
        awgManager.setTunnelStateListener(null)
        pingLoop?.cancel()
        speedJob?.cancel()
        restartJob?.cancel()
        killSwitchReconnectJob?.cancel()
        pingJobs.values.forEach { it.cancel() }
        stopPulse()
        if (isConnected) {
            runCatching { awgManager.stopTunnel() }
            if (selectedServer?.isVless == true) xrayBridge.stop()
        }
        xrayBridge.unbind()
        super.onDestroy()
    }
}
