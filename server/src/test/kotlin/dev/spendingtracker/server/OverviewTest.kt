package dev.spendingtracker.server

import dev.spendingtracker.server.bank.BankAccountInfo
import dev.spendingtracker.server.bank.BankBalance
import dev.spendingtracker.server.bank.LinkState
import dev.spendingtracker.shared.api.Account
import dev.spendingtracker.shared.api.AccountInput
import dev.spendingtracker.shared.api.AccountKind
import dev.spendingtracker.shared.api.AssetKind
import dev.spendingtracker.shared.api.BankConnectionStarted
import dev.spendingtracker.shared.api.CreateConnectionFeeds
import dev.spendingtracker.shared.api.FeedFromAccount
import dev.spendingtracker.shared.api.StartBankConnection
import dev.spendingtracker.shared.api.ValuationInput
import dev.spendingtracker.shared.api.Category
import dev.spendingtracker.shared.api.CategoryInput
import dev.spendingtracker.shared.api.CategoryKind
import dev.spendingtracker.shared.api.ContributionInput
import dev.spendingtracker.shared.api.ExpenseInput
import dev.spendingtracker.shared.api.IncomeInput
import dev.spendingtracker.shared.api.ExpenseLineInput
import dev.spendingtracker.shared.api.Member
import dev.spendingtracker.shared.api.MemberInput
import dev.spendingtracker.shared.api.Asset
import dev.spendingtracker.shared.api.AssetInput
import dev.spendingtracker.shared.api.MonthFlow
import dev.spendingtracker.shared.api.NamedTotal
import dev.spendingtracker.shared.api.Overview
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class OverviewTest {
    @Test
    fun summarisesTheMonthAgainstItsHistory() = apiTest {
        val client = newClient().also { it.signup() }
        val household = "/api/households/${client.createHousehold().id}"
        val groceries = client.post("$household/categories") { setBody(CategoryInput("Food shop")) }.body<Category>()
        val produce = client.post("$household/categories") { setBody(CategoryInput("Fruit", parentId = groceries.id)) }.body<Category>()
        val etf = client.post("$household/categories") { setBody(CategoryInput("Index fund", kind = CategoryKind.INVESTMENT)) }.body<Category>()
        val fund = client.post("$household/assets") { setBody(AssetInput("World ETF")) }.body<Asset>()
        for (month in 7..10) {
            client.post("$household/income") { setBody(IncomeInput(LocalDate(2026, month, 15), 50_000, "Acme")) }
            client.post("$household/expenses") { setBody(ExpenseInput(LocalDate(2026, month, 2), 999, "Netflix", groceries.id)) }
            client.post("$household/expenses") { setBody(ExpenseInput(LocalDate(2026, month, 3), 10_000, "Albert", produce.id)) }
            // The transfer to the broker and the contribution it bought are the same money: only the contribution counts.
            client.post("$household/expenses") { setBody(ExpenseInput(LocalDate(2026, month, 4), 5_000, "Broker", etf.id)) }
            client.post("$household/assets/${fund.id}/contributions") { setBody(ContributionInput(LocalDate(2026, month, 4), 5_000)) }
        }
        client.post("$household/expenses") { setBody(ExpenseInput(LocalDate(2026, 10, 20), 30_000, "Albert", produce.id)) }
        client.post("$household/assets/${fund.id}/contributions") { setBody(ContributionInput(LocalDate(2026, 10, 25), -1_000)) }
        // Borrowing on a loan and repaying it are neither invested nor the start of the ledger.
        val loan = client.post("$household/assets") { setBody(AssetInput("Mortgage", kind = dev.spendingtracker.shared.api.AssetKind.LOAN)) }.body<Asset>()
        client.post("$household/assets/${loan.id}/contributions") { setBody(ContributionInput(LocalDate(2020, 3, 1), -3_000_000)) }
        client.post("$household/assets/${loan.id}/contributions") { setBody(ContributionInput(LocalDate(2026, 10, 10), 12_000)) }
        // Nor is an asset's history entered from before the ledger: it counts toward the asset, not as empty months.
        val savings = client.post("$household/assets") { setBody(AssetInput("Building savings", kind = AssetKind.SCHEME)) }.body<Asset>()
        client.post("$household/assets/${savings.id}/contributions") { setBody(ContributionInput(LocalDate(2026, 2, 10), 1_550)) }

        val overview = client.get("$household/overview?month=2026-10&today=2026-10-31").body<Overview>()

        assertEquals(31, overview.daysElapsed)
        assertEquals(MonthFlow("2026-10", 50_000, 40_999, 4_000), overview.thisMonth)
        assertEquals(listOf("2026-07", "2026-08", "2026-09", "2026-10"), overview.history.map { it.month })
        val typical = overview.typicalMonth!!
        assertEquals(10_999L to 3, typical.spendingMinor to typical.monthCount)
        assertEquals(5_000, typical.investedMinor)
        // Subcategories roll up into their parent; investing stays out of spending.
        assertEquals(listOf(groceries.id), overview.topCategories.map { it.categoryId })
        assertEquals(listOf(groceries.id), overview.anomalies.map { it.categoryId })
        assertEquals(listOf("Albert", "Netflix"), overview.topMerchants.map { it.name })
        assertEquals(listOf(NamedTotal("Acme", 50_000)), overview.topSources)
        assertEquals(listOf("Netflix"), overview.subscriptions.map { it.merchant })
    }

    @Test
    fun filtersToJointOrOneMember() = apiTest {
        val client = newClient()
        val alice = client.signup().id
        val household = "/api/households/${client.createHousehold().id}"
        val bob = client.post("$household/members") { setBody(MemberInput("Bob")) }.body<Member>().userId
        val bobsFund = client.post("$household/assets") { setBody(AssetInput("Bob's ETF", ownerUserId = bob)) }.body<Asset>()
        val day = LocalDate(2026, 10, 5)
        client.post("$household/expenses") { setBody(ExpenseInput(day, 1_000, "Albert")) }
        client.post("$household/expenses") { setBody(ExpenseInput(day, 300, "Zara", paidByUserId = alice)) }
        // A split expense counts each line as whose it is.
        client.post("$household/expenses") {
            setBody(
                ExpenseInput(
                    day, 700, "Tesco",
                    lines = listOf(ExpenseLineInput(amountMinor = 500), ExpenseLineInput(amountMinor = 200, paidByUserId = bob)),
                )
            )
        }
        client.post("$household/income") { setBody(IncomeInput(day, 40_000, "Acme", recipientUserId = alice)) }
        client.post("$household/income") { setBody(IncomeInput(day, 30_000, "Initech", recipientUserId = bob)) }
        client.post("$household/assets/${bobsFund.id}/contributions") { setBody(ContributionInput(day, 2_000)) }

        suspend fun month(whose: String? = null) =
            client.get("$household/overview?month=2026-10&today=2026-10-31" + (whose?.let { "&whose=$it" } ?: "")).body<Overview>().thisMonth

        assertEquals(MonthFlow("2026-10", 70_000, 2_000, 2_000), month())
        assertEquals(MonthFlow("2026-10", 0, 1_500, 0), month("joint"))
        assertEquals(MonthFlow("2026-10", 40_000, 300, 0), month(alice))
        assertEquals(MonthFlow("2026-10", 30_000, 200, 2_000), month(bob))
        val bobs = client.get("$household/overview?month=2026-10&today=2026-10-31&whose=$bob").body<Overview>()
        assertEquals(listOf("Tesco"), bobs.topMerchants.map { it.name })

        client.get("$household/overview?whose=someone").assertError(HttpStatusCode.BadRequest, "invalid_whose")
        client.get("$household/overview?whose=${kotlin.uuid.Uuid.random()}").assertError(HttpStatusCode.BadRequest, "unknown_member")
    }

    @Test
    fun emptyHouseholdAndBadInput() = apiTest {
        val client = newClient().also { it.signup() }
        val household = "/api/households/${client.createHousehold().id}"
        val empty = client.get("$household/overview?month=2026-10&today=2026-10-05").body<Overview>()
        assertEquals(MonthFlow("2026-10", 0, 0, 0), empty.thisMonth)
        assertEquals(null, empty.typicalMonth)
        assertEquals("2026-09", empty.anomalyMonth)
        client.get("$household/overview?month=October").assertError(HttpStatusCode.BadRequest, "invalid_month")
    }

    @Test
    fun netWorthRunwayAndFreshness() {
        val bank = FakeBankData().apply {
            linkState = LinkState.LINKED
            accountIds = listOf("ext-current", "ext-savings", "ext-card")
            accounts["ext-current"] = BankAccountInfo("ext-current", null, null, "CZK", "Current", "Alice")
            accounts["ext-savings"] = BankAccountInfo("ext-savings", null, null, "CZK", "Savings", "Alice")
            accounts["ext-card"] = BankAccountInfo("ext-card", null, null, "CZK", "Card", "Alice")
            balanceByAccount["ext-current"] = BankBalance(40_000, "CZK", "closingBooked", LocalDate(2026, 10, 20))
            balanceByAccount["ext-savings"] = BankBalance(200_000, "CZK", "closingBooked", LocalDate(2026, 10, 20))
            balanceByAccount["ext-card"] = BankBalance(-5_000, "CZK", "closingBooked", LocalDate(2026, 10, 20))
        }
        apiTest(bank = bank) {
            val client = newClient().also { it.signup() }
            val household = "/api/households/${client.createHousehold(currency = "CZK").id}"
            val current = client.post("$household/accounts") { setBody(AccountInput("Current")) }.body<Account>()
            val savingsAccount = client.post("$household/accounts") { setBody(AccountInput("Savings", kind = AccountKind.SAVINGS)) }.body<Account>()
            val card = client.post("$household/accounts") { setBody(AccountInput("Card", kind = AccountKind.CREDIT_CARD)) }.body<Account>()
            // The savings account counts once, through the asset that follows it.
            client.post("$household/assets") { setBody(AssetInput("Savings", kind = AssetKind.SAVINGS, accountId = savingsAccount.id)) }
            val cash = client.post("$household/assets") { setBody(AssetInput("Envelope", kind = AssetKind.SAVINGS)) }.body<Asset>()
            client.post("$household/assets/${cash.id}/valuations") { setBody(ValuationInput(LocalDate(2026, 9, 1), 10_000)) }
            val car = client.post("$household/assets") { setBody(AssetInput("Car", kind = AssetKind.VEHICLE)) }.body<Asset>()
            client.post("$household/assets/${car.id}/valuations") { setBody(ValuationInput(LocalDate(2026, 9, 1), 300_000)) }
            val loan = client.post("$household/assets") { setBody(AssetInput("Car loan", kind = AssetKind.LOAN)) }.body<Asset>()
            client.post("$household/assets/${loan.id}/valuations") { setBody(ValuationInput(LocalDate(2026, 9, 1), 100_000)) }
            for (month in 7..9) {
                client.post("$household/expenses") { setBody(ExpenseInput(LocalDate(2026, month, 3), 25_000, "Albert")) }
            }

            val started = client.post("$household/bank-connections") { setBody(StartBankConnection("AIRBANK_CZ")) }.body<BankConnectionStarted>()
            client.post("$household/bank-connections/${started.connectionId}/finish")
            client.post("$household/bank-connections/${started.connectionId}/feeds") {
                setBody(
                    CreateConnectionFeeds(
                        listOf(
                            FeedFromAccount("ext-current", current.id),
                            FeedFromAccount("ext-savings", savingsAccount.id),
                            FeedFromAccount("ext-card", card.id),
                        ),
                    ),
                )
            }
            client.post("$household/bank-connections/${started.connectionId}/sync")

            val worth = client.get("$household/overview?month=2026-10&today=2026-10-31").body<Overview>().netWorth
            assertEquals(LocalDate(2026, 10, 31), worth.on)
            assertEquals(40_000L - 5_000, worth.accountsMinor)
            assertEquals(200_000L + 10_000 + 300_000, worth.assetsMinor)
            assertEquals(-100_000, worth.liabilitiesMinor)
            assertEquals(35_000L + 510_000 - 100_000, worth.netMinor)
            // Money at hand: the current and savings accounts and the envelope, not the car or the card.
            assertEquals(250_000, worth.liquidMinor)
            assertEquals(10.0, worth.runwayMonths!!, 0.001)
            assertEquals(3, worth.accountsRead)

            // Before any balance was read, only what was valued by hand counts.
            val september = client.get("$household/overview?month=2026-09&today=2026-10-31").body<Overview>().netWorth
            assertEquals(LocalDate(2026, 9, 30), september.on)
            assertEquals(0, september.accountsRead)
            assertEquals(10_000L + 300_000 - 100_000, september.netMinor)

            val freshness = client.get("$household/overview?month=2026-10&today=2026-10-31").body<Overview>().freshness
            assertEquals(3, freshness.feeds.size)
            assertEquals(true, freshness.feeds.all { it.live && it.lastSyncedAt != null && it.consentExpiresAt != null })
            assertEquals(0, freshness.pendingRows)
        }
    }
}
