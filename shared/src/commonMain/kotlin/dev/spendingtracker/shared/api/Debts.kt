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
    @SerialName("top_up") TOP_UP,
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
 * What [userId] put into the joint account [accountId] (negative: took out) by transfers: from an account of theirs
 * into it counts positive, from it into an account of theirs negative. Not a debt, so it doesn't change [Balances.balances].
 */
@Serializable
data class JointContribution(val userId: String, val accountId: String, val amountMinor: Long)

/**
 * Where members stand with each other. [balances]: who owes whom after everything so far, one line per pair that
 * isn't square. [entries]: what moved the balance between from and to, newest first. [jointContributions]: the net
 * transfers into joint accounts between from and to, per member and joint account (none for a net of zero).
 * [jointLeftover]: unspent top-ups still in joint accounts (when `jointFunding` is `contributions` and top-ups
 * exceed joint-account spending), per member and joint account.
 */
@Serializable
data class Balances(
    val balances: List<Debt>,
    val entries: List<BalanceEntry>,
    val jointContributions: List<JointContribution>,
    val jointLeftover: List<JointContribution>,
)
