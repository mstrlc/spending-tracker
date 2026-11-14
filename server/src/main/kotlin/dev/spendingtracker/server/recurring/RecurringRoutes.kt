package dev.spendingtracker.server.recurring

import dev.spendingtracker.server.auth.userId
import dev.spendingtracker.server.category.dbValue
import dev.spendingtracker.server.common.badRequest
import dev.spendingtracker.server.common.checkVersioned
import dev.spendingtracker.server.common.conflict
import dev.spendingtracker.server.common.dateParam
import dev.spendingtracker.server.common.optionalText
import dev.spendingtracker.server.common.parseId
import dev.spendingtracker.server.common.pathId
import dev.spendingtracker.server.common.requireVersion
import dev.spendingtracker.server.common.versionParam
import dev.spendingtracker.server.db.Accounts
import dev.spendingtracker.server.db.Categories
import dev.spendingtracker.server.db.Expenses
import dev.spendingtracker.server.db.Merchants
import dev.spendingtracker.server.db.RecurringPayments
import dev.spendingtracker.server.db.dbQuery
import dev.spendingtracker.server.db.isUniqueViolation
import dev.spendingtracker.server.expense.loadRefundedMinor
import dev.spendingtracker.server.household.household
import dev.spendingtracker.server.household.isMember
import dev.spendingtracker.server.merchant.merchantNames
import dev.spendingtracker.server.overview.shift
import dev.spendingtracker.shared.api.ApiError
import dev.spendingtracker.shared.api.CategoryKind
import dev.spendingtracker.shared.api.RecurringHealth
import dev.spendingtracker.shared.api.RecurringOverview
import dev.spendingtracker.shared.api.RecurringPayment
import dev.spendingtracker.shared.api.RecurringPaymentInput
import dev.spendingtracker.shared.api.RecurringStatus
import dev.spendingtracker.shared.api.RecurringSuggestion
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.github.smiley4.ktoropenapi.put
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.todayIn
import kotlinx.datetime.yearMonth
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.math.roundToLong
import kotlin.time.Clock
import kotlin.uuid.Uuid

// Two years back is enough to see a yearly payment twice.
private const val DETECT_MONTHS = 25

/** Must be inside a HouseholdAccess scope. */
fun Route.recurringRoutes() = route("/recurring") {
    get({
        operationId = "getRecurring"
        description = "Recurring payments with how each stands on the given day, and merchants that look recurring " +
            "but aren't confirmed or dismissed yet."
        request {
            queryParameter<String>("today") { description = "The caller's date, YYYY-MM-DD; defaults to the server's" }
        }
        response {
            code(HttpStatusCode.OK) { body<RecurringOverview>() }
            code(HttpStatusCode.BadRequest) { body<ApiError>() }
        }
    }) {
        val today = call.today()
        val overview = dbQuery { loadOverview(call.household.id, today) }
        call.respond(overview)
    }

    post({
        operationId = "createRecurringPayment"
        description = "Confirms a merchant as recurring, or with status dismissed stops it being suggested. " +
            "A merchant has at most one: 409 recurring_exists."
        request {
            queryParameter<String>("today")
            body<RecurringPaymentInput>()
        }
        response {
            code(HttpStatusCode.Created) { body<RecurringPayment>() }
            code(HttpStatusCode.BadRequest) { body<ApiError>() }
            code(HttpStatusCode.Conflict) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val today = call.today()
        val raw = call.receive<RecurringPaymentInput>()
        val id = Uuid.random()
        val payment = onePerMerchant {
            dbQuery {
                val input = raw.validated(householdId)
                RecurringPayments.insert {
                    it[RecurringPayments.id] = id
                    it[RecurringPayments.householdId] = householdId
                    it.write(input)
                    it[createdBy] = call.userId
                }
                loadPayments(householdId, today, id).single()
            }
        }
        call.respond(HttpStatusCode.Created, payment)
    }

    put("/{recurringId}", {
        operationId = "updateRecurringPayment"
        request {
            pathParameter<String>("recurringId")
            queryParameter<String>("today")
            body<RecurringPaymentInput>()
        }
        response {
            code(HttpStatusCode.OK) { body<RecurringPayment>() }
            code(HttpStatusCode.BadRequest) { body<ApiError>() }
            code(HttpStatusCode.NotFound) { body<ApiError>() }
            code(HttpStatusCode.Conflict) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val id = call.pathId("recurringId", "Recurring payment")
        val today = call.today()
        val raw = call.receive<RecurringPaymentInput>()
        val version = requireVersion(raw.version)
        val payment = onePerMerchant {
            dbQuery {
                val input = raw.validated(householdId)
                val affected = RecurringPayments.update({ match(householdId, id) and (RecurringPayments.version eq version) }) {
                    it.write(input)
                    it[RecurringPayments.version] = version + 1
                    it[updatedAt] = Clock.System.now()
                }
                checkVersioned(affected, { exists(householdId, id) }, "Recurring payment")
                loadPayments(householdId, today, id).single()
            }
        }
        call.respond(payment)
    }

    delete("/{recurringId}", {
        operationId = "deleteRecurringPayment"
        description = "Forgets a recurring payment (or a dismissal): the merchant can be suggested again."
        request {
            pathParameter<String>("recurringId")
            queryParameter<Int>("version") { required = true }
        }
        response {
            code(HttpStatusCode.NoContent) {}
            code(HttpStatusCode.NotFound) { body<ApiError>() }
            code(HttpStatusCode.Conflict) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val id = call.pathId("recurringId", "Recurring payment")
        val version = call.versionParam()
        dbQuery {
            val affected = RecurringPayments.deleteWhere { match(householdId, id) and (RecurringPayments.version eq version) }
            checkVersioned(affected, { exists(householdId, id) }, "Recurring payment")
        }
        call.respond(HttpStatusCode.NoContent)
    }
}

private fun io.ktor.server.routing.RoutingCall.today(): LocalDate =
    dateParam("today") ?: Clock.System.todayIn(TimeZone.currentSystemDefault())

private data class ValidPayment(
    val merchantId: Uuid,
    val status: RecurringStatus,
    val expectedAmountMinor: Long,
    val intervalMonths: Int,
    val anchorDate: LocalDate,
    val categoryId: Uuid?,
    val accountId: Uuid?,
    val paidByUserId: Uuid?,
    val notes: String,
)

private val INTERVALS = setOf(1, 2, 3, 6, 12)

/** Call inside a transaction. */
private fun RecurringPaymentInput.validated(householdId: Uuid): ValidPayment {
    val merchant = parseId(merchantId, "merchantId")!!
    if (Merchants.selectAll().where { (Merchants.householdId eq householdId) and (Merchants.id eq merchant) }.empty()) {
        throw badRequest("invalid_merchant", "Unknown merchant", "merchantId")
    }
    if (expectedAmountMinor < 0) throw badRequest("invalid_amount", "The expected amount can't be negative", "expectedAmountMinor")
    if (intervalMonths !in INTERVALS) {
        throw badRequest("invalid_interval", "Every 1, 2, 3, 6 or 12 months", "intervalMonths")
    }
    val category = parseId(categoryId, "categoryId")
    if (category != null) {
        val kind = Categories.selectAll().where { (Categories.householdId eq householdId) and (Categories.id eq category) }
            .singleOrNull()?.get(Categories.kind)
        if (kind == null || kind == CategoryKind.INCOME.dbValue) throw badRequest("invalid_category", "Unknown category", "categoryId")
    }
    val account = parseId(accountId, "accountId")
    if (account != null && Accounts.selectAll().where { (Accounts.householdId eq householdId) and (Accounts.id eq account) }.empty()) {
        throw badRequest("invalid_account", "Unknown account", "accountId")
    }
    val whose = parseId(paidByUserId, "paidByUserId")
    if (whose != null && !isMember(householdId, whose)) {
        throw badRequest("invalid_member", "Whose must be a member of the household", "paidByUserId")
    }
    return ValidPayment(merchant, status, expectedAmountMinor, intervalMonths, anchorDate, category, account, whose, optionalText(notes, "notes"))
}

private fun org.jetbrains.exposed.v1.core.statements.UpdateBuilder<*>.write(input: ValidPayment) {
    this[RecurringPayments.merchantId] = input.merchantId
    this[RecurringPayments.status] = input.status.name.lowercase()
    this[RecurringPayments.expectedAmountMinor] = input.expectedAmountMinor
    this[RecurringPayments.intervalMonths] = input.intervalMonths
    this[RecurringPayments.anchorDate] = input.anchorDate
    this[RecurringPayments.categoryId] = input.categoryId
    this[RecurringPayments.accountId] = input.accountId
    this[RecurringPayments.paidByUserId] = input.paidByUserId
    this[RecurringPayments.notes] = input.notes
}

/** Each merchant's expenses on or after [from] as charges (refunds netted off), newest first. Call inside a transaction. */
private fun loadCharges(householdId: Uuid, from: LocalDate, merchantIds: Collection<Uuid>? = null): Map<Uuid, List<Charge>> {
    if (merchantIds?.isEmpty() == true) return emptyMap()
    var filter = (Expenses.householdId eq householdId) and Expenses.merchantId.isNotNull() and Expenses.refundOf.isNull() and
        (Expenses.date greaterEq from)
    if (merchantIds != null) filter = filter and (Expenses.merchantId inList merchantIds)
    val rows = Expenses.selectAll().where(filter).orderBy(Expenses.date to SortOrder.DESC).toList()
    val refunded = loadRefundedMinor(householdId, rows.map { it[Expenses.id] })
    return rows.groupBy({ it[Expenses.merchantId]!! }) {
        Charge(
            date = it[Expenses.date],
            amountMinor = it[Expenses.amountMinor] - (refunded[it[Expenses.id]] ?: 0L),
            categoryId = it[Expenses.categoryId],
            accountId = it[Expenses.accountId],
            paidByUserId = it[Expenses.paidByUserId],
        )
    }
}

/** The household's recurring payments (or just [id]) as they stand on [today]. Call inside a transaction. */
private fun loadPayments(householdId: Uuid, today: LocalDate, id: Uuid? = null): List<RecurringPayment> {
    var filter = RecurringPayments.householdId eq householdId
    if (id != null) filter = filter and (RecurringPayments.id eq id)
    val rows = RecurringPayments.selectAll().where(filter).toList()
    if (rows.isEmpty()) return emptyList()
    val earliest = rows.minOf { it[RecurringPayments.anchorDate] }.minus(31, DateTimeUnit.DAY)
    val charges = loadCharges(householdId, earliest, rows.map { it[RecurringPayments.merchantId] })
    val names = merchantNames(householdId)
    return rows.map { it.toPayment(names, charges[it[RecurringPayments.merchantId]].orEmpty(), today) }
}

private fun ResultRow.toPayment(names: Map<Uuid, String>, charges: List<Charge>, today: LocalDate): RecurringPayment {
    val status = RecurringStatus.valueOf(this[RecurringPayments.status].uppercase())
    val interval = this[RecurringPayments.intervalMonths]
    val anchor = this[RecurringPayments.anchorDate]
    val expected = this[RecurringPayments.expectedAmountMinor]
    val schedule = schedule(anchor, interval, expected, charges, today)
    val active = status == RecurringStatus.ACTIVE
    return RecurringPayment(
        id = this[RecurringPayments.id].toString(),
        merchantId = this[RecurringPayments.merchantId].toString(),
        merchant = names[this[RecurringPayments.merchantId]].orEmpty(),
        status = status,
        expectedAmountMinor = expected,
        intervalMonths = interval,
        anchorDate = anchor,
        categoryId = this[RecurringPayments.categoryId]?.toString(),
        accountId = this[RecurringPayments.accountId]?.toString(),
        paidByUserId = this[RecurringPayments.paidByUserId]?.toString(),
        notes = this[RecurringPayments.notes],
        version = this[RecurringPayments.version],
        health = if (active) schedule.health else RecurringHealth.INACTIVE,
        lastDue = schedule.lastDue,
        nextDue = schedule.nextDue,
        lastChargeDate = schedule.lastChargeDate,
        lastChargeMinor = schedule.lastChargeMinor,
        missedDates = if (active) schedule.missedDates else emptyList(),
    )
}

private val ATTENTION = listOf(RecurringHealth.MISSED, RecurringHealth.CHANGED, RecurringHealth.DUE)

private fun loadOverview(householdId: Uuid, today: LocalDate): RecurringOverview {
    val payments = loadPayments(householdId, today)
    val known = payments.map { Uuid.parse(it.merchantId) }.toSet()
    val names = merchantNames(householdId)
    val suggestions = loadCharges(householdId, today.yearMonth.shift(-DETECT_MONTHS).firstDay)
        .filterKeys { it !in known }
        .mapNotNull { (merchantId, charges) ->
            val found = detect(charges, today) ?: return@mapNotNull null
            RecurringSuggestion(
                merchantId = merchantId.toString(),
                merchant = names[merchantId].orEmpty(),
                intervalMonths = found.intervalMonths,
                amountMinor = found.amountMinor,
                anchorDate = found.latest.date,
                timesSeen = found.timesSeen,
                categoryId = found.latest.categoryId?.toString(),
                accountId = found.latest.accountId?.toString(),
                paidByUserId = found.latest.paidByUserId?.toString(),
            )
        }
        .sortedByDescending { it.amountMinor.toDouble() / it.intervalMonths }
    val order = compareBy<RecurringPayment>(
        { it.status.ordinal },
        { ATTENTION.indexOf(it.health).let { i -> if (i < 0) ATTENTION.size else i } },
        { it.nextDue },
        { it.merchant.lowercase() },
    )
    val active = payments.filter { it.status == RecurringStatus.ACTIVE }
    return RecurringOverview(
        payments = payments.sortedWith(order),
        suggestions = suggestions,
        monthlyMinor = active.sumOf { it.expectedAmountMinor.toDouble() / it.intervalMonths }.roundToLong(),
    )
}

private suspend fun <T> onePerMerchant(block: suspend () -> T): T =
    try {
        block()
    } catch (e: Exception) {
        if (e.isUniqueViolation()) throw conflict("recurring_exists", "This merchant already has a recurring payment", "merchantId")
        throw e
    }

private fun match(householdId: Uuid, id: Uuid) = (RecurringPayments.id eq id) and (RecurringPayments.householdId eq householdId)

private fun exists(householdId: Uuid, id: Uuid) = !RecurringPayments.selectAll().where { match(householdId, id) }.empty()
