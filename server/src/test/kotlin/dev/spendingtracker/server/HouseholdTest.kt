package dev.spendingtracker.server

import dev.spendingtracker.shared.api.AccountInput
import dev.spendingtracker.shared.api.Category
import dev.spendingtracker.shared.api.CategoryKind
import dev.spendingtracker.shared.api.CreateHouseholdRequest
import dev.spendingtracker.shared.api.Household
import dev.spendingtracker.shared.api.HouseholdRole
import dev.spendingtracker.shared.api.Member
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HouseholdTest {
    @Test
    fun createdHouseholdIsListedWithOwnerAndStarterCategories() = apiTest {
        val client = newClient()
        val me = client.signup()

        val household = client.createHousehold("Home", "eur")

        assertEquals("EUR", household.currency)
        assertEquals(HouseholdRole.OWNER, household.role)
        assertEquals(listOf(household), client.get("/api/households").body<List<Household>>())
        assertEquals(
            listOf(Member(me.id, "Alice", HouseholdRole.OWNER)),
            client.get("/api/households/${household.id}/members").body<List<Member>>(),
        )
        val categories = client.get("/api/households/${household.id}/categories").body<List<Category>>()
        assertEquals(12, categories.size)
        assertEquals(CategoryKind.INVESTMENT, categories.single { it.name == "Investments" }.kind)
    }

    @Test
    fun rejectsUnknownCurrency() = apiTest {
        val client = newClient()
        client.signup()

        client.post("/api/households") { setBody(CreateHouseholdRequest("Home", "XYZ")) }
            .assertError(HttpStatusCode.BadRequest, "invalid_currency")
    }

    @Test
    fun householdsRequireLogin() = apiTest {
        newClient().get("/api/households").assertError(HttpStatusCode.Unauthorized, "unauthorized")
    }

    @Test
    fun nonMembersCannotSeeOrTouchAnotherHousehold() = apiTest {
        val alice = newClient().also { it.signup("alice@example.com", "Alice") }
        val bob = newClient().also { it.signup("bob@example.com", "Bob") }
        val home = alice.createHousehold()
        val base = "/api/households/${home.id}"

        assertTrue(bob.get("/api/households").body<List<Household>>().isEmpty())
        for (path in listOf("$base/members", "$base/accounts", "$base/categories", "$base/expenses")) {
            bob.get(path).assertError(HttpStatusCode.NotFound, "not_found")
        }
        bob.post("$base/accounts") { setBody(AccountInput("Bob's sneaky account")) }
            .assertError(HttpStatusCode.NotFound, "not_found")
        bob.get("/api/households/not-a-uuid/accounts").assertError(HttpStatusCode.NotFound, "not_found")
    }
}
