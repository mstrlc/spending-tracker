package dev.spendingtracker.android.util

/**
 * Normalizes a server URL the user typed: trims whitespace and trailing slashes, and adds a scheme when
 * missing — `https://` by default, but plain `http://` for loopback and the emulator's host alias, so local
 * development keeps working without typing the scheme. Idempotent: a URL that already has a scheme is
 * returned trimmed only.
 */
fun normalizeServerUrl(raw: String): String {
    val trimmed = raw.trim().trimEnd('/')
    if (trimmed.contains("://")) return trimmed
    val host = runCatching { java.net.URI("dummy://$trimmed").host }.getOrNull()
        ?: trimmed.substringBefore('/').substringBefore(':')
    val scheme = if (host in listOf("localhost", "127.0.0.1", "::1", "10.0.2.2")) "http" else "https"
    return "$scheme://$trimmed"
}
