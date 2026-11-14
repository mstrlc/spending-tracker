package dev.spendingtracker.shared.api

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

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
)
