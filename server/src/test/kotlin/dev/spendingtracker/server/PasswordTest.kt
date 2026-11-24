package dev.spendingtracker.server

import dev.spendingtracker.shared.api.ChangePasswordRequest
import dev.spendingtracker.shared.api.CreateInviteRequest
import dev.spendingtracker.shared.api.HouseholdRole
import dev.spendingtracker.shared.api.Invite
import dev.spendingtracker.shared.api.LoginRequest
import dev.spendingtracker.shared.api.Me
import dev.spendingtracker.shared.api.Member
import dev.spendingtracker.shared.api.MemberInput
import dev.spendingtracker.shared.api.SetPasswordRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import kotlin.test.Test
import kotlin.test.assertEquals

class PasswordTest {
    private suspend fun HttpClient.login(email: String, password: String) =
        post("/api/auth/login") { setBody(LoginRequest(email, password)) }

    /** [owner] invites [email] into [householdId]; returns the new member's client. */
    private suspend fun ApplicationTestBuilder.join(owner: HttpClient, householdId: String, email: String, name: String): HttpClient {
        val token = owner.post("/api/households/$householdId/invites") { setBody(CreateInviteRequest(email, HouseholdRole.MEMBER)) }
            .body<Invite>().token!!
        return newClient().also {
            it.signup(email, name)
            assertEquals(HttpStatusCode.OK, it.post("/api/invites/$token/accept").status)
        }
    }

    @Test
    fun changingYourOwnPasswordNeedsTheCurrentOneAndSignsOutOtherSessions() = apiTest {
        val laptop = newClient().also { it.signup("alice@example.com") }
        val phone = newClient().also { assertEquals(HttpStatusCode.OK, it.login("alice@example.com", "correct horse battery").status) }

        laptop.post("/api/auth/password") { setBody(ChangePasswordRequest("wrong password", "a brand new one")) }
            .assertError(HttpStatusCode.BadRequest, "wrong_password")
        laptop.post("/api/auth/password") { setBody(ChangePasswordRequest("correct horse battery", "short")) }
            .assertError(HttpStatusCode.BadRequest, "invalid_password")

        val changed = laptop.post("/api/auth/password") { setBody(ChangePasswordRequest("correct horse battery", "a brand new one")) }
        assertEquals(HttpStatusCode.NoContent, changed.status)

        assertEquals(HttpStatusCode.OK, laptop.get("/api/auth/me").status)
        phone.get("/api/auth/me").assertError(HttpStatusCode.Unauthorized, "unauthorized")
        newClient().login("alice@example.com", "correct horse battery").assertError(HttpStatusCode.Unauthorized, "invalid_credentials")
        assertEquals(HttpStatusCode.OK, newClient().login("alice@example.com", "a brand new one").status)
    }

    @Test
    fun changingYourPasswordNeedsASession() = apiTest {
        newClient().post("/api/auth/password") { setBody(ChangePasswordRequest("x", "a brand new one")) }
            .assertError(HttpStatusCode.Unauthorized, "unauthorized")
    }

    @Test
    fun ownersSetMembersPasswordsAndSignThemOut() = apiTest {
        val alice = newClient().also { it.signup("alice@example.com", "Alice") }
        val home = alice.createHousehold()
        val bob = join(alice, home.id, "bob@example.com", "Bob")
        val bobId = bob.get("/api/auth/me").body<Me>().id
        val base = "/api/households/${home.id}/members"

        alice.put("$base/$bobId/password") { setBody(SetPasswordRequest("short")) }
            .assertError(HttpStatusCode.BadRequest, "invalid_password")
        assertEquals(HttpStatusCode.NoContent, alice.put("$base/$bobId/password") { setBody(SetPasswordRequest("set by alice")) }.status)

        bob.get("/api/auth/me").assertError(HttpStatusCode.Unauthorized, "unauthorized")
        assertEquals(HttpStatusCode.OK, newClient().login("bob@example.com", "set by alice").status)
    }

    @Test
    fun setMemberPasswordLimits() = apiTest {
        val alice = newClient()
        val aliceMe = alice.signup("alice@example.com", "Alice")
        val home = alice.createHousehold()
        val bob = join(alice, home.id, "bob@example.com", "Bob")
        val bobId = bob.get("/api/auth/me").body<Me>().id
        val base = "/api/households/${home.id}/members"
        val body = SetPasswordRequest("long enough password")

        // Members can't, and owners use their own route for themselves.
        bob.put("$base/${aliceMe.id}/password") { setBody(body) }.assertError(HttpStatusCode.Forbidden, "owner_only")
        alice.put("$base/${aliceMe.id}/password") { setBody(body) }.assertError(HttpStatusCode.BadRequest, "own_password")

        // A member without a login has nothing to set.
        val grandma = alice.post(base) { setBody(MemberInput("Grandma")) }.body<Member>()
        alice.put("$base/${grandma.userId}/password") { setBody(body) }.assertError(HttpStatusCode.Conflict, "member_without_login")

        // Someone outside the household is not found.
        val carol = newClient().signup("carol@example.com", "Carol")
        alice.put("$base/${carol.id}/password") { setBody(body) }.assertError(HttpStatusCode.NotFound, "not_found")

        // Bob also belongs to a household Alice doesn't own: only he can change it.
        val bobsOwn = bob.createHousehold("Bob's flat")
        alice.put("$base/$bobId/password") { setBody(body) }.assertError(HttpStatusCode.Conflict, "member_in_other_household")
        assertEquals(HttpStatusCode.OK, bob.get("/api/households/${bobsOwn.id}/members").status)
    }
}
