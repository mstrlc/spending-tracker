package dev.spendingtracker.server.asset

import kotlinx.datetime.LocalDate
import java.math.BigDecimal

/** A dated amount: a contribution (signed), a valuation or a fee. */
data class Dated(val date: LocalDate, val amountMinor: Long)

/** A contribution with the shares it bought (negative: sold), or null shares when recorded without them. */
data class Lot(val date: LocalDate, val amountMinor: Long, val quantity: BigDecimal?)

/** Shares valued at a close: the value in household minor units, and the day of that close. */
data class Priced(val valueMinor: Long, val date: LocalDate)

/** Where a holding stands; see [dev.spendingtracker.shared.api.Asset] for what each figure means. */
data class Standing(
    val contributedMinor: Long,
    val valueMinor: Long,
    val valuedOn: LocalDate?,
    val firstContributedOn: LocalDate?,
    /** Valued at market prices rather than by valuations. */
    val marketValued: Boolean = false,
) {
    val gainMinor get() = valueMinor - contributedMinor
}

/**
 * Net contributions, and the value: the latest valuation plus whatever went in after its day, at cost, since a
 * contribution the valuation can't have seen yet is still money in the holding. A contribution on the valuation's
 * own day counts as already in it.
 */
fun standing(contributions: List<Dated>, valuations: List<Dated>): Standing {
    val contributed = contributions.sumOf { it.amountMinor }
    val latest = valuations.maxByOrNull { it.date }
    val value = if (latest == null) {
        contributed
    } else {
        latest.amountMinor + contributions.filter { it.date > latest.date }.sumOf { it.amountMinor }
    }
    return Standing(contributed, value, latest?.date, contributions.minOfOrNull { it.date })
}

/**
 * Where a holding stood at the end of [on], counting only what was recorded by then. A holding following a
 * security, with shares on its contributions, is valued at the market through [price]: the shares held at the latest
 * close, plus contributions recorded without shares at cost. Its [fees] (broker commissions) were paid on top of the
 * shares rather than out of them, so they count as money put in. Otherwise, or before its first close, as [standing],
 * where fees are already in the valuations.
 */
fun standingOn(
    lots: List<Lot>,
    valuations: List<Dated>,
    fees: List<Dated>,
    on: LocalDate,
    price: ((shares: BigDecimal, on: LocalDate) -> Priced?)?,
): Standing {
    val held = lots.filter { it.date <= on }
    val tracked = held.filter { it.quantity != null }
    val priced = if (price != null && tracked.isNotEmpty()) price(tracked.sumOf { it.quantity!! }, on) else null
    if (priced == null) {
        return standing(held.map { Dated(it.date, it.amountMinor) }, valuations.filter { it.date <= on })
    }
    val atCost = held.filter { it.quantity == null }.sumOf { it.amountMinor }
    val contributed = held.sumOf { it.amountMinor } + fees.filter { it.date <= on }.sumOf { it.amountMinor }
    return Standing(contributed, priced.valueMinor + atCost, priced.date, held.minOfOrNull { it.date }, marketValued = true)
}
