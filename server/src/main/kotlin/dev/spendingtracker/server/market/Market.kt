package dev.spendingtracker.server.market

import dev.spendingtracker.server.db.FxRates
import dev.spendingtracker.server.db.MarketFetches
import dev.spendingtracker.server.db.Securities
import dev.spendingtracker.server.db.SecurityPrices
import dev.spendingtracker.server.db.dbQuery
import io.ktor.server.application.Application
import io.ktor.util.AttributeKey
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.jdbc.batchUpsert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import org.slf4j.LoggerFactory
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

private val log = LoggerFactory.getLogger(Market::class.java)

// Closing prices and fixings change once a day; this keeps page loads from asking the providers every time.
private val STALE_AFTER = 6.hours

/**
 * Market data kept in the database: securities are fetched on first use, then their prices and the exchange rates
 * they need are refreshed at most every [STALE_AFTER]. A provider that fails leaves the cached data in place.
 */
class Market(private val data: MarketData, private val clock: Clock = Clock.System) {
    suspend fun search(query: String): List<SecurityMatch> = data.search(query)

    /** Makes [symbol] known, with its price history; false when the provider has never heard of it. */
    suspend fun register(symbol: String): Boolean {
        if (dbQuery { !Securities.selectAll().where { Securities.symbol eq symbol }.empty() }) return true
        val history = data.history(symbol, from = null) ?: return false
        dbQuery {
            Securities.upsert {
                it[Securities.symbol] = symbol
                it[name] = history.info.name
                it[exchange] = history.info.exchange
                it[currency] = history.info.currency
            }
            storePrices(symbol, history.closes)
            markFetched("prices:$symbol")
        }
        return true
    }

    /** Brings the prices of [symbols], and the ČNB fixings of [fxYears], up to date where they are stale. */
    suspend fun refresh(symbols: Collection<String>, fxYears: Collection<Int>) {
        val today = clock.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
        for (symbol in symbols.distinct()) {
            val key = "prices:$symbol"
            if (!isStale(key)) continue
            attempt(key) {
                // A week's overlap rewrites any close the provider corrected after the fact.
                val latest = dbQuery { latestPrice(symbol) }
                val history = data.history(symbol, latest?.minus(DatePeriod(days = 7)))
                if (history != null) dbQuery { storePrices(symbol, history.closes) }
            }
        }
        refreshFxYears(fxYears, today)
    }

    /**
     * Ensures ČNB fixings are available for each date in [dates]: refreshes `date.year`, and also
     * `date.year - 1` whenever `date.year` has no fixing on or before `date` (e.g. January 1).
     */
    suspend fun refreshFx(dates: Collection<LocalDate>) {
        if (dates.isEmpty()) return
        val today = clock.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
        val earliestByYear = dates.groupBy { it.year }.mapValues { (_, ds) -> ds.min() }
        refreshFxYears(earliestByYear.keys, today)
        val fallbackYears = dbQuery {
            earliestByYear.mapNotNull { (year, minDate) ->
                val empty = FxRates.select(FxRates.date)
                    .where { (FxRates.date greaterEq LocalDate(year, 1, 1)) and (FxRates.date lessEq minDate) }
                    .limit(1)
                    .empty()
                if (empty) year - 1 else null
            }
        }
        refreshFxYears(fallbackYears, today)
    }

    /** Convenience overload of [refreshFx] for a single [date]. */
    suspend fun refreshFx(date: LocalDate) = refreshFx(listOf(date))

    private suspend fun refreshFxYears(fxYears: Collection<Int>, today: LocalDate) {
        for (year in fxYears.distinct()) {
            val key = "fx:$year"
            // A past year is complete once fetched; only the current one keeps growing.
            if (dbQuery { fetchedAt(key) } != null && (year < today.year || !isStale(key))) continue
            attempt(key) {
                val rates = data.czkRates(year)
                dbQuery {
                    FxRates.batchUpsert(rates) { rate ->
                        this[FxRates.currency] = rate.currency
                        this[FxRates.date] = rate.date
                        this[FxRates.czk] = rate.czk
                    }
                }
            }
        }
    }

    private suspend fun attempt(key: String, block: suspend () -> Unit) {
        try {
            block()
            dbQuery { markFetched(key) }
        } catch (e: Exception) {
            log.warn("Couldn't refresh $key; using cached market data", e)
        }
    }

    private suspend fun isStale(key: String): Boolean {
        val at = dbQuery { fetchedAt(key) } ?: return true
        return clock.now() - at > STALE_AFTER
    }

    private fun fetchedAt(key: String) =
        MarketFetches.selectAll().where { MarketFetches.key eq key }.singleOrNull()?.get(MarketFetches.fetchedAt)

    private fun markFetched(key: String) {
        MarketFetches.upsert {
            it[MarketFetches.key] = key
            it[fetchedAt] = clock.now()
        }
    }

    private fun latestPrice(symbol: String): LocalDate? =
        SecurityPrices.select(SecurityPrices.date.max()).where { SecurityPrices.symbol eq symbol }.single()[SecurityPrices.date.max()]

    private fun storePrices(symbol: String, closes: List<DailyClose>) {
        SecurityPrices.batchUpsert(closes) { close ->
            this[SecurityPrices.symbol] = symbol
            this[SecurityPrices.date] = close.date
            this[SecurityPrices.close] = close.close
        }
    }
}

private val MarketKey = AttributeKey<Market>("Market")

var Application.market: Market
    get() = attributes[MarketKey]
    set(value) = attributes.put(MarketKey, value)

/** The securities' details, for the given symbols. */
fun securities(symbols: Collection<String>): Map<String, SecurityInfo> =
    if (symbols.isEmpty()) {
        emptyMap()
    } else {
        Securities.selectAll().where { Securities.symbol inList symbols }.associate {
            it[Securities.symbol] to SecurityInfo(it[Securities.symbol], it[Securities.name], it[Securities.exchange], it[Securities.currency])
        }
    }
