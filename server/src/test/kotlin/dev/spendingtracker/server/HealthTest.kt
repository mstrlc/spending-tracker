package dev.spendingtracker.server

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import org.testcontainers.postgresql.PostgreSQLContainer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import com.zaxxer.hikari.HikariDataSource

class HealthTest {
    private val postgres = PostgreSQLContainer("postgres:17-alpine")
    private lateinit var dataSource: HikariDataSource

    @BeforeTest
    fun setUp() {
        postgres.start()
        dataSource = connectDatabase(DbConfig(postgres.jdbcUrl, postgres.username, postgres.password))
    }

    @AfterTest
    fun tearDown() {
        dataSource.close()
        postgres.stop()
    }

    @Test
    fun healthReportsDatabaseUp() = testApplication {
        application { module(dataSource) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.get("/api/health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(Health(status = "ok", database = "up"), response.body<Health>())
    }

    @Test
    fun servesOpenApiSpecWithHealthOperation() = testApplication {
        application { module(dataSource) }

        val spec = client.get("/api/openapi.json").bodyAsText()

        assertContains(spec, "getHealth")
    }
}
