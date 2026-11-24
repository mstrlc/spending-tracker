package dev.spendingtracker.server.auth

import dev.spendingtracker.shared.api.ApiError
import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.http.auth.parseAuthorizationHeader
import io.ktor.http.parsing.ParseException
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
            // Mobile clients send the session as a bearer token; browsers use the cookie.
            val bearer = context.call.bearerToken()
            val token = bearer ?: context.call.request.cookies[COOKIE_NAME]
            val session = token?.let { SessionStore.find(it) }
            if (session == null) {
                context.challenge(CHALLENGE_KEY, AuthenticationFailedCause.NoCredentials) { challenge, call ->
                    call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Log in to continue."))
                    challenge.complete()
                }
            } else {
                // Keep the cookie's lifetime in step with a renewed session; a bearer token
                // is held by the client, so renewing must not set a cookie for it.
                if (bearer == null) {
                    context.call.setSessionCookie(token, secureCookies, (session.expiresAt - Clock.System.now()).inWholeSeconds)
                }
                context.principal(UserPrincipal(session.userId))
            }
        }
    }
}

/** The `Authorization: Bearer <token>` header's token, or null when absent or malformed. The scheme name is case-insensitive per RFC 9110. */
fun ApplicationCall.bearerToken(): String? {
    val raw = request.headers["Authorization"] ?: return null
    // A malformed header must not become a 500; it just carries no credentials.
    val header = try {
        parseAuthorizationHeader(raw)
    } catch (e: ParseException) {
        return null
    } ?: return null
    if (!header.authScheme.equals("Bearer", ignoreCase = true)) return null
    return (header as? HttpAuthHeader.Single)?.blob?.ifBlank { null }
}

/** The session cookie's token, or null when the call carries no cookie. */
fun ApplicationCall.cookieToken(): String? = request.cookies[COOKIE_NAME]

/** The session token of this call, from the bearer header first, else the cookie. */
fun ApplicationCall.sessionToken(): String? = bearerToken() ?: cookieToken()

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
