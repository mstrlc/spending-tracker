package dev.spendingtracker.server

import com.zaxxer.hikari.HikariDataSource
import dev.spendingtracker.server.db.connectDatabase
import dev.spendingtracker.server.market.MarketData
import dev.spendingtracker.shared.api.ApiError
import dev.spendingtracker.shared.api.CreateHouseholdRequest
import dev.spendingtracker.shared.api.Household
import dev.spendingtracker.shared.api.Me
import dev.spendingtracker.shared.api.SignupRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.testcontainers.postgresql.PostgreSQLContainer
import kotlin.test.assertEquals

/** One Postgres container and connection pool for the whole test run; tables are truncated per test. */
object TestDatabase {
    private val postgres = PostgreSQLContainer("postgres:17-alpine").apply { start() }

    val dataSource: HikariDataSource by lazy {
        connectDatabase(DbConfig(postgres.jdbcUrl, postgres.username, postgres.password))
    }

    fun reset() {
        dataSource.connection.use { it.createStatement().execute("TRUNCATE users, households, securities, fx_rates, market_fetches CASCADE") }
    }
}

/** Runs [block] against a fresh app and empty database, with [market] standing in for the market-data providers. */
fun apiTest(market: MarketData = FakeMarketData(), block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
    TestDatabase.reset()
    application { module(TestDatabase.dataSource, marketData = market) }
    block()
}

/** A client with its own cookie jar, i.e. one browser / one user. */
fun ApplicationTestBuilder.newClient(): HttpClient = createClient {
    install(ContentNegotiation) { json(apiJson) }
    install(HttpCookies)
    defaultRequest { contentType(ContentType.Application.Json) }
}

suspend fun HttpClient.signup(email: String = "alice@example.com", name: String = "Alice"): Me {
    val response = post("/api/auth/signup") { setBody(SignupRequest(email, "correct horse battery", name)) }
    assertEquals(HttpStatusCode.Created, response.status)
    return response.body()
}

suspend fun HttpClient.createHousehold(name: String = "Home", currency: String = "CZK"): Household {
    val response = post("/api/households") { setBody(CreateHouseholdRequest(name, currency)) }
    assertEquals(HttpStatusCode.Created, response.status)
    return response.body()
}

/** Asserts the response is an [ApiError] with [status] and [code]. */
suspend fun HttpResponse.assertError(status: HttpStatusCode, code: String) {
    assertEquals(status, this.status)
    assertEquals(code, body<ApiError>().code)
}
