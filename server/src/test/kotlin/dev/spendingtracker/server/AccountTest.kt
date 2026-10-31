package dev.spendingtracker.server

import dev.spendingtracker.shared.api.Account
import dev.spendingtracker.shared.api.AccountInput
import dev.spendingtracker.shared.api.AccountStatus
import dev.spendingtracker.shared.api.Expense
import dev.spendingtracker.shared.api.ExpenseInput
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

class AccountTest {
    @Test
    fun createUpdateAndDeleteAccount() = apiTest {
        val client = newClient()
        val me = client.signup()
        val base = "/api/households/${client.createHousehold().id}/accounts"

        val created = client.post(base) { setBody(AccountInput("Revolut", ownerUserId = me.id)) }.body<Account>()
        assertEquals(Account(created.id, "Revolut", me.id, AccountStatus.ACTIVE, 1), created)

        val closed = client.put("$base/${created.id}") {
            setBody(AccountInput("Revolut", ownerUserId = null, status = AccountStatus.CLOSED, version = 1))
        }.body<Account>()
        assertEquals(AccountStatus.CLOSED, closed.status)
        assertEquals(null, closed.ownerUserId)
        assertEquals(2, closed.version)

        assertEquals(HttpStatusCode.NoContent, client.delete("$base/${created.id}?version=2").status)
        assertTrue(client.get(base).body<List<Account>>().isEmpty())
    }

    @Test
    fun rejectsDuplicateNameStaleVersionAndForeignOwner() = apiTest {
        val alice = newClient().also { it.signup("alice@example.com") }
        val bob = newClient().signup("bob@example.com")
        val base = "/api/households/${alice.createHousehold().id}/accounts"
        val account = alice.post(base) { setBody(AccountInput("Joint")) }.body<Account>()

        alice.post(base) { setBody(AccountInput("Joint")) }.assertError(HttpStatusCode.Conflict, "name_taken")
        alice.put("$base/${account.id}") { setBody(AccountInput("Renamed", version = 7)) }
            .assertError(HttpStatusCode.Conflict, "version_conflict")
        alice.put("$base/${account.id}") { setBody(AccountInput("Renamed")) }
            .assertError(HttpStatusCode.BadRequest, "version_required")
        alice.post(base) { setBody(AccountInput("Bob's", ownerUserId = bob.id)) }
            .assertError(HttpStatusCode.BadRequest, "invalid_owner")
    }

    @Test
    fun accountInUseCannotBeDeleted() = apiTest {
        val client = newClient().also { it.signup() }
        val household = "/api/households/${client.createHousehold().id}"
        val account = client.post("$household/accounts") { setBody(AccountInput("Card")) }.body<Account>()
        client.post("$household/expenses") {
            setBody(ExpenseInput(LocalDate(2026, 10, 1), 1000, accountId = account.id))
        }.body<Expense>()

        client.delete("$household/accounts/${account.id}?version=1")
            .assertError(HttpStatusCode.Conflict, "account_in_use")
    }
}
