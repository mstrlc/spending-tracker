package dev.spendingtracker.shared.client

import dev.spendingtracker.shared.api.ApiError
import dev.spendingtracker.shared.api.Me
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApiClientTest {

    private class FakeTokenStore(var token: String? = "token-1") : TokenStore {
        override suspend fun get(): String? = token
        override suspend fun set(token: String) { this.token = token }
        override suspend fun clear() { token = null }
    }

    private val meJson = """{"id":"u1","email":"a@b.c","displayName":"A"}"""

    @Test
    fun sendsTheBearerHeaderFromTheTokenStore() = runTest {
        var authorization: String? = null
        val store = FakeTokenStore()
        val api = ApiClient("http://localhost:8080", store, MockEngine { request ->
            authorization = request.headers[HttpHeaders.Authorization]
            respond(meJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val me = api.get<Me>("/api/auth/me")
        assertEquals("Bearer token-1", authorization)
        assertEquals("u1", me.id)
    }

    @Test
    fun sendsNoHeaderWithoutAToken() = runTest {
        var authorization: String? = "unset"
        val api = ApiClient("http://localhost:8080", FakeTokenStore(null), MockEngine { request ->
            authorization = request.headers[HttpHeaders.Authorization]
            respond(meJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        api.get<Me>("/api/auth/me")
        assertNull(authorization)
    }

    @Test
    fun mapsANon2xxBodyToApiException() = runTest {
        val api = ApiClient("http://localhost:8080", FakeTokenStore(), MockEngine {
            respond(
                """{"code":"invalid_input","message":"Amount is required","field":"amountMinor"}""",
                HttpStatusCode.BadRequest,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val e = assertFailsWith<ApiException> { api.get<Me>("/api/auth/me") }
        assertEquals(400, e.status)
        assertEquals("invalid_input", e.error.code)
        assertEquals("amountMinor", e.error.field)
    }

    @Test
    fun a401ClearsTheStoredToken() = runTest {
        val store = FakeTokenStore()
        val api = ApiClient("http://localhost:8080", store, MockEngine {
            respond(
                """{"code":"unauthorized","message":"Session expired"}""",
                HttpStatusCode.Unauthorized,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val e = assertFailsWith<ApiException> { api.get<Me>("/api/auth/me") }
        assertEquals(401, e.status)
        assertEquals("unauthorized", e.error.code)
        assertNull(store.token)
    }

    @Test
    fun aVersionConflictSurfacesAsItsOwnError() = runTest {
        val api = ApiClient("http://localhost:8080", FakeTokenStore(), MockEngine {
            respond(
                """{"code":"version_conflict","message":"Expense was changed elsewhere"}""",
                HttpStatusCode.Conflict,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val e = assertFailsWith<ApiException> { api.delete("/api/households/h1/expenses/e1") }
        assertEquals(409, e.status)
        assertEquals("version_conflict", e.error.code)
        assertTrue(e.message!!.contains("version_conflict"))
    }

    @Test
    fun unknownErrorFieldsStillDecode() = runTest {
        val api = ApiClient("http://localhost:8080", FakeTokenStore(), MockEngine {
            respond(
                """{"code":"boom","message":"x","somethingNew":42}""",
                HttpStatusCode.InternalServerError,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val e = assertFailsWith<ApiException> { api.get<Me>("/api/auth/me") }
        assertEquals(ApiError("boom", "x"), e.error)
    }
}
