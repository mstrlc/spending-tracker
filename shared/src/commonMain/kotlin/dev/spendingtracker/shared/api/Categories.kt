package dev.spendingtracker.shared.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Investment categories hold capital moved into investments: counted as invested, not spent. Income categories classify earnings. */
@Serializable
enum class CategoryKind {
    @SerialName("consumption") CONSUMPTION,
    @SerialName("investment") INVESTMENT,
    @SerialName("income") INCOME,
}

/** One node of the two-level category tree. Subcategories inherit [kind] from their parent. */
@Serializable
data class Category(
    val id: String,
    val parentId: String?,
    val name: String,
    val kind: CategoryKind,
    val archived: Boolean,
    val sortOrder: Int,
    val version: Int,
)

/**
 * Body for creating and updating a category; [version] is required when updating.
 * [kind] applies to top-level categories only and is ignored for subcategories.
 */
@Serializable
data class CategoryInput(
    val name: String,
    val parentId: String? = null,
    val kind: CategoryKind = CategoryKind.CONSUMPTION,
    val archived: Boolean = false,
    val sortOrder: Int = 0,
    val version: Int? = null,
)
