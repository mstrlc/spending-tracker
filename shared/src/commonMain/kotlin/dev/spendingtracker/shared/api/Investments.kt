package dev.spendingtracker.shared.api

import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class InvestmentKind {
    @SerialName("fund") FUND,
    @SerialName("stock") STOCK,
    @SerialName("bond") BOND,
    @SerialName("crypto") CRYPTO,
    @SerialName("other") OTHER,
}

/**
 * A market holding, with its standing as of today in the household currency. A null [ownerUserId] means held jointly.
 *
 * [contributedMinor] is everything put in less everything taken out. A holding following a [security] whose
 * contributions record shares is valued at the market: [shares] at the latest close ([valuedOn]), converted at
 * the ČNB fixing, plus any contributions without shares at cost. Otherwise [valueMinor] is the latest valuation plus
 * contributions made after it (at cost), or just [contributedMinor] while the holding has never been valued
 * ([valuedOn] null). [gainMinor] is [valueMinor] − [contributedMinor].
 */
@Serializable
data class Investment(
    val id: String,
    val name: String,
    val kind: InvestmentKind,
    val ownerUserId: String?,
    val notes: String,
    val version: Int,
    val security: SecurityQuote?,
    /** Shares held, from contributions that record them; null when none do. */
    val shares: Double?,
    /** True when [valueMinor] comes from market prices rather than valuations. */
    val marketValued: Boolean,
    val contributedMinor: Long,
    val valueMinor: Long,
    val valuedOn: LocalDate?,
    val gainMinor: Long,
    val firstContributedOn: LocalDate?,
)

/**
 * Body for creating and updating a holding; [version] is required when updating. [symbol] is a security from
 * the search (e.g. VWCE.DE), or null for a holding valued by hand.
 */
@Serializable
data class InvestmentInput(
    val name: String,
    val kind: InvestmentKind = InvestmentKind.FUND,
    val ownerUserId: String? = null,
    val notes: String = "",
    val symbol: String? = null,
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

/** The household's holdings (or one of them) at the end of each month, then today. */
@Serializable
data class InvestmentHistory(val points: List<HistoryPoint>)

@Serializable
data class HistoryPoint(val date: LocalDate, val contributedMinor: Long, val valueMinor: Long)

/**
 * Money put into a holding on [date], at cost; negative when taken out. [quantity] is the shares bought (negative:
 * sold), when known. [accountId] is where the money came from.
 */
@Serializable
data class Contribution(
    val id: String,
    val investmentId: String,
    val date: LocalDate,
    val amountMinor: Long,
    val quantity: Double?,
    val accountId: String?,
    val notes: String,
    val version: Int,
)

@Serializable
data class ContributionInput(
    val date: LocalDate,
    val amountMinor: Long,
    val quantity: Double? = null,
    val accountId: String? = null,
    val notes: String = "",
    val version: Int? = null,
)

/** What a holding was worth at the end of [date]. At most one per holding and day. */
@Serializable
data class Valuation(
    val id: String,
    val investmentId: String,
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
