package dev.spendingtracker.server

import dev.spendingtracker.shared.api.Account
import dev.spendingtracker.shared.api.AccountInput
import dev.spendingtracker.shared.api.ActivityAction
import dev.spendingtracker.shared.api.ActivityEntity
import dev.spendingtracker.shared.api.ActivityPage
import dev.spendingtracker.shared.api.CreateInviteRequest
import dev.spendingtracker.shared.api.Expense
import dev.spendingtracker.shared.api.ExpenseInput
import dev.spendingtracker.shared.api.FieldChange
import dev.spendingtracker.shared.api.Invite
import dev.spendingtracker.shared.api.Transfer
import dev.spendingtracker.shared.api.TransferInput
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
import kotlin.test.assertTrue

class ActivityTest {
    @Test
    fun recordsCreateUpdateAndDeleteWithActorAndChanges() = apiTest {
        val client = newClient()
        val me = client.signup()
        val household = "/api/households/${client.createHousehold().id}"
        val created = client.post("$household/expenses") {
            setBody(ExpenseInput(LocalDate(2026, 10, 3), 12_990, "Albert", notes = "weekly shop"))
        }.body<Expense>()
        client.put("$household/expenses/${created.id}") {
            setBody(ExpenseInput(LocalDate(2026, 10, 3), 13_490, "Albert", notes = "weekly shop", version = 1))
        }
        // Saving without changes isn't a change.
        client.put("$household/expenses/${created.id}") {
            setBody(ExpenseInput(LocalDate(2026, 10, 3), 13_490, "Albert", notes = "weekly shop", version = 2))
        }
        assertEquals(HttpStatusCode.NoContent, client.delete("$household/expenses/${created.id}?version=3").status)

        val history = client.get("$household/activity?entity=expense&entityId=${created.id}").body<ActivityPage>()
        assertEquals(listOf(ActivityAction.DELETE, ActivityAction.UPDATE, ActivityAction.CREATE), history.entries.map { it.action })
        assertTrue(history.entries.all { it.actorUserId == me.id && it.actorName == "Alice" && it.label == "Albert" })
        assertEquals(listOf(FieldChange("amountMinor", "12990", "13490")), history.entries[1].changes)
        val fields = history.entries[2].changes.associate { it.field to it.after }
        assertEquals(listOf("date", "amountMinor", "merchantId"), history.entries[2].changes.map { it.field }.take(3))
        assertEquals("2026-10-03", fields["date"])
        assertEquals("weekly shop", fields["notes"])
        assertNull(fields["paidByUserId"])
        assertTrue(history.entries[0].changes.all { it.after == null && it.field != "version" && it.field != "id" })
        assertNull(history.nextBefore)
    }

    @Test
    fun coversEveryKindOfRecordAndPages() = apiTest {
        val alice = newClient().also { it.signup() }
        val home = alice.createHousehold("Home")
        alice.post("/api/households/${home.id}/invites") { setBody(CreateInviteRequest("bob@example.com")) }.body<Invite>()

        val all = alice.get("/api/households/${home.id}/activity?limit=200").body<ActivityPage>().entries
        val kinds = all.map { it.entity }.toSet()
        assertEquals(setOf(ActivityEntity.HOUSEHOLD, ActivityEntity.MEMBER, ActivityEntity.CATEGORY, ActivityEntity.INVITE), kinds)
        assertEquals("Alice", all.single { it.entity == ActivityEntity.MEMBER }.label)
        val invite = all.single { it.entity == ActivityEntity.INVITE }
        assertEquals("bob@example.com", invite.label)
        assertTrue(invite.changes.none { it.field == "tokenHash" })

        val first = alice.get("/api/households/${home.id}/activity?limit=2").body<ActivityPage>()
        assertEquals(all.take(2), first.entries)
        val second = alice.get("/api/households/${home.id}/activity?limit=2&before=${first.nextBefore}").body<ActivityPage>()
        assertEquals(all.drop(2).take(2), second.entries)
    }

    @Test
    fun onlyMembersSeeTheLog() = apiTest {
        val alice = newClient().also { it.signup() }
        val home = alice.createHousehold()
        val mallory = newClient().also { it.signup("mallory@example.com", "Mallory") }
        mallory.get("/api/households/${home.id}/activity").assertError(HttpStatusCode.NotFound, "not_found")
        alice.get("/api/households/${home.id}/activity?entity=nope").assertError(HttpStatusCode.BadRequest, "invalid_entity")
        alice.get("/api/households/${home.id}/activity?limit=0").assertError(HttpStatusCode.BadRequest, "invalid_limit")
    }

    @Test
    fun logsTransferChanges() = apiTest {
        val alice = newClient().also { it.signup() }
        val home = alice.createHousehold()
        val base = "/api/households/${home.id}"
        val current = alice.post("$base/accounts") { setBody(AccountInput("Current")) }.body<Account>()
        val savings = alice.post("$base/accounts") { setBody(AccountInput("Savings")) }.body<Account>()

        val created = alice.post("$base/transfers") {
            setBody(TransferInput(LocalDate(2026, 9, 10), 100_000, current.id, savings.id, "save"))
        }.body<Transfer>()
        val updated = alice.put("$base/transfers/${created.id}") {
            setBody(TransferInput(LocalDate(2026, 9, 10), 120_000, current.id, savings.id, "more", created.version))
        }.body<Transfer>()
        alice.delete("$base/transfers/${created.id}?version=${updated.version}")

        val history = alice.get("$base/activity?entity=transfer").body<ActivityPage>()
        assertEquals(3, history.entries.size)
        assertEquals(listOf(ActivityAction.DELETE, ActivityAction.UPDATE, ActivityAction.CREATE), history.entries.map { it.action })
        assertTrue(history.entries.all { it.entity == ActivityEntity.TRANSFER && it.label == "Current → Savings" })
    }
}
