package dev.spendingtracker.server

import dev.spendingtracker.shared.api.LoginRequest
import dev.spendingtracker.shared.api.Me
import dev.spendingtracker.shared.api.SignupRequest
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class AuthTest {
    @Test
    fun signupLogsInAndSetsHardenedCookie() = apiTest {
        val client = newClient()
        val response = client.post("/api/auth/signup") {
            setBody(SignupRequest(" Alice@Example.com ", "correct horse battery", "Alice"))
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val cookie = response.headers["Set-Cookie"]!!
        assertContains(cookie, "HttpOnly")
        assertContains(cookie, "SameSite=Lax")
        val me = client.get("/api/auth/me").body<Me>()
        assertEquals("alice@example.com", me.email)
        assertEquals("Alice", me.displayName)
    }

    @Test
    fun rejectsDuplicateEmailIgnoringCase() = apiTest {
        newClient().signup("alice@example.com")

        newClient().post("/api/auth/signup") { setBody(SignupRequest("ALICE@example.com", "another password", "A")) }
            .assertError(HttpStatusCode.Conflict, "email_taken")
    }

    @Test
    fun rejectsShortPasswordAndBadEmail() = apiTest {
        val client = newClient()
        client.post("/api/auth/signup") { setBody(SignupRequest("a@example.com", "short", "A")) }
            .assertError(HttpStatusCode.BadRequest, "invalid_password")
        client.post("/api/auth/signup") { setBody(SignupRequest("not-an-email", "long enough pw", "A")) }
            .assertError(HttpStatusCode.BadRequest, "invalid_email")
    }

    @Test
    fun loginChecksPassword() = apiTest {
        newClient().signup("alice@example.com")
        val client = newClient()

        client.post("/api/auth/login") { setBody(LoginRequest("alice@example.com", "wrong password")) }
            .assertError(HttpStatusCode.Unauthorized, "invalid_credentials")
        client.post("/api/auth/login") { setBody(LoginRequest("nobody@example.com", "wrong password")) }
            .assertError(HttpStatusCode.Unauthorized, "invalid_credentials")

        val response = client.post("/api/auth/login") { setBody(LoginRequest("alice@example.com", "correct horse battery")) }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/auth/me").status)
    }

    @Test
    fun meRequiresSessionAndLogoutEndsIt() = apiTest {
        val client = newClient()
        client.get("/api/auth/me").assertError(HttpStatusCode.Unauthorized, "unauthorized")

        client.signup()
        assertEquals(HttpStatusCode.NoContent, client.post("/api/auth/logout").status)

        client.get("/api/auth/me").assertError(HttpStatusCode.Unauthorized, "unauthorized")
    }

    @Test
    fun signupCanBeClosed() = apiTest(signupOpen = false) {
        newClient().post("/api/auth/signup") { setBody(SignupRequest("bob@example.com", "correct horse battery", "Bob")) }
            .assertError(HttpStatusCode.Forbidden, "signup_closed")
    }

    @Test
    fun behindAProxyTheLoginLimitIsPerClient() = apiTest(behindProxy = true) {
        val client = newClient()
        suspend fun loginFrom(ip: String) = client.post("/api/auth/login") {
            header("X-Forwarded-For", ip)
            setBody(LoginRequest("nobody@example.com", "wrong password"))
        }.status

        repeat(20) { assertEquals(HttpStatusCode.Unauthorized, loginFrom("203.0.113.1")) }
        assertEquals(HttpStatusCode.TooManyRequests, loginFrom("203.0.113.1"))
        assertEquals(HttpStatusCode.Unauthorized, loginFrom("203.0.113.2"))
    }
}
