package dev.spendingtracker.shared.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What kind of record an [ActivityEntry] is about. */
@Serializable
enum class ActivityEntity {
    @SerialName("household") HOUSEHOLD,
    @SerialName("member") MEMBER,
    @SerialName("invite") INVITE,
    @SerialName("account") ACCOUNT,
    @SerialName("category") CATEGORY,
    @SerialName("expense") EXPENSE,
    @SerialName("expense_line") EXPENSE_LINE,
    @SerialName("income") INCOME,
    @SerialName("asset") ASSET,
    @SerialName("asset_contribution") ASSET_CONTRIBUTION,
    @SerialName("asset_valuation") ASSET_VALUATION,
    @SerialName("asset_fee") ASSET_FEE,
    @SerialName("trip") TRIP,
    @SerialName("merchant") MERCHANT,
    @SerialName("split_rule") SPLIT_RULE,
    @SerialName("split_share") SPLIT_SHARE,
    @SerialName("settlement") SETTLEMENT,
    @SerialName("bank_row") BANK_ROW,
    @SerialName("import") IMPORT,
}

@Serializable
enum class ActivityAction {
    @SerialName("create") CREATE,
    @SerialName("update") UPDATE,
    @SerialName("delete") DELETE,
}

/**
 * One field of a changed record, named as in the API models (amountMinor, categoryId, …), with values as text:
 * numbers and ids as they are, null when absent. A create lists every field with [before] null, a delete
 * every field with [after] null, an update only the fields that changed.
 */
@Serializable
data class FieldChange(val field: String, val before: String?, val after: String?)

/**
 * One entry of the household's audit log. [label] names the record (an expense's merchant, an account's name);
 * [actorName] is null for changes made outside the app.
 */
@Serializable
data class ActivityEntry(
    val id: Long,
    val at: String,
    val actorUserId: String?,
    val actorName: String?,
    val entity: ActivityEntity,
    val entityId: String,
    val action: ActivityAction,
    val label: String,
    val changes: List<FieldChange>,
)

/** A page of the audit log, newest first; pass [nextBefore] as ?before= for the next page, null at the end. */
@Serializable
data class ActivityPage(val entries: List<ActivityEntry>, val nextBefore: Long?)
