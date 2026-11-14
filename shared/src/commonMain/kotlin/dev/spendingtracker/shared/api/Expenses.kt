package dev.spendingtracker.shared.api

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/**
 * One expense in the household currency. [amountMinor] is negative for refunds; [merchant] is the name of the
 * merchant [merchantId] ("" for none);
 * a null [paidByUserId] means a shared expense. Paid in another currency, [originalAmountMinor] and [originalCurrency]
 * say what it cost in that currency (both or neither).
 */
@Serializable
data class Expense(
    val id: String,
    val date: LocalDate,
    val amountMinor: Long,
    val merchantId: String?,
    val merchant: String,
    val categoryId: String?,
    val accountId: String?,
    val paidByUserId: String?,
    val notes: String,
    val version: Int,
    val tripId: String?,
    val originalAmountMinor: Long?,
    val originalCurrency: String?,
)

/**
 * Body for creating and updating an expense; [version] is required when updating. [merchant] is a name: the
 * household's merchant of that name (ignoring case), or a new one filed under [categoryId] by default; blank for none.
 */
@Serializable
data class ExpenseInput(
    val date: LocalDate,
    val amountMinor: Long,
    val merchant: String = "",
    val categoryId: String? = null,
    val accountId: String? = null,
    val paidByUserId: String? = null,
    val notes: String = "",
    val version: Int? = null,
    val tripId: String? = null,
    val originalAmountMinor: Long? = null,
    val originalCurrency: String? = null,
)
