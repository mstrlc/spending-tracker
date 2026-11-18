package dev.spendingtracker.shared.api

import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What an asset is. Only the [market] kinds can follow a listed security; the rest are valued by hand.
 * [liability] kinds represent what the household owes (loans, credit card debt, payables).
 * [trackable] kinds can link to an account and take their value from its bank feed balances.
 */
@Serializable
enum class AssetKind(val market: Boolean, val liability: Boolean = false, val trackable: Boolean = false) {
    @SerialName("fund") FUND(true),
    @SerialName("stock") STOCK(true),
    @SerialName("bond") BOND(true),
    @SerialName("crypto") CRYPTO(true),
    /** A savings account. */
    @SerialName("savings") SAVINGS(false, trackable = true),
    /** Building savings, a pension or another scheme the money is locked in. */
    @SerialName("scheme") SCHEME(false, trackable = true),
    /** Money owed to the household. */
    @SerialName("receivable") RECEIVABLE(false),
    @SerialName("vehicle") VEHICLE(false),
    @SerialName("property") PROPERTY(false),
    @SerialName("other") OTHER(false, trackable = true),
    /** A mortgage, car loan or personal loan. */
    @SerialName("loan") LOAN(false, liability = true, trackable = true),
    /** What's owed on a credit card. */
    @SerialName("card_debt") CARD_DEBT(false, liability = true, trackable = true),
    /** Money the household owes someone. */
    @SerialName("payable") PAYABLE(false, liability = true, trackable = true),
}

/** Where an asset's current [Asset.valueMinor] comes from. */
@Serializable
enum class ValueSource {
    @SerialName("market") MARKET,
    @SerialName("bank") BANK,
    @SerialName("valuation") VALUATION,
    @SerialName("cost") COST,
}

/**
 * Something the household owns (a market holding, savings, a car…) or owes (a loan, credit card debt…), with its
 * standing as of today in the household currency. A null [ownerUserId] means held jointly.
 *
 * [contributedMinor] is everything put in less everything taken out; for a market-valued holding, its fees too, since
 * commissions are paid on top of the shares rather than out of them. A holding following a [security] whose
 * contributions record shares is valued at the market: [shares] at the latest close ([valuedOn]), converted at
 * the ČNB fixing, plus any contributions without shares at cost. Otherwise [valueMinor] is the latest valuation plus
 * contributions made after it (at cost), or just [contributedMinor] while the holding has never been valued
 * ([valuedOn] null). [gainMinor] is [valueMinor] − [contributedMinor].
 *
 * Sign convention for [AssetKind.liability] kinds:
 * - Valuations ([Valuation.valueMinor], >= 0) store **what's owed** as a positive number.
 * - Standing ([valueMinor] here and in [HistoryPoint]) is reported **negative** (`-owed`), so summing every
 *   asset's [valueMinor] gives net worth.
 * - Contributions: negative is money borrowed (the balance owed goes up), positive is a repayment of principal.
 *   Interest and fees use [Fee] (already in the balance the lender reports, like hand-valued assets).
 * - [gainMinor] is always `0` for a liability.
 * - Note: repayments from a bank account are transfers today; creating a contribution from a transfer is not yet
 *   wired up.
 */
@Serializable
data class Asset(
    val id: String,
    val name: String,
    val kind: AssetKind,
    val ownerUserId: String?,
    val notes: String,
    val version: Int,
    val security: SecurityQuote?,
    /** Shares held, from contributions that record them; null when none do. */
    val shares: Double?,
    /** True when [valueMinor] comes from market prices rather than valuations. */
    val marketValued: Boolean,
    val accountId: String?,
    val valueSource: ValueSource,
    val contributedMinor: Long,
    val valueMinor: Long,
    val valuedOn: LocalDate?,
    val gainMinor: Long,
    /** Annualised money-weighted return (XIRR); null for liabilities, under 30 days of history, or if non-convergent. */
    val annualReturn: Double?,
    val firstContributedOn: LocalDate?,
    /**
     * Fees charged so far, less discounts. Taken out of a holding valued by hand, so already in [valueMinor]; paid on
     * top for a [marketValued] one, so in [contributedMinor]. Either way already in [gainMinor].
     */
    val feesMinor: Long,
)

/**
 * Household asset and liability totals in the household currency.
 * [assetsMinor] is the total worth of owned assets; [liabilitiesMinor] is the signed total of liabilities
 * (negative when owing), so [netMinor] = [assetsMinor] + [liabilitiesMinor].
 * [contributedMinor] and [gainMinor] cover owned assets only; [feesMinor] covers all holdings.
 */
@Serializable
data class AssetSummary(
    val assetsMinor: Long,
    val liabilitiesMinor: Long,
    val netMinor: Long,
    val contributedMinor: Long,
    val gainMinor: Long,
    val feesMinor: Long,
    /** Annualised money-weighted return (XIRR) across all owned assets; null if under 30 days or non-convergent. */
    val annualReturn: Double?,
)

/**
 * Body for creating and updating a holding; [version] is required when updating. [symbol] is a security from
 * the search (e.g. VWCE.DE), or null for a holding valued by hand.
 */
@Serializable
data class AssetInput(
    val name: String,
    val kind: AssetKind = AssetKind.FUND,
    val ownerUserId: String? = null,
    val notes: String = "",
    val symbol: String? = null,
    val accountId: String? = null,
    val version: Int? = null,
)

/** A listed security and its latest close ([close] in [currency], null before any is known). */
@Serializable
data class SecurityQuote(
    val symbol: String,
    val name: String,
    val exchange: String,
    val currency: String,
    val close: Double?,
    val closeOn: LocalDate?,
)

/** A search result: something a holding can follow. */
@Serializable
data class SecurityMatch(val symbol: String, val name: String, val exchange: String, val type: String)

/** ČNB exchange rate for 1 unit of [from] in [to] on or before [date]; [rate] is null when unavailable. */
@Serializable
data class FxRateQuote(val from: String, val to: String, val date: LocalDate, val rate: Double?)

/** The household's holdings (or one of them) at the end of each month, then today. */
@Serializable
data class AssetHistory(val points: List<HistoryPoint>)

@Serializable
data class HistoryPoint(val date: LocalDate, val contributedMinor: Long, val valueMinor: Long)

/**
 * Money put into a holding on [date], at cost; negative when taken out. [quantity] is the shares bought (negative:
 * sold), when known. [accountId] is where the money came from.
 */
@Serializable
data class Contribution(
    val id: String,
    val assetId: String,
    val date: LocalDate,
    val amountMinor: Long,
    val originalAmountMinor: Long?,
    val originalCurrency: String?,
    val quantity: Double?,
    val accountId: String?,
    val notes: String,
    val version: Int,
)

@Serializable
data class ContributionInput(
    val date: LocalDate,
    val amountMinor: Long? = null,
    val quantity: Double? = null,
    val accountId: String? = null,
    val notes: String = "",
    val version: Int? = null,
    val originalAmountMinor: Long? = null,
    val originalCurrency: String? = null,
)

/** A fee the provider charged on [date]; negative for a discount. Informational: it's already in the value. */
@Serializable
data class Fee(
    val id: String,
    val assetId: String,
    val date: LocalDate,
    val amountMinor: Long,
    val notes: String,
    val version: Int,
)

@Serializable
data class FeeInput(
    val date: LocalDate,
    val amountMinor: Long,
    val notes: String = "",
    val version: Int? = null,
)

/** What a holding was worth at the end of [date]. At most one per holding and day. */
@Serializable
data class Valuation(
    val id: String,
    val assetId: String,
    val date: LocalDate,
    val valueMinor: Long,
    val notes: String,
    val version: Int,
)

@Serializable
data class ValuationInput(
    val date: LocalDate,
    val valueMinor: Long,
    val notes: String = "",
    val version: Int? = null,
)
