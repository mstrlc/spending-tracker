package dev.spendingtracker.server

import dev.spendingtracker.server.expense.spreadByWeight
import dev.spendingtracker.shared.api.Account
import dev.spendingtracker.shared.api.AccountInput
import dev.spendingtracker.shared.api.Balances
import dev.spendingtracker.shared.api.Category
import dev.spendingtracker.shared.api.CategoryInput
import dev.spendingtracker.shared.api.Debt
import dev.spendingtracker.shared.api.Expense
import dev.spendingtracker.shared.api.ExpenseInput
import dev.spendingtracker.shared.api.ExpenseLineInput
import dev.spendingtracker.shared.api.Member
import dev.spendingtracker.shared.api.MemberInput
import dev.spendingtracker.shared.api.Overview
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RefundTest {
    private suspend fun HttpClient.category(base: String, name: String) =
        post("$base/categories") { setBody(CategoryInput(name)) }.body<Category>().id

    private suspend fun HttpClient.expense(base: String, input: ExpenseInput): Expense {
        val response = post("$base/expenses") { setBody(input) }
        assertEquals(HttpStatusCode.Created, response.status)
        return response.body()
    }

    private suspend fun HttpClient.expenses(base: String, query: String = "") = get("$base/expenses$query").body<List<Expense>>()

    @Test
    fun spreadsAnAmountInProportionAndAddsUpExactly() {
        assertEquals(listOf(150L, 100L), spreadByWeight(250, listOf(600, 400)))
        assertEquals(listOf(-150L, -100L), spreadByWeight(-250, listOf(600, 400)))
        // The leftover unit goes to the largest remainder, ties to the earlier line.
        assertEquals(listOf(34L, 33L, 33L), spreadByWeight(100, listOf(1, 1, 1)))
        assertEquals(listOf(333L, 667L), spreadByWeight(1000, listOf(1, 2)))
        assertEquals(listOf(-7L), spreadByWeight(-7, listOf(10)))
        assertEquals(emptyList(), spreadByWeight(5, emptyList()))
    }

    @Test
    fun aRefundIsANegativeExpenseOfAnotherPositiveOne() = apiTest {
        val client = newClient().also { it.signup() }
        val base = "/api/households/${client.createHousehold().id}"
        val original = client.expense(base, ExpenseInput(LocalDate(2026, 8, 10), 1_000_00, "Shoe Shop"))
        assertNull(original.refundOfId)
        assertEquals(0, original.refundedMinor)

        val refund = client.expense(base, ExpenseInput(LocalDate(2026, 8, 20), -300_00, "Shoe Shop", refundOfId = original.id))
        assertEquals(original.id, refund.refundOfId)
        assertEquals(0, refund.refundedMinor)
        client.expense(base, ExpenseInput(LocalDate(2026, 8, 25), -200_00, "Shoe Shop", refundOfId = original.id))
        // What was refunded shows on the original, as a positive amount, and in the list as well.
        assertEquals(500_00, client.expenses(base).first { it.id == original.id }.refundedMinor)

        fun bad(code: String, input: ExpenseInput) = suspend { client.post("$base/expenses") { setBody(input) }.assertError(HttpStatusCode.BadRequest, code) }
        val day = LocalDate(2026, 9, 1)
        bad("invalid_refund", ExpenseInput(day, 100, "x", refundOfId = original.id))()
        bad("invalid_refund", ExpenseInput(day, -100, "x", refundOfId = "00000000-0000-0000-0000-000000000001"))()
        bad("invalid_id", ExpenseInput(day, -100, "x", refundOfId = "nope"))()
        // A refund can't be refunded, and a negative expense isn't an original either.
        bad("invalid_refund", ExpenseInput(day, -100, "x", refundOfId = refund.id))()
        val negative = client.expense(base, ExpenseInput(day, -50, "Loose"))
        bad("invalid_refund", ExpenseInput(day, -100, "x", refundOfId = negative.id))()
        // Another household's expense isn't there to refund.
        val other = newClient().also { it.signup("bob@example.com", "Bob") }
        val otherBase = "/api/households/${other.createHousehold().id}"
        val foreign = other.expense(otherBase, ExpenseInput(day, 500, "Foreign"))
        bad("invalid_refund", ExpenseInput(day, -100, "x", refundOfId = foreign.id))()

        // An expense with refunds can't turn into a refund or go negative; changing its amount is fine.
        suspend fun update(id: String, input: ExpenseInput) = client.put("$base/expenses/$id") { setBody(input) }
        update(original.id, ExpenseInput(original.date, -1_000_00, "Shoe Shop", version = original.version))
            .assertError(HttpStatusCode.BadRequest, "has_refunds")
        val sibling = client.expense(base, ExpenseInput(day, 90_00, "Sibling"))
        update(original.id, ExpenseInput(original.date, -1_000_00, "Shoe Shop", version = original.version, refundOfId = sibling.id))
            .assertError(HttpStatusCode.BadRequest, "has_refunds")
        val edited = update(original.id, ExpenseInput(original.date, 1_200_00, "Shoe Shop", version = original.version)).body<Expense>()
        assertEquals(1_200_00L to 500_00L, edited.amountMinor to edited.refundedMinor)

        // A refund can't be its own original, but can be re-pointed or detached.
        update(refund.id, ExpenseInput(refund.date, -300_00, "Shoe Shop", version = refund.version, refundOfId = refund.id))
            .assertError(HttpStatusCode.BadRequest, "invalid_refund")
        update(refund.id, ExpenseInput(refund.date, 300_00, "Shoe Shop", version = refund.version, refundOfId = original.id))
            .assertError(HttpStatusCode.BadRequest, "invalid_refund")
        val detached = update(refund.id, ExpenseInput(refund.date, -300_00, "Shoe Shop", version = refund.version)).body<Expense>()
        assertNull(detached.refundOfId)
        assertEquals(200_00, client.expenses(base).first { it.id == original.id }.refundedMinor)
    }

    @Test
    fun deletingTheOriginalLeavesTheRefundAnOrdinaryNegativeExpense() = apiTest {
        val client = newClient().also { it.signup() }
        val base = "/api/households/${client.createHousehold().id}"
        val original = client.expense(base, ExpenseInput(LocalDate(2026, 8, 10), 1_000_00, "Shoe Shop"))
        val refund = client.expense(base, ExpenseInput(LocalDate(2026, 8, 20), -300_00, "Shoe Shop", refundOfId = original.id))

        assertEquals(HttpStatusCode.NoContent, client.delete("$base/expenses/${original.id}?version=${original.version}").status)
        val left = client.expenses(base).single()
        assertEquals(refund.id, left.id)
        assertNull(left.refundOfId)
        assertEquals(-300_00, left.amountMinor)
    }

    @Test
    fun reportsCountARefundInItsOriginalsMonthAndCategory() = apiTest {
        val client = newClient().also { it.signup() }
        val base = "/api/households/${client.createHousehold().id}"
        val clothes = client.category(base, "Refund clothes")
        val home = client.category(base, "Refund home")
        val fun_ = client.category(base, "Refund leisure")

        // August: a plain purchase and a receipt split over two categories, both refunded in October.
        val shoes = client.expense(base, ExpenseInput(LocalDate(2026, 8, 10), 1_000_00, "Shoe Shop", clothes))
        val receipt = client.expense(
            base,
            ExpenseInput(
                LocalDate(2026, 8, 12), 1_000_00, "Big Store",
                lines = listOf(ExpenseLineInput(home, 600_00), ExpenseLineInput(fun_, 400_00)),
            ),
        )
        client.expense(base, ExpenseInput(LocalDate(2026, 8, 15), 100_00, "Cinema", fun_))
        // September has its own spending; October only a purchase and the refunds.
        client.expense(base, ExpenseInput(LocalDate(2026, 9, 5), 50_00, "Cinema", fun_))
        client.expense(base, ExpenseInput(LocalDate(2026, 10, 2), 70_00, "Cinema", fun_))
        client.expense(base, ExpenseInput(LocalDate(2026, 10, 5), -400_00, "Shoe Shop", refundOfId = shoes.id))
        client.expense(base, ExpenseInput(LocalDate(2026, 10, 6), -250_00, "Big Store", refundOfId = receipt.id))

        val october = client.get("$base/overview?month=2026-10&today=2026-10-31").body<Overview>()
        val months = october.history.associateBy { it.month }
        // August nets the refunds off: 1000 - 400 + 1000 - 250 + 100; October keeps only what was bought in it.
        assertEquals(1_450_00, months.getValue("2026-08").spendingMinor)
        assertEquals(50_00, months.getValue("2026-09").spendingMinor)
        assertEquals(70_00, october.thisMonth.spendingMinor)
        assertEquals(listOf(fun_ to 70_00L), october.topCategories.map { it.categoryId to it.amountMinor })
        assertEquals(listOf("Cinema"), october.topMerchants.map { it.name })

        // Category totals in August: the refund of the split receipt is spread 600:400 over its lines (150 and 100).
        val august = client.get("$base/overview?month=2026-08&today=2026-10-31").body<Overview>()
        assertEquals(
            listOf(clothes to 600_00L, home to 450_00L, fun_ to 400_00L),
            august.topCategories.map { it.categoryId to it.amountMinor },
        )
        assertEquals(1_450_00, august.thisMonth.spendingMinor)
        // A month's refund doesn't reach back before the original: the window of an earlier month holds neither.
        val july = client.get("$base/overview?month=2026-07&today=2026-10-31").body<Overview>()
        assertEquals(0, july.thisMonth.spendingMinor)

        // Lists keep the refund on its own date.
        assertEquals(
            listOf(-400_00L, -250_00L, 70_00L),
            client.expenses(base, "?from=2026-10-01&to=2026-10-31").map { it.amountMinor }.sortedBy { it },
        )
        // And a refund filed under a category filter shows with it, as before.
        assertEquals(setOf(shoes.id), client.expenses(base, "?categoryId=$clothes&from=2026-08-01&to=2026-08-31").map { it.id }.toSet())
    }

    @Test
    fun aRefundOnTheSameAccountReversesItsShareOfTheDebt() = apiTest {
        val client = newClient()
        val alice = client.signup().id
        val base = "/api/households/${client.createHousehold().id}"
        val bob = client.post("$base/members") { setBody(MemberInput("Bob")) }.body<Member>().userId
        val aliceCard = client.post("$base/accounts") { setBody(AccountInput("Alice's card", alice)) }.body<Account>().id
        fun owes(amount: Long) = listOf(Debt(bob, alice, amount))

        // A joint purchase on Alice's card: Bob owes half. Refunded 400, the same account and whose: he owes 300 net.
        val original = client.expense(base, ExpenseInput(LocalDate(2026, 7, 1), 1_000, accountId = aliceCard))
        assertEquals(owes(500), original.debts)
        val refund = client.expense(base, ExpenseInput(LocalDate(2026, 7, 9), -400, accountId = aliceCard, refundOfId = original.id))
        assertEquals(owes(-200), refund.debts)
        assertEquals(owes(300), client.get("$base/balances").body<Balances>().balances)

        // Deleting the refund puts the debt back.
        client.delete("$base/expenses/${refund.id}?version=${refund.version}")
        assertEquals(owes(500), client.get("$base/balances").body<Balances>().balances)
    }
}
