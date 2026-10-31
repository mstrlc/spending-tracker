package dev.spendingtracker.server.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.spendingtracker.server.DbConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.postgresql.util.PSQLException
import javax.sql.DataSource
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.uuid.Uuid

/** Opens the connection pool, applies pending Flyway migrations and connects Exposed. */
fun connectDatabase(config: DbConfig): HikariDataSource {
    val dataSource = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = config.url
            username = config.user
            password = config.password
            maximumPoolSize = 10
        },
    )
    migrate(dataSource)
    Database.connect(dataSource)
    return dataSource
}

private fun migrate(dataSource: DataSource) {
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .load()
        .migrate()
}

/**
 * Carries a request's call in the coroutine context, so every [dbQuery] in it can tell the audit log who is acting.
 * The user is read when the transaction starts: the element is installed before authentication has run.
 */
class AuditActor(val userId: () -> Uuid?) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AuditActor>
}

/**
 * Runs [block] in a transaction on the IO dispatcher. Inside a request with an [AuditActor], the transaction
 * first sets app.actor_id, which the audit triggers record as the author of every change.
 */
suspend fun <T> dbQuery(block: JdbcTransaction.() -> T): T {
    val actor = currentCoroutineContext()[AuditActor]?.userId?.invoke()
    return withContext(Dispatchers.IO) {
        transaction {
            if (actor != null) exec("SELECT set_config('app.actor_id', '$actor', true)")
            block()
        }
    }
}

private fun Throwable.postgresError(): PSQLException? =
    generateSequence(this) { it.cause }.filterIsInstance<PSQLException>().firstOrNull()

/** True when this exception is a unique-constraint violation (optionally of [constraint]). */
fun Throwable.isUniqueViolation(constraint: String? = null): Boolean {
    val error = postgresError() ?: return false
    return error.sqlState == "23505" && (constraint == null || error.serverErrorMessage?.constraint == constraint)
}

/** True when this exception is a foreign-key violation, e.g. deleting a row that is still referenced. */
fun Throwable.isForeignKeyViolation(): Boolean = postgresError()?.sqlState == "23503"
