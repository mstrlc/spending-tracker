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

internal actual fun defaultEngine(debugBuild: Boolean): HttpClientEngine = OkHttp.create {
    if (debugBuild) {
        // Debug only: dev machines and emulators often carry a stale global http_proxy (a packet-capture
        // tool that isn't running), which turns every call into ECONNREFUSED. Release builds honor the
        // system proxy, for Wi-Fi networks that require one.
        config {
            proxySelector(object : ProxySelector() {
                override fun select(uri: URI): List<Proxy> = listOf(Proxy.NO_PROXY)

                override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) {}
            })
        }
    }
}
