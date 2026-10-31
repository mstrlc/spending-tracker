package dev.spendingtracker.shared.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class AccountStatus {
    @SerialName("active") ACTIVE,
    @SerialName("closed") CLOSED,
}

/** A ledger account. A null [ownerUserId] means a shared (joint) account. */
@Serializable
data class Account(
    val id: String,
    val name: String,
    val ownerUserId: String?,
    val status: AccountStatus,
    val version: Int,
)

/** Body for creating and updating an account; [version] is required when updating. */
@Serializable
data class AccountInput(
    val name: String,
    val ownerUserId: String? = null,
    val status: AccountStatus = AccountStatus.ACTIVE,
    val version: Int? = null,
)
