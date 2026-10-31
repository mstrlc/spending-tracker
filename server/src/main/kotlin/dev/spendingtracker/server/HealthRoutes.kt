package dev.spendingtracker.server

import io.github.smiley4.ktoropenapi.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import javax.sql.DataSource

@Serializable
data class Health(val status: String, val database: String)

fun Route.healthRoutes(dataSource: DataSource) {
    get("/health", {
        operationId = "getHealth"
        description = "Reports whether the API and its database are reachable."
        response {
            code(HttpStatusCode.OK) { body<Health>() }
            code(HttpStatusCode.ServiceUnavailable) { body<Health>() }
        }
    }) {
        val dbUp = withContext(Dispatchers.IO) {
            runCatching { dataSource.connection.use { it.isValid(2) } }.getOrDefault(false)
        }
        if (dbUp) {
            call.respond(Health(status = "ok", database = "up"))
        } else {
            call.respond(HttpStatusCode.ServiceUnavailable, Health(status = "degraded", database = "down"))
        }
    }
}
