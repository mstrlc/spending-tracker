package dev.spendingtracker.server

import dev.spendingtracker.shared.api.Category
import dev.spendingtracker.shared.api.CategoryInput
import dev.spendingtracker.shared.api.Expense
import dev.spendingtracker.shared.api.ExpenseInput
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
import kotlin.test.assertTrue

class ExpenseTest {
    private suspend fun HttpClient.add(base: String, input: ExpenseInput) = post(base) { setBody(input) }.body<Expense>()

    @Test
    fun createUpdateDeleteWithOptimisticConcurrency() = apiTest {
        val client = newClient()
        val me = client.signup()
        val base = "/api/households/${client.createHousehold().id}/expenses"

        val created = client.add(base, ExpenseInput(LocalDate(2026, 10, 3), 12_990, " Albert ", paidByUserId = me.id))
        assertEquals("Albert", created.merchant)
        assertEquals(1, created.version)

        val refund = client.put("$base/${created.id}") {
            setBody(ExpenseInput(LocalDate(2026, 10, 3), -12_990, "Albert", notes = "returned", version = 1))
        }.body<Expense>()
        assertEquals(-12_990, refund.amountMinor)
        assertEquals(null, refund.paidByUserId)
        assertEquals(2, refund.version)

        client.put("$base/${created.id}") { setBody(ExpenseInput(LocalDate(2026, 10, 3), 1, version = 1)) }
            .assertError(HttpStatusCode.Conflict, "version_conflict")
        client.delete("$base/${created.id}?version=1").assertError(HttpStatusCode.Conflict, "version_conflict")
        assertEquals(HttpStatusCode.NoContent, client.delete("$base/${created.id}?version=2").status)
        client.delete("$base/${created.id}?version=2").assertError(HttpStatusCode.NotFound, "not_found")
    }

    @Test
    fun rejectsZeroAmountAndInvalidInput() = apiTest {
        val client = newClient().also { it.signup() }
        val base = "/api/households/${client.createHousehold().id}/expenses"

        client.post(base) { setBody(ExpenseInput(LocalDate(2026, 10, 3), 0)) }
            .assertError(HttpStatusCode.BadRequest, "invalid_amount")
        client.post(base) { setBody("""{"date":"2026-13-40","amountMinor":100}""") }
            .assertError(HttpStatusCode.BadRequest, "invalid_body")
        client.get("$base?from=yesterday").assertError(HttpStatusCode.BadRequest, "invalid_date")
    }

    @Test
    fun filtersByDateRangeAndCategoryIncludingSubcategories() = apiTest {
        val client = newClient().also { it.signup() }
        val household = "/api/households/${client.createHousehold().id}"
        val base = "$household/expenses"
        val food = client.post("$household/categories") { setBody(CategoryInput("Food")) }.body<Category>()
        val snacks = client.post("$household/categories") { setBody(CategoryInput("Snacks", parentId = food.id)) }.body<Category>()
        val sept = client.add(base, ExpenseInput(LocalDate(2026, 9, 30), 100, categoryId = food.id))
        val octFood = client.add(base, ExpenseInput(LocalDate(2026, 10, 1), 200, categoryId = food.id))
        val octSnack = client.add(base, ExpenseInput(LocalDate(2026, 10, 31), 300, categoryId = snacks.id))
        val octOther = client.add(base, ExpenseInput(LocalDate(2026, 10, 15), 400))

        val october = client.get("$base?from=2026-10-01&to=2026-10-31").body<List<Expense>>()
        assertEquals(listOf(octSnack, octOther, octFood), october)

        val foodAll = client.get("$base?categoryId=${food.id}").body<List<Expense>>()
        assertEquals(setOf(sept, octFood, octSnack), foodAll.toSet())
    }

    @Test
    fun cannotReferenceAnotherHouseholdsCategory() = apiTest {
        val alice = newClient().also { it.signup("alice@example.com") }
        val bob = newClient().also { it.signup("bob@example.com") }
        val aliceHome = "/api/households/${alice.createHousehold().id}"
        val bobCategory = bob.get("/api/households/${bob.createHousehold().id}/categories").body<List<Category>>().first()

        alice.post("$aliceHome/expenses") { setBody(ExpenseInput(LocalDate(2026, 10, 1), 100, categoryId = bobCategory.id)) }
            .assertError(HttpStatusCode.BadRequest, "invalid_category")
        assertTrue(alice.get("$aliceHome/expenses").body<List<Expense>>().isEmpty())
    }
}
