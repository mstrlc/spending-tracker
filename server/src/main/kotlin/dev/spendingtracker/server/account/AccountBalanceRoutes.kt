package dev.spendingtracker.server.account

import dev.spendingtracker.server.auth.userId
import dev.spendingtracker.server.common.checkVersioned
import dev.spendingtracker.server.common.conflict
import dev.spendingtracker.server.common.notFound
import dev.spendingtracker.server.common.optionalText
import dev.spendingtracker.server.common.pathId
import dev.spendingtracker.server.common.versionParam
import dev.spendingtracker.server.db.AccountBalances
import dev.spendingtracker.server.db.Accounts
import dev.spendingtracker.server.db.BankBalances
import dev.spendingtracker.server.db.BankFeeds
import dev.spendingtracker.server.db.dbQuery
import dev.spendingtracker.server.db.isUniqueViolation
import dev.spendingtracker.server.household.household
import dev.spendingtracker.shared.api.AccountBalance
import dev.spendingtracker.shared.api.AccountBalanceInput
import dev.spendingtracker.shared.api.ApiError
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/** Balance checks of one account, under /accounts/{accountId}/balances. Must be inside a HouseholdAccess scope. */
internal fun Route.accountBalanceRoutes() {
    get({
        operationId = "listAccountBalances"
        description = "The account's balance checks, newest first."
        request { pathParameter<String>("accountId") }
        response {
            code(HttpStatusCode.OK) { body<List<AccountBalance>>() }
            code(HttpStatusCode.NotFound) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val accountId = call.pathId("accountId", "Account")
        val checks = dbQuery {
            requireAccount(householdId, accountId)
            AccountBalances.selectAll()
                .where { (AccountBalances.householdId eq householdId) and (AccountBalances.accountId eq accountId) }
                .orderBy(AccountBalances.date to SortOrder.DESC)
                .map { it.toAccountBalance() }
        }
        call.respond(checks)
    }

    post({
        operationId = "createAccountBalance"
        description = "Records what the account held at the end of a day. Fails with 409 balance_exists when it already " +
            "has a check that day, and with 409 bank_reports_balance when the bank already reports the account's balance."
        request {
            pathParameter<String>("accountId")
            body<AccountBalanceInput>()
        }
        response {
            code(HttpStatusCode.Created) { body<AccountBalance>() }
            code(HttpStatusCode.BadRequest) { body<ApiError>() }
            code(HttpStatusCode.NotFound) { body<ApiError>() }
            code(HttpStatusCode.Conflict) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val accountId = call.pathId("accountId", "Account")
        val input = call.receive<AccountBalanceInput>()
        val notes = optionalText(input.notes, "notes", max = 2000)
        val id = Uuid.random()
        val check = try {
            dbQuery {
                requireAccount(householdId, accountId)
                if (bankReportsBalance(householdId, accountId)) {
                    throw conflict("bank_reports_balance", "The bank already reports this account's balance")
                }
                AccountBalances.insert {
                    it[AccountBalances.id] = id
                    it[AccountBalances.householdId] = householdId
                    it[AccountBalances.accountId] = accountId
                    it[date] = input.date
                    it[amountMinor] = input.amountMinor
                    it[AccountBalances.notes] = notes
                    it[version] = 1
                    it[createdBy] = call.userId
                }
                AccountBalances.selectAll().where { AccountBalances.id eq id }.single().toAccountBalance()
            }
        } catch (e: Exception) {
            if (e.isUniqueViolation()) throw conflict("balance_exists", "This account already has a balance check that day", "date")
            throw e
        }
        call.respond(HttpStatusCode.Created, check)
    }

    delete("/{balanceId}", {
        operationId = "deleteAccountBalance"
        request {
            pathParameter<String>("accountId")
            pathParameter<String>("balanceId")
            queryParameter<Int>("version") { required = true }
        }
        response {
            code(HttpStatusCode.NoContent) {}
            code(HttpStatusCode.NotFound) { body<ApiError>() }
            code(HttpStatusCode.Conflict) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val accountId = call.pathId("accountId", "Account")
        val id = call.pathId("balanceId", "Balance check")
        val version = call.versionParam()
        dbQuery {
            val match = (AccountBalances.id eq id) and (AccountBalances.householdId eq householdId) and
                (AccountBalances.accountId eq accountId)
            val affected = AccountBalances.deleteWhere { match and (AccountBalances.version eq version) }
            checkVersioned(affected, { !AccountBalances.selectAll().where { match }.empty() }, "Balance check")
        }
        call.respond(HttpStatusCode.NoContent)
    }
}

private fun requireAccount(householdId: Uuid, accountId: Uuid) {
    val found = Accounts.select(Accounts.id).where { (Accounts.id eq accountId) and (Accounts.householdId eq householdId) }.empty()
    if (found) throw notFound("Account")
}

/** Whether any of the account's feeds has a balance reading. Call inside a transaction. */
internal fun bankReportsBalance(householdId: Uuid, accountId: Uuid): Boolean {
    val feedIds = BankFeeds.select(BankFeeds.id)
        .where { (BankFeeds.householdId eq householdId) and (BankFeeds.accountId eq accountId) }
        .map { it[BankFeeds.id] }
    if (feedIds.isEmpty()) return false
    return !BankBalances.select(BankBalances.id)
        .where { (BankBalances.householdId eq householdId) and (BankBalances.feedId inList feedIds) }
        .limit(1)
        .empty()
}

private fun ResultRow.toAccountBalance() = AccountBalance(
    id = this[AccountBalances.id].toString(),
    accountId = this[AccountBalances.accountId].toString(),
    date = this[AccountBalances.date],
    amountMinor = this[AccountBalances.amountMinor],
    notes = this[AccountBalances.notes],
    version = this[AccountBalances.version],
)
