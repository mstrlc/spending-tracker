package dev.spendingtracker.shared.api

import kotlinx.serialization.Serializable

/**
 * The household at a glance for one month (YYYY-MM). Amounts are minor units of the household currency.
 * Spending is consumption only; investing is counted separately, as money set aside rather than spent:
 * invested is what went into assets (contributions less withdrawals), and expenses in investment
 * categories, being the same money leaving an account, count as neither.
 */
@Serializable
data class Overview(
    val month: String,
    /** Days of [month] that have passed; equal to [daysInMonth] once the month is over, 0 for a future month. */
    val daysElapsed: Int,
    val daysInMonth: Int,
    val thisMonth: MonthFlow,
    /** The average complete month before [month], or null when there is none yet. */
    val typicalMonth: TypicalMonth?,
    /** Up to 12 months ending with [month], oldest first, starting no earlier than the first recorded month. */
    val history: List<MonthFlow>,
    /** The month [anomalies] describe: [month], or the one before while [month] is under 10 days in. */
    val anomalyMonth: String,
    /** Categories well above or below their usual month, the largest deviation first. */
    val anomalies: List<CategoryAnomaly>,
    /** Recurring charges, the most expensive first. */
    val subscriptions: List<Subscription>,
    /** [month]'s spending by top-level category, largest first. Null id: uncategorized. */
    val topCategories: List<CategoryTotal>,
    val topMerchants: List<NamedTotal>,
    val topSources: List<NamedTotal>,
)

/** [incomeMinor] is net: earnings less deductions, counted in the month they were earned. */
@Serializable
data class MonthFlow(val month: String, val incomeMinor: Long, val spendingMinor: Long, val investedMinor: Long)

/**
 * Averages over the last 6 complete months before the overview's month. Unusually expensive months
 * (more than 1.5× the interquartile range above the upper quartile) are left out whole and listed in [outlierMonths].
 */
@Serializable
data class TypicalMonth(
    val incomeMinor: Long,
    val spendingMinor: Long,
    val investedMinor: Long,
    val monthCount: Int,
    val outlierMonths: List<String>,
)

/**
 * A top-level category's spending in the anomaly month against [baselineMinor]: its average month over the
 * 12 months before, scaled to the part of the month that has passed. [ratio] is spent / baseline.
 */
@Serializable
data class CategoryAnomaly(val categoryId: String?, val spentMinor: Long, val baselineMinor: Long, val ratio: Double)

/** A merchant charged in at least 3 of the last 6 months with spending, by similar amounts. */
@Serializable
data class Subscription(val merchant: String, val categoryId: String?, val monthlyMinor: Long, val monthsSeen: Int)

@Serializable
data class CategoryTotal(val categoryId: String?, val amountMinor: Long)

@Serializable
data class NamedTotal(val name: String, val amountMinor: Long)
