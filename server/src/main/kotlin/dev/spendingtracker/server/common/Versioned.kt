package dev.spendingtracker.server.common

import io.ktor.server.routing.RoutingCall

/** Reads the required ?version= query parameter of a versioned DELETE. */
fun RoutingCall.versionParam(): Int =
    request.queryParameters["version"]?.toIntOrNull()
        ?: throw badRequest("version_required", "version query parameter is required", "version")

/**
 * Turns the row count of an optimistic-concurrency UPDATE/DELETE (matched on id, household and version)
 * into an error: no row because it doesn't exist → 404, because the version moved on → 409.
 */
fun checkVersioned(affected: Int, exists: () -> Boolean, what: String) {
    if (affected == 0) throw if (exists()) versionConflict() else notFound(what)
}
