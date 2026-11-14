package dev.spendingtracker.server.household

import dev.spendingtracker.server.auth.AUTH_RATE_LIMIT
import dev.spendingtracker.server.auth.SESSION_AUTH
import dev.spendingtracker.server.auth.Tokens
import dev.spendingtracker.server.auth.normalizeEmail
import dev.spendingtracker.server.auth.userId
import dev.spendingtracker.server.common.ApiException
import dev.spendingtracker.server.common.conflict
import dev.spendingtracker.server.common.forbidden
import dev.spendingtracker.server.common.notFound
import dev.spendingtracker.server.common.parseId
import dev.spendingtracker.server.common.pathId
import dev.spendingtracker.server.common.badRequest
import dev.spendingtracker.server.db.HouseholdInvites
import dev.spendingtracker.server.db.HouseholdMembers
import dev.spendingtracker.server.db.Households
import dev.spendingtracker.server.db.Users
import dev.spendingtracker.server.db.dbQuery
import dev.spendingtracker.shared.api.ApiError
import dev.spendingtracker.shared.api.CreateInviteRequest
import dev.spendingtracker.shared.api.Household
import dev.spendingtracker.shared.api.Invite
import dev.spendingtracker.shared.api.InvitePreview
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.github.smiley4.ktoropenapi.route
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

val INVITE_LIFETIME = 7.days

/** The member without a login an invite hands over, joined for their name. */
private val Claimed = Users.alias("claimed")

/** Invites with their inviter and claimed member, for [toInvite]. */
private fun invitesWithPeople() = HouseholdInvites
    .join(Users, JoinType.LEFT, HouseholdInvites.invitedBy, Users.id)
    .join(Claimed, JoinType.LEFT, HouseholdInvites.claimUserId, Claimed[Users.id])

/** Owner-side invite management. Must be inside a HouseholdAccess scope. */
fun Route.householdInviteRoutes() = route("/invites") {
    get({
        operationId = "listInvites"
        description = "Invites not yet accepted, including expired ones. Owners only."
        response {
            code(HttpStatusCode.OK) { body<List<Invite>>() }
            code(HttpStatusCode.Forbidden) { body<ApiError>() }
        }
    }) {
        call.requireOwner()
        val invites = dbQuery {
            invitesWithPeople()
                .selectAll()
                .where { (HouseholdInvites.householdId eq call.household.id) and HouseholdInvites.acceptedAt.isNull() }
                .orderBy(HouseholdInvites.createdAt to SortOrder.DESC)
                .map { it.toInvite() }
        }
        call.respond(invites)
    }

    post({
        operationId = "createInvite"
        description = "Invites an email address to the household. Replaces any open invite for the same address. " +
            "With claimUserId (a member without a login), whoever accepts takes that member's place. " +
            "The response's token is shown only once; share it as a link."
        request { body<CreateInviteRequest>() }
        response {
            code(HttpStatusCode.Created) { body<Invite>() }
            code(HttpStatusCode.BadRequest) { body<ApiError>() }
            code(HttpStatusCode.Forbidden) { body<ApiError>() }
            code(HttpStatusCode.Conflict) { body<ApiError>() }
        }
    }) {
        call.requireOwner()
        val body = call.receive<CreateInviteRequest>()
        val email = normalizeEmail(body.email)
        val claimUserId = parseId(body.claimUserId, "claimUserId")
        val householdId = call.household.id
        val (token, hash) = Tokens.generate()
        val id = Uuid.random()
        val invite = dbQuery {
            val alreadyMember = !HouseholdMembers
                .join(Users, JoinType.INNER, HouseholdMembers.userId, Users.id)
                .selectAll()
                .where { (HouseholdMembers.householdId eq householdId) and (Users.email eq email) }
                .empty()
            if (alreadyMember) throw conflict("already_member", "$email is already a member", "email")
            if (claimUserId != null) {
                val claimable = !HouseholdMembers
                    .join(Users, JoinType.INNER, HouseholdMembers.userId, Users.id)
                    .selectAll()
                    .where { (HouseholdMembers.householdId eq householdId) and (HouseholdMembers.userId eq claimUserId) and Users.email.isNull() }
                    .empty()
                if (!claimable) throw badRequest("invalid_claim", "Pick a member without a login", "claimUserId")
            }
            HouseholdInvites.deleteWhere {
                (HouseholdInvites.householdId eq householdId) and (HouseholdInvites.email eq email) and acceptedAt.isNull()
            }
            HouseholdInvites.insert {
                it[HouseholdInvites.id] = id
                it[HouseholdInvites.householdId] = householdId
                it[tokenHash] = hash
                it[HouseholdInvites.email] = email
                it[role] = body.role.dbValue()
                it[invitedBy] = call.userId
                it[expiresAt] = Clock.System.now() + INVITE_LIFETIME
                it[HouseholdInvites.claimUserId] = claimUserId
            }
            invitesWithPeople()
                .selectAll()
                .where { HouseholdInvites.id eq id }
                .single()
                .toInvite()
        }
        call.respond(HttpStatusCode.Created, invite.copy(token = token))
    }

    delete("/{inviteId}", {
        operationId = "revokeInvite"
        request { pathParameter<String>("inviteId") }
        response {
            code(HttpStatusCode.NoContent) {}
            code(HttpStatusCode.Forbidden) { body<ApiError>() }
            code(HttpStatusCode.NotFound) { body<ApiError>() }
        }
    }) {
        call.requireOwner()
        val id = call.pathId("inviteId", "Invite")
        val deleted = dbQuery {
            HouseholdInvites.deleteWhere {
                (HouseholdInvites.id eq id) and (householdId eq call.household.id) and acceptedAt.isNull()
            }
        }
        if (deleted == 0) throw notFound("Invite")
        call.respond(HttpStatusCode.NoContent)
    }
}

/** The invitee's side, addressed by the token from the link. Previewing needs no session; accepting does. */
fun Route.inviteLinkRoutes() = route("/invites/{token}", {
    request { pathParameter<String>("token") }
}) {
    rateLimit(AUTH_RATE_LIMIT) {
        get({
            operationId = "previewInvite"
            response {
                code(HttpStatusCode.OK) { body<InvitePreview>() }
                code(HttpStatusCode.NotFound) { body<ApiError>() }
                code(HttpStatusCode.Gone) { body<ApiError>() }
            }
        }) {
            val preview = dbQuery {
                val row = openInvite(call)
                InvitePreview(
                    householdName = row[Households.name],
                    invitedBy = row.getOrNull(Users.displayName),
                    email = row[HouseholdInvites.email],
                    role = roleFrom(row[HouseholdInvites.role]),
                    expiresAt = row[HouseholdInvites.expiresAt].toString(),
                    claimName = row.getOrNull(Claimed[Users.displayName]),
                )
            }
            call.respond(preview)
        }
    }

    authenticate(SESSION_AUTH) {
        post("/accept", {
            operationId = "acceptInvite"
            description = "Joins the household. The logged-in user's email must match the invite's. " +
                "An invite that claims a member without a login puts the user in that member's place: " +
                "everything that belonged to the member is theirs."
            response {
                code(HttpStatusCode.OK) { body<Household>() }
                code(HttpStatusCode.Forbidden) { body<ApiError>() }
                code(HttpStatusCode.NotFound) { body<ApiError>() }
                code(HttpStatusCode.Gone) { body<ApiError>() }
            }
        }) {
            val userId = call.userId
            val household = dbQuery {
                val row = openInvite(call)
                val householdId = row[HouseholdInvites.householdId]
                val email = Users.selectAll().where { Users.id eq userId }.single()[Users.email]
                if (email == null || !email.equals(row[HouseholdInvites.email], ignoreCase = true)) {
                    throw forbidden("invite_email_mismatch", "This invite is for ${row[HouseholdInvites.email]}. Log in with that address to accept it.")
                }
                val claim = row[HouseholdInvites.claimUserId]
                if (claim != null) {
                    if (isMember(householdId, userId)) {
                        throw conflict("already_member", "You're already in this household, so you can't take another member's place.")
                    }
                    // Every reference to the member cascades to the new user id (V10), then the stand-in goes.
                    HouseholdMembers.update({ (HouseholdMembers.householdId eq householdId) and (HouseholdMembers.userId eq claim) }) {
                        it[HouseholdMembers.userId] = userId
                        it[role] = row[HouseholdInvites.role]
                    }
                    Users.deleteWhere { (Users.id eq claim) and Users.email.isNull() }
                } else if (!isMember(householdId, userId)) {
                    HouseholdMembers.insert {
                        it[HouseholdMembers.householdId] = householdId
                        it[HouseholdMembers.userId] = userId
                        it[role] = row[HouseholdInvites.role]
                    }
                }
                HouseholdInvites.update({ HouseholdInvites.id eq row[HouseholdInvites.id] }) {
                    it[acceptedAt] = Clock.System.now()
                    it[acceptedBy] = userId
                }
                val role = HouseholdMembers.selectAll()
                    .where { (HouseholdMembers.householdId eq householdId) and (HouseholdMembers.userId eq userId) }
                    .single()[HouseholdMembers.role]
                Household(householdId.toString(), row[Households.name], row[Households.currency], roleFrom(role))
            }
            call.respond(household)
        }
    }
}

/** The unaccepted, unexpired invite for the path's token, joined with its household and inviter. Call inside a transaction. */
private fun openInvite(call: RoutingCall): ResultRow {
    val hash = call.parameters["token"]?.let(Tokens::hash) ?: throw inviteNotFound()
    val row = HouseholdInvites
        .join(Households, JoinType.INNER, HouseholdInvites.householdId, Households.id)
        .join(Users, JoinType.LEFT, HouseholdInvites.invitedBy, Users.id)
        .join(Claimed, JoinType.LEFT, HouseholdInvites.claimUserId, Claimed[Users.id])
        .selectAll()
        .where { (HouseholdInvites.tokenHash eq hash) and HouseholdInvites.acceptedAt.isNull() }
        .singleOrNull() ?: throw inviteNotFound()
    if (row[HouseholdInvites.expiresAt] <= Clock.System.now()) {
        throw ApiException(HttpStatusCode.Gone, "invite_expired", "This invite has expired. Ask for a new one.")
    }
    return row
}

private fun inviteNotFound() =
    ApiException(HttpStatusCode.NotFound, "invite_not_found", "This invite link is invalid or has already been used.")

private fun ResultRow.toInvite() = Invite(
    id = this[HouseholdInvites.id].toString(),
    email = this[HouseholdInvites.email],
    role = roleFrom(this[HouseholdInvites.role]),
    invitedBy = getOrNull(Users.displayName),
    createdAt = this[HouseholdInvites.createdAt].toString(),
    expiresAt = this[HouseholdInvites.expiresAt].toString(),
    claimUserId = this[HouseholdInvites.claimUserId]?.toString(),
    claimName = getOrNull(Claimed[Users.displayName]),
)
