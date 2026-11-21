package dev.spendingtracker.shared.api

import kotlinx.datetime.LocalDate
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
    /**
     * [month]'s gross earnings by source, every source, largest first; a blank name is an empty string. With
     * [deductionsMinor] they add up to [thisMonth]'s income.
     */
    val incomeSources: List<NamedTotal>,
    /** [month]'s deductions (insurance advances, withheld tax) as a positive amount. */
    val deductionsMinor: Long,
    val netWorth: NetWorth,
    val freshness: Freshness,
)

/**
 * What the household had on [on]: the overview month's last day, or today while the month is in progress.
 * [netMinor] = [accountsMinor] + [assetsMinor] + [liabilitiesMinor].
 */
@Serializable
data class NetWorth(
    val on: LocalDate,
    val netMinor: Long,
    /**
     * The latest bank balance on or before [on] of each account no asset follows: current accounts, cash, credit
     * cards (negative when owed). Accounts without a balance read from the bank count as nothing.
     */
    val accountsMinor: Long,
    /** Owned assets, as on the Assets page. */
    val assetsMinor: Long,
    /** Negative: what's owed on loans, cards and other liabilities. */
    val liabilitiesMinor: Long,
    /** Money at hand: current, savings and cash accounts' bank balances, and savings assets not counted through one. */
    val liquidMinor: Long,
    /** How many months [liquidMinor] would pay for a typical month's spending with no income; null without a typical month. */
    val runwayMonths: Double?,
    /** Accounts whose balance has been read from the bank by [on]. */
    val accountsRead: Int,
)

/** How far the bank data behind the figures reaches, and what's still waiting in the Inbox (left out of them). */
@Serializable
data class Freshness(
    /** Every bank feed, the one reaching least far first. */
    val feeds: List<FeedFreshness>,
    val pendingRows: Int,
    /** Pending rows dated in the overview's month. */
    val pendingInMonth: Int,
)

@Serializable
data class FeedFreshness(
    val feedId: String,
    val accountId: String,
    val name: String,
    /** Read through a bank connection rather than uploaded statements. */
    val live: Boolean,
    /** The latest row's date. */
    val lastDate: LocalDate?,
    /** ISO timestamp of the last sync that reached the bank. */
    val lastSyncedAt: String?,
    /** ISO timestamp the bank connection's consent ends; null for statement files. */
    val consentExpiresAt: String?,
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
