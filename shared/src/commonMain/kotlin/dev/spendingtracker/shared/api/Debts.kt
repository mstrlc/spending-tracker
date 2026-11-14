package dev.spendingtracker.shared.api

import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One member's part of a split, as a weight: 60 and 40, or 1 and 1. Members without one have no part. */
@Serializable
data class Share(val userId: String, val weight: Int)

/**
 * How members share spending from [validFrom] until the household's next rule of the same scope: the household
 * default when [categoryId] is null, otherwise that category's (and its subcategories', unless they have their own).
 */
@Serializable
data class SplitRule(
    val id: String,
    val categoryId: String?,
    val validFrom: LocalDate,
    val shares: List<Share>,
    val version: Int,
)

/** Body for creating and updating a split rule; [version] is required when updating. */
@Serializable
data class SplitRuleInput(
    val validFrom: LocalDate,
    val shares: List<Share>,
    val categoryId: String? = null,
    val version: Int? = null,
)

/** Money one member paid another back. Neither income nor spending; it only moves the balance between them. */
@Serializable
data class Settlement(
    val id: String,
    val date: LocalDate,
    val fromUserId: String,
    val toUserId: String,
    val amountMinor: Long,
    val notes: String,
    val version: Int,
)

/** Body for creating and updating a settlement; [version] is required when updating. */
@Serializable
data class SettlementInput(
    val date: LocalDate,
    val fromUserId: String,
    val toUserId: String,
    val amountMinor: Long,
    val notes: String = "",
    val version: Int? = null,
)

/** [fromUserId] owes [toUserId] [amountMinor] (negative for a refund that reduces what's owed). */
@Serializable
data class Debt(val fromUserId: String, val toUserId: String, val amountMinor: Long)

@Serializable
enum class BalanceEntryKind {
    @SerialName("expense") EXPENSE,
    @SerialName("settlement") SETTLEMENT,
}

/**
 * Something that moved the balance between members: an expense's [debts], or a settlement (one debt, the payer
 * as [Debt.toUserId]: paying back reduces what they owe). [label] names it (an expense's merchant).
 */
@Serializable
data class BalanceEntry(
    val kind: BalanceEntryKind,
    val id: String,
    val date: LocalDate,
    val label: String,
    val amountMinor: Long,
    val debts: List<Debt>,
)

/**
 * Where members stand with each other. [balances]: who owes whom after everything so far, one line per pair that
 * isn't square. [entries]: what moved the balance between from and to, newest first.
 */
@Serializable
data class Balances(val balances: List<Debt>, val entries: List<BalanceEntry>)
