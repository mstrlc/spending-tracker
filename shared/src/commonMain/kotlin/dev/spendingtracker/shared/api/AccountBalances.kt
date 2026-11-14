package dev.spendingtracker.shared.api

import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A balance check: what an account the bank doesn't report held at the end of [date], in the household's currency.
 * Its balance on other days is worked out from the nearest check and what the ledger recorded on it in between.
 */
@Serializable
data class AccountBalance(
    val id: String,
    val accountId: String,
    val date: LocalDate,
    val amountMinor: Long,
    val notes: String,
    val version: Int,
)

/** Body for adding a balance check. At most one per account and day. */
@Serializable
data class AccountBalanceInput(
    val date: LocalDate,
    val amountMinor: Long,
    val notes: String = "",
)

/** Where an account's value comes from. */
@Serializable
enum class AccountValueSource {
    /** The latest balance the bank reported. */
    @SerialName("bank") BANK,

    /** The nearest balance check, moved by the transfers, income, expenses and contributions recorded since. */
    @SerialName("computed") COMPUTED,

    /** Neither: the bank reports nothing and no balance was checked. */
    @SerialName("none") NONE,
}

/**
 * An account's value today in the household's currency (null with [AccountValueSource.NONE]). [asOf] is the day of
 * the bank reading, or of the balance check a computed value starts from. [followedByAssetId] is set when an asset
 * follows the account: its value counts once, through that asset.
 */
@Serializable
data class AccountValue(
    val accountId: String,
    val valueMinor: Long?,
    val source: AccountValueSource,
    val asOf: LocalDate?,
    val followedByAssetId: String? = null,
)
