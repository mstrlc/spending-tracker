package dev.spendingtracker.server.market

import kotlinx.datetime.LocalDate
import java.math.BigDecimal

/** A security as the provider describes it. */
data class SecurityInfo(val symbol: String, val name: String, val exchange: String, val currency: String)

data class SecurityMatch(val symbol: String, val name: String, val exchange: String, val type: String)

data class DailyClose(val date: LocalDate, val close: BigDecimal)

data class SecurityHistory(val info: SecurityInfo, val closes: List<DailyClose>)

/** CZK for one unit of [currency] on [date]. */
data class CzkRate(val currency: String, val date: LocalDate, val czk: BigDecimal)

/** Where public market data comes from. Implementations do network calls; tests use a fake. */
interface MarketData {
    suspend fun search(query: String): List<SecurityMatch>

    /** Daily closes from [from] (everything when null) to today, or null when the provider doesn't know [symbol]. */
    suspend fun history(symbol: String, from: LocalDate?): SecurityHistory?

    /** The ČNB fixing of every currency it fixes, for each business day of [year] so far. */
    suspend fun czkRates(year: Int): List<CzkRate>
}
