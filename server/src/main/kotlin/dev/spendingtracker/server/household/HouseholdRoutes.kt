package dev.spendingtracker.server.household

import dev.spendingtracker.server.account.accountRoutes
import dev.spendingtracker.server.auth.userId
import dev.spendingtracker.server.category.categoryRoutes
import dev.spendingtracker.server.category.seedDefaultCategories
import dev.spendingtracker.server.common.badRequest
import dev.spendingtracker.server.common.conflict
import dev.spendingtracker.server.common.notFound
import dev.spendingtracker.server.common.pathId
import dev.spendingtracker.server.common.requiredText
import dev.spendingtracker.server.db.HouseholdMembers
import dev.spendingtracker.server.db.Households
import dev.spendingtracker.server.db.Users
import dev.spendingtracker.server.db.dbQuery
import dev.spendingtracker.server.expense.expenseRoutes
import dev.spendingtracker.server.income.incomeRoutes
import dev.spendingtracker.shared.api.ApiError
import dev.spendingtracker.shared.api.CreateHouseholdRequest
import dev.spendingtracker.shared.api.Household
import dev.spendingtracker.shared.api.HouseholdRole
import dev.spendingtracker.shared.api.Member
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.github.smiley4.ktoropenapi.route
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.Currency
import kotlin.uuid.Uuid

/** Must be inside authenticate(SESSION_AUTH). */
fun Route.householdRoutes() = route("/households") {
    get({
        operationId = "listHouseholds"
        description = "Households the current user belongs to."
        response { code(HttpStatusCode.OK) { body<List<Household>>() } }
    }) {
        val households = dbQuery {
            HouseholdMembers
                .join(Households, JoinType.INNER, HouseholdMembers.householdId, Households.id)
                .selectAll()
                .where { HouseholdMembers.userId eq call.userId }
                .orderBy(Households.createdAt to SortOrder.ASC)
                .map {
                    Household(
                        id = it[Households.id].toString(),
                        name = it[Households.name],
                        currency = it[Households.currency],
                        role = roleFrom(it[HouseholdMembers.role]),
                    )
                }
        }
        call.respond(households)
    }

    post({
        operationId = "createHousehold"
        description = "Creates a household owned by the current user, with a starter set of categories."
        request { body<CreateHouseholdRequest>() }
        response {
            code(HttpStatusCode.Created) { body<Household>() }
            code(HttpStatusCode.BadRequest) { body<ApiError>() }
        }
    }) {
        val body = call.receive<CreateHouseholdRequest>()
        val name = requiredText(body.name, "name", max = 100)
        val currency = body.currency.trim().uppercase()
        if (runCatching { Currency.getInstance(currency) }.isFailure) {
            throw badRequest("invalid_currency", "Unknown currency code", "currency")
        }
        val id = Uuid.random()
        dbQuery {
            Households.insert {
                it[Households.id] = id
                it[Households.name] = name
                it[Households.currency] = currency
            }
            HouseholdMembers.insert {
                it[householdId] = id
                it[userId] = call.userId
                it[role] = "owner"
            }
            seedDefaultCategories(id)
        }
        call.respond(HttpStatusCode.Created, Household(id.toString(), name, currency, HouseholdRole.OWNER))
    }

    route("/{householdId}", {
        request { pathParameter<String>("householdId") }
        response { code(HttpStatusCode.NotFound) { description = "Not a member of this household, or no such household" } }
    }) {
        install(HouseholdAccess)

        get("/members", {
            operationId = "listMembers"
            response {
                code(HttpStatusCode.OK) { body<List<Member>>() }
                code(HttpStatusCode.NotFound) { body<ApiError>() }
            }
        }) {
            val members = dbQuery {
                HouseholdMembers
                    .join(Users, JoinType.INNER, HouseholdMembers.userId, Users.id)
                    .selectAll()
                    .where { HouseholdMembers.householdId eq call.household.id }
                    .orderBy(Users.displayName to SortOrder.ASC)
                    .map { Member(it[Users.id].toString(), it[Users.displayName], roleFrom(it[HouseholdMembers.role])) }
            }
            call.respond(members)
        }

        delete("/members/{userId}", {
            operationId = "removeMember"
            description = "Owners can remove anyone; members can remove only themselves (leave). " +
                "The last owner can't be removed. Their accounts and expenses become shared."
            request { pathParameter<String>("userId") }
            response {
                code(HttpStatusCode.NoContent) {}
                code(HttpStatusCode.Forbidden) { body<ApiError>() }
                code(HttpStatusCode.NotFound) { body<ApiError>() }
                code(HttpStatusCode.Conflict) { body<ApiError>() }
            }
        }) {
            val target = call.pathId("userId", "Member")
            if (target != call.userId) call.requireOwner()
            val householdId = call.household.id
            dbQuery {
                val members = HouseholdMembers.selectAll().where { HouseholdMembers.householdId eq householdId }.toList()
                val targetRow = members.find { it[HouseholdMembers.userId] == target } ?: throw notFound("Member")
                val owners = members.count { it[HouseholdMembers.role] == "owner" }
                if (targetRow[HouseholdMembers.role] == "owner" && owners == 1) {
                    throw conflict("last_owner", "A household needs at least one owner")
                }
                HouseholdMembers.deleteWhere {
                    (HouseholdMembers.householdId eq householdId) and (userId eq target)
                }
            }
            call.respond(HttpStatusCode.NoContent)
        }

        householdInviteRoutes()
        accountRoutes()
        categoryRoutes()
        expenseRoutes()
        incomeRoutes()
    }
}

/** Whether [userId] belongs to [householdId]. Call inside a transaction. */
fun isMember(householdId: Uuid, userId: Uuid): Boolean =
    HouseholdMembers.selectAll()
        .where { (HouseholdMembers.householdId eq householdId) and (HouseholdMembers.userId eq userId) }
        .empty().not()
