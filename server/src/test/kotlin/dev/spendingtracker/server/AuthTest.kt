package dev.spendingtracker.server

import dev.spendingtracker.server.auth.Tokens
import dev.spendingtracker.server.db.Sessions
import dev.spendingtracker.server.db.dbQuery
import dev.spendingtracker.shared.api.LoginRequest
import dev.spendingtracker.shared.api.Me
import dev.spendingtracker.shared.api.SignupRequest
import dev.spendingtracker.shared.api.TokenResponse
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.update

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
    fun tokenLoginReturnsBearerAndSetsNoCookie() = apiTest {
        newClient().signup("alice@example.com")
        val client = newClient()

        val response = client.post("/api/auth/token") { setBody(LoginRequest("alice@example.com", "correct horse battery")) }
        assertEquals(HttpStatusCode.OK, response.status)
        assertNull(response.headers["Set-Cookie"])
        val token = response.body<TokenResponse>()
        assertEquals("alice@example.com", token.me.email)

        // Same wrong-password answer as /login.
        client.post("/api/auth/token") { setBody(LoginRequest("alice@example.com", "wrong password")) }
            .assertError(HttpStatusCode.Unauthorized, "invalid_credentials")
    }

    @Test
    fun bearerTokenAuthenticatesWithoutACookie() = apiTest {
        newClient().signup("alice@example.com")
        val token = newClient().post("/api/auth/token") { setBody(LoginRequest("alice@example.com", "correct horse battery")) }
            .body<TokenResponse>().token
        val client = newClient()

        val me = client.get("/api/auth/me") { header("Authorization", "Bearer $token") }
        assertEquals(HttpStatusCode.OK, me.status)
        assertEquals("alice@example.com", me.body<Me>().email)
        // A header-authenticated call gets no cookie back, not even a renewal.
        assertNull(me.headers["Set-Cookie"])
        // The scheme name is case-insensitive (RFC 9110).
        assertEquals(HttpStatusCode.OK, client.get("/api/auth/me") { header("Authorization", "bearer $token") }.status)
    }

    @Test
    fun badOrExpiredBearerGetsUnauthorized() = apiTest {
        newClient().signup("alice@example.com")
        val client = newClient()

        client.get("/api/auth/me") { header("Authorization", "Bearer not-a-real-token") }
            .assertError(HttpStatusCode.Unauthorized, "unauthorized")
        // A malformed header is just no credentials, never a 500.
        client.get("/api/auth/me") { header("Authorization", "Bearer a b") }
            .assertError(HttpStatusCode.Unauthorized, "unauthorized")
        val unknown = Tokens.generate().first
        client.get("/api/auth/me") { header("Authorization", "Bearer $unknown") }
            .assertError(HttpStatusCode.Unauthorized, "unauthorized")

        val token = client.post("/api/auth/token") { setBody(LoginRequest("alice@example.com", "correct horse battery")) }
            .body<TokenResponse>().token
        dbQuery {
            Sessions.update({ Sessions.tokenHash eq Tokens.hash(token)!! }) { it[expiresAt] = Clock.System.now() - 1.days }
        }
        client.get("/api/auth/me") { header("Authorization", "Bearer $token") }
            .assertError(HttpStatusCode.Unauthorized, "unauthorized")
    }

    @Test
    fun logoutWithBearerKillsTheSession() = apiTest {
        newClient().signup("alice@example.com")
        val client = newClient()
        val token = client.post("/api/auth/token") { setBody(LoginRequest("alice@example.com", "correct horse battery")) }
            .body<TokenResponse>().token

        assertEquals(HttpStatusCode.OK, client.get("/api/auth/me") { header("Authorization", "Bearer $token") }.status)
        assertEquals(HttpStatusCode.NoContent, client.post("/api/auth/logout") { header("Authorization", "Bearer $token") }.status)
        client.get("/api/auth/me") { header("Authorization", "Bearer $token") }
            .assertError(HttpStatusCode.Unauthorized, "unauthorized")
    }

    @Test
    fun tokenLoginSharesTheLoginRateLimit() = apiTest {
        val client = newClient()
        repeat(20) {
            client.post("/api/auth/token") { setBody(LoginRequest("nobody@example.com", "wrong password")) }
                .assertError(HttpStatusCode.Unauthorized, "invalid_credentials")
        }
        assertEquals(
            HttpStatusCode.TooManyRequests,
            client.post("/api/auth/token") { setBody(LoginRequest("nobody@example.com", "wrong password")) }.status,
        )
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
