package dev.spendingtracker.shared.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class AccountStatus {
    @SerialName("active") ACTIVE,
    @SerialName("closed") CLOSED,
}

/**
 * What an account is. A credit card's purchases are spending and paying it off is a transfer; [CASH] and [OTHER] have
 * no bank behind them (an invoice something was netted against, say).
 */
@Serializable
enum class AccountKind {
    /** A current (checking) account. */
    @SerialName("bank") BANK,

    /** A savings account: pays interest, usually monthly, with tax withheld ([Account.interestTaxPercent]). */
    @SerialName("savings") SAVINGS,
    @SerialName("credit_card") CREDIT_CARD,
    @SerialName("cash") CASH,
    @SerialName("other") OTHER,
}

/** A ledger account. A null [ownerUserId] means a shared (joint) account. [iban] is normalized (no spaces, upper case). */
@Serializable
data class Account(
    val id: String,
    val name: String,
    val ownerUserId: String?,
    val status: AccountStatus,
    val version: Int,
    val kind: AccountKind,
    val iban: String?,
    /** A savings account's tax withheld from interest, in percent (15 in Czechia); null for other kinds. */
    val interestTaxPercent: Int? = null,
)

/** Body for creating and updating an account; [version] is required when updating. */
@Serializable
data class AccountInput(
    val name: String,
    val ownerUserId: String? = null,
    val status: AccountStatus = AccountStatus.ACTIVE,
    val version: Int? = null,
    val kind: AccountKind = AccountKind.BANK,
    /** Spaces and case don't matter; blank means none. */
    val iban: String? = null,
    /** Savings accounts only: tax withheld from interest in percent, 0–99; null on a savings account means 15. */
    val interestTaxPercent: Int? = null,
)
