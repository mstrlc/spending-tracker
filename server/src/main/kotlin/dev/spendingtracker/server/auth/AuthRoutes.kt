package dev.spendingtracker.server.auth

import dev.spendingtracker.server.common.ApiException
import dev.spendingtracker.server.common.badRequest
import dev.spendingtracker.server.common.conflict
import dev.spendingtracker.server.common.forbidden
import dev.spendingtracker.server.common.requiredText
import dev.spendingtracker.server.db.Users
import dev.spendingtracker.server.db.dbQuery
import dev.spendingtracker.server.db.isUniqueViolation
import dev.spendingtracker.shared.api.ApiError
import dev.spendingtracker.shared.api.ChangePasswordRequest
import dev.spendingtracker.shared.api.LoginRequest
import dev.spendingtracker.shared.api.Me
import dev.spendingtracker.shared.api.SignupRequest
import dev.spendingtracker.shared.api.TokenResponse
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

val AUTH_RATE_LIMIT = RateLimitName("auth")

fun Route.authRoutes(secureCookies: Boolean, signupOpen: Boolean = true) = route("/auth") {
    rateLimit(AUTH_RATE_LIMIT) {
        post("/signup", {
            operationId = "signup"
            request { body<SignupRequest>() }
            response {
                code(HttpStatusCode.Created) { body<Me>() }
                code(HttpStatusCode.BadRequest) { body<ApiError>() }
                code(HttpStatusCode.Forbidden) { body<ApiError>() }
                code(HttpStatusCode.Conflict) { body<ApiError>() }
            }
        }) {
            if (!signupOpen) throw forbidden("signup_closed", "New accounts can't be created here")
            val body = call.receive<SignupRequest>()
            val email = normalizeEmail(body.email)
            val displayName = requiredText(body.displayName, "displayName", max = 100)
            Passwords.requireValid(body.password)
            val hash = Passwords.hash(body.password)
            val id = Uuid.random()
            try {
                dbQuery {
                    Users.insert {
                        it[Users.id] = id
                        it[Users.email] = email
                        it[passwordHash] = hash
                        it[Users.displayName] = displayName
                    }
                }
            } catch (e: Exception) {
                if (e.isUniqueViolation()) throw conflict("email_taken", "An account with this email already exists", "email")
                throw e
            }
            call.setSessionCookie(SessionStore.create(id), secureCookies)
            call.respond(HttpStatusCode.Created, Me(id.toString(), email, displayName))
        }

        post("/login", {
            operationId = "login"
            request { body<LoginRequest>() }
            response {
                code(HttpStatusCode.OK) { body<Me>() }
                code(HttpStatusCode.Unauthorized) { body<ApiError>() }
            }
        }) {
            val row = call.checkCredentials(call.receive<LoginRequest>())
            call.setSessionCookie(SessionStore.create(row[Users.id]), secureCookies)
            call.respond(row.toMe())
        }

        post("/token", {
            operationId = "loginToken"
            description = "Logs in and returns a bearer token instead of a session cookie, for mobile clients."
            request { body<LoginRequest>() }
            response {
                code(HttpStatusCode.OK) { body<TokenResponse>() }
                code(HttpStatusCode.Unauthorized) { body<ApiError>() }
            }
        }) {
            val row = call.checkCredentials(call.receive<LoginRequest>())
            val token = SessionStore.create(row[Users.id])
            call.respond(TokenResponse(token, row.toMe()))
        }
    }

    post("/logout", {
        operationId = "logout"
        response { code(HttpStatusCode.NoContent) {} }
    }) {
        call.sessionToken()?.let { SessionStore.delete(it) }
        call.clearSessionCookie(secureCookies)
        call.respond(HttpStatusCode.NoContent)
    }

    authenticate(SESSION_AUTH) {
        get("/me", {
            operationId = "getMe"
            response {
                code(HttpStatusCode.OK) { body<Me>() }
                code(HttpStatusCode.Unauthorized) { body<ApiError>() }
            }
        }) {
            val me = dbQuery { Users.selectAll().where { Users.id eq call.userId }.single().toMe() }
            call.respond(me)
        }

        rateLimit(AUTH_RATE_LIMIT) {
            post("/password", {
                operationId = "changePassword"
                description = "Changes your own password. Signs out your other sessions; this one stays."
                request { body<ChangePasswordRequest>() }
                response {
                    code(HttpStatusCode.NoContent) {}
                    code(HttpStatusCode.BadRequest) { body<ApiError>() }
                    code(HttpStatusCode.Unauthorized) { body<ApiError>() }
                }
            }) {
                val body = call.receive<ChangePasswordRequest>()
                val userId = call.userId
                val current = dbQuery { Users.selectAll().where { Users.id eq userId }.single()[Users.passwordHash] }
                if (!Passwords.verify(current, body.currentPassword)) {
                    throw badRequest("wrong_password", "That isn't your current password", "currentPassword")
                }
                Passwords.requireValid(body.newPassword, "newPassword")
                val hash = Passwords.hash(body.newPassword)
                dbQuery { Users.update({ Users.id eq userId }) { it[passwordHash] = hash } }
                SessionStore.deleteAllFor(userId, keepToken = call.sessionToken())
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

// Same wrong-password response for /login and /token, so the two don't leak which exists.
private suspend fun ApplicationCall.checkCredentials(body: LoginRequest): ResultRow {
    val email = body.email.trim().lowercase()
    val row = dbQuery { Users.selectAll().where { Users.email eq email }.singleOrNull() }
    if (!Passwords.verify(row?.get(Users.passwordHash), body.password)) {
        throw ApiException(HttpStatusCode.Unauthorized, "invalid_credentials", "Wrong email or password")
    }
    return row!!
}

internal fun normalizeEmail(raw: String): String {
    val email = raw.trim().lowercase()
    // Deliberately loose: real validation is a confirmation email (not built yet).
    if (email.length > 254 || !Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(email)) {
        throw badRequest("invalid_email", "Enter a valid email address", "email")
    }
    return email
}

// Only users with a login have sessions, so the email is there.
private fun ResultRow.toMe() = Me(this[Users.id].toString(), this[Users.email]!!, this[Users.displayName])
