package dev.spendingtracker.server.overview

import dev.spendingtracker.server.asset.Dated
import dev.spendingtracker.shared.api.CategoryAnomaly
import dev.spendingtracker.shared.api.CategoryTotal
import dev.spendingtracker.shared.api.MonthFlow
import dev.spendingtracker.shared.api.NamedTotal
import dev.spendingtracker.shared.api.Subscription
import dev.spendingtracker.shared.api.TypicalMonth
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.yearMonth
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.uuid.Uuid

// Pure calculations behind the overview, kept apart from the database so the rules can be tested directly.

/**
 * An expense reduced to what the overview needs: its top-level category and whether that is an investment category.
 * Those are transfers into investments, not spending; what was invested is read from asset contributions instead.
 */
data class Spend(val date: LocalDate, val amountMinor: Long, val merchant: String, val topCategoryId: Uuid?, val investment: Boolean)

/** An income line counted in its month: [amountMinor] is negative for a [deduction], so the month sums to net income. */
data class Earning(val date: LocalDate, val amountMinor: Long, val source: String, val deduction: Boolean = false)

const val TYPICAL_WINDOW = 6
const val BASELINE_MONTHS = 12
const val TOP_LIMIT = 5

// Before this many days a month in progress says too little; anomalies look at the month before instead.
private const val MIN_ELAPSED_DAYS = 10
private const val HIGH_RATIO = 1.4
private const val LOW_RATIO = 0.6
// A category whose usual month is under this share of total spending is too small to be worth flagging.
private const val MIN_BASELINE_SHARE = 0.02
private const val SUBSCRIPTION_MIN_MONTHS = 3
private const val SUBSCRIPTION_MAX_SPREAD = 0.25

fun YearMonth.shift(months: Int): YearMonth =
    if (months >= 0) plus(months, DateTimeUnit.MONTH) else minus(-months, DateTimeUnit.MONTH)

/** Inclusive range of months, oldest first. */
fun monthsBetween(from: YearMonth, to: YearMonth): List<YearMonth> =
    generateSequence(from) { it.shift(1) }.takeWhile { it <= to }.toList()

/** Days of [month] that have passed by [today]. */
fun daysElapsed(month: YearMonth, today: LocalDate): Int = when {
    today.yearMonth > month -> month.numberOfDays
    today.yearMonth < month -> 0
    else -> today.day
}

fun monthFlow(month: YearMonth, spends: List<Spend>, earnings: List<Earning>, contributions: List<Dated>): MonthFlow {
    val monthSpends = spends.filter { it.date.yearMonth == month }
    return MonthFlow(
        month = month.toString(),
        incomeMinor = earnings.filter { it.date.yearMonth == month }.sumOf { it.amountMinor },
        spendingMinor = monthSpends.filterNot { it.investment }.sumOf { it.amountMinor },
        investedMinor = contributions.filter { it.date.yearMonth == month }.sumOf { it.amountMinor },
    )
}

/**
 * Averages [complete] months (oldest first) over the last [TYPICAL_WINDOW], with unusually expensive months
 * dropped whole so a one-off car purchase doesn't define "typical" and income and spending stay paired.
 */
fun typicalMonth(complete: List<MonthFlow>): TypicalMonth? {
    val recent = complete.takeLast(TYPICAL_WINDOW)
    if (recent.isEmpty()) return null
    val limit = highOutlierLimit(recent.map { it.spendingMinor.toDouble() })
    val (outliers, typical) = recent.partition { limit != null && it.spendingMinor > limit }
    return TypicalMonth(
        incomeMinor = typical.averageOf { it.incomeMinor },
        spendingMinor = typical.averageOf { it.spendingMinor },
        investedMinor = typical.averageOf { it.investedMinor },
        monthCount = typical.size,
        outlierMonths = outliers.map { it.month },
    )
}

/** Upper fence (Q3 + 1.5×IQR), or null with too few values for quartiles to mean anything. */
private fun highOutlierLimit(values: List<Double>): Double? {
    if (values.size < 4) return null
    val sorted = values.sorted()
    val q1 = quantile(sorted, 0.25)
    val q3 = quantile(sorted, 0.75)
    return q3 + 1.5 * (q3 - q1)
}

private fun quantile(sorted: List<Double>, q: Double): Double {
    val position = (sorted.size - 1) * q
    val lower = sorted[position.toInt()]
    val upper = sorted[minOf(position.toInt() + 1, sorted.size - 1)]
    return lower + (upper - lower) * (position - position.toInt())
}

/** The month anomalies describe: the month in progress only once it is [MIN_ELAPSED_DAYS] in. */
fun anomalyMonth(month: YearMonth, today: LocalDate): YearMonth =
    if (today.yearMonth == month && today.day < MIN_ELAPSED_DAYS) month.shift(-1) else month

/**
 * Top-level categories in [month] well above or below their baseline: the average month over the
 * [BASELINE_MONTHS] calendar months before it, back to [firstMonth] (a month before the ledger starts isn't
 * a month of 0, it's one we don't have). Months without spending in the category count as 0, so occasional
 * categories like travel keep their real weight; at least 2 of them need some. A month in progress is
 * compared against the same fraction of its baseline.
 */
fun spendingAnomalies(spends: List<Spend>, month: YearMonth, firstMonth: YearMonth, today: LocalDate): List<CategoryAnomaly> {
    val fraction = daysElapsed(month, today).toDouble() / month.numberOfDays
    val history = monthsBetween(maxOf(month.shift(-BASELINE_MONTHS), firstMonth), month.shift(-1))
    if (fraction == 0.0 || history.isEmpty()) return emptyList()

    val consumption = spends.filterNot { it.investment }
    val byMonth = consumption.groupBy { it.date.yearMonth }
        .mapValues { (_, rows) -> rows.groupBy { it.topCategoryId }.mapValues { (_, r) -> r.sumOf { it.amountMinor } } }
    val usualTotal = history.sumOf { m -> byMonth[m]?.values?.sum() ?: 0L }.toDouble() / history.size

    return byMonth[month].orEmpty().mapNotNull { (categoryId, spent) ->
        if (spent == 0L) return@mapNotNull null
        val perMonth = history.map { byMonth[it]?.get(categoryId) ?: 0L }
        if (perMonth.count { it > 0 } < 2) return@mapNotNull null
        val fullMonth = perMonth.average()
        if (fullMonth <= 0 || fullMonth < usualTotal * MIN_BASELINE_SHARE) return@mapNotNull null
        val baseline = fullMonth * fraction
        val ratio = spent / baseline
        if (ratio in LOW_RATIO..HIGH_RATIO) null else CategoryAnomaly(categoryId?.toString(), spent, baseline.roundToLong(), ratio)
    }.sortedByDescending { abs(it.ratio - 1) }
}

/**
 * Recurring charges: the same merchant (ignoring case) in at least 3 of the last 6 months with spending up to
 * [month], with month totals within 25% of each other. A merchant's charges within a month are summed first,
 * so something billed twice a month isn't halved. The category is that of the newest charge.
 */
fun subscriptions(spends: List<Spend>, month: YearMonth): List<Subscription> {
    val consumption = spends.filter { !it.investment && it.date.yearMonth <= month && it.merchant.isNotBlank() }
    val recentMonths = consumption.map { it.date.yearMonth }.distinct().sorted().takeLast(TYPICAL_WINDOW).toSet()
    return consumption.filter { it.date.yearMonth in recentMonths }
        .sortedByDescending { it.date }
        .groupBy { it.merchant.trim().lowercase() }
        .mapNotNull { (_, charges) ->
            val totals = charges.groupBy { it.date.yearMonth }.values.map { rows -> rows.sumOf { it.amountMinor } }
            if (totals.size < SUBSCRIPTION_MIN_MONTHS) return@mapNotNull null
            val monthly = totals.average()
            if (monthly <= 0 || (totals.max() - totals.min()) / monthly > SUBSCRIPTION_MAX_SPREAD) return@mapNotNull null
            val newest = charges.first()
            Subscription(newest.merchant.trim(), newest.topCategoryId?.toString(), monthly.roundToLong(), totals.size)
        }
        .sortedByDescending { it.monthlyMinor }
}

/** [month]'s spending by top-level category, largest first; refunds net against their category. */
fun topCategories(spends: List<Spend>, month: YearMonth): List<CategoryTotal> =
    spends.filter { !it.investment && it.date.yearMonth == month }
        .groupBy { it.topCategoryId }
        .map { (id, rows) -> CategoryTotal(id?.toString(), rows.sumOf { it.amountMinor }) }
        .filter { it.amountMinor > 0 }
        .sortedByDescending { it.amountMinor }

fun topMerchants(spends: List<Spend>, month: YearMonth): List<NamedTotal> =
    ranked(spends.filter { !it.investment && it.date.yearMonth == month }.map { it.merchant to it.amountMinor })

fun topSources(earnings: List<Earning>, month: YearMonth): List<NamedTotal> =
    ranked(earnings.filter { !it.deduction && it.date.yearMonth == month }.map { it.source to it.amountMinor })

/** Sums by name (ignoring case, blank names left out), largest [TOP_LIMIT] first, named as most recently written. */
private fun ranked(rows: List<Pair<String, Long>>): List<NamedTotal> =
    rows.filter { it.first.isNotBlank() }
        .groupBy { it.first.trim().lowercase() }
        .map { (_, group) -> NamedTotal(group.first().first.trim(), group.sumOf { it.second }) }
        .filter { it.amountMinor > 0 }
        .sortedByDescending { it.amountMinor }
        .take(TOP_LIMIT)

private fun <T> List<T>.averageOf(value: (T) -> Long): Long = if (isEmpty()) 0 else (sumOf(value).toDouble() / size).roundToLong()
