package dev.spendingtracker.shared.api

import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Which bank a feed is at: card descriptors, transaction codes and dates are read differently per bank. */
@Serializable
enum class BankInstitution {
    @SerialName("revolut") REVOLUT,
    @SerialName("csob") CSOB,
    @SerialName("kb") KB,
    @SerialName("raiffeisen") RAIFFEISEN,
    @SerialName("other") OTHER,
}

/**
 * What a bank provides (one currency pocket, one card), read into the ledger account [accountId]; several feeds can
 * belong to one account. Rows booked before [importFrom] aren't brought in: the ledger already has that history.
 * [source] is "file" for uploaded statements and "gocardless" for a feed read live through a bank connection.
 */
@Serializable
data class BankFeed(
    val id: String,
    val accountId: String,
    val name: String,
    val institution: BankInstitution,
    val source: String,
    val iban: String?,
    val currency: String,
    val importFrom: LocalDate?,
    val version: Int,
    val rowCount: Int,
    val pendingCount: Int,
    /** The latest row's date: how far the feed reaches. */
    val lastDate: LocalDate?,
    /** The bank connection a live feed is read through; null for statement files and for a connection since deleted. */
    val connectionId: String?,
    /** ISO timestamp of the last sync that reached the bank. */
    val lastSyncedAt: String?,
    /** Times today (Prague time) the bank was asked for this feed; the bank allows [BANK_SYNCS_PER_DAY]. */
    val syncsToday: Int,
    /** The latest balance a sync read. */
    val balance: FeedBalance?,
)

/** Body for creating and updating a feed; [version] is required when updating. */
@Serializable
data class BankFeedInput(
    val accountId: String,
    val name: String,
    val institution: BankInstitution = BankInstitution.OTHER,
    val iban: String? = null,
    val currency: String,
    val importFrom: LocalDate? = null,
    val version: Int? = null,
)

/**
 * A statement file's text: a bank's CSV export (Revolut, ČSOB, KB, Raiffeisenbank current account or credit card;
 * the format is recognised from its header) or GoCardless transactions JSON.
 */
@Serializable
data class StatementUpload(
    val content: String,
    val fileName: String = "",
)

/**
 * What a statement brought in. [existing] rows were there already (statements can overlap); [beforeImportFrom]
 * were older than the feed's import-from date; [notBooked] hadn't settled yet (pending, declined), so they were left out.
 */
@Serializable
data class StatementResult(
    val format: String,
    val added: Int,
    val existing: Int,
    val beforeImportFrom: Int,
    val notBooked: Int,
)

@Serializable
enum class BankRowOutcome {
    @SerialName("pending") PENDING,
    @SerialName("expense") EXPENSE,
    @SerialName("income") INCOME,
    @SerialName("transfer") TRANSFER,
    @SerialName("settlement") SETTLEMENT,
    @SerialName("skipped") SKIPPED,
}

/**
 * One bank transaction. [amountMinor] is in the row's own [currency], money out negative. [text] names who was paid
 * or who paid; [hint] is what the reader noticed (between own accounts, a top-up) or what the old app decided, for
 * whoever decides. A pending row carries a [suggestion].
 */
@Serializable
data class BankRow(
    val id: String,
    val feedId: String,
    val accountId: String,
    val date: LocalDate,
    val bookingDate: LocalDate,
    val amountMinor: Long,
    val currency: String,
    val counterpartyName: String,
    val counterpartyIban: String?,
    val counterpartyAccount: String,
    val text: String,
    val message: String,
    val code: String,
    val hint: String,
    /** The bank row's descriptor without numbers (what rules and past decisions are matched on); an IBAN when there is none. */
    val merchantKey: String,
    val outcome: BankRowOutcome,
    val expenseId: String?,
    val incomeId: String?,
    val transferId: String?,
    val settlementId: String?,
    val skipReason: String?,
    val decidedByUserId: String?,
    val decidedAuto: Boolean,
    /** The rule that filed it automatically; null for a person's decision, the matcher's, or a rule since deleted. */
    val ruleId: String?,
    /** ISO timestamp. */
    val decidedAt: String?,
    val version: Int,
    val suggestion: BankRowSuggestion?,
    /** Name of the linked record for decided rows (e.g. merchant, income source, "From → To"). */
    val outcomeLabel: String? = null,
)

@Serializable
enum class SuggestedRecord {
    @SerialName("expense") EXPENSE,
    @SerialName("income") INCOME,
    @SerialName("transfer") TRANSFER,
}

/**
 * What a pending row probably is: money out an expense, money in income. [amountMinor] is in the household currency
 * as the record would have it (an expense positive, a refund negative); null when there's no exchange rate for the
 * day yet. [merchant], [categoryId] and [paidByUserId] come from the last row with the same descriptor, else the
 * merchant's default and the account's owner. [duplicates] are records already entered by hand that look like
 * this same transaction.
 *
 * The record is [SuggestedRecord.TRANSFER] when the money probably moved between the household's own accounts: a
 * pending row looks like the other side ([transferCandidates], best first, at most 3), the counterparty's IBAN is
 * one of the household's accounts ([transferAccountId], only when there is no candidate), or the hint says so. The
 * expense and income fields stay filled, as for the record the row would be otherwise.
 *
 * A rule that matches the row ([ruleId], [ruleName]) takes precedence over the guess from past decisions: its record,
 * merchant, category, whose, income source and other account replace the guesses, and [skipReason] is set when the
 * rule skips. For income, [paidByUserId] is who receives it.
 *
 * A [RuleOutcome.DEDUCTION][dev.spendingtracker.shared.api.RuleOutcome.DEDUCTION] rule makes the record income with
 * [incomeKind] [IncomeKind.DEDUCTION]: [amountMinor] is minus the row's amount (money out is a positive deduction) and
 * [earnedMonth] (YYYY-MM) the row's month shifted by the rule's offset. Otherwise [incomeKind] is
 * [IncomeKind.EARNING] and [earnedMonth] null.
 *
 * For money in, [refundCandidates] are the recent expenses the row may refund (same merchant key or merchant, past 120
 * days, at least as large as the row), newest first, at most 5; the first is what "Refund of" is prefilled with.
 */
@Serializable
data class BankRowSuggestion(
    val record: SuggestedRecord,
    val amountMinor: Long?,
    val originalAmountMinor: Long?,
    val originalCurrency: String?,
    val merchant: String,
    val categoryId: String?,
    val paidByUserId: String?,
    val incomeSource: String,
    val duplicates: List<DuplicateCandidate>,
    val transferCandidates: List<TransferCandidate>,
    val transferAccountId: String?,
    val ruleId: String?,
    val ruleName: String?,
    val skipReason: String?,
    val incomeKind: IncomeKind,
    val earnedMonth: String?,
    val refundCandidates: List<RefundCandidate>,
)

/** A recent expense a money-in row may refund; [amountMinor] is the original's, [refundedMinor] what its refunds already returned. */
@Serializable
data class RefundCandidate(
    val id: String,
    val date: LocalDate,
    val amountMinor: Long,
    val refundedMinor: Long,
    val label: String,
)

/** A pending row that looks like the other side of a transfer; [amountMinor] is in the row's own [currency], money out negative. */
@Serializable
data class TransferCandidate(
    val rowId: String,
    val version: Int,
    val accountId: String,
    val date: LocalDate,
    val amountMinor: Long,
    val currency: String,
    val text: String,
)

@Serializable
data class DuplicateCandidate(
    val record: SuggestedRecord,
    val id: String,
    val date: LocalDate,
    val amountMinor: Long,
    val label: String,
)

@Serializable
data class BankRowRef(val id: String, val version: Int)

/** Files [rows] under an existing expense or income line (exactly one of them): entered by hand, or made from another row. */
@Serializable
data class LinkBankRows(
    val rows: List<BankRowRef>,
    val expenseId: String? = null,
    val incomeId: String? = null,
)

@Serializable
data class SkipBankRows(
    val rows: List<BankRowRef>,
    val reason: String,
)

/**
 * Puts [rows] back in the inbox. An expense or income line made from them is deleted once none of its rows is left;
 * one entered by hand stays.
 */
@Serializable
data class UndoBankRows(val rows: List<BankRowRef>)
