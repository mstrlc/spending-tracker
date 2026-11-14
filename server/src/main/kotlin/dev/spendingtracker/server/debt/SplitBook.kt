package dev.spendingtracker.server.debt

import dev.spendingtracker.server.db.Accounts
import dev.spendingtracker.server.db.Categories
import dev.spendingtracker.server.db.HouseholdMembers
import dev.spendingtracker.server.db.SplitRules
import dev.spendingtracker.server.db.SplitShares
import dev.spendingtracker.shared.api.Debt
import kotlinx.datetime.LocalDate
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/** What deciding an expense's debts needs to know about it. A null [ownerUserId] is a joint expense. */
data class ExpenseFacts(
    val id: Uuid,
    val date: LocalDate,
    val amountMinor: Long,
    val categoryId: Uuid?,
    val accountId: Uuid?,
    val ownerUserId: Uuid?,
    val createsDebt: Boolean,
)

/** Weights by member. */
typealias Weights = Map<Uuid, Int>

internal data class DatedRule(val validFrom: LocalDate, val weights: Weights)

/**
 * A household's split rules, account owners and members: everything needed to work out who owes whom for an
 * expense. Load it with [loadSplitBook] inside a transaction.
 *
 * - A joint expense paid from a member's account makes the others owe the payer their share.
 * - A member's expense paid from a joint account makes them owe the others their share of the pot (the household
 *   default split; categories don't change who the pot belongs to).
 * - A member's expense paid from another member's account makes them owe the payer all of it.
 * Without an account nobody is known to have paid, so there's no debt; nor when the expense switches it off.
 */
class SplitBook internal constructor(
    private val members: List<Uuid>,
    /** Household and category rules by category (null: the household default), latest first. */
    private val rules: Map<Uuid?, List<DatedRule>>,
    /** Each expense's own split, if it has one. */
    val overrides: Map<Uuid, Weights>,
    private val parents: Map<Uuid, Uuid?>,
    /** Every account's owner; null for a joint account. */
    private val accountOwners: Map<Uuid, Uuid?>,
) {
    fun debtsFor(expense: ExpenseFacts): List<Debt> {
        val accountId = expense.accountId ?: return emptyList()
        if (!expense.createsDebt || accountId !in accountOwners) return emptyList()
        val payer = accountOwners[accountId]
        val owner = expense.ownerUserId
        return when {
            owner == null && payer != null ->
                allocate(expense.amountMinor, splitFor(expense))
                    .filter { (member, part) -> member != payer && part != 0L }
                    .map { (member, part) -> Debt(member.toString(), payer.toString(), part) }
            owner != null && payer == null ->
                allocate(expense.amountMinor, overrides[expense.id] ?: dated(null, expense.date) ?: equal())
                    .filter { (member, part) -> member != owner && part != 0L }
                    .map { (member, part) -> Debt(owner.toString(), member.toString(), part) }
            owner != null && payer != null && owner != payer ->
                listOf(Debt(owner.toString(), payer.toString(), expense.amountMinor))
            else -> emptyList()
        }
    }

    /** How a joint expense is shared: its own split, its category's (or the parent's), the household's, or equally. */
    fun splitFor(expense: ExpenseFacts): Weights {
        overrides[expense.id]?.let { return it }
        val category = expense.categoryId
        val parent = category?.let { parents[it] }
        return category?.let { dated(it, expense.date) }
            ?: parent?.let { dated(it, expense.date) }
            ?: dated(null, expense.date)
            ?: equal()
    }

    private fun dated(categoryId: Uuid?, date: LocalDate): Weights? =
        rules[categoryId]?.firstOrNull { it.validFrom <= date }?.weights?.takeIf { it.isNotEmpty() }

    private fun equal(): Weights = members.associateWith { 1 }
}

/**
 * Splits [amountMinor] by [weights] in whole minor units that add up exactly: each gets its rounded-down part, and
 * the units left over go to the largest remainders (ties by member id, so the result never changes between calls).
 */
fun allocate(amountMinor: Long, weights: Weights): Map<Uuid, Long> {
    val total = weights.values.sumOf { it.toLong() }
    if (total <= 0L) return emptyMap()
    val sign = if (amountMinor < 0) -1L else 1L
    val amount = amountMinor * sign
    val members = weights.keys.sortedBy { it.toString() }
    val parts = members.associateWith { amount * weights.getValue(it) / total }.toMutableMap()
    var left = amount - parts.values.sum()
    val byRemainder = members.sortedByDescending { amount * weights.getValue(it) % total }
    for (member in byRemainder) {
        if (left == 0L) break
        parts[member] = parts.getValue(member) + 1
        left--
    }
    return parts.mapValues { it.value * sign }
}

/** Nets debts into one line per pair of members that isn't square, largest first. */
fun netBalances(debts: Sequence<Debt>): List<Debt> {
    // Keyed by the pair in a fixed order; positive means the first owes the second.
    val net = mutableMapOf<Pair<String, String>, Long>()
    for (debt in debts) {
        if (debt.fromUserId < debt.toUserId) {
            net.merge(debt.fromUserId to debt.toUserId, debt.amountMinor, Long::plus)
        } else {
            net.merge(debt.toUserId to debt.fromUserId, -debt.amountMinor, Long::plus)
        }
    }
    return net.filterValues { it != 0L }
        .map { (pair, amount) -> if (amount > 0) Debt(pair.first, pair.second, amount) else Debt(pair.second, pair.first, -amount) }
        .sortedByDescending { it.amountMinor }
}

/** Call inside a transaction. [expenseIds] limits which expenses' own splits are loaded (all when null). */
fun loadSplitBook(householdId: Uuid, expenseIds: Collection<Uuid>? = null): SplitBook {
    val members = HouseholdMembers.select(HouseholdMembers.userId)
        .where { HouseholdMembers.householdId eq householdId }
        .map { it[HouseholdMembers.userId] }
        .sortedBy { it.toString() }
    val weights = SplitShares.selectAll().where { SplitShares.householdId eq householdId }
        .groupBy({ it[SplitShares.ruleId] }, { it[SplitShares.userId] to it[SplitShares.weight] })
        .mapValues { it.value.toMap() }

    val rules = SplitRules.selectAll()
        .where { (SplitRules.householdId eq householdId) and SplitRules.expenseId.isNull() }
        .map { it[SplitRules.categoryId] to DatedRule(it[SplitRules.validFrom]!!, weights[it[SplitRules.id]].orEmpty()) }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, list) -> list.sortedByDescending { it.validFrom } }

    var overrideFilter = (SplitRules.householdId eq householdId) and SplitRules.expenseId.isNotNull()
    if (expenseIds != null) overrideFilter = overrideFilter and (SplitRules.expenseId inList expenseIds)
    val overrides = if (expenseIds?.isEmpty() == true) emptyMap() else {
        SplitRules.selectAll().where(overrideFilter)
            .associate { it[SplitRules.expenseId]!! to weights[it[SplitRules.id]].orEmpty() }
            .filterValues { it.isNotEmpty() }
    }

    val parents = Categories.select(Categories.id, Categories.parentId)
        .where { Categories.householdId eq householdId }
        .associate { it[Categories.id] to it[Categories.parentId] }
    val accountOwners = Accounts.select(Accounts.id, Accounts.ownerUserId)
        .where { Accounts.householdId eq householdId }
        .associate { it[Accounts.id] to it[Accounts.ownerUserId] }
    return SplitBook(members, rules, overrides, parents, accountOwners)
}
