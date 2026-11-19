package dev.spendingtracker.server.debt

import dev.spendingtracker.server.db.Accounts
import dev.spendingtracker.server.db.Categories
import dev.spendingtracker.server.db.HouseholdMembers
import dev.spendingtracker.server.db.Households
import dev.spendingtracker.server.db.SplitRules
import dev.spendingtracker.server.db.SplitShares
import dev.spendingtracker.server.household.jointFundingFrom
import dev.spendingtracker.shared.api.Debt
import dev.spendingtracker.shared.api.JointFunding
import kotlinx.datetime.LocalDate
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/** One line of a split expense, for working out category-dependent debts. */
data class ExpenseLineFacts(val categoryId: Uuid?, val amountMinor: Long)

/** What deciding an expense's debts needs to know about it. A null [ownerUserId] is a joint expense. */
data class ExpenseFacts(
    val id: Uuid,
    val date: LocalDate,
    val amountMinor: Long,
    val categoryId: Uuid?,
    val accountId: Uuid?,
    val ownerUserId: Uuid?,
    val createsDebt: Boolean,
    val lines: List<ExpenseLineFacts> = emptyList(),
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
 * - Under [JointFunding.CONTRIBUTIONS], top-ups into joint accounts are credited to the member who put them in
 *   (and withdrawals debited) against the household default split, proportionally capped at total joint-account
 *   spending so money still sitting in joint accounts is not owed. Joint expenses paid from a joint account
 *   adjust for any difference between the expense's split (category or own) and the household default split, so
 *   both members topping up by the household split and spending it all produces zero debt.
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
    /** Expenses before this date produce no debts (null means all history counts). */
    val debtsFrom: LocalDate? = null,
    val jointFunding: JointFunding = JointFunding.POT,
) {
    fun isJointAccount(accountId: Uuid?): Boolean =
        accountId != null && accountId in accountOwners && accountOwners[accountId] == null

    fun debtsFor(expense: ExpenseFacts): List<Debt> {
        if (debtsFrom != null && expense.date < debtsFrom) return emptyList()
        val accountId = expense.accountId ?: return emptyList()
        if (!expense.createsDebt || accountId !in accountOwners) return emptyList()
        val payer = accountOwners[accountId]
        val owner = expense.ownerUserId
        return when {
            owner == null && payer != null ->
                allocateJoint(expense)
                    .filter { (member, part) -> member != payer && part != 0L }
                    .map { (member, part) -> Debt(member.toString(), payer.toString(), part) }
            owner == null && payer == null && jointFunding == JointFunding.CONTRIBUTIONS -> {
                val potShare = allocate(expense.amountMinor, dated(null, expense.date) ?: equal())
                val expenseShare = allocateJoint(expense)
                val deltas = members.associateWith { (expenseShare[it] ?: 0L) - (potShare[it] ?: 0L) }
                debtsFromNet(deltas)
            }
            owner != null && payer == null ->
                allocate(expense.amountMinor, overrides[expense.id] ?: dated(null, expense.date) ?: equal())
                    .filter { (member, part) -> member != owner && part != 0L }
                    .map { (member, part) -> Debt(owner.toString(), member.toString(), part) }
            owner != null && payer != null && owner != payer ->
                listOf(Debt(owner.toString(), payer.toString(), expense.amountMinor))
            else -> emptyList()
        }
    }

    /**
     * Debts from a member's top-up (positive [amountMinor]) or withdrawal (negative [amountMinor]) on a joint
     * account: the household default split says what share each member owed of that funding.
     */
    fun debtsForTopUp(userId: Uuid, amountMinor: Long, date: LocalDate): List<Debt> {
        if (amountMinor == 0L) return emptyList()
        return allocate(amountMinor, dated(null, date) ?: equal())
            .filter { (member, part) -> member != userId && part != 0L }
            .map { (member, part) ->
                if (part > 0) Debt(member.toString(), userId.toString(), part)
                else Debt(userId.toString(), member.toString(), -part)
            }
    }

    /** How a joint expense is shared: its own split, its category's (or the parent's), the household's, or equally. */
    fun splitFor(expense: ExpenseFacts): Weights {
        overrides[expense.id]?.let { return it }
        return splitForCategory(expense.categoryId, expense.date)
    }

    fun allocateJointShare(expense: ExpenseFacts): Map<Uuid, Long> = allocateJoint(expense)

    private fun allocateJoint(expense: ExpenseFacts): Map<Uuid, Long> {
        if (expense.id in overrides || expense.lines.isEmpty()) {
            return allocate(expense.amountMinor, splitFor(expense))
        }
        // Lines that share the same split rule are summed before rounding so parts don't drift by a penny.
        val totals = mutableMapOf<Uuid, Long>()
        for ((weights, group) in expense.lines.groupBy { splitForCategory(it.categoryId, expense.date) }) {
            for ((member, part) in allocate(group.sumOf { it.amountMinor }, weights)) {
                totals.merge(member, part, Long::plus)
            }
        }
        return totals
    }

    private fun splitForCategory(category: Uuid?, date: LocalDate): Weights {
        val parent = category?.let { parents[it] }
        return category?.let { dated(it, date) }
            ?: parent?.let { dated(it, date) }
            ?: dated(null, date)
            ?: equal()
    }

    private fun dated(categoryId: Uuid?, date: LocalDate): Weights? =
        rules[categoryId]?.firstOrNull { it.validFrom <= date }?.weights?.takeIf { it.isNotEmpty() }

    private fun equal(): Weights = members.associateWith { 1 }
}

/**
 * Splits [amountMinor] by [weights] in whole minor units that add up exactly: each gets its rounded-down part, and
 * the units left over go to the largest remainders (ties by key order, so the result never changes between calls).
 */
fun <K : Comparable<K>> allocateByWeights(amountMinor: Long, weights: Map<K, Long>): Map<K, Long> {
    val total = weights.values.sum()
    if (total <= 0L) return emptyMap()
    val sign = if (amountMinor < 0) -1L else 1L
    val amount = amountMinor * sign
    val keys = weights.keys.sorted()
    val parts = keys.associateWith { amount * weights.getValue(it) / total }.toMutableMap()
    var left = amount - parts.values.sum()
    val byRemainder = keys.sortedByDescending { amount * weights.getValue(it) % total }
    for (key in byRemainder) {
        if (left == 0L) break
        parts[key] = parts.getValue(key) + 1
        left--
    }
    return parts.mapValues { it.value * sign }
}

/**
 * Splits [amountMinor] by [weights] in whole minor units that add up exactly: each gets its rounded-down part, and
 * the units left over go to the largest remainders (ties by member id, so the result never changes between calls).
 */
fun allocate(amountMinor: Long, weights: Weights): Map<Uuid, Long> =
    allocateByWeights(amountMinor, weights.mapKeys { it.key.toString() }.mapValues { it.value.toLong() })
        .mapKeys { Uuid.parse(it.key) }

/**
 * Turns zero-sum member deltas (positive = debtor, negative = creditor) into deterministic pairwise debts.
 */
fun debtsFromNet(deltas: Map<Uuid, Long>): List<Debt> {
    val debtors = deltas.filterValues { it > 0 }.entries.sortedBy { it.key.toString() }
        .map { it.key to it.value }.toMutableList()
    val creditors = deltas.filterValues { it < 0 }.entries.sortedBy { it.key.toString() }
        .map { it.key to -it.value }.toMutableList()
    val result = mutableListOf<Debt>()
    var di = 0
    var ci = 0
    while (di < debtors.size && ci < creditors.size) {
        val (debtor, owes) = debtors[di]
        val (creditor, owed) = creditors[ci]
        val amount = minOf(owes, owed)
        if (amount > 0L) result += Debt(debtor.toString(), creditor.toString(), amount)
        if (owes == amount) di++ else debtors[di] = debtor to (owes - amount)
        if (owed == amount) ci++ else creditors[ci] = creditor to (owed - amount)
    }
    return result
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
    val householdRow = Households.select(Households.debtsFrom, Households.jointFunding)
        .where { Households.id eq householdId }
        .single()
    return SplitBook(
        members,
        rules,
        overrides,
        parents,
        accountOwners,
        householdRow[Households.debtsFrom],
        jointFundingFrom(householdRow[Households.jointFunding]),
    )
}
