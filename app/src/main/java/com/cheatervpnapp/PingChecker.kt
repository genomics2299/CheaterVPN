package com.cheatervpnapp

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

object PingChecker {

    private const val TAG = "PingChecker"
    private const val CONTROL_HOST = "1.1.1.1"
    private val tcpPorts = listOf(443, 853, 80, 22, 53, 8080, 8443, 2053, 2083, 2096, 123, 137)

    suspend fun ping(host: String, port: Int, timeoutMs: Int = 2000): Long? {
        if (host.isEmpty()) return null
        return withContext(Dispatchers.IO) {
            withTimeout(10000) {
                pingIcmp(host) ?: pingTcpChain(host, port, timeoutMs)
            }
        }
    }

    private fun pingIcmp(host: String): Long? {
        val argSets = listOf(
            listOf("ping", "-c", "1", "-W", "2", "-4", host),
            listOf("ping", "-c", "1", "-W", "2", host),
        )
        for (args in argSets) {
            val ms = runCatching {
                val process = ProcessBuilder(args).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                process.waitFor(4, TimeUnit.SECONDS)
                if (process.isAlive) process.destroyForcibly()
                parsePingTime(output).also { result ->
                    if (result == null) Log.d(TAG, "ping output: ${output.trim().replace('\n', ' ')}")
                }
            }.getOrNull()
            if (ms != null) {
                Log.d(TAG, "ICMP ok: ${args.joinToString(" ")} -> $ms ms")
                return ms
            }
        }
        Log.d(TAG, "ICMP failed for $host")
        if (host != CONTROL_HOST) {
            val control = pingIcmp(CONTROL_HOST)
            Log.d(TAG, "Control ping to $CONTROL_HOST: ${control?.let { "$it ms" } ?: "FAILED (ICMP blocked on device?)"}")
        }
        return null
    }

    private fun parsePingTime(output: String): Long? {
        val match = Regex("""time[=<]([\d.]+)\s*ms""").find(output)
            ?: Regex("""rtt min/avg/max.*=\s*[\d.]+/([\d.]+)/[\d.]+""").find(output)
            ?: return null
        return match.groupValues[1].toDoubleOrNull()?.toLong()
    }

    private suspend fun pingTcpChain(host: String, port: Int, timeoutMs: Int): Long? {
        val ports = buildList {
            if (port in 1..65535) add(port)
            addAll(tcpPorts.filterNot { it == port })
        }
        val latencies = coroutineScope {
            ports.map { p -> async { pingTcp(host, p, timeoutMs) } }.awaitAll()
        }
        val best = latencies.filterNotNull().minOrNull()
        Log.d(TAG, "TCP chain for $host (ports=${ports.take(4)}...): $latencies")
        return best
    }

    private fun pingTcp(host: String, port: Int, timeoutMs: Int): Long? {
        return runCatching {
            val start = System.nanoTime()
            Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
            (System.nanoTime() - start) / 1_000_000
        }.getOrNull()
    }
}
