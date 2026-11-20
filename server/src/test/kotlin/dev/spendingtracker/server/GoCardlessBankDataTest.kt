package dev.spendingtracker.server

import dev.spendingtracker.server.bank.BankDataException
import dev.spendingtracker.server.bank.GoCardlessBankData
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoCardlessBankDataTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    /** A GoCardless that hands out token-1, token-2, … (new or refreshed) and answers other calls through [answer]. */
    private fun gocardless(answer: MockRequestHandleScope.(HttpRequestData, String) -> HttpResponseData): Pair<GoCardlessBankData, List<String>> {
        var tokens = 0
        val calls = mutableListOf<String>()
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            if (path.endsWith("/token/new/")) {
                tokens++
                respond("""{"access":"token-$tokens","access_expires":86400,"refresh":"refresh-$tokens","refresh_expires":2592000}""", HttpStatusCode.OK, json)
            } else if (path.endsWith("/token/refresh/")) {
                tokens++
                respond("""{"access":"token-$tokens","access_expires":86400}""", HttpStatusCode.OK, json)
            } else {
                val token = request.headers[HttpHeaders.Authorization].orEmpty().removePrefix("Bearer ")
                calls += "$path $token"
                answer(request, token)
            }
        }
        return GoCardlessBankData("id", "key", HttpClient(engine), baseUrl = "https://gc.example/api/v2") to calls
    }

    private val balances = """{"balances":[{"balanceAmount":{"amount":"12.34","currency":"CZK"},"balanceType":"closingBooked"}]}"""

    @Test
    fun aRejectedTokenIsRenewedOnceBeforeTheConsentIsBlamed(): Unit = runBlocking {
        val (bank, calls) = gocardless { _, token ->
            if (token == "token-1") respond("""{"summary":"Invalid token"}""", HttpStatusCode.Unauthorized, json)
            else respond(balances, HttpStatusCode.OK, json)
        }
        assertEquals(1234L, bank.balance("acc")?.amountMinor)
        assertEquals(listOf("/api/v2/accounts/acc/balances/ token-1", "/api/v2/accounts/acc/balances/ token-2"), calls)
    }

    @Test
    fun a401WithAFreshTokenMeansTheConsentIsGone(): Unit = runBlocking {
        val (bank, calls) = gocardless { _, _ -> respond("""{"summary":"Access expired"}""", HttpStatusCode.Unauthorized, json) }
        val e = assertFailsWith<BankDataException> { bank.balance("acc") }
        assertTrue(e.consentLapsed)
        assertEquals(2, calls.size)
    }

    @Test
    fun aRateLimitIsNotALapsedConsent(): Unit = runBlocking {
        val (bank, calls) = gocardless { _, _ -> respond("""{"summary":"Rate limit exceeded"}""", HttpStatusCode.TooManyRequests, json) }
        val e = assertFailsWith<BankDataException> { bank.balance("acc") }
        assertEquals(429, e.status)
        assertFalse(e.consentLapsed)
        assertEquals(1, calls.size)
    }

    @Test
    fun aNetworkFailureIsABankError(): Unit = runBlocking {
        val (bank, _) = gocardless { _, _ -> throw IOException("connection reset") }
        val e = assertFailsWith<BankDataException> { bank.balance("acc") }
        assertEquals(504, e.status)
        assertFalse(e.consentLapsed)
    }
}
