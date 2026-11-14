package dev.spendingtracker.shared.api

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/**
 * One income line in the household currency. [amountMinor] is negative for clawbacks;
 * [source] is who paid it, and a null [recipientUserId] means shared income. [date] is when it arrived;
 * [earnedMonth] (YYYY-MM) is the month it was earned, which totals count it in.
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
)
