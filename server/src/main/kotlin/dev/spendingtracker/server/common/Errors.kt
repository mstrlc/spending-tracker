package dev.spendingtracker.server.common

import dev.spendingtracker.shared.api.ApiError
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.response.respond

/** Thrown anywhere in request handling; rendered as an [ApiError] with [status]. */
class ApiException(
    val status: HttpStatusCode,
    val code: String,
    override val message: String? = null,
    val field: String? = null,
) : RuntimeException(message)

fun badRequest(code: String, message: String? = null, field: String? = null) =
    ApiException(HttpStatusCode.BadRequest, code, message, field)

fun notFound(what: String) = ApiException(HttpStatusCode.NotFound, "not_found", "$what not found")

fun conflict(code: String, message: String? = null, field: String? = null) =
    ApiException(HttpStatusCode.Conflict, code, message, field)

fun versionConflict() =
    conflict("version_conflict", "This record was changed by someone else. Reload and try again.")

fun StatusPagesConfig.apiErrors() {
    exception<ApiException> { call, e ->
        call.respond(e.status, ApiError(e.code, e.message, e.field))
    }
    exception<BadRequestException> { call, e ->
        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_body", e.cause?.message ?: e.message))
    }
    exception<Throwable> { call, cause ->
        call.application.environment.log.error("Unhandled error", cause)
        call.respond(HttpStatusCode.InternalServerError, ApiError("internal_error"))
    }
}

fun forbidden(code: String, message: String? = null) = ApiException(HttpStatusCode.Forbidden, code, message)
