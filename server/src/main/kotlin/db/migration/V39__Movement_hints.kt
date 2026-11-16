package db.migration

import dev.spendingtracker.server.inbox.HintContext
import dev.spendingtracker.server.inbox.MEMBER_NAME_HINT
import dev.spendingtracker.server.inbox.OWN_ACCOUNTS_HINT
import dev.spendingtracker.server.inbox.memberNameHint
import dev.spendingtracker.server.inbox.ownAccountsHint
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import java.sql.Connection
import java.util.UUID
import kotlin.uuid.Uuid
import kotlin.uuid.toKotlinUuid

/**
 * Hints on money between the household's own accounts now say whose accounts: one member's ("Between your own
 * accounts"), a member's and a joint one, or two members'. Money to or from an account in the account owner's own name
 * the app doesn't read is "To or from your own account". Rows read before get the hint they'd get now; the matching
 * is Kotlin (names ignore diacritics and word order), so this is a Kotlin migration.
 */
@Suppress("ClassName")
class V39__Movement_hints : BaseJavaMigration() {
    override fun migrate(context: Context) {
        val db = context.connection
        val households = db.query("SELECT id FROM households") { it.getObject(1, UUID::class.java) }
        for (household in households) rehint(db, household)
    }

    private fun rehint(db: Connection, household: UUID) {
        val accounts = db.query("SELECT id, iban, owner_user_id FROM accounts WHERE household_id = ?", household) {
            Triple(it.getObject(1, UUID::class.java), it.getString(2), it.getObject(3, UUID::class.java)?.toKotlinUuid())
        }
        val ownerOf = accounts.associate { it.first to it.third }
        val ownerByIban: Map<String, Uuid?> =
            db.query("SELECT iban, account_id FROM bank_feeds WHERE household_id = ? AND iban IS NOT NULL", household) {
                it.getString(1) to ownerOf[it.getObject(2, UUID::class.java)]
            }.toMap() + accounts.filter { it.second != null }.associate { it.second!! to it.third }
        val namesByMember = db.query(
            "SELECT m.user_id, u.display_name, m.legal_name FROM household_members m JOIN users u ON u.id = m.user_id WHERE m.household_id = ?",
            household,
        ) { it.getObject(1, UUID::class.java).toKotlinUuid() to listOfNotNull(it.getString(2), it.getString(3)?.takeIf(String::isNotBlank)) }
            .toMap()
        val hints = HintContext(ownerByIban.keys, namesByMember.values.flatten(), ownerByIban, namesByMember)

        val rows = db.query(
            """
            SELECT r.id, r.hint, r.counterparty_iban, r.counterparty_name, a.owner_user_id
            FROM bank_rows r JOIN bank_feeds f ON f.id = r.feed_id JOIN accounts a ON a.id = f.account_id
            WHERE r.household_id = ? AND r.hint IN (?, ?)
            """.trimIndent(),
            household, OWN_ACCOUNTS_HINT, MEMBER_NAME_HINT,
        ) { Row(it.getObject(1, UUID::class.java), it.getString(2), it.getString(3), it.getString(4), it.getObject(5, UUID::class.java)?.toKotlinUuid()) }

        db.prepareStatement("UPDATE bank_rows SET hint = ? WHERE id = ?").use { update ->
            for (row in rows) {
                val hint = when (row.hint) {
                    OWN_ACCOUNTS_HINT -> row.iban?.takeIf { it in ownerByIban }?.let { ownAccountsHint(row.owner, ownerByIban[it]) }
                    else -> memberNameHint(row.counterpartyName, row.owner, hints)
                } ?: continue
                if (hint == row.hint) continue
                update.setString(1, hint)
                update.setObject(2, row.id)
                update.addBatch()
            }
            update.executeBatch()
        }
    }

    private class Row(val id: UUID, val hint: String, val iban: String?, val counterpartyName: String, val owner: Uuid?)

    private fun <T> Connection.query(sql: String, vararg params: Any, read: (java.sql.ResultSet) -> T): List<T> =
        prepareStatement(sql).use { statement ->
            params.forEachIndexed { i, p -> statement.setObject(i + 1, p) }
            statement.executeQuery().use { rs -> buildList { while (rs.next()) add(read(rs)) } }
        }
}
