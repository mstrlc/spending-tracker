package dev.spendingtracker.server.common

import io.ktor.server.routing.RoutingCall
import kotlin.uuid.Uuid

/** Trims [value] and checks it is non-blank and at most [max] characters. */
fun requiredText(value: String, field: String, max: Int = 200): String {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) throw badRequest("required", "$field is required", field)
    return maxLength(trimmed, field, max)
}

/** Trims [value] (which may be empty) and checks it is at most [max] characters. */
fun optionalText(value: String, field: String, max: Int = 2000): String = maxLength(value.trim(), field, max)

private fun maxLength(value: String, field: String, max: Int): String {
    if (value.length > max) throw badRequest("too_long", "$field must be at most $max characters", field)
    return value
}

/** Parses an id from a request body or query; malformed ids are a 400. */
fun parseId(value: String?, field: String): Uuid? =
    value?.let { runCatching { Uuid.parse(it) }.getOrElse { throw badRequest("invalid_id", "$field is not a valid id", field) } }

/** Reads an id path parameter; malformed ids are a 404, since no such resource can exist. */
fun RoutingCall.pathId(name: String, what: String): Uuid =
    parameters[name]?.let { runCatching { Uuid.parse(it) }.getOrNull() } ?: throw notFound(what)

fun requireVersion(version: Int?): Int = version ?: throw badRequest("version_required", "version is required when updating", "version")
