package dev.spendingtracker.server.income

import dev.spendingtracker.server.auth.userId
import dev.spendingtracker.server.common.badRequest
import dev.spendingtracker.server.common.checkVersioned
import dev.spendingtracker.server.common.dateParam
import dev.spendingtracker.server.common.nonZeroAmount
import dev.spendingtracker.server.common.optionalText
import dev.spendingtracker.server.common.parseId
import dev.spendingtracker.server.common.pathId
import dev.spendingtracker.server.common.requireVersion
import dev.spendingtracker.server.common.versionParam
import dev.spendingtracker.server.db.Accounts
import dev.spendingtracker.server.db.Income
import dev.spendingtracker.server.db.dbQuery
import dev.spendingtracker.server.household.household
import dev.spendingtracker.server.inbox.fileBankRows
import dev.spendingtracker.server.household.isMember
import dev.spendingtracker.shared.api.ApiError
import dev.spendingtracker.shared.api.IncomeInput
import dev.spendingtracker.shared.api.IncomeKind
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.github.smiley4.ktoropenapi.put
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.yearMonth
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.uuid.Uuid
import dev.spendingtracker.shared.api.Income as IncomeDto

private const val LIST_LIMIT = 5_000

/** Must be inside a HouseholdAccess scope. */
fun Route.incomeRoutes() = route("/income") {
    get({
        operationId = "listIncome"
        description = "Income and deductions newest first, filtered by inclusive date range, account and recipient."
        request {
            queryParameter<String>("from") { description = "Inclusive start date, YYYY-MM-DD; matched against the month earned" }
            queryParameter<String>("to") { description = "Inclusive end date, YYYY-MM-DD; matched against the month earned" }
            queryParameter<String>("accountId")
            queryParameter<String>("recipientUserId")
        }
        response {
            code(HttpStatusCode.OK) { body<List<IncomeDto>>() }
            code(HttpStatusCode.BadRequest) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val from = call.dateParam("from")
        val to = call.dateParam("to")
        val accountId = parseId(call.request.queryParameters["accountId"], "accountId")
        val recipientUserId = parseId(call.request.queryParameters["recipientUserId"], "recipientUserId")
        val income = dbQuery {
            var filter: Op<Boolean> = Income.householdId eq householdId
            // Income belongs to the month it was earned in, whenever it arrived.
            if (from != null) filter = filter and (Income.earnedMonth greaterEq from.yearMonth.firstDay)
            if (to != null) filter = filter and (Income.earnedMonth lessEq to)
            if (accountId != null) filter = filter and (Income.accountId eq accountId)
            if (recipientUserId != null) filter = filter and (Income.recipientUserId eq recipientUserId)
            Income.selectAll()
                .where(filter)
                .orderBy(Income.earnedMonth to SortOrder.DESC, Income.date to SortOrder.DESC, Income.id to SortOrder.ASC)
                .limit(LIST_LIMIT)
                .map { it.toIncome() }
        }
        call.respond(income)
    }

    post({
        operationId = "createIncome"
        request { body<IncomeInput>() }
        response {
            code(HttpStatusCode.Created) { body<IncomeDto>() }
            code(HttpStatusCode.BadRequest) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val raw = call.receive<IncomeInput>()
        val income = dbQuery {
            val id = createIncome(householdId, call.userId, raw)
            fileBankRows(householdId, raw.bankRows, call.userId, incomeId = id, created = true)
            Income.selectAll().where { Income.id eq id }.single().toIncome()
        }
        call.respond(HttpStatusCode.Created, income)
    }

    put("/{incomeId}", {
        operationId = "updateIncome"
        request {
            pathParameter<String>("incomeId")
            body<IncomeInput>()
        }
        response {
            code(HttpStatusCode.OK) { body<IncomeDto>() }
            code(HttpStatusCode.BadRequest) { body<ApiError>() }
            code(HttpStatusCode.NotFound) { body<ApiError>() }
            code(HttpStatusCode.Conflict) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val id = call.pathId("incomeId", "Income")
        val raw = call.receive<IncomeInput>()
        val version = requireVersion(raw.version)
        val input = raw.validated()
        val income = dbQuery {
            input.checkReferences(householdId)
            val affected = Income.update({ match(householdId, id) and (Income.version eq version) }) {
                it.write(input)
                it[Income.version] = version + 1
                it[updatedAt] = Clock.System.now()
            }
            checkVersioned(affected, { exists(householdId, id) }, "Income")
            Income.selectAll().where { Income.id eq id }.single().toIncome()
        }
        call.respond(income)
    }

    delete("/{incomeId}", {
        operationId = "deleteIncome"
        request {
            pathParameter<String>("incomeId")
            queryParameter<Int>("version") { required = true }
        }
        response {
            code(HttpStatusCode.NoContent) {}
            code(HttpStatusCode.NotFound) { body<ApiError>() }
            code(HttpStatusCode.Conflict) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val id = call.pathId("incomeId", "Income")
        val version = call.versionParam()
        dbQuery {
            val affected = Income.deleteWhere { match(householdId, id) and (Income.version eq version) }
            checkVersioned(affected, { exists(householdId, id) }, "Income")
        }
        call.respond(HttpStatusCode.NoContent)
    }
}

/**
 * Validates [raw] and creates the income line: what the form and the inbox rules both make. Returns its id; filing bank
 * rows under it is up to the caller. Call inside a transaction.
 */
fun createIncome(householdId: Uuid, userId: Uuid, raw: IncomeInput): Uuid {
    val input = raw.validated()
    val id = Uuid.random()
    input.checkReferences(householdId)
    Income.insert {
        it[Income.id] = id
        it[Income.householdId] = householdId
        it.write(input)
        it[version] = 1
        it[createdBy] = userId
    }
    return id
}

private data class ValidIncome(
    val date: LocalDate,
    val amountMinor: Long,
    val source: String,
    val accountId: Uuid?,
    val recipientUserId: Uuid?,
    val notes: String,
    val earnedMonth: LocalDate,
    val kind: IncomeKind,
)

private fun IncomeInput.validated() = ValidIncome(
    date = date,
    amountMinor = nonZeroAmount(amountMinor),
    source = optionalText(source, "source", max = 200),
    accountId = parseId(accountId, "accountId"),
    recipientUserId = parseId(recipientUserId, "recipientUserId"),
    notes = optionalText(notes, "notes", max = 2000),
    earnedMonth = earnedMonth?.trim()?.takeIf { it.isNotEmpty() }?.let {
        runCatching { YearMonth.parse(it) }.getOrElse { throw badRequest("invalid_month", "earnedMonth must be YYYY-MM", "earnedMonth") }
    }?.firstDay ?: date.yearMonth.firstDay,
    kind = kind,
)

/** Field-level errors for references the composite foreign keys would otherwise reject opaquely. */
private fun ValidIncome.checkReferences(householdId: Uuid) {
    if (accountId != null &&
        Accounts.selectAll().where { (Accounts.id eq accountId) and (Accounts.householdId eq householdId) }.empty()
    ) {
        throw badRequest("invalid_account", "Unknown account", "accountId")
    }
    if (recipientUserId != null && !isMember(householdId, recipientUserId)) {
        throw badRequest("invalid_member", "Recipient must be a member of the household", "recipientUserId")
    }
}

private fun org.jetbrains.exposed.v1.core.statements.UpdateBuilder<*>.write(input: ValidIncome) {
    this[Income.date] = input.date
    this[Income.amountMinor] = input.amountMinor
    this[Income.sourceName] = input.source
    this[Income.accountId] = input.accountId
    this[Income.recipientUserId] = input.recipientUserId
    this[Income.notes] = input.notes
    this[Income.earnedMonth] = input.earnedMonth
    this[Income.kind] = input.kind.dbValue
}

val IncomeKind.dbValue get() = name.lowercase()

fun incomeKind(dbValue: String) = IncomeKind.entries.first { it.dbValue == dbValue }

/** Positive for what was earned, negative for what was deducted: what the line adds to net income. */
fun ResultRow.netIncomeMinor() =
    if (this[Income.kind] == IncomeKind.DEDUCTION.dbValue) -this[Income.amountMinor] else this[Income.amountMinor]

private fun match(householdId: Uuid, id: Uuid) = (Income.id eq id) and (Income.householdId eq householdId)

private fun exists(householdId: Uuid, id: Uuid) = !Income.selectAll().where { match(householdId, id) }.empty()

private fun ResultRow.toIncome() = IncomeDto(
    id = this[Income.id].toString(),
    date = this[Income.date],
    amountMinor = this[Income.amountMinor],
    source = this[Income.sourceName],
    accountId = this[Income.accountId]?.toString(),
    recipientUserId = this[Income.recipientUserId]?.toString(),
    notes = this[Income.notes],
    version = this[Income.version],
    earnedMonth = this[Income.earnedMonth].yearMonth.toString(),
    kind = incomeKind(this[Income.kind]),
)
