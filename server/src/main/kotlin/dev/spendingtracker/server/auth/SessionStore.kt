package dev.spendingtracker.server.auth

import dev.spendingtracker.server.db.Sessions
import dev.spendingtracker.server.db.dbQuery
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Server-side sessions, keyed by a [Tokens] token held in a cookie. */
object SessionStore {
    val LIFETIME = 30.days
    // Sessions used within this long of expiring are extended by a full lifetime.
    private val RENEW_WITHIN = 15.days

    data class Session(val userId: Uuid, val expiresAt: Instant)

    suspend fun create(userId: Uuid): String {
        val (token, hash) = Tokens.generate()
        val now = Clock.System.now()
        dbQuery {
            Sessions.deleteWhere { (Sessions.userId eq userId) and (Sessions.expiresAt lessEq now) }
            Sessions.insert {
                it[tokenHash] = hash
                it[Sessions.userId] = userId
                it[expiresAt] = now + LIFETIME
            }
        }
        return token
    }

    /** Returns the live session for [token], extending it when it is close to expiry. */
    suspend fun find(token: String): Session? {
        val hash = Tokens.hash(token) ?: return null
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
        val hash = Tokens.hash(token) ?: return
        dbQuery { Sessions.deleteWhere { tokenHash eq hash } }
    }

    /** Ends every session of [userId], except the one holding [keepToken]. */
    suspend fun deleteAllFor(userId: Uuid, keepToken: String? = null) {
        val keep = keepToken?.let { Tokens.hash(it) }
        dbQuery {
            Sessions.deleteWhere {
                if (keep == null) Sessions.userId eq userId else (Sessions.userId eq userId) and (tokenHash neq keep)
            }
        }
    }
}
