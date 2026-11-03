package dev.spendingtracker.server

import dev.spendingtracker.server.market.CzkRate
import dev.spendingtracker.server.market.DailyClose
import dev.spendingtracker.server.market.MarketData
import dev.spendingtracker.server.market.SecurityHistory
import dev.spendingtracker.server.market.SecurityInfo
import dev.spendingtracker.server.market.SecurityMatch
import kotlinx.datetime.LocalDate

/** Market data held in memory; counts the calls so tests can see what was fetched. */
class FakeMarketData(
    val securities: Map<String, SecurityHistory> = emptyMap(),
    val rates: List<CzkRate> = emptyList(),
) : MarketData {
    var historyCalls = 0
    var rateCalls = 0

    override suspend fun search(query: String) = securities.values
        .filter { it.info.name.contains(query, ignoreCase = true) || it.info.symbol.equals(query, ignoreCase = true) }
        .map { SecurityMatch(it.info.symbol, it.info.name, it.info.exchange, "ETF") }

    override suspend fun history(symbol: String, from: LocalDate?): SecurityHistory? {
        historyCalls++
        val history = securities[symbol] ?: return null
        return history.copy(closes = history.closes.filter { from == null || it.date >= from })
    }

    override suspend fun czkRates(year: Int): List<CzkRate> {
        rateCalls++
        return rates.filter { it.date.year == year }
    }
}

fun security(symbol: String, name: String, currency: String, vararg closes: Pair<LocalDate, String>) =
    symbol to SecurityHistory(SecurityInfo(symbol, name, "XETRA", currency), closes.map { DailyClose(it.first, it.second.toBigDecimal()) })
