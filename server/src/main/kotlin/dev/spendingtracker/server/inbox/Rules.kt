package dev.spendingtracker.server.inbox

import dev.spendingtracker.server.db.BankFeeds
import dev.spendingtracker.server.db.BankRows
import dev.spendingtracker.server.db.InboxRules
import dev.spendingtracker.shared.api.RuleDirection
import dev.spendingtracker.shared.api.RuleOutcome
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** An inbox rule as the engine uses it: its conditions, its outcome and what that fills in. */
class RuleDef(
    val id: Uuid,
    val name: String,
    val counterpartyIban: String?,
    val merchantKey: String?,
    /** Normalised, see [normalizeText]. */
    val textContains: String?,
    val feedId: Uuid?,
    val accountId: Uuid?,
    val direction: RuleDirection?,
    val outcome: RuleOutcome,
    val merchant: String,
    val categoryId: Uuid?,
    val personUserId: Uuid?,
    val incomeSource: String,
    val otherAccountId: Uuid?,
    val skipReason: String,
    val auto: Boolean,
    val priority: Int,
    val version: Int,
    val createdAt: Instant,
) {
    /** How many conditions the rule sets: of two rules of one priority, the more specific wins. */
    val specificity = listOfNotNull(counterpartyIban, merchantKey, textContains, feedId, accountId, direction).size

    /** Whether the row (of [rowsWithFeed]) satisfies every condition the rule sets. */
    fun matches(row: ResultRow): Boolean {
        if (feedId != null && row[BankRows.feedId] != feedId) return false
        if (accountId != null && row[BankFeeds.accountId] != accountId) return false
        if (direction != null) {
            val amount = row[BankRows.amountMinor]
            if (if (direction == RuleDirection.IN) amount <= 0 else amount >= 0) return false
        }
        if (counterpartyIban != null) {
            val iban = ibanOf(row[BankRows.counterpartyIban]) ?: ibanOf(row[BankRows.counterpartyAccount])
            if (iban != counterpartyIban) return false
        }
        if (merchantKey != null && row[BankRows.merchantKey] != merchantKey) return false
        if (textContains != null) {
            val haystack = normalizeText("${row[BankRows.text]} ${row[BankRows.counterpartyName]} ${row[BankRows.message]}")
            if (textContains !in haystack) return false
        }
        return true
    }
}

/** The winning rule first: highest priority, then most conditions, then oldest. */
private val PRECEDENCE = compareByDescending<RuleDef> { it.priority }
    .thenByDescending { it.specificity }
    .thenBy { it.createdAt }
    .thenBy { it.id.toString() }

/** The household's rules, in the order they win. Call inside a transaction. */
fun loadRules(householdId: Uuid): List<RuleDef> =
    InboxRules.selectAll().where { InboxRules.householdId eq householdId }
        .orderBy(InboxRules.createdAt to SortOrder.ASC)
        .map { it.toRuleDef() }
        .sortedWith(PRECEDENCE)

/** The rule that applies to [row] (of [rowsWithFeed]), if any. [rules] must be in the order of [loadRules]. */
fun List<RuleDef>.firstMatch(row: ResultRow): RuleDef? = firstOrNull { it.matches(row) }

fun ResultRow.toRuleDef() = RuleDef(
    id = this[InboxRules.id],
    name = this[InboxRules.name],
    counterpartyIban = this[InboxRules.counterpartyIban],
    merchantKey = this[InboxRules.merchantKey],
    textContains = this[InboxRules.textContains],
    feedId = this[InboxRules.feedId],
    accountId = this[InboxRules.accountId],
    direction = this[InboxRules.direction]?.let { d -> RuleDirection.entries.first { it.dbValue == d } },
    outcome = RuleOutcome.entries.first { it.dbValue == this[InboxRules.outcome] },
    merchant = this[InboxRules.merchant],
    categoryId = this[InboxRules.categoryId],
    personUserId = this[InboxRules.personUserId],
    incomeSource = this[InboxRules.incomeSource],
    otherAccountId = this[InboxRules.otherAccountId],
    skipReason = this[InboxRules.skipReason],
    auto = this[InboxRules.auto],
    priority = this[InboxRules.priority],
    version = this[InboxRules.version],
    createdAt = this[InboxRules.createdAt],
)

val RuleOutcome.dbValue get() = name.lowercase()

val RuleDirection.dbValue get() = name.lowercase()
