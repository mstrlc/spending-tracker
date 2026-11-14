package dev.spendingtracker.server.recurring

import dev.spendingtracker.server.overview.shift
import dev.spendingtracker.shared.api.RecurringHealth
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus
import kotlinx.datetime.yearMonth
import kotlin.math.abs
import kotlin.uuid.Uuid

// Pure rules behind recurring payments, kept apart from the database so they can be tested directly.

/** A merchant's expense as recurring payments see it: refunds already netted off. */
data class Charge(
    val date: LocalDate,
    val amountMinor: Long,
    val categoryId: Uuid? = null,
    val accountId: Uuid? = null,
    val paidByUserId: Uuid? = null,
)

/** A changed amount is one more than this far from the expected one. */
const val CHANGE_TOLERANCE = 0.05
private const val MAX_MISSED = 12

/** How far from a due date a charge still counts for it: 10 days for a monthly payment, 20 for longer intervals. */
fun matchWindowDays(intervalMonths: Int): Int = if (intervalMonths == 1) 10 else 20

/** The [k]th due date (0 is the anchor itself), the anchor's day clamped to shorter months. */
fun dueDate(anchor: LocalDate, intervalMonths: Int, k: Int): LocalDate = anchor.plus(k * intervalMonths, DateTimeUnit.MONTH)

data class Schedule(
    val health: RecurringHealth,
    val lastDue: LocalDate?,
    val nextDue: LocalDate,
    val lastChargeDate: LocalDate?,
    val lastChargeMinor: Long?,
    val missedDates: List<LocalDate>,
)

/**
 * Where a payment due every [intervalMonths] from [anchor] for about [expectedMinor] stands on [today], given the
 * merchant's [charges]. Each due date takes the charges within [matchWindowDays] of it (summed: billed twice still
 * counts once). The latest due date decides: charged (OK, or CHANGED when off by more than 5%), not charged yet
 * while its window is open (DUE, or MISSED when the one before was missed too), or not charged at all (MISSED).
 */
fun schedule(anchor: LocalDate, intervalMonths: Int, expectedMinor: Long, charges: List<Charge>, today: LocalDate): Schedule {
    val window = matchWindowDays(intervalMonths)
    val pastDues = generateSequence(0) { it + 1 }.map { dueDate(anchor, intervalMonths, it) }.takeWhile { it <= today }.toList()
    val nextDue = dueDate(anchor, intervalMonths, pastDues.size)
    fun chargesFor(due: LocalDate) = charges.filter { abs(it.date.daysUntil(due)) <= window }
    val charged = pastDues.associateWith(::chargesFor)
    val missed = pastDues.filter { charged[it]!!.isEmpty() && due(it, window) < today }.reversed()
    val lastCharged = pastDues.lastOrNull { charged[it]!!.isNotEmpty() }
    val lastChargeDate = lastCharged?.let { charged[it]!!.maxOf { c -> c.date } }
    val lastChargeMinor = lastCharged?.let { charged[it]!!.sumOf { c -> c.amountMinor } }

    val lastDue = pastDues.lastOrNull()
    val health = when {
        lastDue == null -> RecurringHealth.UPCOMING
        charged[lastDue]!!.isNotEmpty() -> if (changed(expectedMinor, lastChargeMinor!!)) RecurringHealth.CHANGED else RecurringHealth.OK
        due(lastDue, window) >= today -> {
            val before = pastDues.getOrNull(pastDues.size - 2)
            if (before != null && before in missed) RecurringHealth.MISSED else RecurringHealth.DUE
        }
        else -> RecurringHealth.MISSED
    }
    return Schedule(health, lastDue, nextDue, lastChargeDate, lastChargeMinor, missed.take(MAX_MISSED))
}

private fun due(date: LocalDate, window: Int) = date.plus(window, DateTimeUnit.DAY)

fun changed(expectedMinor: Long, actualMinor: Long): Boolean =
    abs(actualMinor - expectedMinor) > expectedMinor * CHANGE_TOLERANCE

/** A merchant's charges look recurring: every [intervalMonths], about [amountMinor], latest [latest]. */
data class Detected(val intervalMonths: Int, val amountMinor: Long, val latest: Charge, val timesSeen: Int)

private const val MONTHLY_MIN_MONTHS = 3
private const val LOOKBACK_MONTHS = 6
private const val MAX_SPREAD = 0.25
// A payment whose last charge is older than this has probably stopped.
private const val MONTHLY_STALE_DAYS = 45
private const val YEARLY_STALE_DAYS = 400
private const val YEARLY_DAY_SLACK = 20

/**
 * Whether one merchant's [charges] (any order) look like a recurring payment on [today]. Monthly: charged in at least
 * 3 of the 7 calendar months up to today's, the month totals within 25% of each other, the latest in the last 45 days;
 * the amount is the median month. Yearly: two charges a year apart (20 days either way) by amounts within 25%, with
 * nothing charged in the months between, the latest in the last 400 days; the amount is the latest.
 */
fun detect(charges: List<Charge>, today: LocalDate): Detected? {
    val positive = charges.filter { it.amountMinor > 0 && it.date <= today }.sortedBy { it.date }
    val latest = positive.lastOrNull() ?: return null
    val age = latest.date.daysUntil(today)

    val since = today.yearMonth.shift(-LOOKBACK_MONTHS)
    val months = positive.filter { it.date.yearMonth >= since }.groupBy { it.date.yearMonth }.mapValues { (_, c) -> c.sumOf { it.amountMinor } }
    if (months.size >= MONTHLY_MIN_MONTHS && age <= MONTHLY_STALE_DAYS && similar(months.values)) {
        return Detected(1, median(months.values), latest, months.size)
    }

    if (age <= YEARLY_STALE_DAYS) {
        val earlier = positive.dropLast(1).lastOrNull { yearApart(it.date, latest.date) && similar(listOf(it.amountMinor, latest.amountMinor)) }
        // Charged in the months between too: that's something else (a monthly payment that moved), not yearly.
        val between = earlier?.let { e -> positive.count { it.date > e.date.plus(2, DateTimeUnit.MONTH) && it.date < latest.date.plus(-2, DateTimeUnit.MONTH) } }
        if (earlier != null && between == 0) {
            val times = positive.count { c -> similar(listOf(c.amountMinor, latest.amountMinor)) && sameTimeOfYear(c.date, latest.date) }
            return Detected(12, latest.amountMinor, latest, times)
        }
    }
    return null
}

private fun similar(totals: Collection<Long>): Boolean {
    val average = totals.average()
    return average > 0 && (totals.max() - totals.min()) / average <= MAX_SPREAD
}

private fun median(values: Collection<Long>): Long {
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
}

private fun yearApart(earlier: LocalDate, later: LocalDate): Boolean {
    val days = earlier.daysUntil(later)
    return days in (365 - YEARLY_DAY_SLACK)..(365 + YEARLY_DAY_SLACK + 1)
}

private fun sameTimeOfYear(a: LocalDate, b: LocalDate): Boolean {
    val shifted = LocalDate(b.year, a.month, minOf(a.day, YearMonth(b.year, a.month).numberOfDays))
    return abs(shifted.daysUntil(b)) <= YEARLY_DAY_SLACK || abs(shifted.daysUntil(b)) >= 365 - YEARLY_DAY_SLACK
}
