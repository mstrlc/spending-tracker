package dev.spendingtracker.server

import dev.spendingtracker.server.asset.CashFlow
import dev.spendingtracker.server.asset.Dated
import dev.spendingtracker.server.asset.standing
import dev.spendingtracker.server.asset.xirr
import dev.spendingtracker.server.bank.BankAccountInfo
import dev.spendingtracker.server.bank.BankBalance
import dev.spendingtracker.server.bank.LinkState
import dev.spendingtracker.server.market.CzkRate
import dev.spendingtracker.shared.api.Account
import dev.spendingtracker.shared.api.AccountInput
import dev.spendingtracker.shared.api.AccountKind
import dev.spendingtracker.shared.api.ActivityEntity
import dev.spendingtracker.shared.api.ActivityPage
import dev.spendingtracker.shared.api.Asset
import dev.spendingtracker.shared.api.AssetHistory
import dev.spendingtracker.shared.api.AssetInput
import dev.spendingtracker.shared.api.AssetKind
import dev.spendingtracker.shared.api.AssetSummary
import dev.spendingtracker.shared.api.BankConnectionStarted
import dev.spendingtracker.shared.api.Contribution
import dev.spendingtracker.shared.api.ContributionInput
import dev.spendingtracker.shared.api.CreateConnectionFeeds
import dev.spendingtracker.shared.api.FeedFromAccount
import dev.spendingtracker.shared.api.Fee
import dev.spendingtracker.shared.api.FeeInput
import dev.spendingtracker.shared.api.FxRateQuote
import dev.spendingtracker.shared.api.StartBankConnection
import dev.spendingtracker.shared.api.Valuation
import dev.spendingtracker.shared.api.ValuationInput
import dev.spendingtracker.shared.api.ValueSource
import java.math.BigDecimal
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.todayIn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AssetTest {
    @Test
    fun valueIsTheLatestValuationPlusLaterContributions() {
        val contributions = listOf(Dated(LocalDate(2026, 1, 15), 10_000), Dated(LocalDate(2026, 6, 30), 5_000), Dated(LocalDate(2026, 9, 1), 2_000))
        val valuations = listOf(Dated(LocalDate(2026, 3, 31), 9_000), Dated(LocalDate(2026, 6, 30), 16_000))

        val standing = standing(contributions, valuations)
        assertEquals(17_000, standing.contributedMinor)
        // The June valuation already includes that day's contribution; September's is added at cost.
        assertEquals(18_000, standing.valueMinor)
        assertEquals(1_000, standing.gainMinor)
        assertEquals(LocalDate(2026, 6, 30), standing.valuedOn)
        assertEquals(LocalDate(2026, 1, 15), standing.firstContributedOn)

        val unvalued = standing(contributions, emptyList())
        assertEquals(17_000L to 0L, unvalued.valueMinor to unvalued.gainMinor)
        assertEquals(null, unvalued.valuedOn)
    }

    @Test
    fun assetsBeyondMarketHoldingsAreValuedByHand() = apiTest {
        val client = newClient()
        val me = client.signup()
        val household = "/api/households/${client.createHousehold().id}"

        val car = client.post("$household/assets") { setBody(AssetInput("Octavia", kind = AssetKind.VEHICLE)) }.body<Asset>()
        client.post("$household/assets/${car.id}/valuations") { setBody(ValuationInput(LocalDate(2026, 6, 30), 250_000)) }
        val savings = client.post("$household/assets") { setBody(AssetInput("Building savings", kind = AssetKind.SCHEME, ownerUserId = me.id)) }
            .body<Asset>()

        val listed = client.get("$household/assets").body<List<Asset>>().associateBy { it.id }
        assertEquals(Triple(AssetKind.VEHICLE, false, 250_000L), listed.getValue(car.id).let { Triple(it.kind, it.marketValued, it.valueMinor) })
        assertEquals(AssetKind.SCHEME to me.id, listed.getValue(savings.id).let { it.kind to it.ownerUserId })
    }

    @Test
    fun holdingsShowContributionsValuationsAndGain() = apiTest {
        val client = newClient()
        val me = client.signup()
        val household = "/api/households/${client.createHousehold().id}"
        val bank = client.post("$household/accounts") { setBody(AccountInput("Bank")) }.body<Account>()

        val fund = client.post("$household/assets") { setBody(AssetInput(" World ETF ", ownerUserId = me.id)) }.body<Asset>()
        assertEquals("World ETF", fund.name)
        assertEquals(AssetKind.FUND, fund.kind)
        assertEquals(0L to null, fund.contributedMinor to fund.valuedOn)

        val contributions = "$household/assets/${fund.id}/contributions"
        val first = client.post(contributions) { setBody(ContributionInput(LocalDate(2026, 1, 15), 100_000, accountId = bank.id)) }.body<Contribution>()
        client.post(contributions) { setBody(ContributionInput(LocalDate(2026, 8, 1), 50_000)) }
        client.post(contributions) { setBody(ContributionInput(LocalDate(2026, 9, 1), -20_000, notes = "rebalance")) }
        val valuations = "$household/assets/${fund.id}/valuations"
        client.post(valuations) { setBody(ValuationInput(LocalDate(2026, 6, 30), 112_000)) }

        val listed = client.get("$household/assets").body<List<Asset>>().single()
        assertEquals(130_000, listed.contributedMinor)
        assertEquals(142_000, listed.valueMinor)
        assertEquals(12_000, listed.gainMinor)
        assertEquals(LocalDate(2026, 6, 30), listed.valuedOn)
        assertEquals(LocalDate(2026, 1, 15), listed.firstContributedOn)
        assertEquals(3, client.get(contributions).body<List<Contribution>>().size)

        client.post(valuations) { setBody(ValuationInput(LocalDate(2026, 6, 30), 1)) }
            .assertError(HttpStatusCode.Conflict, "valuation_exists")
        client.post(valuations) { setBody(ValuationInput(LocalDate(2026, 7, 1), -1)) }
            .assertError(HttpStatusCode.BadRequest, "invalid_amount")
        client.post(contributions) { setBody(ContributionInput(LocalDate(2026, 7, 1), 0)) }
            .assertError(HttpStatusCode.BadRequest, "invalid_amount")
        client.delete("$household/accounts/${bank.id}?version=1").assertError(HttpStatusCode.Conflict, "account_in_use")

        val edited = client.put("$contributions/${first.id}") {
            setBody(ContributionInput(LocalDate(2026, 1, 15), 90_000, version = 1))
        }.body<Contribution>()
        assertEquals(2, edited.version)
        client.put("$contributions/${first.id}") { setBody(ContributionInput(LocalDate(2026, 1, 15), 1, version = 1)) }
            .assertError(HttpStatusCode.Conflict, "version_conflict")

        val latest = client.post(valuations) { setBody(ValuationInput(LocalDate(2026, 10, 31), 150_000)) }.body<Valuation>()
        val now = client.get("$household/assets").body<List<Asset>>().single()
        assertEquals(30_000, now.gainMinor)
        assertEquals(HttpStatusCode.NoContent, client.delete("$valuations/${latest.id}?version=1").status)

        assertEquals(HttpStatusCode.NoContent, client.delete("$household/assets/${fund.id}?version=1").status)
        assertTrue(client.get("$household/assets").body<List<Asset>>().isEmpty())
        client.get(contributions).assertError(HttpStatusCode.NotFound, "not_found")
    }

    @Test
    fun householdsCannotReachEachOthersHoldings() = apiTest {
        val alice = newClient().also { it.signup("alice@example.com") }
        val bob = newClient()
        val bobMe = bob.signup("bob@example.com", "Bob")
        val aliceHome = "/api/households/${alice.createHousehold().id}"
        val bobHome = "/api/households/${bob.createHousehold().id}"
        val bobsFund = bob.post("$bobHome/assets") { setBody(AssetInput("Bob's fund")) }.body<Asset>()

        alice.post("$aliceHome/assets") { setBody(AssetInput("Fund", ownerUserId = bobMe.id)) }
            .assertError(HttpStatusCode.BadRequest, "invalid_member")
        alice.post("$aliceHome/assets") { setBody(AssetInput(" ")) }
            .assertError(HttpStatusCode.BadRequest, "required")
        alice.post("$aliceHome/assets/${bobsFund.id}/contributions") { setBody(ContributionInput(LocalDate(2026, 10, 1), 100)) }
            .assertError(HttpStatusCode.NotFound, "not_found")
        alice.get("$aliceHome/assets/${bobsFund.id}/valuations").assertError(HttpStatusCode.NotFound, "not_found")
    }

    @Test
    fun contributionsAreLoggedUnderTheirHolding() = apiTest {
        val client = newClient().also { it.signup() }
        val household = "/api/households/${client.createHousehold().id}"
        val fund = client.post("$household/assets") { setBody(AssetInput("World ETF")) }.body<Asset>()
        client.post("$household/assets/${fund.id}/contributions") { setBody(ContributionInput(LocalDate(2026, 10, 1), 100)) }

        val entry = client.get("$household/activity?entity=asset_contribution").body<ActivityPage>().entries.single()
        assertEquals(ActivityEntity.ASSET_CONTRIBUTION, entry.entity)
        assertEquals("World ETF", entry.label)
        assertEquals(listOf("date", "amountMinor", "quantity", "accountId", "notes", "originalCurrency", "originalAmountMinor"), entry.changes.map { it.field })
    }

    @Test
    fun feesAreTotalledWithoutTouchingWorthOrMoneyPutIn() = apiTest {
        val client = newClient().also { it.signup() }
        val household = "/api/households/${client.createHousehold().id}"
        val fund = client.post("$household/assets") { setBody(AssetInput("Portu")) }.body<Asset>()
        client.post("$household/assets/${fund.id}/contributions") { setBody(ContributionInput(LocalDate(2026, 1, 5), 100_000)) }
        val fees = "$household/assets/${fund.id}/fees"
        val fee = client.post(fees) { setBody(FeeInput(LocalDate(2026, 2, 4), 9_000, "Správcovský poplatek")) }.body<Fee>()
        // A discount comes off.
        client.post(fees) { setBody(FeeInput(LocalDate(2026, 2, 4), -4_000, "Sleva na poplatku")) }
        client.post(fees) { setBody(FeeInput(LocalDate(2026, 2, 4), 0)) }.assertError(HttpStatusCode.BadRequest, "invalid_amount")

        val held = client.get("$household/assets").body<List<Asset>>().single()
        assertEquals(5_000, held.feesMinor)
        assertEquals(100_000L to 100_000L, held.contributedMinor to held.valueMinor)
        assertEquals(2, client.get(fees).body<List<Fee>>().size)

        client.put("$fees/${fee.id}") { setBody(FeeInput(LocalDate(2026, 2, 4), 8_000, version = 1)) }
        assertEquals(4_000, client.get("$household/assets").body<List<Asset>>().single().feesMinor)
        assertEquals(HttpStatusCode.NoContent, client.delete("$fees/${fee.id}?version=2").status)
    }

    @Test
    fun liabilityStandingUsesPositiveValuationsAndNegativeValueMinor() {
        val borrowed = listOf(Dated(LocalDate(2026, 1, 1), -100_000), Dated(LocalDate(2026, 2, 1), 10_000))
        val unvalued = standing(borrowed, emptyList(), liability = true)
        assertEquals(-90_000, unvalued.valueMinor)
        assertEquals(0L, unvalued.gainMinor)

        val valued = standing(
            borrowed + Dated(LocalDate(2026, 3, 1), 10_000),
            listOf(Dated(LocalDate(2026, 2, 15), 92_000)),
            liability = true,
        )
        assertEquals(-82_000, valued.valueMinor)
        assertEquals(0L, valued.gainMinor)
    }

    @Test
    fun liabilitiesAreReportedNegativeAndSummarizedAsNetWorth() = apiTest {
        val client = newClient().also { it.signup() }
        val household = "/api/households/${client.createHousehold().id}"

        val savings = client.post("$household/assets") { setBody(AssetInput("Savings", kind = AssetKind.SAVINGS)) }.body<Asset>()
        client.post("$household/assets/${savings.id}/valuations") { setBody(ValuationInput(LocalDate(2026, 6, 30), 500_000)) }

        client.post("$household/assets") { setBody(AssetInput("Bad loan", kind = AssetKind.LOAN, symbol = "VWCE.DE")) }
            .assertError(HttpStatusCode.BadRequest, "symbol_not_market")

        val loan = client.post("$household/assets") { setBody(AssetInput("Car loan", kind = AssetKind.LOAN)) }.body<Asset>()
        client.post("$household/assets/${loan.id}/contributions") { setBody(ContributionInput(LocalDate(2026, 1, 1), -200_000)) }
        client.post("$household/assets/${loan.id}/contributions") { setBody(ContributionInput(LocalDate(2026, 2, 1), 20_000)) }
        client.post("$household/assets/${loan.id}/valuations") { setBody(ValuationInput(LocalDate(2026, 6, 30), 185_000)) }
        client.post("$household/assets/${loan.id}/contributions") { setBody(ContributionInput(LocalDate(2026, 7, 1), 15_000)) }

        val listed = client.get("$household/assets").body<List<Asset>>().associateBy { it.id }
        val gotLoan = listed.getValue(loan.id)
        assertEquals(-170_000, gotLoan.valueMinor)
        assertEquals(0L, gotLoan.gainMinor)
        assertEquals(LocalDate(2026, 6, 30), gotLoan.valuedOn)

        val summary = client.get("$household/assets/summary").body<AssetSummary>()
        assertEquals(500_000, summary.assetsMinor)
        assertEquals(-170_000, summary.liabilitiesMinor)
        assertEquals(330_000, summary.netMinor)
        // Nothing was put into the savings (only valued), so there's no return to speak of.
        assertEquals(null, summary.annualReturn)

        // With a fund bought for 100,000 now worth 110,000 a year on, the savings' worth isn't counted as return.
        val fund = client.post("$household/assets") { setBody(AssetInput("Fund", kind = AssetKind.FUND)) }.body<Asset>()
        val today = kotlin.time.Clock.System.todayIn(TimeZone.currentSystemDefault())
        val yearAgo = today.minus(1, DateTimeUnit.YEAR)
        client.post("$household/assets/${fund.id}/contributions") { setBody(ContributionInput(yearAgo, 100_000)) }
        client.post("$household/assets/${fund.id}/valuations") { setBody(ValuationInput(today, 110_000)) }
        val withFund = client.get("$household/assets/summary").body<AssetSummary>()
        assertEquals(0.10, withFund.annualReturn!!, 0.005)
    }

    @Test
    fun bankTrackedAssetsAndLiabilitiesUseConvertedFeedBalances() {
        val bank = FakeBankData().apply {
            linkState = LinkState.LINKED
            accountIds = listOf("ext-czk", "ext-eur", "ext-card")
            accounts["ext-czk"] = BankAccountInfo("ext-czk", null, null, "CZK", "Savings CZK", "Alice")
            accounts["ext-eur"] = BankAccountInfo("ext-eur", null, null, "EUR", "Savings EUR", "Alice")
            accounts["ext-card"] = BankAccountInfo("ext-card", null, null, "CZK", "Credit Card", "Alice")
            balanceByAccount["ext-czk"] = BankBalance(100_000, "CZK", "closingBooked", LocalDate(2026, 3, 15))
            // €100.00 at 25.00 CZK/EUR = 2,500.00 CZK (250,000 minor)
            balanceByAccount["ext-eur"] = BankBalance(10_000, "EUR", "closingBooked", LocalDate(2026, 3, 20))
            // Credit card debt is negative at the bank: -12,500 minor (-125.00 CZK)
            balanceByAccount["ext-card"] = BankBalance(-12_500, "CZK", "closingBooked", LocalDate(2026, 3, 20))
        }
        val market = FakeMarketData(
            rates = listOf(CzkRate("EUR", LocalDate(2026, 3, 20), BigDecimal("25"))),
        )
        apiTest(market = market, bank = bank) {
            val client = newClient().also { it.signup() }
            val hhId = client.createHousehold(currency = "CZK").id
            val household = "/api/households/$hhId"

            val savingsAccount = client.post("$household/accounts") { setBody(AccountInput("Revolut Savings")) }.body<Account>()
            val cardAccount = client.post("$household/accounts") { setBody(AccountInput("Credit Card", kind = AccountKind.CREDIT_CARD)) }.body<Account>()

            // Market kinds can't track an account.
            client.post("$household/assets") { setBody(AssetInput("Bad fund", kind = AssetKind.FUND, accountId = savingsAccount.id)) }
                .assertError(HttpStatusCode.BadRequest, "account_not_trackable")

            val savings = client.post("$household/assets") {
                setBody(AssetInput("Revolut Savings", kind = AssetKind.SAVINGS, accountId = savingsAccount.id))
            }.body<Asset>()
            assertEquals(ValueSource.COST, savings.valueSource)

            // Only one asset may track a given account.
            client.post("$household/assets") {
                setBody(AssetInput("Duplicate", kind = AssetKind.SAVINGS, accountId = savingsAccount.id))
            }.assertError(HttpStatusCode.Conflict, "account_already_tracked")

            // Hand valuation acts as fallback before any bank balance reading exists.
            client.post("$household/assets/${savings.id}/valuations") { setBody(ValuationInput(LocalDate(2026, 2, 28), 80_000)) }
            assertEquals(ValueSource.VALUATION, client.get("$household/assets").body<List<Asset>>().single().valueSource)

            val cardDebt = client.post("$household/assets") {
                setBody(AssetInput("Card Debt", kind = AssetKind.CARD_DEBT, accountId = cardAccount.id))
            }.body<Asset>()

            // Connect and sync feeds.
            val started = client.post("$household/bank-connections") { setBody(StartBankConnection("AIRBANK_CZ")) }.body<BankConnectionStarted>()
            client.post("$household/bank-connections/${started.connectionId}/finish")
            client.post("$household/bank-connections/${started.connectionId}/feeds") {
                setBody(
                    CreateConnectionFeeds(
                        listOf(
                            FeedFromAccount("ext-czk", savingsAccount.id),
                            FeedFromAccount("ext-eur", savingsAccount.id),
                            FeedFromAccount("ext-card", cardAccount.id),
                        ),
                    ),
                )
            }
            client.post("$household/bank-connections/${started.connectionId}/sync")

            val listed = client.get("$household/assets").body<List<Asset>>().associateBy { it.id }
            val gotSavings = listed.getValue(savings.id)
            assertEquals(ValueSource.BANK, gotSavings.valueSource)
            assertEquals(savingsAccount.id, gotSavings.accountId)
            assertEquals(350_000, gotSavings.valueMinor)
            assertEquals(LocalDate(2026, 3, 20), gotSavings.valuedOn)

            val gotCard = listed.getValue(cardDebt.id)
            assertEquals(ValueSource.BANK, gotCard.valueSource)
            assertEquals(-12_500, gotCard.valueMinor)
            assertEquals(0L, gotCard.gainMinor)

            // History: Feb 28 falls back to hand valuation (80,000); Mar 31+ uses bank balance (350,000).
            val savingsHistory = client.get("$household/assets/history?assetId=${savings.id}").body<AssetHistory>().points
            assertEquals(80_000, savingsHistory.first { it.date == LocalDate(2026, 2, 28) }.valueMinor)
            assertEquals(350_000, savingsHistory.first { it.date == LocalDate(2026, 3, 31) }.valueMinor)
        }
    }

    @Test
    fun xirrComputesAnnualisedMoneyWeightedReturn() {
        // Single contribution doubling in 1 year (365 days) -> 100%.
        val doubled = xirr(listOf(CashFlow(LocalDate(2025, 1, 1), -100_000.0), CashFlow(LocalDate(2026, 1, 1), 200_000.0)))
        assertEquals(1.0, doubled!!, 1e-4)

        // Mixed in/out flows: -100k on Jan 1, +50k withdrawn Jul 2 (182 days), +65k ending Jan 1 -> positive return.
        val mixed = xirr(
            listOf(
                CashFlow(LocalDate(2025, 1, 1), -100_000.0),
                CashFlow(LocalDate(2025, 7, 2), 50_000.0),
                CashFlow(LocalDate(2026, 1, 1), 65_000.0),
            ),
        )
        assertTrue(mixed != null && mixed in 0.15..0.30, "Expected ~20% return, got $mixed")

        // Under 30 days -> null.
        assertEquals(null, xirr(listOf(CashFlow(LocalDate(2026, 1, 1), -100_000.0), CashFlow(LocalDate(2026, 1, 20), 110_000.0))))

        // No sign change -> null.
        assertEquals(null, xirr(listOf(CashFlow(LocalDate(2025, 1, 1), -100_000.0), CashFlow(LocalDate(2026, 1, 1), -50_000.0))))
    }

    @Test
    fun contributionsCanBeEnteredInForeignCurrency() {
        val market = FakeMarketData(
            securities = mapOf(security("VWCE.DE", "Vanguard FTSE All-World", "EUR", LocalDate(2026, 3, 20) to "100")),
            rates = listOf(CzkRate("EUR", LocalDate(2026, 3, 20), BigDecimal("25"))),
        )
        apiTest(market = market) {
            val client = newClient().also { it.signup() }
            val household = "/api/households/${client.createHousehold(currency = "CZK").id}"
            val fund = client.post("$household/assets") { setBody(AssetInput("World", symbol = "VWCE.DE")) }.body<Asset>()

            val fx = client.get("/api/securities/fx?from=EUR&to=CZK&date=2026-03-20").body<FxRateQuote>()
            assertEquals(25.0, fx.rate)

            // Omit amountMinor: server converts €200.00 (20_000 minor) at 25.0 CZK/EUR = 5,000.00 CZK (500_000 minor).
            val autoConverted = client.post("$household/assets/${fund.id}/contributions") {
                setBody(
                    ContributionInput(
                        date = LocalDate(2026, 3, 20),
                        quantity = 2.0,
                        originalAmountMinor = 20_000,
                        originalCurrency = "EUR",
                    ),
                )
            }.body<Contribution>()
            assertEquals(500_000, autoConverted.amountMinor)
            assertEquals(20_000, autoConverted.originalAmountMinor)
            assertEquals("EUR", autoConverted.originalCurrency)

            // Send both: server keeps what the client sent.
            val explicit = client.post("$household/assets/${fund.id}/contributions") {
                setBody(
                    ContributionInput(
                        date = LocalDate(2026, 3, 20),
                        amountMinor = 505_000,
                        quantity = 2.0,
                        originalAmountMinor = 20_000,
                        originalCurrency = "EUR",
                    ),
                )
            }.body<Contribution>()
            assertEquals(505_000, explicit.amountMinor)
            assertEquals(20_000, explicit.originalAmountMinor)
            assertEquals("EUR", explicit.originalCurrency)
        }
    }

    @Test
    fun januaryFirstContributionFallsBackToPreviousYearsLastRate() {
        val market = FakeMarketData(
            securities = mapOf(security("VWCE.DE", "Vanguard FTSE All-World", "EUR", LocalDate(2026, 1, 2) to "100")),
            rates = listOf(
                CzkRate("EUR", LocalDate(2025, 12, 31), BigDecimal("25")),
                CzkRate("EUR", LocalDate(2026, 1, 2), BigDecimal("26")),
            ),
        )
        apiTest(market = market) {
            val client = newClient().also { it.signup() }
            val household = "/api/households/${client.createHousehold(currency = "CZK").id}"
            val fund = client.post("$household/assets") { setBody(AssetInput("World", symbol = "VWCE.DE")) }.body<Asset>()

            val fx = client.get("/api/securities/fx?from=EUR&to=CZK&date=2026-01-01").body<FxRateQuote>()
            assertEquals(25.0, fx.rate)

            val contribution = client.post("$household/assets/${fund.id}/contributions") {
                setBody(
                    ContributionInput(
                        date = LocalDate(2026, 1, 1),
                        quantity = 2.0,
                        originalAmountMinor = 20_000,
                        originalCurrency = "EUR",
                    ),
                )
            }.body<Contribution>()
            assertEquals(500_000, contribution.amountMinor)
            assertEquals(20_000, contribution.originalAmountMinor)
            assertEquals("EUR", contribution.originalCurrency)
        }
    }
}
