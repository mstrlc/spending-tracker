package dev.spendingtracker.server.household

import dev.spendingtracker.server.auth.UserPrincipal
import dev.spendingtracker.server.common.notFound
import dev.spendingtracker.server.db.HouseholdMembers
import dev.spendingtracker.server.db.Households
import dev.spendingtracker.server.db.dbQuery
import dev.spendingtracker.shared.api.HouseholdRole
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.auth.AuthenticationChecked
import io.ktor.server.auth.principal
import io.ktor.util.AttributeKey
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/** The household a request is scoped to, resolved from the {householdId} path segment. */
data class HouseholdContext(val id: Uuid, val currency: String, val role: HouseholdRole)

private val HouseholdKey = AttributeKey<HouseholdContext>("HouseholdContext")

val ApplicationCall.household: HouseholdContext
    get() = attributes.getOrNull(HouseholdKey) ?: error("Route is not inside a HouseholdAccess scope")

/**
 * Admits only members of the household in the path. Non-members get 404, not 403,
 * so household ids can't be probed for existence.
 */
val HouseholdAccess = createRouteScopedPlugin("HouseholdAccess") {
    on(AuthenticationChecked) { call ->
        val userId = call.principal<UserPrincipal>()?.userId ?: return@on
        val householdId = call.parameters["householdId"]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            ?: throw notFound("Household")
        val context = dbQuery {
            HouseholdMembers
                .join(Households, JoinType.INNER, HouseholdMembers.householdId, Households.id)
                .selectAll()
                .where { (HouseholdMembers.householdId eq householdId) and (HouseholdMembers.userId eq userId) }
                .singleOrNull()
                ?.let { HouseholdContext(householdId, it[Households.currency], roleFrom(it[HouseholdMembers.role])) }
        } ?: throw notFound("Household")
        call.attributes.put(HouseholdKey, context)
    }
}

fun roleFrom(value: String) = if (value == "owner") HouseholdRole.OWNER else HouseholdRole.MEMBER
