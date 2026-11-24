package dev.spendingtracker.android

import dev.spendingtracker.android.util.normalizeServerUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class ServerUrlTest {
    @Test
    fun `a bare domain gets https`() {
        assertEquals("https://spend.brq.mstrlc.eu", normalizeServerUrl("spend.brq.mstrlc.eu"))
    }

    @Test
    fun `a bare domain with a path keeps it, trimmed of whitespace and trailing slash`() {
        assertEquals("https://example.com/api", normalizeServerUrl(" example.com/api/ "))
    }

    @Test
    fun `loopback and the emulator host alias get http`() {
        assertEquals("http://10.0.2.2:8080", normalizeServerUrl("10.0.2.2:8080"))
        assertEquals("http://localhost:8080", normalizeServerUrl("localhost:8080"))
        assertEquals("http://127.0.0.1:8080", normalizeServerUrl("127.0.0.1:8080"))
    }

    @Test
    fun `an explicit scheme is kept`() {
        assertEquals("http://192.168.0.8:9090", normalizeServerUrl("http://192.168.0.8:9090/"))
        assertEquals("https://spend.brq.mstrlc.eu", normalizeServerUrl("https://spend.brq.mstrlc.eu"))
    }
}
