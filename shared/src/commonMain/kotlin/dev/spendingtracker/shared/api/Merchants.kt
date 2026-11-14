package dev.spendingtracker.shared.api

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/**
 * Where money was spent. Names are unique per household ignoring case; [defaultCategoryId] is what a new expense
 * there gets filed under. [spentMinor], [expenseCount] and [lastDate] add up its expenses.
 */
@Serializable
data class Merchant(
    val id: String,
    val name: String,
    val defaultCategoryId: String?,
    val version: Int,
    val spentMinor: Long,
    val expenseCount: Int,
    val lastDate: LocalDate?,
)

/** Body for creating and updating a merchant; [version] is required when updating. */
@Serializable
data class MerchantInput(
    val name: String,
    val defaultCategoryId: String? = null,
    val version: Int? = null,
)

/** Moves every expense of a merchant (at [version]) to [intoId] and deletes it. */
@Serializable
data class MerchantMerge(val intoId: String, val version: Int)
