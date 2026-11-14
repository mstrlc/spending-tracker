package dev.spendingtracker.server.expense

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
import dev.spendingtracker.server.db.Categories
import dev.spendingtracker.server.db.ExpenseLines
import dev.spendingtracker.server.db.Expenses
import dev.spendingtracker.server.db.Merchants
import dev.spendingtracker.server.db.Trips
import dev.spendingtracker.server.db.dbQuery
import dev.spendingtracker.server.debt.SplitBook
import dev.spendingtracker.server.debt.Weights
import dev.spendingtracker.server.debt.checkMembers
import dev.spendingtracker.server.debt.loadSplitBook
import dev.spendingtracker.server.debt.toExpenseFacts
import dev.spendingtracker.server.debt.toShares
import dev.spendingtracker.server.debt.validatedWeights
import dev.spendingtracker.server.debt.writeExpenseSplit
import dev.spendingtracker.server.household.household
import dev.spendingtracker.server.inbox.fileBankRows
import dev.spendingtracker.server.household.isMember
import dev.spendingtracker.server.merchant.resolveMerchant
import dev.spendingtracker.shared.api.ApiError
import dev.spendingtracker.shared.api.Expense
import dev.spendingtracker.shared.api.ExpenseInput
import dev.spendingtracker.shared.api.ExpenseLine
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
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val LIST_LIMIT = 5_000

/** Must be inside a HouseholdAccess scope. */
fun Route.expenseRoutes() = route("/expenses") {
    get({
        operationId = "listExpenses"
        description = "Expenses newest first, filtered by inclusive date range, category (subcategories included), account and merchant."
        request {
            queryParameter<String>("from") { description = "Inclusive start date, YYYY-MM-DD" }
            queryParameter<String>("to") { description = "Inclusive end date, YYYY-MM-DD" }
            queryParameter<String>("categoryId")
            queryParameter<String>("accountId")
            queryParameter<String>("merchantId")
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
        val merchantId = parseId(call.request.queryParameters["merchantId"], "merchantId")
        val expenses = dbQuery {
            val book = loadSplitBook(householdId)
            var filter: Op<Boolean> = Expenses.householdId eq householdId
            if (from != null) filter = filter and (Expenses.date greaterEq from)
            if (to != null) filter = filter and (Expenses.date lessEq to)
            if (accountId != null) filter = filter and (Expenses.accountId eq accountId)
            if (merchantId != null) filter = filter and (Expenses.merchantId eq merchantId)
            if (categoryId != null) {
                val subcategories = Categories.select(Categories.id)
                    .where { (Categories.householdId eq householdId) and (Categories.parentId eq categoryId) }
                val inCategory = (Expenses.categoryId eq categoryId) or (Expenses.categoryId inSubQuery subcategories)
                val lineExpenses = ExpenseLines.select(ExpenseLines.expenseId).where {
                    (ExpenseLines.householdId eq householdId) and
                        ((ExpenseLines.categoryId eq categoryId) or (ExpenseLines.categoryId inSubQuery subcategories))
                }
                filter = filter and (inCategory or (Expenses.id inSubQuery lineExpenses))
            }
            val rows = withMerchant()
                .where(filter)
                .orderBy(Expenses.date to SortOrder.DESC, Expenses.id to SortOrder.ASC)
                .limit(LIST_LIMIT)
                .toList()
            val lines = loadExpenseLines(householdId, rows.map { it[Expenses.id] })
            rows.map { it.toExpense(book, lines[it[Expenses.id]].orEmpty()) }
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
        val raw = call.receive<ExpenseInput>()
        val expense = dbQuery {
            val id = createExpense(householdId, call.userId, raw)
            fileBankRows(householdId, raw.bankRows, call.userId, expenseId = id, created = true)
            loadExpense(householdId, id)
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
            val merchantId = resolveMerchant(householdId, input.merchant, input.defaultCategoryId, call.userId)
            val affected = Expenses.update({ match(householdId, id) and (Expenses.version eq version) }) {
                it.write(input, merchantId)
                it[Expenses.version] = version + 1
                it[updatedAt] = Clock.System.now()
            }
            checkVersioned(affected, { exists(householdId, id) }, "Expense")
            writeExpenseLines(householdId, id, input.lines)
            writeExpenseSplit(householdId, id, input.split, call.userId)
            loadExpense(householdId, id)
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

/**
 * Validates [raw] and creates the expense (merchant found or created, split lines, own split): what the form and
 * the inbox rules both make. Returns its id; filing bank rows under it is up to the caller. Call inside a transaction.
 */
fun createExpense(householdId: Uuid, userId: Uuid, raw: ExpenseInput): Uuid {
    val input = raw.validated()
    val id = Uuid.random()
    input.checkReferences(householdId)
    val merchantId = resolveMerchant(householdId, input.merchant, input.defaultCategoryId, userId)
    Expenses.insert {
        it[Expenses.id] = id
        it[Expenses.householdId] = householdId
        it.write(input, merchantId)
        it[version] = 1
        it[createdBy] = userId
    }
    writeExpenseLines(householdId, id, input.lines)
    writeExpenseSplit(householdId, id, input.split, userId)
    return id
}

private data class ValidLine(val categoryId: Uuid?, val amountMinor: Long, val notes: String)

private data class ValidExpense(
    val date: LocalDate,
    val amountMinor: Long,
    val merchant: String,
    val categoryId: Uuid?,
    val accountId: Uuid?,
    val paidByUserId: Uuid?,
    val notes: String,
    val tripId: Uuid?,
    val originalAmountMinor: Long?,
    val originalCurrency: String?,
    val split: Weights?,
    val createsDebt: Boolean,
    val lines: List<ValidLine>,
) {
    /** The category a new merchant takes as its default: the expense's, or its largest split line's. */
    val defaultCategoryId: Uuid? get() = categoryId ?: lines.maxByOrNull { abs(it.amountMinor) }?.categoryId
}

private fun ExpenseInput.validated(): ValidExpense {
    val total = nonZeroAmount(amountMinor)
    val validLines = lines.map { line ->
        val lineAmount = nonZeroAmount(line.amountMinor, "lines")
        if ((lineAmount > 0) != (total > 0)) {
            throw badRequest("invalid_line_amount", "Every line must have the same sign as the expense", "lines")
        }
        ValidLine(parseId(line.categoryId, "lines"), lineAmount, optionalText(line.notes, "lines", max = 500))
    }
    if (validLines.size == 1) {
        throw badRequest("too_few_lines", "Split an expense into at least two lines, or give it one category", "lines")
    }
    if (validLines.isNotEmpty() && validLines.sumOf { it.amountMinor } != total) {
        throw badRequest("lines_mismatch", "Split lines must add up to the expense amount", "lines")
    }
    return ValidExpense(
        date = date,
        amountMinor = total,
        merchant = optionalText(merchant, "merchant", max = 200),
        categoryId = if (validLines.isEmpty()) parseId(categoryId, "categoryId") else null,
        accountId = parseId(accountId, "accountId"),
        paidByUserId = parseId(paidByUserId, "paidByUserId"),
        notes = optionalText(notes, "notes", max = 2000),
        tripId = parseId(tripId, "tripId"),
        originalAmountMinor = originalAmountMinor?.let { nonZeroAmount(it, "originalAmountMinor") },
        originalCurrency = originalCurrency?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }?.also {
            if (!Regex("^[A-Z]{3}$").matches(it)) throw badRequest("invalid_currency", "Use a 3-letter currency code", "originalCurrency")
        },
        split = split?.validatedWeights("split"),
        createsDebt = createsDebt,
        lines = validLines,
    ).also {
        if ((it.originalAmountMinor == null) != (it.originalCurrency == null)) {
            val field = if (it.originalAmountMinor == null) "originalAmountMinor" else "originalCurrency"
            throw badRequest("original_incomplete", "Give both the original amount and its currency, or neither", field)
        }
    }
}

/** Field-level errors for references the composite foreign keys would otherwise reject opaquely. */
private fun ValidExpense.checkReferences(householdId: Uuid) {
    val categoryIds = (listOfNotNull(categoryId) + lines.mapNotNull { it.categoryId }).distinct()
    if (categoryIds.isNotEmpty()) {
        val found = Categories.select(Categories.id)
            .where { (Categories.householdId eq householdId) and (Categories.id inList categoryIds) }
            .count()
        if (found < categoryIds.size) {
            throw badRequest("invalid_category", "Unknown category", if (categoryId != null) "categoryId" else "lines")
        }
    }
    if (accountId != null &&
        Accounts.selectAll().where { (Accounts.id eq accountId) and (Accounts.householdId eq householdId) }.empty()
    ) {
        throw badRequest("invalid_account", "Unknown account", "accountId")
    }
    if (tripId != null && Trips.selectAll().where { (Trips.id eq tripId) and (Trips.householdId eq householdId) }.empty()) {
        throw badRequest("invalid_trip", "Unknown trip", "tripId")
    }
    if (paidByUserId != null && !isMember(householdId, paidByUserId)) {
        throw badRequest("invalid_member", "Paid by must be a member of the household", "paidByUserId")
    }
    split?.checkMembers(householdId, "split")
}

private fun org.jetbrains.exposed.v1.core.statements.UpdateBuilder<*>.write(input: ValidExpense, merchantId: Uuid?) {
    this[Expenses.date] = input.date
    this[Expenses.amountMinor] = input.amountMinor
    this[Expenses.merchantId] = merchantId
    this[Expenses.categoryId] = input.categoryId
    this[Expenses.accountId] = input.accountId
    this[Expenses.paidByUserId] = input.paidByUserId
    this[Expenses.notes] = input.notes
    this[Expenses.tripId] = input.tripId
    this[Expenses.originalAmountMinor] = input.originalAmountMinor
    this[Expenses.originalCurrency] = input.originalCurrency
    this[Expenses.createsDebt] = input.createsDebt
}

/** Lines of the household's expenses (or just [expenseIds]), in order. Call inside a transaction. */
fun loadExpenseLines(householdId: Uuid, expenseIds: Collection<Uuid>? = null): Map<Uuid, List<ExpenseLine>> {
    if (expenseIds?.isEmpty() == true) return emptyMap()
    var filter: Op<Boolean> = ExpenseLines.householdId eq householdId
    if (expenseIds != null) filter = filter and (ExpenseLines.expenseId inList expenseIds)
    return ExpenseLines.selectAll().where(filter)
        .orderBy(ExpenseLines.sortOrder to SortOrder.ASC)
        .groupBy(
            { it[ExpenseLines.expenseId] },
            { ExpenseLine(it[ExpenseLines.categoryId]?.toString(), it[ExpenseLines.amountMinor], it[ExpenseLines.notes]) },
        )
}

private fun writeExpenseLines(householdId: Uuid, expenseId: Uuid, lines: List<ValidLine>) {
    val current = ExpenseLines.selectAll()
        .where { (ExpenseLines.householdId eq householdId) and (ExpenseLines.expenseId eq expenseId) }
        .orderBy(ExpenseLines.sortOrder to SortOrder.ASC)
        .map { ValidLine(it[ExpenseLines.categoryId], it[ExpenseLines.amountMinor], it[ExpenseLines.notes]) }
    if (current == lines) return
    if (current.isNotEmpty()) {
        ExpenseLines.deleteWhere { (ExpenseLines.householdId eq householdId) and (ExpenseLines.expenseId eq expenseId) }
    }
    lines.forEachIndexed { index, line ->
        ExpenseLines.insert {
            it[ExpenseLines.householdId] = householdId
            it[ExpenseLines.expenseId] = expenseId
            it[sortOrder] = index
            it[categoryId] = line.categoryId
            it[amountMinor] = line.amountMinor
            it[notes] = line.notes
        }
    }
}

/** One expense as the API returns it. Call inside a transaction. */
fun loadExpense(householdId: Uuid, id: Uuid): Expense {
    val lines = loadExpenseLines(householdId, listOf(id))[id].orEmpty()
    return withMerchant().where { Expenses.id eq id }.single().toExpense(loadSplitBook(householdId, listOf(id)), lines)
}

private fun match(householdId: Uuid, id: Uuid) = (Expenses.id eq id) and (Expenses.householdId eq householdId)

private fun exists(householdId: Uuid, id: Uuid) = !Expenses.selectAll().where { match(householdId, id) }.empty()

private fun withMerchant() =
    Expenses.join(Merchants, JoinType.LEFT, Expenses.merchantId, Merchants.id).selectAll()

/** A row of [withMerchant]; [book] holds its split, if it has its own. */
private fun ResultRow.toExpense(book: SplitBook, lines: List<ExpenseLine>) = Expense(
    id = this[Expenses.id].toString(),
    date = this[Expenses.date],
    amountMinor = this[Expenses.amountMinor],
    merchantId = this[Expenses.merchantId]?.toString(),
    merchant = this.getOrNull(Merchants.name).orEmpty(),
    categoryId = this[Expenses.categoryId]?.toString(),
    accountId = this[Expenses.accountId]?.toString(),
    paidByUserId = this[Expenses.paidByUserId]?.toString(),
    notes = this[Expenses.notes],
    version = this[Expenses.version],
    tripId = this[Expenses.tripId]?.toString(),
    originalAmountMinor = this[Expenses.originalAmountMinor],
    originalCurrency = this[Expenses.originalCurrency],
    split = book.overrides[this[Expenses.id]]?.toShares(),
    createsDebt = this[Expenses.createsDebt],
    debts = book.debtsFor(toExpenseFacts(lines)),
    lines = lines,
)
