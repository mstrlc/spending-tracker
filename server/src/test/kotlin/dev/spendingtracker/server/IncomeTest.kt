package dev.spendingtracker.server

import dev.spendingtracker.shared.api.Account
import dev.spendingtracker.shared.api.AccountInput
import dev.spendingtracker.shared.api.CreateInviteRequest
import dev.spendingtracker.shared.api.Income
import dev.spendingtracker.shared.api.IncomeInput
import dev.spendingtracker.shared.api.Invite
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
import kotlin.test.assertTrue

class IncomeTest {
    private suspend fun HttpClient.add(base: String, input: IncomeInput) = post(base) { setBody(input) }.body<Income>()

    @Test
    fun createUpdateDeleteWithOptimisticConcurrency() = apiTest {
        val client = newClient()
        val me = client.signup()
        val base = "/api/households/${client.createHousehold().id}/income"

        val created = client.add(base, IncomeInput(LocalDate(2026, 10, 15), 6_500_000, " Acme ", recipientUserId = me.id))
        assertEquals("Acme", created.source)
        assertEquals(me.id, created.recipientUserId)
        assertEquals(1, created.version)

        val clawback = client.put("$base/${created.id}") {
            setBody(IncomeInput(LocalDate(2026, 10, 15), -20_000, "Acme", notes = "overpaid", version = 1))
        }.body<Income>()
        assertEquals(-20_000, clawback.amountMinor)
        assertEquals(null, clawback.recipientUserId)
        assertEquals(2, clawback.version)

        client.put("$base/${created.id}") { setBody(IncomeInput(LocalDate(2026, 10, 15), 1, version = 1)) }
            .assertError(HttpStatusCode.Conflict, "version_conflict")
        client.put("$base/${created.id}") { setBody(IncomeInput(LocalDate(2026, 10, 15), 1)) }
            .assertError(HttpStatusCode.BadRequest, "version_required")
        client.delete("$base/${created.id}?version=1").assertError(HttpStatusCode.Conflict, "version_conflict")
        assertEquals(HttpStatusCode.NoContent, client.delete("$base/${created.id}?version=2").status)
        client.delete("$base/${created.id}?version=2").assertError(HttpStatusCode.NotFound, "not_found")
    }

    @Test
    fun rejectsZeroAmountAndInvalidInput() = apiTest {
        val client = newClient().also { it.signup() }
        val base = "/api/households/${client.createHousehold().id}/income"

        client.post(base) { setBody(IncomeInput(LocalDate(2026, 10, 3), 0)) }
            .assertError(HttpStatusCode.BadRequest, "invalid_amount")
        client.post(base) { setBody(IncomeInput(LocalDate(2026, 10, 3), 100, recipientUserId = "nope")) }
            .assertError(HttpStatusCode.BadRequest, "invalid_id")
        client.get("$base?to=tomorrow").assertError(HttpStatusCode.BadRequest, "invalid_date")
    }

    @Test
    fun filtersByDateRangeAndAccount() = apiTest {
        val client = newClient().also { it.signup() }
        val household = "/api/households/${client.createHousehold().id}"
        val base = "$household/income"
        val bank = client.post("$household/accounts") { setBody(AccountInput("Bank")) }.body<Account>()
        val sept = client.add(base, IncomeInput(LocalDate(2026, 9, 30), 100, accountId = bank.id))
        val octFirst = client.add(base, IncomeInput(LocalDate(2026, 10, 1), 200, accountId = bank.id))
        val octLast = client.add(base, IncomeInput(LocalDate(2026, 10, 31), 300))

        val october = client.get("$base?from=2026-10-01&to=2026-10-31").body<List<Income>>()
        assertEquals(listOf(octLast, octFirst), october)
        assertEquals(setOf(sept, octFirst), client.get("$base?accountId=${bank.id}").body<List<Income>>().toSet())
    }

    @Test
    fun accountWithIncomeCannotBeDeleted() = apiTest {
        val client = newClient().also { it.signup() }
        val household = "/api/households/${client.createHousehold().id}"
        val bank = client.post("$household/accounts") { setBody(AccountInput("Bank")) }.body<Account>()
        client.add("$household/income", IncomeInput(LocalDate(2026, 10, 15), 100, accountId = bank.id))

        client.delete("$household/accounts/${bank.id}?version=1").assertError(HttpStatusCode.Conflict, "account_in_use")
    }

    @Test
    fun cannotReferenceOutsidersAndRemovedMembersBecomeShared() = apiTest {
        val alice = newClient().also { it.signup("alice@example.com") }
        val bob = newClient()
        val bobMe = bob.signup("bob@example.com", "Bob")
        val home = alice.createHousehold()
        val base = "/api/households/${home.id}/income"
        val bobAccount = bob.post("/api/households/${bob.createHousehold().id}/accounts") { setBody(AccountInput("Bob's")) }
            .body<Account>()

        alice.post(base) { setBody(IncomeInput(LocalDate(2026, 10, 1), 100, recipientUserId = bobMe.id)) }
            .assertError(HttpStatusCode.BadRequest, "invalid_member")
        alice.post(base) { setBody(IncomeInput(LocalDate(2026, 10, 1), 100, accountId = bobAccount.id)) }
            .assertError(HttpStatusCode.BadRequest, "invalid_account")
        assertTrue(alice.get(base).body<List<Income>>().isEmpty())

        val token = alice.post("/api/households/${home.id}/invites") { setBody(CreateInviteRequest("bob@example.com")) }
            .body<Invite>().token
        bob.post("/api/invites/$token/accept")
        val bobsSalary = alice.add(base, IncomeInput(LocalDate(2026, 10, 15), 100, "Globex", recipientUserId = bobMe.id))
        assertEquals(bobMe.id, bobsSalary.recipientUserId)

        alice.delete("/api/households/${home.id}/members/${bobMe.id}")
        assertEquals(null, alice.get(base).body<List<Income>>().single().recipientUserId)
    }

    @Test
    fun incomeCountsInTheMonthItWasEarned() = apiTest {
        val client = newClient().also { it.signup() }
        val household = "/api/households/${client.createHousehold().id}"
        val base = "$household/income"

        // October's salary, paid on 2 November.
        val salary = client.post(base) { setBody(IncomeInput(LocalDate(2026, 11, 2), 50_000_00, "Initech", earnedMonth = "2026-10")) }
            .body<Income>()
        assertEquals("2026-10", salary.earnedMonth)
        assertEquals("2026-11", client.post(base) { setBody(IncomeInput(LocalDate(2026, 11, 5), 1_000_00, "Gift")) }.body<Income>().earnedMonth)
        client.post(base) { setBody(IncomeInput(LocalDate(2026, 11, 5), 1_00, earnedMonth = "October")) }
            .assertError(HttpStatusCode.BadRequest, "invalid_month")

        assertEquals(listOf("Initech"), client.get("$base?from=2026-10-01&to=2026-10-31").body<List<Income>>().map { it.source })
        assertEquals(listOf("Gift"), client.get("$base?from=2026-11-01&to=2026-11-30").body<List<Income>>().map { it.source })
        val history = client.get("$household/overview?month=2026-11").body<Overview>().history.associate { it.month to it.incomeMinor }
        assertEquals(50_000_00L to 1_000_00L, history["2026-10"] to history["2026-11"])
    }
}
