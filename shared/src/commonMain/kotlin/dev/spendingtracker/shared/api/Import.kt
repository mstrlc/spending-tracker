package dev.spendingtracker.shared.api

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * A batch of household data to import, such as another app's export converted to this shape. Everything refers to
 * people, accounts, categories, trips and assets by name: each name is matched (ignoring case) to what the household
 * already has, or to an entry of the lists here, which are created when nothing matches. A null person means joint.
 *
 * Amounts are minor units of [currency], which must be the household's. Expenses and income carry an [ImportExpense.externalRef]
 * so the same file can be imported again: rows already imported are skipped.
 */
@Serializable
data class HouseholdImport(
    val currency: String,
    val people: List<ImportPerson> = emptyList(),
    val accounts: List<ImportAccount> = emptyList(),
    val categories: List<ImportCategory> = emptyList(),
    val trips: List<ImportTrip> = emptyList(),
    val expenses: List<ImportExpense> = emptyList(),
    val income: List<ImportIncome> = emptyList(),
    val assets: List<ImportAsset> = emptyList(),
    val bankFeeds: List<ImportBankFeed> = emptyList(),
    val bankRows: List<ImportBankRow> = emptyList(),
)

/**
 * A person rows can belong to. Matched to a member by the email of their login, then by display name; otherwise added
 * as a member without a login, who can claim the place later through an invite. [email] is only used for matching.
 */
@Serializable
data class ImportPerson(val name: String, val email: String? = null)

/** Matched by name or IBAN. [owner] is a person's name; null is joint. */
@Serializable
data class ImportAccount(
    val name: String,
    val kind: AccountKind = AccountKind.BANK,
    val owner: String? = null,
    val status: AccountStatus = AccountStatus.ACTIVE,
    val iban: String? = null,
)

/** A top-level category, or a subcategory of [parent]. [kind] applies to top-level ones only. */
@Serializable
data class ImportCategory(
    val name: String,
    val parent: String? = null,
    val kind: CategoryKind = CategoryKind.CONSUMPTION,
)

@Serializable
data class ImportTrip(
    val name: String,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val notes: String = "",
)

/** [subcategory] is a subcategory of [category]; [person] is whose spending it is (null for joint). */
@Serializable
data class ImportExpense(
    val externalRef: String,
    val date: LocalDate,
    val amountMinor: Long,
    val merchant: String = "",
    val category: String? = null,
    val subcategory: String? = null,
    val account: String? = null,
    val person: String? = null,
    val notes: String = "",
    val trip: String? = null,
    val originalAmountMinor: Long? = null,
    val originalCurrency: String? = null,
    val createsDebt: Boolean = true,
)

/** [earnedMonth] (YYYY-MM) defaults to the month of [date]; [recipient] null is joint. A deduction's amount is positive. */
@Serializable
data class ImportIncome(
    val externalRef: String,
    val date: LocalDate,
    val amountMinor: Long,
    val source: String = "",
    val earnedMonth: String? = null,
    val account: String? = null,
    val recipient: String? = null,
    val notes: String = "",
    val kind: IncomeKind = IncomeKind.EARNING,
)

/** Matched by name; a new one is created with [kind] and [owner]. Contributions already recorded (same date and amount) are skipped. */
@Serializable
data class ImportAsset(
    val name: String,
    val kind: AssetKind = AssetKind.OTHER,
    val owner: String? = null,
    val notes: String = "",
    val contributions: List<ImportContribution> = emptyList(),
)

@Serializable
data class ImportContribution(
    val date: LocalDate,
    val amountMinor: Long,
    val account: String? = null,
    val notes: String = "",
)

/** Matched by name; a new one is read into [account] (a name). Rows booked before [importFrom] aren't imported. */
@Serializable
data class ImportBankFeed(
    val name: String,
    val account: String,
    val currency: String,
    val institution: BankInstitution = BankInstitution.OTHER,
    val iban: String? = null,
    val importFrom: LocalDate? = null,
)

/**
 * A bank row of [feed] (a name), as the bank's GoCardless transaction JSON ([raw]). What a person decided comes across:
 * the [expense] or [income] line it became (their externalRef in this or an earlier import) or the [skipped] reason;
 * none of them leaves it in the inbox, with [hint] saying what was thought of it. Rows already imported are skipped.
 */
@Serializable
data class ImportBankRow(
    val feed: String,
    val raw: JsonObject,
    val expense: String? = null,
    val income: String? = null,
    val skipped: String? = null,
    val hint: String = "",
)

/**
 * What an import created, and what was [existing] already: names matched to the household's records, and expenses,
 * income and contributions imported before, which were skipped. With [dryRun] nothing was saved.
 */
@Serializable
data class ImportResult(
    val dryRun: Boolean,
    val created: ImportCounts,
    val existing: ImportCounts,
)

@Serializable
data class ImportCounts(
    val members: Int,
    val accounts: Int,
    val categories: Int,
    val trips: Int,
    val merchants: Int,
    val expenses: Int,
    val income: Int,
    val assets: Int,
    val contributions: Int,
    val bankFeeds: Int,
    val bankRows: Int,
)

/** One completed import batch in the household. */
@Serializable
data class ImportSummary(
    val id: String,
    val createdAt: String,
    val createdByName: String?,
    val counts: ImportCounts,
    val undoneAt: String?,
    val undoneByName: String?,
)

/** A container created by an import that was kept during undo because something else still uses it. */
@Serializable
data class KeptImportItem(
    val kind: String,
    val name: String,
    val reason: String,
)

/** What undoing an import removed, what had been edited since, and what was kept because it is still referenced. */
@Serializable
data class ImportUndoResult(
    val dryRun: Boolean,
    val deleted: ImportCounts,
    val edited: ImportCounts,
    val kept: ImportCounts,
    val keptItems: List<KeptImportItem>,
    val unlinkedBankRows: Int,
    val unlinkedRefunds: Int,
)
