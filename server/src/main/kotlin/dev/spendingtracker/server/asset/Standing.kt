package dev.spendingtracker.server.asset

import dev.spendingtracker.shared.api.ValueSource
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
    val valueSource: ValueSource,
    val liability: Boolean = false,
    val repaidMinor: Long? = null,
    val borrowedMinor: Long? = null,
) {
    /** Valued at market prices rather than by valuations. */
    val marketValued: Boolean get() = valueSource == ValueSource.MARKET
    val gainMinor: Long? get() = when {
        liability -> 0L
        firstContributedOn == null -> null
        else -> valueMinor - contributedMinor
    }
}

private fun liabilityFlows(amounts: List<Long>, liability: Boolean): Pair<Long?, Long?> {
    if (!liability) return null to null
    var repaid = 0L
    var borrowed = 0L
    for (amount in amounts) {
        if (amount > 0) repaid += amount else if (amount < 0) borrowed -= amount
    }
    return repaid to borrowed
}

/**
 * Net contributions, and the value: the latest valuation plus whatever went in after its day, at cost, since a
 * contribution the valuation can't have seen yet is still money in the holding. A contribution on the valuation's
 * own day counts as already in it.
 *
 * For a [liability], valuations store what's owed as positive; the returned [Standing.valueMinor] is negated
 * (`-owed`) and later repayments (positive contributions) reduce what's owed (move [Standing.valueMinor] toward 0).
 */
fun standing(contributions: List<Dated>, valuations: List<Dated>, liability: Boolean = false): Standing {
    val contributed = contributions.sumOf { it.amountMinor }
    val latest = valuations.maxByOrNull { it.date }
    val value = if (latest == null) {
        contributed
    } else {
        val base = if (liability) -latest.amountMinor else latest.amountMinor
        base + contributions.filter { it.date > latest.date }.sumOf { it.amountMinor }
    }
    val source = if (latest != null) ValueSource.VALUATION else ValueSource.COST
    val (repaid, borrowed) = liabilityFlows(contributions.map { it.amountMinor }, liability)
    return Standing(contributed, value, latest?.date, contributions.minOfOrNull { it.date }, valueSource = source, liability = liability, repaidMinor = repaid, borrowedMinor = borrowed)
}

/**
 * Where a holding stood at the end of [on], counting only what was recorded by then. A holding following a
 * security, with shares on its contributions, is valued at the market through [price]: the shares held at the latest
 * close, plus contributions recorded without shares at cost. Its [fees] (broker commissions) were paid on top of the
 * shares rather than out of them, so they count as money put in. A holding tracked by an account uses [bankValue]
 * (latest feed balances on or before [on]) when any reading exists; otherwise, or before its first close, as [standing],
 * where fees are already in the valuations.
 */
fun standingOn(
    lots: List<Lot>,
    valuations: List<Dated>,
    fees: List<Dated>,
    on: LocalDate,
    price: ((shares: BigDecimal, on: LocalDate) -> Priced?)?,
    liability: Boolean = false,
    bankValue: ((on: LocalDate) -> Priced?)? = null,
): Standing {
    val held = lots.filter { it.date <= on }
    val tracked = held.filter { it.quantity != null }
    val priced = if (!liability && price != null && tracked.isNotEmpty()) price(tracked.sumOf { it.quantity!! }, on) else null
    if (priced != null) {
        val atCost = held.filter { it.quantity == null }.sumOf { it.amountMinor }
        val contributed = held.sumOf { it.amountMinor } + fees.filter { it.date <= on }.sumOf { it.amountMinor }
        return Standing(contributed, priced.valueMinor + atCost, priced.date, held.minOfOrNull { it.date }, ValueSource.MARKET)
    }
    val bankPriced = bankValue?.invoke(on)
    if (bankPriced != null) {
        val contributed = held.sumOf { it.amountMinor }
        val (repaid, borrowed) = liabilityFlows(held.map { it.amountMinor }, liability)
        return Standing(contributed, bankPriced.valueMinor, bankPriced.date, held.minOfOrNull { it.date }, ValueSource.BANK, liability = liability, repaidMinor = repaid, borrowedMinor = borrowed)
    }
    return standing(held.map { Dated(it.date, it.amountMinor) }, valuations.filter { it.date <= on }, liability = liability)
}
