package dev.spendingtracker.shared.api

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/**
 * Money moving between the household's own accounts: neither income nor spending. [amountMinor] is in the household
 * currency, what left [fromAccountId]. The two accounts are the same for an exchange between two pockets of one
 * account. [rowIds]: the bank rows it was made from (one or two).
 */
@Serializable
data class Transfer(
    val id: String,
    val date: LocalDate,
    val amountMinor: Long,
    val fromAccountId: String,
    val toAccountId: String,
    val notes: String,
    val version: Int,
    val rowIds: List<String>,
)

/**
 * Files [rows] as one transfer. Two rows: one outgoing and one incoming, of different feeds ([otherAccountId] must
 * be null). One row: [otherAccountId], an account of the household that isn't read, is the other side.
 */
@Serializable
data class TransferBankRows(
    val rows: List<BankRowRef>,
    val otherAccountId: String? = null,
    val notes: String = "",
)

/**
 * Files [rows] (one or two, as for a transfer) as a payment from member [fromUserId] to member [toUserId]: a
 * settlement of what they owe each other, dated and sized by the money that moved.
 */
@Serializable
data class SettleBankRows(
    val rows: List<BankRowRef>,
    val fromUserId: String,
    val toUserId: String,
    val notes: String = "",
)
