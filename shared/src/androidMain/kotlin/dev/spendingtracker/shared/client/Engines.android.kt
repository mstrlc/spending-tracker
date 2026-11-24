package dev.spendingtracker.shared.client

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

internal actual fun defaultEngine(): HttpClientEngine = OkHttp.create {
    // The app talks to the user's own server — often on the LAN or the emulator's 10.0.2.2 — and those must
    // not go through the system proxy: dev machines and emulators often carry a stale global http_proxy
    // (a packet-capture tool that isn't running), which turns every call into ECONNREFUSED.
    config {
        proxySelector(object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> {
                val host = uri.host ?: return ProxySelector.getDefault().select(uri)
                return if (isDirectHost(host)) listOf(Proxy.NO_PROXY) else ProxySelector.getDefault().select(uri)
            }

            override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) {
                ProxySelector.getDefault().connectFailed(uri, sa, ioe)
            }
        })
    }
}

/** Loopback, the emulator's host alias, and private LAN ranges (10/8, 172.16/12, 192.168/16) go direct. */
private fun isDirectHost(host: String): Boolean {
    if (host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2" || host == "::1") return true
    val addr = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
    return addr.isLoopbackAddress || addr.isSiteLocalAddress
}
