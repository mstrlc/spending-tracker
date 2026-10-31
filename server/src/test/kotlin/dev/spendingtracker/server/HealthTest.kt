package dev.spendingtracker.server

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class HealthTest {
    @Test
    fun healthReportsDatabaseUp() = apiTest {
        val response = newClient().get("/api/health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(Health(status = "ok", database = "up"), response.body<Health>())
    }

    @Test
    fun servesOpenApiSpecWithSimpleSchemaNames() = apiTest {
        val spec = client.get("/api/openapi.json").bodyAsText()

        assertContains(spec, "getHealth")
        assertContains(spec, "\"#/components/schemas/Expense\"")
    }
}
