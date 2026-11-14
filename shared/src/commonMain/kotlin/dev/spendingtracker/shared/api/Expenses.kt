package dev.spendingtracker.shared.api

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/**
 * One line of a split expense: its own category, amount and optional note. An expense's lines add up to its
 * [Expense.amountMinor] and take the place of its single [Expense.categoryId].
 */
@Serializable
data class ExpenseLine(
    val categoryId: String?,
    val amountMinor: Long,
    val notes: String,
)

@Serializable
data class ExpenseLineInput(
    val categoryId: String? = null,
    val amountMinor: Long,
    val notes: String = "",
)

/**
 * One expense in the household currency. [amountMinor] is negative for refunds; [merchant] is the name of the
 * merchant [merchantId] ("" for none);
 * a null [paidByUserId] means a shared expense. Paid in another currency, [originalAmountMinor] and [originalCurrency]
 * say what it cost in that currency (both or neither).
 *
 * [paidByUserId] is whose expense it is; the account says who paid. When they don't match, [debts] says who owes whom
 * because of it, unless [createsDebt] is off. [split] overrides the household's split rules for this expense.
 * When split across categories, [lines] holds the parts (adding up to [amountMinor]) and [categoryId] is null.
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
    val split: List<Share>?,
    val createsDebt: Boolean,
    val debts: List<Debt>,
    val lines: List<ExpenseLine>,
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
    /** Null: the household's split rules apply. */
    val split: List<Share>? = null,
    val createsDebt: Boolean = true,
    /** Non-empty: splits the expense across these lines (at least two, summing to [amountMinor]), clearing [categoryId]. */
    val lines: List<ExpenseLineInput> = emptyList(),
    /** On create only: bank rows this expense is made from, filed under it in the same transaction. */
    val bankRows: List<BankRowRef> = emptyList(),
)
