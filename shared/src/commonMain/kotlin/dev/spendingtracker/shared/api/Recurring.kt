package dev.spendingtracker.shared.api

import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class RecurringStatus {
    /** Expected to keep coming. */
    @SerialName("active") ACTIVE,
    /** Stopped (a cancelled subscription); kept for the record, never flagged. */
    @SerialName("ended") ENDED,
    /** Not a recurring payment: only stops the merchant being suggested again. */
    @SerialName("dismissed") DISMISSED,
}

/** How an active recurring payment stands on the day asked about. */
@Serializable
enum class RecurringHealth {
    /** The latest due charge came, by about the expected amount. */
    @SerialName("ok") OK,
    /** Due now: the charge hasn't come yet, but it still may (within the matching window). */
    @SerialName("due") DUE,
    /** The window around the latest due date passed without a charge. */
    @SerialName("missed") MISSED,
    /** The latest charge differs from the expected amount by more than 5%. */
    @SerialName("changed") CHANGED,
    /** Not yet due for the first time. */
    @SerialName("upcoming") UPCOMING,
    /** Ended or dismissed: nothing is expected. */
    @SerialName("inactive") INACTIVE,
}

/**
 * A merchant charged on a schedule: due on [anchorDate] and every [intervalMonths] months from it (the day clamped to
 * shorter months). Charges are the merchant's expenses within 10 days of a due date (20 for longer intervals), a
 * refund netting off its original. Amounts are minor units of the household currency.
 */
@Serializable
data class RecurringPayment(
    val id: String,
    val merchantId: String,
    val merchant: String,
    val status: RecurringStatus,
    val expectedAmountMinor: Long,
    val intervalMonths: Int,
    val anchorDate: LocalDate,
    val categoryId: String?,
    val accountId: String?,
    /** Whose it is; null for joint. */
    val paidByUserId: String?,
    val notes: String,
    val version: Int,
    val health: RecurringHealth,
    /** The latest due date on or before the day asked about, or null before the first. */
    val lastDue: LocalDate?,
    /** The next due date after the day asked about. */
    val nextDue: LocalDate,
    /** The latest charge matched to a due date, if any. */
    val lastChargeDate: LocalDate?,
    val lastChargeMinor: Long?,
    /** Due dates since the anchor that passed without a charge, the latest first (up to 12). */
    val missedDates: List<LocalDate>,
)

/** Body for creating and updating a recurring payment; [version] is required when updating. */
@Serializable
data class RecurringPaymentInput(
    val merchantId: String,
    val expectedAmountMinor: Long,
    val anchorDate: LocalDate,
    val intervalMonths: Int = 1,
    val status: RecurringStatus = RecurringStatus.ACTIVE,
    val categoryId: String? = null,
    val accountId: String? = null,
    val paidByUserId: String? = null,
    val notes: String = "",
    val version: Int? = null,
)

/**
 * A merchant the expenses suggest is charged on a schedule, not yet confirmed or dismissed: monthly when charged in
 * at least 3 of the last 6 months with spending by similar amounts, yearly when charged around the same date in at
 * least 2 years running. [amountMinor] is the typical charge, [anchorDate] the latest one, and the category, account
 * and whose are the latest charge's.
 */
@Serializable
data class RecurringSuggestion(
    val merchantId: String,
    val merchant: String,
    val intervalMonths: Int,
    val amountMinor: Long,
    val anchorDate: LocalDate,
    val timesSeen: Int,
    val categoryId: String?,
    val accountId: String?,
    val paidByUserId: String?,
)

@Serializable
data class RecurringOverview(
    /** Active payments first (needing attention first, then by next due date), then ended and dismissed ones. */
    val payments: List<RecurringPayment>,
    /** Most expensive per month first. */
    val suggestions: List<RecurringSuggestion>,
    /** What the active payments cost per month on average (a yearly one counts a twelfth). */
    val monthlyMinor: Long,
)
