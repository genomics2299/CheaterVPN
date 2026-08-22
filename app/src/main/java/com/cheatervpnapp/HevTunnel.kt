package com.cheatervpnapp

object HevTunnel {

    val available: Boolean by lazy {
        runCatching { System.loadLibrary("hev-socks5-tunnel") }.isSuccess
    }

    external fun TProxyStartService(configPath: String, fd: Int): Boolean

    external fun TProxyStopService(): Boolean

    external fun TProxyIsRunning(): Boolean

    external fun TProxyGetStats(): LongArray
}
