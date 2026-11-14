package dev.spendingtracker.server

import dev.spendingtracker.shared.api.Account
import dev.spendingtracker.shared.api.AccountInput
import dev.spendingtracker.shared.api.Asset
import dev.spendingtracker.shared.api.AssetInput
import dev.spendingtracker.shared.api.AssetKind
import dev.spendingtracker.shared.api.ContributionInput
import dev.spendingtracker.shared.api.Category
import dev.spendingtracker.shared.api.CategoryInput
import dev.spendingtracker.shared.api.CategoryKind
import dev.spendingtracker.shared.api.Expense
import dev.spendingtracker.shared.api.ExpenseInput
import dev.spendingtracker.shared.api.ExpenseLineInput
import dev.spendingtracker.shared.api.Member
import dev.spendingtracker.shared.api.MemberInput
import dev.spendingtracker.shared.api.ReportMonth
import dev.spendingtracker.shared.api.SpendingReport
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class SpendingReportTest {
    @Test
    fun totalsACategoryOverAPeriodAgainstTheYearBefore() = apiTest {
        val client = newClient().also { it.signup() }
        val household = "/api/households/${client.createHousehold().id}"
        val care = client.post("$household/categories") { setBody(CategoryInput("Personal care")) }.body<Category>()
        val haircut = client.post("$household/categories") { setBody(CategoryInput("Haircut", parentId = care.id)) }.body<Category>()
        val food = client.post("$household/categories") { setBody(CategoryInput("Food")) }.body<Category>()
        val etf = client.post("$household/categories") { setBody(CategoryInput("ETF", kind = CategoryKind.INVESTMENT)) }.body<Category>()

        suspend fun spend(date: LocalDate, amount: Long, merchant: String, categoryId: String?) =
            client.post("$household/expenses") { setBody(ExpenseInput(date, amount, merchant, categoryId)) }.body<Expense>()

        spend(LocalDate(2024, 12, 1), 900, "Albert", food.id)
        spend(LocalDate(2025, 3, 10), 400, "Barber", haircut.id)
        spend(LocalDate(2026, 1, 12), 500, "Barber", haircut.id)
        val march = spend(LocalDate(2026, 3, 9), 500, "Barber", haircut.id)
        spend(LocalDate(2026, 3, 20), 200, "DM", care.id)
        spend(LocalDate(2026, 3, 21), 900, "Albert", food.id)
        spend(LocalDate(2026, 3, 22), 5_000, "Broker", etf.id)
        // A refund counts against its original, in the original's month, whenever it came.
        client.post("$household/expenses") {
            setBody(ExpenseInput(LocalDate(2026, 4, 2), -100, "Barber", haircut.id, refundOfId = march.id))
        }
        // One line of a split expense matches, the other doesn't.
        client.post("$household/expenses") {
            setBody(
                ExpenseInput(
                    LocalDate(2026, 2, 5), 1_000, "Rossmann",
                    lines = listOf(ExpenseLineInput(haircut.id, 300), ExpenseLineInput(food.id, 700)),
                )
            )
        }

        suspend fun report(query: String) = client.get("$household/spending-report?today=2026-04-15&$query").body<SpendingReport>()

        val haircuts = report("from=2026-01-01&to=2026-12-31&categoryId=${haircut.id}")
        assertEquals(LocalDate(2026, 1, 1) to LocalDate(2026, 4, 15), haircuts.from to haircuts.to)
        assertEquals(500 + 400 + 300L, haircuts.totalMinor)
        assertEquals(3, haircuts.count)
        assertEquals(
            listOf(ReportMonth("2026-01", 500, 1, 0), ReportMonth("2026-02", 300, 1, 0), ReportMonth("2026-03", 400, 1, 0), ReportMonth("2026-04", 0, 0, 0)),
            haircuts.months,
        )
        assertEquals(300, haircuts.averageMonthMinor)
        assertEquals(400, haircuts.previous!!.totalMinor)
        assertEquals(LocalDate(2025, 1, 1), haircuts.previous!!.from)
        assertEquals(listOf("Barber", "Rossmann"), haircuts.merchants.map { it.name })
        assertEquals(300, haircuts.expenses.single { it.merchant == "Rossmann" }.amountMinor)

        // A top-level category takes in its subcategories and breaks down into them.
        val care2026 = report("from=2026-01-01&categoryId=${care.id}")
        assertEquals(1_400, care2026.totalMinor)
        assertEquals(listOf(haircut.id to 1_200L, care.id to 200L), care2026.categories.map { it.categoryId to it.amountMinor })

        // Leaving a subcategory out of its parent, and a whole top-level category out of everything.
        assertEquals(200, report("from=2026-01-01&categoryId=${care.id}&excludeCategoryId=${haircut.id}").totalMinor)
        assertEquals(900 + 700L, report("from=2026-01-01&excludeCategoryId=${care.id}").totalMinor)
        client.get("$household/spending-report?excludeCategoryId=${Uuid.random()}").assertError(HttpStatusCode.BadRequest, "unknown_category")

        // Without a filter: every top-level category except investing.
        val all = report("from=2026-01-01")
        assertEquals(1_200 + 200 + 900 + 700L, all.totalMinor)
        assertEquals(setOf(care.id, food.id), all.categories.map { it.categoryId }.toSet())
        // Unless investing is what's asked about.
        assertEquals(5_000, report("categoryId=${etf.id}").totalMinor)

        // A period over a year has nothing to compare with; nor does one before the ledger started.
        assertNull(report("from=2025-01-01&to=2026-04-15").previous)
        assertNull(report("from=2025-03-01&to=2025-12-31").previous)
        // The ledger starts at the first expense.
        assertEquals(LocalDate(2024, 12, 1), report("categoryId=${haircut.id}").from)
    }

    @Test
    fun filtersByWhoseAndChecksItsInput() = apiTest {
        val client = newClient()
        val alice = client.signup().id
        val household = "/api/households/${client.createHousehold().id}"
        val bob = client.post("$household/members") { setBody(MemberInput("Bob")) }.body<Member>().userId
        val day = LocalDate(2026, 10, 5)
        client.post("$household/expenses") { setBody(ExpenseInput(day, 1_000, "Albert")) }
        client.post("$household/expenses") { setBody(ExpenseInput(day, 300, "Zara", paidByUserId = alice)) }
        client.post("$household/expenses") {
            setBody(ExpenseInput(day, 700, "Tesco", lines = listOf(ExpenseLineInput(amountMinor = 500), ExpenseLineInput(amountMinor = 200, paidByUserId = bob))))
        }

        suspend fun total(whose: String) = client.get("$household/spending-report?today=2026-10-31&whose=$whose").body<SpendingReport>().totalMinor
        assertEquals(1_500, total("joint"))
        assertEquals(300, total(alice))
        assertEquals(200, total(bob))
        assertEquals(2_000, client.get("$household/spending-report?today=2026-10-31&categoryId=none").body<SpendingReport>().totalMinor)
        assertEquals(0, client.get("$household/spending-report?today=2026-10-31&merchantId=none").body<SpendingReport>().totalMinor)

        client.get("$household/spending-report?whose=someone").assertError(HttpStatusCode.BadRequest, "invalid_whose")
        client.get("$household/spending-report?from=2026-10-10&to=2026-10-01").assertError(HttpStatusCode.BadRequest, "invalid_range")
        client.get("$household/spending-report?categoryId=${bob}").assertError(HttpStatusCode.BadRequest, "unknown_category")
    }

    @Test
    fun showsInvestingBesideSpendingWhenNoCategoryOrMerchantIsPicked() = apiTest {
        val client = newClient()
        val alice = client.signup().id
        val household = "/api/households/${client.createHousehold().id}"
        val food = client.post("$household/categories") { setBody(CategoryInput("Food")) }.body<Category>()
        val broker = client.post("$household/accounts") { setBody(AccountInput("Broker")) }.body<Account>()
        val joint = client.post("$household/assets") { setBody(AssetInput("World ETF")) }.body<Asset>()
        val alices = client.post("$household/assets") { setBody(AssetInput("Alice's pension", ownerUserId = alice)) }.body<Asset>()
        val loan = client.post("$household/assets") { setBody(AssetInput("Mortgage", kind = AssetKind.LOAN)) }.body<Asset>()
        client.post("$household/expenses") { setBody(ExpenseInput(LocalDate(2026, 1, 3), 900, "Albert", food.id)) }
        client.post("$household/assets/${joint.id}/contributions") { setBody(ContributionInput(LocalDate(2026, 1, 10), 5_000, accountId = broker.id)) }
        client.post("$household/assets/${joint.id}/contributions") { setBody(ContributionInput(LocalDate(2026, 2, 10), -1_000)) }
        client.post("$household/assets/${alices.id}/contributions") { setBody(ContributionInput(LocalDate(2026, 2, 12), 2_000)) }
        // Repaying a loan isn't investing.
        client.post("$household/assets/${loan.id}/contributions") { setBody(ContributionInput(LocalDate(2026, 2, 15), 7_000)) }

        suspend fun report(query: String = "") =
            client.get("$household/spending-report?from=2026-01-01&to=2026-02-28&today=2026-03-01$query").body<SpendingReport>()

        val all = report()
        assertEquals(900, all.totalMinor)
        assertEquals(6_000, all.investedMinor)
        assertEquals(listOf(5_000L, 1_000L), all.months.map { it.investedMinor })
        assertEquals(4_000, report("&whose=joint").investedMinor)
        assertEquals(2_000, report("&whose=$alice").investedMinor)
        assertEquals(5_000, report("&accountId=${broker.id}").investedMinor)
        // A category or merchant leaves investing out.
        val food2026 = report("&categoryId=${food.id}")
        assertNull(food2026.investedMinor)
        assertEquals(listOf(0L, 0L), food2026.months.map { it.investedMinor })
    }
}
