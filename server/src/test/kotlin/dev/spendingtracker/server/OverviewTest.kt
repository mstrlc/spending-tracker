package dev.spendingtracker.server

import dev.spendingtracker.shared.api.Category
import dev.spendingtracker.shared.api.CategoryInput
import dev.spendingtracker.shared.api.CategoryKind
import dev.spendingtracker.shared.api.ContributionInput
import dev.spendingtracker.shared.api.ExpenseInput
import dev.spendingtracker.shared.api.IncomeInput
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
    fun emptyHouseholdAndBadInput() = apiTest {
        val client = newClient().also { it.signup() }
        val household = "/api/households/${client.createHousehold().id}"
        val empty = client.get("$household/overview?month=2026-10&today=2026-10-05").body<Overview>()
        assertEquals(MonthFlow("2026-10", 0, 0, 0), empty.thisMonth)
        assertEquals(null, empty.typicalMonth)
        assertEquals("2026-09", empty.anomalyMonth)
        client.get("$household/overview?month=October").assertError(HttpStatusCode.BadRequest, "invalid_month")
    }
}
