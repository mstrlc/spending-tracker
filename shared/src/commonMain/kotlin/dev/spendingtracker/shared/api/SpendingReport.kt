package dev.spendingtracker.shared.api

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/**
 * Spending over any stretch of time, narrowed by category, merchant, account and whose (all optional): what "how much
 * did haircuts cost in 2026" asks. Counted as everywhere else: consumption only (investment categories are left out
 * unless the filter is one), a split expense by its lines, and a refund netted off its original, in the original's
 * month and category. Amounts are minor units of the household currency.
 */
@Serializable
data class SpendingReport(
    /** The period counted: the one asked for, starting no earlier than the ledger and ending no later than today. */
    val from: LocalDate,
    val to: LocalDate,
    val totalMinor: Long,
    /** Expenses counted (a split expense once, even when several of its lines match). */
    val count: Int,
    /** [totalMinor] over the number of [months]. */
    val averageMonthMinor: Long,
    /** Every month of the period, oldest first, empty ones included. */
    val months: List<ReportMonth>,
    /**
     * What went into assets over the period (contributions less withdrawals, liabilities left out), counted as on the
     * Overview: by the asset's owner for whose, by the account it was paid from for an account. Null when a category or
     * merchant is filtered on, which investing has neither of; it's never part of [totalMinor].
     */
    val investedMinor: Long?,
    /**
     * The same dates a year earlier, for a period of up to a year that the ledger covers by then; otherwise null
     * (before the ledger starts, spending is unknown rather than zero).
     */
    val previous: ReportPeriod?,
    /**
     * One level below the category filter, largest first: top-level categories without one, the subcategories of a
     * top-level one (its own expenses under its id), or just the filtered subcategory. Null id: uncategorized.
     */
    val categories: List<ReportCategory>,
    /** By merchant, largest first. Null id: no merchant. */
    val merchants: List<ReportMerchant>,
    /** The matching expenses, newest first, up to [EXPENSE_LIMIT]; [count] says how many there are in all. */
    val expenses: List<ReportExpense>,
) {
    companion object {
        const val EXPENSE_LIMIT = 500
    }
}

@Serializable
data class ReportMonth(
    val month: String,
    val amountMinor: Long,
    val count: Int,
    /** The month's share of [SpendingReport.investedMinor]; 0 when that's null. */
    val investedMinor: Long,
)

@Serializable
data class ReportPeriod(val from: LocalDate, val to: LocalDate, val totalMinor: Long, val count: Int)

@Serializable
data class ReportCategory(val categoryId: String?, val amountMinor: Long, val count: Int)

@Serializable
data class ReportMerchant(val merchantId: String?, val name: String, val amountMinor: Long, val count: Int)

/** One expense as the report counts it: [amountMinor] is only its matching lines, net of refunds. */
@Serializable
data class ReportExpense(
    val expenseId: String,
    val date: LocalDate,
    val merchant: String,
    /** The matching lines' categories, largest first. */
    val categoryIds: List<String?>,
    val accountId: String?,
    val amountMinor: Long,
)
