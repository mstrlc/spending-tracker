package dev.spendingtracker.server.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Random bearer tokens (session cookies, invite links). Clients hold the token; the database
 * stores only its SHA-256, so a leaked database can't be replayed.
 */
object Tokens {
    private val random = SecureRandom()

    /** A new token: the URL-safe string to hand out, and the hash to store. */
    fun generate(): Pair<String, ByteArray> {
        val bytes = ByteArray(32).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) to sha256(bytes)
    }

    /** The stored hash for [token], or null when it isn't a well-formed token. */
    fun hash(token: String): ByteArray? =
        runCatching { Base64.getUrlDecoder().decode(token) }.getOrNull()?.let(::sha256)

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}
