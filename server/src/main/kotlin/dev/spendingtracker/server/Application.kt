package dev.spendingtracker.server

import io.github.smiley4.ktoropenapi.OpenApi
import io.github.smiley4.ktoropenapi.config.SchemaGenerator
import io.github.smiley4.ktoropenapi.openApi
import io.github.smiley4.ktorswaggerui.swaggerUI
import io.github.smiley4.schemakenerator.swagger.data.RefType
import io.github.smiley4.schemakenerator.swagger.data.TitleType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.sql.DataSource

fun main() {
    val config = Config.fromEnv()
    val dataSource = connectDatabase(config.db)
    embeddedServer(Netty, port = config.port) { module(dataSource) }.start(wait = true)
}

val apiJson = Json { ignoreUnknownKeys = true }

fun Application.module(dataSource: DataSource) {
    install(CallLogging)
    install(ContentNegotiation) { json(apiJson) }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("Unhandled error", cause)
            call.respond(HttpStatusCode.InternalServerError, ApiError("internal_error"))
        }
    }
    install(OpenApi) {
        info {
            title = "Spending Tracker API"
            version = "0.1.0"
        }
        schemas {
            // Generate schemas from the same serializers the API uses, named "Health"
            // rather than "dev.spendingtracker.server.Health" so the generated TS types stay readable.
            generator = SchemaGenerator.kotlinx(apiJson) {
                title = TitleType.SIMPLE
                referencePath = RefType.OPENAPI_SIMPLE
            }
        }
    }

    routing {
        route("/api") {
            route("/openapi.json") { openApi() }
            route("/docs") { swaggerUI("/api/openapi.json") }
            healthRoutes(dataSource)
        }
    }
}

@Serializable
data class ApiError(val code: String)
