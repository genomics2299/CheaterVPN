package com.cheatervpnapp

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

class XrayManager private constructor(context: Context) {

    private val appContext = context.applicationContext

    @Volatile
    private var process: Process? = null

    fun prepareConfigFile(server: Server): File? {
        if (!server.isXray) return null
        val parsed = XrayConfig.parse(server.config) ?: return null
        val dir = File(appContext.filesDir, "xray").apply { mkdirs() }
        val file = File(dir, "config.json")
        runCatching {
            Files.copy(
                java.io.ByteArrayInputStream(parsed.fullConfig.toByteArray()),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        return file.takeIf { it.length() > 0 }
    }

    fun startProcess(server: Server): Boolean {
        stopProcess()
        val cfg = prepareConfigFile(server) ?: return false
        val bin = File(appContext.applicationInfo.nativeLibraryDir, "libxray.so")
        if (!bin.exists()) return false
        val dir = File(appContext.filesDir, "xray").apply { mkdirs() }
        val log = File(dir, "xray.log")
        process = try {
            ProcessBuilder(bin.absolutePath, "run", "-c", cfg.absolutePath, "-format", "json")
                .directory(dir)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
                .start()
        } catch (_: Exception) {
            return false
        }
        return awaitSocksPort(6000)
    }

    fun stopProcess() {
        val p = process ?: return
        process = null
        if (!p.isAlive) return
        p.destroy()
        runCatching { p.waitFor(3, TimeUnit.SECONDS) }
        if (p.isAlive) {
            p.destroyForcibly()
            runCatching { p.waitFor(2, TimeUnit.SECONDS) }
        }
    }

    fun isAlive(): Boolean = process?.isAlive == true

    private fun awaitSocksPort(timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (process?.isAlive != true) return false
            runCatching {
                Socket().use { s ->
                    s.connect(InetSocketAddress("127.0.0.1", SOCKS_PORT), 300)
                }
            }.onSuccess { return true }
            Thread.sleep(150)
        }
        return false
    }

    companion object {
        const val SOCKS_PORT = 10808
        const val DNS_FAKE_IP = "198.18.0.2"

        @Volatile
        private var instance: XrayManager? = null

        fun get(context: Context): XrayManager =
            instance ?: synchronized(this) {
                instance ?: XrayManager(context.applicationContext).also { instance = it }
            }
    }
}
