package dev.spendingtracker.server.expense

import dev.spendingtracker.server.auth.userId
import dev.spendingtracker.server.common.badRequest
import dev.spendingtracker.server.common.checkVersioned
import dev.spendingtracker.server.common.optionalText
import dev.spendingtracker.server.common.parseId
import dev.spendingtracker.server.common.pathId
import dev.spendingtracker.server.common.requireVersion
import dev.spendingtracker.server.common.versionParam
import dev.spendingtracker.server.db.Accounts
import dev.spendingtracker.server.db.Categories
import dev.spendingtracker.server.db.Expenses
import dev.spendingtracker.server.db.dbQuery
import dev.spendingtracker.server.household.household
import dev.spendingtracker.server.household.isMember
import dev.spendingtracker.shared.api.ApiError
import dev.spendingtracker.shared.api.Expense
import dev.spendingtracker.shared.api.ExpenseInput
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.github.smiley4.ktoropenapi.put
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.route
import kotlinx.datetime.LocalDate
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.math.absoluteValue
import kotlin.time.Clock
import kotlin.uuid.Uuid

// Keeps sums of many expenses far from Long overflow (10^13 minor units = 100 billion in major units).
private const val MAX_ABS_AMOUNT = 10_000_000_000_000L
private const val LIST_LIMIT = 5_000

/** Must be inside a HouseholdAccess scope. */
fun Route.expenseRoutes() = route("/expenses") {
    get({
        operationId = "listExpenses"
        description = "Expenses newest first, filtered by inclusive date range, category (subcategories included) and account."
        request {
            queryParameter<String>("from") { description = "Inclusive start date, YYYY-MM-DD" }
            queryParameter<String>("to") { description = "Inclusive end date, YYYY-MM-DD" }
            queryParameter<String>("categoryId")
            queryParameter<String>("accountId")
        }
        response {
            code(HttpStatusCode.OK) { body<List<Expense>>() }
            code(HttpStatusCode.BadRequest) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val from = call.dateParam("from")
        val to = call.dateParam("to")
        val categoryId = parseId(call.request.queryParameters["categoryId"], "categoryId")
        val accountId = parseId(call.request.queryParameters["accountId"], "accountId")
        val expenses = dbQuery {
            var filter: Op<Boolean> = Expenses.householdId eq householdId
            if (from != null) filter = filter and (Expenses.date greaterEq from)
            if (to != null) filter = filter and (Expenses.date lessEq to)
            if (accountId != null) filter = filter and (Expenses.accountId eq accountId)
            if (categoryId != null) {
                val subcategories = Categories.select(Categories.id)
                    .where { (Categories.householdId eq householdId) and (Categories.parentId eq categoryId) }
                filter = filter and ((Expenses.categoryId eq categoryId) or (Expenses.categoryId inSubQuery subcategories))
            }
            Expenses.selectAll()
                .where(filter)
                .orderBy(Expenses.date to SortOrder.DESC, Expenses.id to SortOrder.ASC)
                .limit(LIST_LIMIT)
                .map { it.toExpense() }
        }
        call.respond(expenses)
    }

    post({
        operationId = "createExpense"
        request { body<ExpenseInput>() }
        response {
            code(HttpStatusCode.Created) { body<Expense>() }
            code(HttpStatusCode.BadRequest) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val input = call.receive<ExpenseInput>().validated()
        val id = Uuid.random()
        val expense = dbQuery {
            input.checkReferences(householdId)
            Expenses.insert {
                it[Expenses.id] = id
                it[Expenses.householdId] = householdId
                it.write(input)
                it[version] = 1
                it[createdBy] = call.userId
            }
            Expenses.selectAll().where { Expenses.id eq id }.single().toExpense()
        }
        call.respond(HttpStatusCode.Created, expense)
    }

    put("/{expenseId}", {
        operationId = "updateExpense"
        request {
            pathParameter<String>("expenseId")
            body<ExpenseInput>()
        }
        response {
            code(HttpStatusCode.OK) { body<Expense>() }
            code(HttpStatusCode.BadRequest) { body<ApiError>() }
            code(HttpStatusCode.NotFound) { body<ApiError>() }
            code(HttpStatusCode.Conflict) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val id = call.pathId("expenseId", "Expense")
        val raw = call.receive<ExpenseInput>()
        val version = requireVersion(raw.version)
        val input = raw.validated()
        val expense = dbQuery {
            input.checkReferences(householdId)
            val affected = Expenses.update({ match(householdId, id) and (Expenses.version eq version) }) {
                it.write(input)
                it[Expenses.version] = version + 1
                it[updatedAt] = Clock.System.now()
            }
            checkVersioned(affected, { exists(householdId, id) }, "Expense")
            Expenses.selectAll().where { Expenses.id eq id }.single().toExpense()
        }
        call.respond(expense)
    }

    delete("/{expenseId}", {
        operationId = "deleteExpense"
        request {
            pathParameter<String>("expenseId")
            queryParameter<Int>("version") { required = true }
        }
        response {
            code(HttpStatusCode.NoContent) {}
            code(HttpStatusCode.NotFound) { body<ApiError>() }
            code(HttpStatusCode.Conflict) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val id = call.pathId("expenseId", "Expense")
        val version = call.versionParam()
        dbQuery {
            val affected = Expenses.deleteWhere { match(householdId, id) and (Expenses.version eq version) }
            checkVersioned(affected, { exists(householdId, id) }, "Expense")
        }
        call.respond(HttpStatusCode.NoContent)
    }
}

private data class ValidExpense(
    val date: LocalDate,
    val amountMinor: Long,
    val merchant: String,
    val categoryId: Uuid?,
    val accountId: Uuid?,
    val paidByUserId: Uuid?,
    val notes: String,
)

private fun ExpenseInput.validated(): ValidExpense {
    if (amountMinor == 0L) throw badRequest("invalid_amount", "Amount can't be zero", "amountMinor")
    if (amountMinor.absoluteValue > MAX_ABS_AMOUNT) throw badRequest("invalid_amount", "Amount is too large", "amountMinor")
    return ValidExpense(
        date = date,
        amountMinor = amountMinor,
        merchant = optionalText(merchant, "merchant", max = 200),
        categoryId = parseId(categoryId, "categoryId"),
        accountId = parseId(accountId, "accountId"),
        paidByUserId = parseId(paidByUserId, "paidByUserId"),
        notes = optionalText(notes, "notes", max = 2000),
    )
}

/** Field-level errors for references the composite foreign keys would otherwise reject opaquely. */
private fun ValidExpense.checkReferences(householdId: Uuid) {
    if (categoryId != null &&
        Categories.selectAll().where { (Categories.id eq categoryId) and (Categories.householdId eq householdId) }.empty()
    ) {
        throw badRequest("invalid_category", "Unknown category", "categoryId")
    }
    if (accountId != null &&
        Accounts.selectAll().where { (Accounts.id eq accountId) and (Accounts.householdId eq householdId) }.empty()
    ) {
        throw badRequest("invalid_account", "Unknown account", "accountId")
    }
    if (paidByUserId != null && !isMember(householdId, paidByUserId)) {
        throw badRequest("invalid_member", "Paid by must be a member of the household", "paidByUserId")
    }
}

private fun org.jetbrains.exposed.v1.core.statements.UpdateBuilder<*>.write(input: ValidExpense) {
    this[Expenses.date] = input.date
    this[Expenses.amountMinor] = input.amountMinor
    this[Expenses.merchant] = input.merchant
    this[Expenses.categoryId] = input.categoryId
    this[Expenses.accountId] = input.accountId
    this[Expenses.paidByUserId] = input.paidByUserId
    this[Expenses.notes] = input.notes
}

private fun RoutingCall.dateParam(name: String): LocalDate? =
    request.queryParameters[name]?.let {
        runCatching { LocalDate.parse(it) }.getOrElse { throw badRequest("invalid_date", "$name must be YYYY-MM-DD", name) }
    }

private fun match(householdId: Uuid, id: Uuid) = (Expenses.id eq id) and (Expenses.householdId eq householdId)

private fun exists(householdId: Uuid, id: Uuid) = !Expenses.selectAll().where { match(householdId, id) }.empty()

private fun ResultRow.toExpense() = Expense(
    id = this[Expenses.id].toString(),
    date = this[Expenses.date],
    amountMinor = this[Expenses.amountMinor],
    merchant = this[Expenses.merchant],
    categoryId = this[Expenses.categoryId]?.toString(),
    accountId = this[Expenses.accountId]?.toString(),
    paidByUserId = this[Expenses.paidByUserId]?.toString(),
    notes = this[Expenses.notes],
    version = this[Expenses.version],
)
