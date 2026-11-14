package dev.spendingtracker.server.asset

import dev.spendingtracker.server.db.FxRates
import dev.spendingtracker.server.db.SecurityPrices
import dev.spendingtracker.server.market.SecurityInfo
import dev.spendingtracker.server.market.securities
import kotlinx.datetime.LocalDate
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.util.Currency
import java.util.TreeMap

/**
 * Closing prices and ČNB fixings for a set of securities, to value shares on any day in the household currency.
 * A day without a close or fixing (weekends, holidays) uses the latest one before it.
 */
class Prices(
    private val householdCurrency: String,
    val securities: Map<String, SecurityInfo>,
    private val closes: Map<String, TreeMap<LocalDate, BigDecimal>>,
    private val czk: Map<String, TreeMap<LocalDate, BigDecimal>>,
) {
    private val minorUnits = BigDecimal.TEN.pow(Currency.getInstance(householdCurrency).defaultFractionDigits)

    /** The latest close of [symbol] on or before [on], in its own currency. */
    fun close(symbol: String, on: LocalDate): Pair<LocalDate, BigDecimal>? =
        closes[symbol]?.floorEntry(on)?.let { it.key to it.value }

    /** [shares] of [symbol] at its latest close by [on], converted at that day's fixing; null without either. */
    fun value(symbol: String, shares: BigDecimal, on: LocalDate): Priced? {
        val security = securities[symbol] ?: return null
        val (date, close) = close(symbol, on) ?: return null
        val rate = rate(security.currency, date) ?: return null
        val minor = (shares * close * rate * minorUnits).setScale(0, RoundingMode.HALF_UP)
        return Priced(minor.toLong(), date)
    }

    /** Household currency for one unit of [currency], crossing through CZK as the ČNB quotes everything in it. */
    private fun rate(currency: String, on: LocalDate): BigDecimal? {
        if (currency == householdCurrency) return BigDecimal.ONE
        val from = czkFor(currency, on) ?: return null
        val to = czkFor(householdCurrency, on) ?: return null
        return from.divide(to, MathContext.DECIMAL64)
    }

    private fun czkFor(currency: String, on: LocalDate): BigDecimal? =
        if (currency == "CZK") BigDecimal.ONE else czk[currency]?.floorEntry(on)?.value
}

/** Loads what [Prices] needs for [symbols]. Must run in a transaction. */
fun loadPrices(householdCurrency: String, symbols: Collection<String>): Prices {
    val securities = securities(symbols)
    val closes = HashMap<String, TreeMap<LocalDate, BigDecimal>>()
    if (securities.isNotEmpty()) {
        SecurityPrices.selectAll().where { SecurityPrices.symbol inList securities.keys }.forEach {
            closes.getOrPut(it[SecurityPrices.symbol]) { TreeMap() }[it[SecurityPrices.date]] = it[SecurityPrices.close]
        }
    }
    val currencies = (securities.values.map { it.currency } + householdCurrency).filter { it != "CZK" }.distinct()
    val czk = HashMap<String, TreeMap<LocalDate, BigDecimal>>()
    if (currencies.isNotEmpty() && securities.values.any { it.currency != householdCurrency }) {
        FxRates.selectAll().where { FxRates.currency inList currencies }.forEach {
            czk.getOrPut(it[FxRates.currency]) { TreeMap() }[it[FxRates.date]] = it[FxRates.czk]
        }
    }
    return Prices(householdCurrency, securities, closes, czk)
}
