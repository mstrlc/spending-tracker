package dev.spendingtracker.server.auth

import dev.spendingtracker.shared.api.ApiError
import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import kotlin.time.Clock
import kotlin.uuid.Uuid

const val SESSION_AUTH = "session"
private const val COOKIE_NAME = "sid"
private const val CHALLENGE_KEY = "SessionAuth"

data class UserPrincipal(val userId: Uuid)

val ApplicationCall.userId: Uuid
    get() = principal<UserPrincipal>()?.userId ?: error("Route is not inside authenticate(SESSION_AUTH)")

fun AuthenticationConfig.sessionCookie(secureCookies: Boolean) {
    provider(SESSION_AUTH) {
        authenticate { context ->
            val token = context.call.request.cookies[COOKIE_NAME]
            val session = token?.let { SessionStore.find(it) }
            if (session == null) {
                context.challenge(CHALLENGE_KEY, AuthenticationFailedCause.NoCredentials) { challenge, call ->
                    call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Log in to continue."))
                    challenge.complete()
                }
            } else {
                // Keep the cookie's lifetime in step with a renewed session.
                context.call.setSessionCookie(token, secureCookies, (session.expiresAt - Clock.System.now()).inWholeSeconds)
                context.principal(UserPrincipal(session.userId))
            }
        }
    }
}

fun ApplicationCall.sessionToken(): String? = request.cookies[COOKIE_NAME]

fun ApplicationCall.setSessionCookie(token: String, secure: Boolean, maxAgeSeconds: Long = SessionStore.LIFETIME.inWholeSeconds) {
    response.cookies.append(
        Cookie(
            name = COOKIE_NAME,
            value = token,
            maxAge = maxAgeSeconds.toInt(),
            path = "/",
            httpOnly = true,
            secure = secure,
            // Lax: the cookie isn't sent on cross-site POSTs, which blocks CSRF on mutating endpoints.
            extensions = mapOf("SameSite" to "Lax"),
        ),
    )
}

fun ApplicationCall.clearSessionCookie(secure: Boolean) = setSessionCookie("", secure, maxAgeSeconds = 0)
