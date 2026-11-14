package dev.spendingtracker.server

import dev.spendingtracker.server.investment.Prices
import dev.spendingtracker.server.market.CzkRate
import dev.spendingtracker.server.market.SecurityInfo
import dev.spendingtracker.shared.api.ContributionInput
import dev.spendingtracker.shared.api.FeeInput
import dev.spendingtracker.shared.api.HistoryPoint
import dev.spendingtracker.shared.api.Investment
import dev.spendingtracker.shared.api.InvestmentHistory
import dev.spendingtracker.shared.api.InvestmentInput
import dev.spendingtracker.shared.api.SecurityMatch
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDate
import java.math.BigDecimal
import java.util.TreeMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarketValuationTest {
    private fun market() = FakeMarketData(
        securities = mapOf(
            security(
                "VWCE.DE", "Vanguard FTSE All-World", "EUR",
                LocalDate(2026, 1, 15) to "100", LocalDate(2026, 2, 27) to "110", LocalDate(2026, 3, 31) to "120",
            ),
        ),
        rates = listOf(
            CzkRate("EUR", LocalDate(2026, 1, 2), BigDecimal("25")),
            CzkRate("EUR", LocalDate(2026, 3, 31), BigDecimal("24")),
        ),
    )

    @Test
    fun holdingFollowingASecurityIsValuedAtTheLatestCloseInTheHouseholdCurrency() {
        val market = market()
        apiTest(market) {
            val client = newClient().also { it.signup() }
            val household = "/api/households/${client.createHousehold(currency = "CZK").id}"

            assertEquals(
                listOf("VWCE.DE"),
                client.get("/api/securities/search?q=all-world").body<List<SecurityMatch>>().map { it.symbol },
            )
            client.post("$household/investments") { setBody(InvestmentInput("Nope", symbol = "NOPE.DE")) }
                .assertError(HttpStatusCode.BadRequest, "invalid_symbol")

            val fund = client.post("$household/investments") { setBody(InvestmentInput("World", symbol = " vwce.de ")) }.body<Investment>()
            assertEquals("VWCE.DE", fund.security?.symbol)
            assertEquals("EUR", fund.security?.currency)
            assertEquals(120.0, fund.security?.close)

            val contributions = "$household/investments/${fund.id}/contributions"
            // 1 share at €100 when the euro was 25 CZK.
            client.post(contributions) { setBody(ContributionInput(LocalDate(2026, 1, 15), 250_000, quantity = 1.0)) }
            client.post(contributions) { setBody(ContributionInput(LocalDate(2026, 2, 1), 100, quantity = -1.0)) }
                .assertError(HttpStatusCode.BadRequest, "invalid_quantity")

            val held = client.get("$household/investments").body<List<Investment>>().single()
            assertTrue(held.marketValued)
            assertEquals(1.0, held.shares)
            // 1 × €120 × 24 CZK.
            assertEquals(288_000, held.valueMinor)
            assertEquals(38_000, held.gainMinor)
            assertEquals(LocalDate(2026, 3, 31), held.valuedOn)

            // A commission was paid on top of the share: money put in that the worth doesn't hold.
            client.post("$household/investments/${fund.id}/fees") { setBody(FeeInput(LocalDate(2026, 1, 15), 3_100)) }
            val charged = client.get("$household/investments").body<List<Investment>>().single()
            assertEquals(253_100L to 288_000L, charged.contributedMinor to charged.valueMinor)
            assertEquals(34_900L to 3_100L, charged.gainMinor to charged.feesMinor)

            // Money recorded without shares counts at cost on top.
            client.post(contributions) { setBody(ContributionInput(LocalDate(2026, 2, 10), 10_000)) }
            val history = client.get("$household/investments/history").body<InvestmentHistory>().points
            assertEquals(
                listOf(
                    HistoryPoint(LocalDate(2026, 1, 31), 253_100, 250_000),
                    HistoryPoint(LocalDate(2026, 2, 28), 263_100, 285_000),
                    HistoryPoint(LocalDate(2026, 3, 31), 263_100, 298_000),
                ),
                history.take(3),
            )
            assertEquals(298_000, history.last().valueMinor)

            // Prices fetched once on registration (plus the lookup of NOPE.DE), the fixings once per year,
            // and nothing again while fresh.
            assertEquals(2, market.historyCalls)
            assertEquals(1, market.rateCalls)
        }
    }

    @Test
    fun crossesCurrenciesThroughCzk() {
        val prices = Prices(
            householdCurrency = "EUR",
            securities = mapOf("SPY" to SecurityInfo("SPY", "S&P 500", "NYSE", "USD")),
            closes = mapOf("SPY" to TreeMap(mapOf(LocalDate(2026, 3, 2) to BigDecimal("500")))),
            czk = mapOf(
                "USD" to TreeMap(mapOf(LocalDate(2026, 3, 2) to BigDecimal("20"))),
                "EUR" to TreeMap(mapOf(LocalDate(2026, 3, 2) to BigDecimal("25"))),
            ),
        )
        // 2 × $500 at 0.8 EUR to the dollar, on the Saturday after.
        assertEquals(80_000, prices.value("SPY", BigDecimal(2), LocalDate(2026, 3, 7))?.valueMinor)
        assertEquals(null, prices.value("SPY", BigDecimal(2), LocalDate(2026, 3, 1)))
    }
}
