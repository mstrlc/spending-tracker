package dev.spendingtracker.server.account

import dev.spendingtracker.server.common.badRequest
import dev.spendingtracker.server.common.checkVersioned
import dev.spendingtracker.server.common.conflict
import dev.spendingtracker.server.common.parseId
import dev.spendingtracker.server.common.pathId
import dev.spendingtracker.server.common.requireVersion
import dev.spendingtracker.server.common.requiredText
import dev.spendingtracker.server.common.versionParam
import dev.spendingtracker.server.db.Accounts
import dev.spendingtracker.server.db.dbQuery
import dev.spendingtracker.server.db.isForeignKeyViolation
import dev.spendingtracker.server.db.isUniqueViolation
import dev.spendingtracker.server.household.household
import dev.spendingtracker.server.household.isMember
import dev.spendingtracker.shared.api.Account
import dev.spendingtracker.shared.api.AccountInput
import dev.spendingtracker.shared.api.AccountStatus
import dev.spendingtracker.shared.api.ApiError
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.github.smiley4.ktoropenapi.put
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Must be inside a HouseholdAccess scope. */
fun Route.accountRoutes() = route("/accounts") {
    get({
        operationId = "listAccounts"
        response { code(HttpStatusCode.OK) { body<List<Account>>() } }
    }) {
        val accounts = dbQuery {
            Accounts.selectAll()
                .where { Accounts.householdId eq call.household.id }
                .orderBy(Accounts.status to SortOrder.ASC, Accounts.name to SortOrder.ASC)
                .map { it.toAccount() }
        }
        call.respond(accounts)
    }

    post({
        operationId = "createAccount"
        request { body<AccountInput>() }
        response {
            code(HttpStatusCode.Created) { body<Account>() }
            code(HttpStatusCode.BadRequest) { body<ApiError>() }
            code(HttpStatusCode.Conflict) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val input = call.receive<AccountInput>().validated()
        val id = Uuid.random()
        val account = uniqueName {
            dbQuery {
                input.checkOwner(householdId)
                Accounts.insert {
                    it[Accounts.id] = id
                    it[Accounts.householdId] = householdId
                    it[name] = input.name
                    it[ownerUserId] = input.owner
                    it[status] = input.status.dbValue
                    it[version] = 1
                }
                Accounts.selectAll().where { Accounts.id eq id }.single().toAccount()
            }
        }
        call.respond(HttpStatusCode.Created, account)
    }

    put("/{accountId}", {
        operationId = "updateAccount"
        request {
            pathParameter<String>("accountId")
            body<AccountInput>()
        }
        response {
            code(HttpStatusCode.OK) { body<Account>() }
            code(HttpStatusCode.BadRequest) { body<ApiError>() }
            code(HttpStatusCode.NotFound) { body<ApiError>() }
            code(HttpStatusCode.Conflict) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val id = call.pathId("accountId", "Account")
        val raw = call.receive<AccountInput>()
        val version = requireVersion(raw.version)
        val input = raw.validated()
        val account = uniqueName {
            dbQuery {
                input.checkOwner(householdId)
                val affected = Accounts.update({ match(householdId, id) and (Accounts.version eq version) }) {
                    it[name] = input.name
                    it[ownerUserId] = input.owner
                    it[status] = input.status.dbValue
                    it[Accounts.version] = version + 1
                    it[updatedAt] = Clock.System.now()
                }
                checkVersioned(affected, { exists(householdId, id) }, "Account")
                Accounts.selectAll().where { Accounts.id eq id }.single().toAccount()
            }
        }
        call.respond(account)
    }

    delete("/{accountId}", {
        operationId = "deleteAccount"
        description = "Fails with 409 account_in_use while expenses, income or asset contributions reference the account; close it instead."
        request {
            pathParameter<String>("accountId")
            queryParameter<Int>("version") { required = true }
        }
        response {
            code(HttpStatusCode.NoContent) {}
            code(HttpStatusCode.NotFound) { body<ApiError>() }
            code(HttpStatusCode.Conflict) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val id = call.pathId("accountId", "Account")
        val version = call.versionParam()
        try {
            dbQuery {
                val affected = Accounts.deleteWhere { match(householdId, id) and (Accounts.version eq version) }
                checkVersioned(affected, { exists(householdId, id) }, "Account")
            }
        } catch (e: Exception) {
            if (e.isForeignKeyViolation()) {
                throw conflict("account_in_use", "Expenses, income or assets use this account. Close it instead of deleting it.")
            }
            throw e
        }
        call.respond(HttpStatusCode.NoContent)
    }
}

private data class ValidAccount(val name: String, val owner: Uuid?, val status: AccountStatus)

private fun AccountInput.validated() =
    ValidAccount(requiredText(name, "name", max = 100), parseId(ownerUserId, "ownerUserId"), status)

private fun ValidAccount.checkOwner(householdId: Uuid) {
    if (owner != null && !isMember(householdId, owner)) {
        throw badRequest("invalid_owner", "Owner must be a member of the household", "ownerUserId")
    }
}

private suspend fun <T> uniqueName(block: suspend () -> T): T =
    try {
        block()
    } catch (e: Exception) {
        if (e.isUniqueViolation()) throw conflict("name_taken", "An account with this name already exists", "name")
        throw e
    }

private fun match(householdId: Uuid, id: Uuid) = (Accounts.id eq id) and (Accounts.householdId eq householdId)

private fun exists(householdId: Uuid, id: Uuid) = !Accounts.selectAll().where { match(householdId, id) }.empty()

private val AccountStatus.dbValue get() = if (this == AccountStatus.ACTIVE) "active" else "closed"

private fun ResultRow.toAccount() = Account(
    id = this[Accounts.id].toString(),
    name = this[Accounts.name],
    ownerUserId = this[Accounts.ownerUserId]?.toString(),
    status = if (this[Accounts.status] == "active") AccountStatus.ACTIVE else AccountStatus.CLOSED,
    version = this[Accounts.version],
)
