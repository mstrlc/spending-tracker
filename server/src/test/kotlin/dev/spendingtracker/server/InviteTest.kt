package dev.spendingtracker.server

import dev.spendingtracker.shared.api.CreateInviteRequest
import dev.spendingtracker.shared.api.Household
import dev.spendingtracker.shared.api.HouseholdRole
import dev.spendingtracker.shared.api.Invite
import dev.spendingtracker.shared.api.InvitePreview
import dev.spendingtracker.shared.api.Member
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class InviteTest {
    private suspend fun HttpClient.invite(householdId: String, email: String, role: HouseholdRole = HouseholdRole.MEMBER): Invite {
        val response = post("/api/households/$householdId/invites") { setBody(CreateInviteRequest(email, role)) }
        assertEquals(HttpStatusCode.Created, response.status)
        return response.body()
    }

    @Test
    fun invitedUserPreviewsAndJoins() = apiTest {
        val alice = newClient()
        val aliceMe = alice.signup("alice@example.com", "Alice")
        val home = alice.createHousehold("Home")
        val invite = alice.invite(home.id, "Bob@Example.com")
        val token = assertNotNull(invite.token)
        assertEquals("bob@example.com", invite.email)

        // The link works before the invitee has an account.
        val anonymous = newClient()
        val preview = anonymous.get("/api/invites/$token").body<InvitePreview>()
        assertEquals("Home", preview.householdName)
        assertEquals("Alice", preview.invitedBy)
        anonymous.post("/api/invites/$token/accept").assertError(HttpStatusCode.Unauthorized, "unauthorized")

        val bob = newClient()
        val bobMe = bob.signup("bob@example.com", "Bob")
        val joined = bob.post("/api/invites/$token/accept").body<Household>()

        assertEquals(Household(home.id, "Home", "CZK", HouseholdRole.MEMBER), joined)
        assertEquals(listOf(joined), bob.get("/api/households").body<List<Household>>())
        assertEquals(
            listOf(Member(aliceMe.id, "Alice", HouseholdRole.OWNER), Member(bobMe.id, "Bob", HouseholdRole.MEMBER)),
            bob.get("/api/households/${home.id}/members").body<List<Member>>(),
        )
        // Single use, and gone from the owner's list.
        bob.post("/api/invites/$token/accept").assertError(HttpStatusCode.NotFound, "invite_not_found")
        assertEquals(emptyList(), alice.get("/api/households/${home.id}/invites").body<List<Invite>>())
    }

    @Test
    fun inviteIsBoundToItsEmail() = apiTest {
        val alice = newClient().also { it.signup("alice@example.com", "Alice") }
        val home = alice.createHousehold()
        val token = alice.invite(home.id, "bob@example.com").token!!

        val mallory = newClient().also { it.signup("mallory@example.com", "Mallory") }
        mallory.post("/api/invites/$token/accept").assertError(HttpStatusCode.Forbidden, "invite_email_mismatch")
        mallory.get("/api/households/${home.id}/members").assertError(HttpStatusCode.NotFound, "not_found")
    }

    @Test
    fun onlyOwnersManageInvites() = apiTest {
        val alice = newClient().also { it.signup("alice@example.com", "Alice") }
        val home = alice.createHousehold()
        val bob = newClient().also { it.signup("bob@example.com", "Bob") }
        bob.post("/api/invites/${alice.invite(home.id, "bob@example.com").token}/accept")

        bob.get("/api/households/${home.id}/invites").assertError(HttpStatusCode.Forbidden, "owner_only")
        bob.post("/api/households/${home.id}/invites") { setBody(CreateInviteRequest("carol@example.com")) }
            .assertError(HttpStatusCode.Forbidden, "owner_only")
    }

    @Test
    fun reinvitingReplacesTheOpenInviteAndRevokingKillsTheLink() = apiTest {
        val alice = newClient().also { it.signup("alice@example.com", "Alice") }
        val home = alice.createHousehold()
        val first = alice.invite(home.id, "bob@example.com")
        val second = alice.invite(home.id, "bob@example.com")

        val open = alice.get("/api/households/${home.id}/invites").body<List<Invite>>()
        assertEquals(listOf(second.id), open.map { it.id })
        assertNull(open.single().token)
        newClient().get("/api/invites/${first.token}").assertError(HttpStatusCode.NotFound, "invite_not_found")

        assertEquals(HttpStatusCode.NoContent, alice.delete("/api/households/${home.id}/invites/${second.id}").status)
        newClient().get("/api/invites/${second.token}").assertError(HttpStatusCode.NotFound, "invite_not_found")
    }

    @Test
    fun cannotInviteAnExistingMemberOrABadAddress() = apiTest {
        val alice = newClient().also { it.signup("alice@example.com", "Alice") }
        val home = alice.createHousehold()

        alice.post("/api/households/${home.id}/invites") { setBody(CreateInviteRequest("ALICE@example.com")) }
            .assertError(HttpStatusCode.Conflict, "already_member")
        alice.post("/api/households/${home.id}/invites") { setBody(CreateInviteRequest("not an email")) }
            .assertError(HttpStatusCode.BadRequest, "invalid_email")
    }

    @Test
    fun expiredInvitesCannotBeUsed() = apiTest {
        val alice = newClient().also { it.signup("alice@example.com", "Alice") }
        val home = alice.createHousehold()
        val token = alice.invite(home.id, "bob@example.com").token
        TestDatabase.dataSource.connection.use {
            it.createStatement().execute("UPDATE household_invites SET expires_at = now() - interval '1 minute'")
        }

        newClient().get("/api/invites/$token").assertError(HttpStatusCode.Gone, "invite_expired")
        newClient().get("/api/invites/garbage!").assertError(HttpStatusCode.NotFound, "invite_not_found")
    }

    @Test
    fun membersCanLeaveButTheLastOwnerCannot() = apiTest {
        val alice = newClient()
        val aliceMe = alice.signup("alice@example.com", "Alice")
        val home = alice.createHousehold()
        val bob = newClient()
        val bobMe = bob.signup("bob@example.com", "Bob")
        bob.post("/api/invites/${alice.invite(home.id, "bob@example.com").token}/accept")
        val members = "/api/households/${home.id}/members"

        bob.delete("$members/${aliceMe.id}").assertError(HttpStatusCode.Forbidden, "owner_only")
        alice.delete("$members/${aliceMe.id}").assertError(HttpStatusCode.Conflict, "last_owner")

        assertEquals(HttpStatusCode.NoContent, bob.delete("$members/${bobMe.id}").status)
        bob.get(members).assertError(HttpStatusCode.NotFound, "not_found")
        assertEquals(listOf(aliceMe.id), alice.get(members).body<List<Member>>().map { it.userId })
    }
}
