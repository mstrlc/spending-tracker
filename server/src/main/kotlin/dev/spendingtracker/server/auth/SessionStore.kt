package dev.spendingtracker.server.auth

import dev.spendingtracker.server.db.Sessions
import dev.spendingtracker.server.db.dbQuery
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Server-side sessions. The client holds a random token; the database stores only its SHA-256,
 * so a leaked database can't be used to log in.
 */
object SessionStore {
    val LIFETIME = 30.days
    // Sessions used within this long of expiring are extended by a full lifetime.
    private val RENEW_WITHIN = 15.days

    private val random = SecureRandom()

    data class Session(val userId: Uuid, val expiresAt: Instant)

    suspend fun create(userId: Uuid): String {
        val token = ByteArray(32).also(random::nextBytes)
        val now = Clock.System.now()
        dbQuery {
            Sessions.deleteWhere { (Sessions.userId eq userId) and (Sessions.expiresAt lessEq now) }
            Sessions.insert {
                it[tokenHash] = sha256(token)
                it[Sessions.userId] = userId
                it[expiresAt] = now + LIFETIME
            }
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token)
    }

    /** Returns the live session for [token], extending it when it is close to expiry. */
    suspend fun find(token: String): Session? {
        val hash = decode(token)?.let(::sha256) ?: return null
        val now = Clock.System.now()
        return dbQuery {
            val row = Sessions.selectAll()
                .where { (Sessions.tokenHash eq hash) and (Sessions.expiresAt greater now) }
                .singleOrNull() ?: return@dbQuery null
            var session = Session(row[Sessions.userId], row[Sessions.expiresAt])
            if (session.expiresAt - now < RENEW_WITHIN) {
                val renewed = now + LIFETIME
                Sessions.update({ Sessions.tokenHash eq hash }) { it[expiresAt] = renewed }
                session = session.copy(expiresAt = renewed)
            }
            session
        }
    }

    suspend fun delete(token: String) {
        val hash = decode(token)?.let(::sha256) ?: return
        dbQuery { Sessions.deleteWhere { tokenHash eq hash } }
    }

    private fun decode(token: String): ByteArray? = runCatching { Base64.getUrlDecoder().decode(token) }.getOrNull()

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}
