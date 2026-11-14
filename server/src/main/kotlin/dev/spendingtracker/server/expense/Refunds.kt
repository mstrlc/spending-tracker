package dev.spendingtracker.server.expense

import dev.spendingtracker.server.db.Expenses
import kotlinx.datetime.LocalDate
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.between
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.math.abs
import kotlin.uuid.Uuid

/**
 * What each of [expenseIds] has been refunded, as a positive amount (the refunds are negative expenses): only those
 * with refunds are in the map. Call inside a transaction.
 */
fun loadRefundedMinor(householdId: Uuid, expenseIds: Collection<Uuid>): Map<Uuid, Long> {
    if (expenseIds.isEmpty()) return emptyMap()
    val refunded = Expenses.amountMinor.sum()
    return expenseIds.chunked(5_000).flatMap { chunk ->
        Expenses.select(Expenses.refundOf, refunded)
            .where { (Expenses.householdId eq householdId) and (Expenses.refundOf inList chunk) }
            .groupBy(Expenses.refundOf)
            .map { it[Expenses.refundOf]!! to -(it[refunded] ?: 0L) }
    }.toMap()
}

/**
 * [amountMinor] shared out over [weights] in whole minor units that add up exactly: each gets its rounded-down part
 * and the units left over go to the largest remainders (ties to the earlier one). The result keeps the order of
 * [weights]. Weights are taken by size, so a list of negative amounts spreads the same way.
 */
fun spreadByWeight(amountMinor: Long, weights: List<Long>): List<Long> {
    if (weights.isEmpty()) return emptyList()
    val sizes = weights.map { abs(it) }
    val total = sizes.sum()
    if (total == 0L) return listOf(amountMinor) + List(weights.size - 1) { 0L }
    val sign = if (amountMinor < 0) -1L else 1L
    val amount = amountMinor * sign
    val parts = sizes.map { amount * it / total }.toMutableList()
    var left = amount - parts.sum()
    for (index in sizes.indices.sortedByDescending { amount * sizes[it] % total }) {
        if (left == 0L) break
        parts[index] += 1
        left--
    }
    return parts.map { it * sign }
}

/**
 * One slice of spending as reports count it: on [date] (its original's, for a refund), in [categoryId], whose it is
 * [ownerUserId] (the expense's or its line's; null for joint).
 */
data class SpendingPart(
    val date: LocalDate,
    val merchantId: Uuid?,
    val categoryId: Uuid?,
    val amountMinor: Long,
    val ownerUserId: Uuid? = null,
    val expenseId: Uuid? = null,
    val accountId: Uuid? = null,
)

/**
 * The spending between [from] and [to] (inclusive) as reports count it: one part per expense, or per line of a split
 * expense, each with its category. A refund isn't a part of its own: it nets off its original, so it counts in the
 * original's month and category, whenever the refund itself is dated. Where the original is split into lines, all
 * its refunds together are spread over the lines in proportion to the lines' amounts ([spreadByWeight]); that is
 * also what the web's Expenses page does with `refundedMinor`. A refund whose original is gone (deleted) is an
 * ordinary negative expense on its own date. Call inside a transaction.
 */
fun loadSpendingParts(householdId: Uuid, from: LocalDate, to: LocalDate): List<SpendingPart> {
    val rows = Expenses.selectAll()
        .where { (Expenses.householdId eq householdId) and Expenses.refundOf.isNull() and Expenses.date.between(from, to) }
        .orderBy(Expenses.date to SortOrder.DESC)
        .toList()
    val ids = rows.map { it[Expenses.id] }
    val lines = loadExpenseLines(householdId, ids)
    val refunded = loadRefundedMinor(householdId, ids)
    return rows.flatMap { row ->
        val id = row[Expenses.id]
        val expenseLines = lines[id].orEmpty()
        val parts = if (expenseLines.isEmpty()) {
            listOf(Triple(row[Expenses.categoryId], row[Expenses.amountMinor], row[Expenses.paidByUserId]))
        } else {
            expenseLines.map { Triple(it.categoryId?.let(Uuid::parse), it.amountMinor, it.paidByUserId?.let(Uuid::parse)) }
        }
        val back = spreadByWeight(-(refunded[id] ?: 0L), parts.map { it.second })
        parts.mapIndexed { index, (categoryId, amount, owner) ->
            SpendingPart(row[Expenses.date], row[Expenses.merchantId], categoryId, amount + back[index], owner, id, row[Expenses.accountId])
        }
    }
}
