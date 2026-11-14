package dev.spendingtracker.shared.api

import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Income is recorded gross. A [DEDUCTION] is what comes off it (health and social insurance advances, income tax):
 * its amount is what was taken off, positive, and it lowers net income. Deductions are never spending.
 */
@Serializable
enum class IncomeKind {
    @SerialName("earning") EARNING,
    @SerialName("deduction") DEDUCTION,
}

/**
 * One income line in the household currency. [amountMinor] is negative for clawbacks;
 * [source] is who paid it, and a null [recipientUserId] means shared income. [date] is when it arrived;
 * [earnedMonth] (YYYY-MM) is the month it was earned, which totals count it in. A [IncomeKind.DEDUCTION] is taken off
 * the month's income: [source] is who took it (ZPMV, OSSZ) and [amountMinor] is positive, negative for a refund.
 */
@Serializable
data class Income(
    val id: String,
    val date: LocalDate,
    val amountMinor: Long,
    val source: String,
    val accountId: String?,
    val recipientUserId: String?,
    val notes: String,
    val version: Int,
    val earnedMonth: String,
    val kind: IncomeKind,
)

/** Body for creating and updating income; [version] is required when updating. */
@Serializable
data class IncomeInput(
    val date: LocalDate,
    val amountMinor: Long,
    val source: String = "",
    val accountId: String? = null,
    val recipientUserId: String? = null,
    val notes: String = "",
    val version: Int? = null,
    /** YYYY-MM; defaults to the month of [date]. */
    val earnedMonth: String? = null,
    val kind: IncomeKind = IncomeKind.EARNING,
    /** On create only: bank rows this income line is made from, filed under it in the same transaction. */
    val bankRows: List<BankRowRef> = emptyList(),
)
