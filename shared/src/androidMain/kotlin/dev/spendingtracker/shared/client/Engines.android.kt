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
    // The app talks to the user's own server — a LAN address, the emulator's 10.0.2.2, or a public domain —
    // and must connect directly: dev machines and emulators often carry a stale global http_proxy (a
    // packet-capture tool that isn't running), which turns every call into ECONNREFUSED.
    config {
        proxySelector(object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> = listOf(Proxy.NO_PROXY)

            override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) {}
        })
    }
}
