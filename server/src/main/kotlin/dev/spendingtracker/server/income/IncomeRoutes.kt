package dev.spendingtracker.server.income

import dev.spendingtracker.server.auth.userId
import dev.spendingtracker.server.common.badRequest
import dev.spendingtracker.server.common.checkVersioned
import dev.spendingtracker.server.common.conflict
import dev.spendingtracker.server.common.dateParam
import dev.spendingtracker.server.common.nonZeroAmount
import dev.spendingtracker.server.common.optionalText
import dev.spendingtracker.server.common.parseId
import dev.spendingtracker.server.common.pathId
import dev.spendingtracker.server.common.requireVersion
import dev.spendingtracker.server.common.versionParam
import dev.spendingtracker.server.category.dbValue
import dev.spendingtracker.server.db.Accounts
import dev.spendingtracker.server.db.Categories
import dev.spendingtracker.server.db.Income
import dev.spendingtracker.server.db.dbQuery
import dev.spendingtracker.server.household.household
import dev.spendingtracker.server.inbox.fileBankRows
import dev.spendingtracker.server.household.isMember
import dev.spendingtracker.shared.api.ApiError
import dev.spendingtracker.shared.api.CategoryKind
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
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
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
            queryParameter<String>("categoryId")
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
        val categoryId = parseId(call.request.queryParameters["categoryId"], "categoryId")
        val accountId = parseId(call.request.queryParameters["accountId"], "accountId")
        val recipientUserId = parseId(call.request.queryParameters["recipientUserId"], "recipientUserId")
        val income = dbQuery {
            var filter: Op<Boolean> = Income.householdId eq householdId
            // Income belongs to the month it was earned in, whenever it arrived.
            if (from != null) filter = filter and (Income.earnedMonth greaterEq from.yearMonth.firstDay)
            if (to != null) filter = filter and (Income.earnedMonth lessEq to)
            if (categoryId != null) {
                val matching = (Categories.select(Categories.id)
                    .where { (Categories.householdId eq householdId) and (Categories.parentId eq categoryId) }
                    .map { it[Categories.id] } + categoryId).distinct()
                filter = filter and (Income.categoryId inList matching)
            }
            if (accountId != null) filter = filter and (Income.accountId eq accountId)
            if (recipientUserId != null) filter = filter and (Income.recipientUserId eq recipientUserId)
            val rows = Income.selectAll()
                .where(filter)
                .orderBy(Income.earnedMonth to SortOrder.DESC, Income.date to SortOrder.DESC, Income.id to SortOrder.ASC)
                .limit(LIST_LIMIT)
                .toList()
            val deductedByEarning = loadDeductedMinor(
                householdId,
                rows.filter { it[Income.kind] == IncomeKind.EARNING.dbValue }.map { it[Income.id] },
            )
            rows.map { it.toIncome(deductedByEarning[it[Income.id]] ?: 0L) }
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
            val deducted = loadDeductedMinor(householdId, listOf(id))[id] ?: 0L
            Income.selectAll().where { Income.id eq id }.single().toIncome(deducted)
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
        val input = raw.validated(onCreate = false)
        val income = dbQuery {
            input.checkReferences(householdId, selfId = id)
            val before = Income.select(Income.earnedMonth, Income.recipientUserId)
                .where { match(householdId, id) and (Income.version eq version) }
                .singleOrNull()
            val affected = Income.update({ match(householdId, id) and (Income.version eq version) }) {
                it.write(input)
                it[Income.version] = version + 1
                it[updatedAt] = Clock.System.now()
            }
            checkVersioned(affected, { exists(householdId, id) }, "Income")
            if (before != null) moveLinkedDeductions(householdId, id, before, input)
            val deducted = loadDeductedMinor(householdId, listOf(id))[id] ?: 0L
            Income.selectAll().where { Income.id eq id }.single().toIncome(deducted)
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
    val input = raw.validated(onCreate = true)
    val id = Uuid.random()
    input.checkReferences(householdId)
    Income.insert {
        it[Income.id] = id
        it[Income.householdId] = householdId
        it.write(input)
        it[version] = 1
        it[createdBy] = userId
    }
    for (d in input.deductions) {
        Income.insert {
            it[Income.id] = Uuid.random()
            it[Income.householdId] = householdId
            it[Income.date] = input.date
            it[Income.amountMinor] = d.amountMinor
            it[Income.sourceName] = d.source
            it[Income.accountId] = null
            it[Income.recipientUserId] = input.recipientUserId
            it[Income.notes] = d.notes
            it[Income.earnedMonth] = input.earnedMonth
            it[Income.kind] = IncomeKind.DEDUCTION.dbValue
            it[Income.deductedFrom] = id
            it[Income.version] = 1
            it[Income.createdBy] = userId
        }
    }
    return id
}

private data class ValidPayslipDeduction(
    val source: String,
    val amountMinor: Long,
    val notes: String,
)

private data class ValidIncome(
    val date: LocalDate,
    val amountMinor: Long,
    val source: String,
    val categoryId: Uuid?,
    val accountId: Uuid?,
    val recipientUserId: Uuid?,
    val notes: String,
    val earnedMonth: LocalDate,
    val kind: IncomeKind,
    val deductedFromId: Uuid?,
    val deductions: List<ValidPayslipDeduction>,
)

private fun IncomeInput.validated(onCreate: Boolean): ValidIncome {
    val categoryId = parseId(categoryId, "categoryId")
    if (categoryId != null && kind != IncomeKind.EARNING) {
        throw badRequest("invalid_category", "Deductions cannot have a category", "categoryId")
    }
    val deductedFrom = parseId(deductedFromId, "deductedFromId")
    if (deductedFrom != null && kind != IncomeKind.DEDUCTION) {
        throw badRequest("invalid_deducted_from", "Only a deduction can be linked to an earning", "deductedFromId")
    }
    if (!onCreate && deductions.isNotEmpty()) {
        throw badRequest("invalid_deductions", "Payslip deductions can only be added when creating an earning", "deductions")
    }
    if (deductions.isNotEmpty() && kind != IncomeKind.EARNING) {
        throw badRequest("invalid_deductions", "Only an earning can have payslip deductions", "deductions")
    }
    val validDeductions = deductions.mapIndexed { index, d ->
        ValidPayslipDeduction(
            source = optionalText(d.source, "deductions[$index].source", max = 200),
            amountMinor = nonZeroAmount(d.amountMinor, "deductions[$index].amountMinor"),
            notes = optionalText(d.notes, "deductions[$index].notes", max = 2000),
        )
    }
    return ValidIncome(
        date = date,
        amountMinor = nonZeroAmount(amountMinor),
        source = optionalText(source, "source", max = 200),
        categoryId = categoryId,
        accountId = parseId(accountId, "accountId"),
        recipientUserId = parseId(recipientUserId, "recipientUserId"),
        notes = optionalText(notes, "notes", max = 2000),
        earnedMonth = earnedMonth?.trim()?.takeIf { it.isNotEmpty() }?.let {
            runCatching { YearMonth.parse(it) }.getOrElse { throw badRequest("invalid_month", "earnedMonth must be YYYY-MM", "earnedMonth") }
        }?.firstDay ?: date.yearMonth.firstDay,
        kind = kind,
        deductedFromId = deductedFrom,
        deductions = validDeductions,
    )
}

/** Field-level errors for references the composite foreign keys would otherwise reject opaquely. */
private fun ValidIncome.checkReferences(householdId: Uuid, selfId: Uuid? = null) {
    if (categoryId != null) {
        val cat = Categories.select(Categories.kind)
            .where { (Categories.id eq categoryId) and (Categories.householdId eq householdId) }
            .singleOrNull()
            ?: throw badRequest("invalid_category", "Unknown category", "categoryId")
        if (cat[Categories.kind] != CategoryKind.INCOME.dbValue) {
            throw badRequest("invalid_category", "Income must use an income category", "categoryId")
        }
    }
    if (accountId != null &&
        Accounts.selectAll().where { (Accounts.id eq accountId) and (Accounts.householdId eq householdId) }.empty()
    ) {
        throw badRequest("invalid_account", "Unknown account", "accountId")
    }
    if (recipientUserId != null && !isMember(householdId, recipientUserId)) {
        throw badRequest("invalid_member", "Recipient must be a member of the household", "recipientUserId")
    }
    if (deductedFromId != null) {
        val target = Income.select(Income.kind)
            .where { (Income.householdId eq householdId) and (Income.id eq deductedFromId) }
            .singleOrNull()
            ?: throw badRequest("invalid_deducted_from", "Unknown earning to link to", "deductedFromId")
        if (deductedFromId == selfId || target[Income.kind] != IncomeKind.EARNING.dbValue) {
            throw badRequest("invalid_deducted_from", "A deduction must refer to an earning", "deductedFromId")
        }
    }
    if (selfId != null && kind != IncomeKind.EARNING &&
        !Income.select(Income.id).where { (Income.householdId eq householdId) and (Income.deductedFrom eq selfId) }.empty()
    ) {
        throw conflict("income_has_deductions", "Deductions are linked to this earning. Unlink or delete them first.")
    }
}

private fun org.jetbrains.exposed.v1.core.statements.UpdateBuilder<*>.write(input: ValidIncome) {
    this[Income.date] = input.date
    this[Income.amountMinor] = input.amountMinor
    this[Income.sourceName] = input.source
    this[Income.categoryId] = input.categoryId
    this[Income.accountId] = input.accountId
    this[Income.recipientUserId] = input.recipientUserId
    this[Income.notes] = input.notes
    this[Income.earnedMonth] = input.earnedMonth
    this[Income.kind] = input.kind.dbValue
    this[Income.deductedFrom] = input.deductedFromId
}

/**
 * Keeps an earning's linked deductions in step when its earned month or recipient changes: the deductions that had the
 * old value take the new one, so a payslip moved to another month or person moves whole. A deduction linked by hand
 * with a month or person of its own keeps it.
 */
private fun moveLinkedDeductions(householdId: Uuid, earningId: Uuid, before: ResultRow, input: ValidIncome) {
    val linked = (Income.householdId eq householdId) and (Income.deductedFrom eq earningId)
    val now = Clock.System.now()
    val oldMonth = before[Income.earnedMonth]
    if (oldMonth != input.earnedMonth) {
        Income.update({ linked and (Income.earnedMonth eq oldMonth) }) {
            it[Income.earnedMonth] = input.earnedMonth
            it[Income.version] = Income.version + 1
            it[updatedAt] = now
        }
    }
    val oldRecipient = before[Income.recipientUserId]
    if (oldRecipient != input.recipientUserId) {
        val hadOld = if (oldRecipient == null) Income.recipientUserId.isNull() else Income.recipientUserId eq oldRecipient
        Income.update({ linked and hadOld }) {
            it[Income.recipientUserId] = input.recipientUserId
            it[Income.version] = Income.version + 1
            it[updatedAt] = now
        }
    }
}

/**
 * Sum of linked deductions for each of [incomeIds] (positive when money was deducted); only earnings with linked
 * deductions are in the map. Call inside a transaction.
 */
fun loadDeductedMinor(householdId: Uuid, incomeIds: Collection<Uuid>): Map<Uuid, Long> {
    if (incomeIds.isEmpty()) return emptyMap()
    val deducted = Income.amountMinor.sum()
    return incomeIds.chunked(5_000).flatMap { chunk ->
        Income.select(Income.deductedFrom, deducted)
            .where { (Income.householdId eq householdId) and (Income.deductedFrom inList chunk) }
            .groupBy(Income.deductedFrom)
            .map { it[Income.deductedFrom]!! to (it[deducted] ?: 0L) }
    }.toMap()
}

val IncomeKind.dbValue get() = name.lowercase()

fun incomeKind(dbValue: String) = IncomeKind.entries.first { it.dbValue == dbValue }

/** Positive for what was earned, negative for what was deducted: what the line adds to net income. */
fun ResultRow.netIncomeMinor() =
    if (this[Income.kind] == IncomeKind.DEDUCTION.dbValue) -this[Income.amountMinor] else this[Income.amountMinor]

private fun match(householdId: Uuid, id: Uuid) = (Income.id eq id) and (Income.householdId eq householdId)

private fun exists(householdId: Uuid, id: Uuid) = !Income.selectAll().where { match(householdId, id) }.empty()

private fun ResultRow.toIncome(deductedMinor: Long = 0L) = IncomeDto(
    id = this[Income.id].toString(),
    date = this[Income.date],
    amountMinor = this[Income.amountMinor],
    source = this[Income.sourceName],
    categoryId = this[Income.categoryId]?.toString(),
    accountId = this[Income.accountId]?.toString(),
    recipientUserId = this[Income.recipientUserId]?.toString(),
    notes = this[Income.notes],
    version = this[Income.version],
    earnedMonth = this[Income.earnedMonth].yearMonth.toString(),
    kind = incomeKind(this[Income.kind]),
    deductedFromId = this[Income.deductedFrom]?.toString(),
    deductedMinor = if (this[Income.kind] == IncomeKind.EARNING.dbValue) deductedMinor else 0L,
)
