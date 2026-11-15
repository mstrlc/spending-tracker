package dev.spendingtracker.server.inbox

import dev.spendingtracker.server.auth.userId
import dev.spendingtracker.server.common.badRequest
import dev.spendingtracker.server.common.checkVersioned
import dev.spendingtracker.server.common.conflict
import dev.spendingtracker.server.common.dateParam
import dev.spendingtracker.server.common.optionalText
import dev.spendingtracker.server.common.parseId
import dev.spendingtracker.server.common.pathId
import dev.spendingtracker.server.common.versionParam
import dev.spendingtracker.server.db.Accounts
import dev.spendingtracker.server.db.BankFeeds
import dev.spendingtracker.server.db.BankRows
import dev.spendingtracker.server.db.Settlements
import dev.spendingtracker.server.db.Transfers
import dev.spendingtracker.server.db.dbQuery
import dev.spendingtracker.server.household.household
import dev.spendingtracker.server.household.isMember
import dev.spendingtracker.server.market.market
import dev.spendingtracker.shared.api.ApiError
import dev.spendingtracker.shared.api.BankRow
import dev.spendingtracker.shared.api.BankRowRef
import dev.spendingtracker.shared.api.SettleBankRows
import dev.spendingtracker.shared.api.Transfer
import dev.spendingtracker.shared.api.TransferBankRows
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
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
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Must be inside a HouseholdAccess scope. */
fun Route.transferRoutes() {
    route("/bank-rows") {
        post("/transfer", {
            operationId = "transferBankRows"
            description = "Files pending rows as one transfer between the household's own accounts: an outgoing and an incoming row " +
                "of different feeds, or one row with the other account given. Foreign amounts are converted at the ČNB fixing."
            request { body<TransferBankRows>() }
            response {
                code(HttpStatusCode.OK) { body<List<BankRow>>() }
                code(HttpStatusCode.BadRequest) { body<ApiError>() }
                code(HttpStatusCode.NotFound) { body<ApiError>() }
                code(HttpStatusCode.Conflict) { body<ApiError>() }
            }
        }) {
            val householdId = call.household.id
            val input = call.receive<TransferBankRows>()
            val notes = optionalText(input.notes, "notes", max = 2000)
            val otherAccountId = parseId(input.otherAccountId, "otherAccountId")
            if (input.rows.size == 2 && otherAccountId != null) {
                throw badRequest("other_account_given", "Two rows already name both accounts", "otherAccountId")
            }
            call.refreshRates(input.rows)
            val rows = dbQuery {
                val id = createTransfer(householdId, call.userId, call.household.currency, lockRows(householdId, input.rows), otherAccountId, notes)
                fileBankRows(householdId, input.rows, call.userId, transferId = id, created = true)
                reload(householdId, input.rows.map { Uuid.parse(it.id) })
            }
            call.respond(rows)
        }

        post("/settle", {
            operationId = "settleBankRows"
            description = "Files pending rows (one or two, as for a transfer) as a settlement: one member paying another back. " +
                "It shows in the settlements and moves the balance between them."
            request { body<SettleBankRows>() }
            response {
                code(HttpStatusCode.OK) { body<List<BankRow>>() }
                code(HttpStatusCode.BadRequest) { body<ApiError>() }
                code(HttpStatusCode.NotFound) { body<ApiError>() }
                code(HttpStatusCode.Conflict) { body<ApiError>() }
            }
        }) {
            val householdId = call.household.id
            val input = call.receive<SettleBankRows>()
            val notes = optionalText(input.notes, "notes", max = 2000)
            val from = parseId(input.fromUserId, "fromUserId") ?: throw badRequest("invalid_member", "Say who paid", "fromUserId")
            val to = parseId(input.toUserId, "toUserId") ?: throw badRequest("invalid_member", "Say who was paid", "toUserId")
            if (from == to) throw badRequest("same_member", "Someone can't pay themselves back", "toUserId")
            call.refreshRates(input.rows)
            val rows = dbQuery {
                if (!isMember(householdId, from)) throw badRequest("invalid_member", "Must be a member of the household", "fromUserId")
                if (!isMember(householdId, to)) throw badRequest("invalid_member", "Must be a member of the household", "toUserId")
                val id = createSettlement(householdId, call.userId, call.household.currency, lockRows(householdId, input.rows), from, to, notes)
                fileBankRows(householdId, input.rows, call.userId, settlementId = id, created = true)
                reload(householdId, input.rows.map { Uuid.parse(it.id) })
            }
            call.respond(rows)
        }
    }

    route("/transfers") {
        get({
            operationId = "listTransfers"
            description = "Transfers newest first, filtered by inclusive date range, with the bank rows they were made from."
            request {
                queryParameter<String>("from") { description = "Inclusive start date, YYYY-MM-DD" }
                queryParameter<String>("to") { description = "Inclusive end date, YYYY-MM-DD" }
            }
            response {
                code(HttpStatusCode.OK) { body<List<Transfer>>() }
                code(HttpStatusCode.BadRequest) { body<ApiError>() }
            }
        }) {
            val householdId = call.household.id
            val from = call.dateParam("from")
            val to = call.dateParam("to")
            val transfers = dbQuery {
                var filter: Op<Boolean> = Transfers.householdId eq householdId
                if (from != null) filter = filter and (Transfers.date greaterEq from)
                if (to != null) filter = filter and (Transfers.date lessEq to)
                val found = Transfers.selectAll().where(filter)
                    .orderBy(Transfers.date to SortOrder.DESC, Transfers.id to SortOrder.ASC)
                    .toList()
                val rowIds = rowIdsByTransfer(found.map { it[Transfers.id] })
                found.map { it.toTransfer(rowIds[it[Transfers.id]].orEmpty()) }
            }
            call.respond(transfers)
        }

        delete("/{transferId}", {
            operationId = "deleteTransfer"
            description = "Deletes a transfer. The bank rows it was made from go back to the inbox."
            request {
                pathParameter<String>("transferId")
                queryParameter<Int>("version") { required = true }
            }
            response {
                code(HttpStatusCode.NoContent) {}
                code(HttpStatusCode.NotFound) { body<ApiError>() }
                code(HttpStatusCode.Conflict) { body<ApiError>() }
            }
        }) {
            val householdId = call.household.id
            val id = call.pathId("transferId", "Transfer")
            val version = call.versionParam()
            dbQuery {
                val rowIds = rowIdsByTransfer(listOf(id))[id].orEmpty()
                val affected = Transfers.deleteWhere { match(householdId, id) and (Transfers.version eq version) }
                checkVersioned(affected, { !Transfers.selectAll().where { match(householdId, id) }.empty() }, "Transfer")
                // The rows are in the inbox again (the link went with the transfer): clear what was decided about them.
                if (rowIds.isNotEmpty()) {
                    val now = Clock.System.now()
                    BankRows.update({ BankRows.id inList rowIds }) {
                        it[createdRecord] = false
                        it[decidedBy] = null
                        it[decidedAuto] = false
                        it[BankRows.ruleId] = null
                        it[decidedAt] = null
                        it[BankRows.version] = BankRows.version + 1
                        it[updatedAt] = now
                    }
                }
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/**
 * Creates the transfer [rows] (one or two locked, pending rows) make: with two, an outgoing and an incoming one of
 * different feeds; with one, [otherAccountId] is the account on the other side. Amounts in a foreign currency are
 * converted at the ČNB fixing. Returns its id; filing the rows under it is up to the caller. Call inside a transaction.
 */
fun createTransfer(
    householdId: Uuid,
    userId: Uuid,
    householdCurrency: String,
    rows: List<ResultRow>,
    otherAccountId: Uuid?,
    notes: String,
): Uuid {
    val sides = Sides.of(rows)
    val other = when {
        sides.rows.size == 1 -> {
            if (otherAccountId == null) throw badRequest("other_account_required", "Say which account the other side is", "otherAccountId")
            if (Accounts.selectAll().where { (Accounts.id eq otherAccountId) and (Accounts.householdId eq householdId) }.empty()) {
                throw badRequest("invalid_account", "Unknown account", "otherAccountId")
            }
            if (otherAccountId == sides.rows.single()[BankFeeds.accountId]) {
                throw badRequest("same_account", "The other side is the row's own account", "otherAccountId")
            }
            otherAccountId
        }
        else -> null
    }
    val amount = sides.amount(householdCurrency)
    val from = sides.out?.get(BankFeeds.accountId) ?: other!!
    val to = sides.inn?.get(BankFeeds.accountId) ?: other!!
    val id = Uuid.random()
    Transfers.insert {
        it[Transfers.id] = id
        it[Transfers.householdId] = householdId
        it[date] = sides.date
        it[amountMinor] = amount
        it[fromAccountId] = from
        it[toAccountId] = to
        it[Transfers.notes] = notes
        it[createdBy] = userId
    }
    return id
}

/**
 * Inserts a settlement made from [rows] (one or two, as for a transfer) between [from] and [to].
 * Call inside a transaction, after exchange rates are fresh.
 */
fun createSettlement(
    householdId: Uuid,
    userId: Uuid,
    householdCurrency: String,
    rows: List<ResultRow>,
    from: Uuid,
    to: Uuid,
    notes: String,
): Uuid {
    val sides = Sides.of(rows)
    val amount = sides.amount(householdCurrency)
    val id = Uuid.random()
    Settlements.insert {
        it[Settlements.id] = id
        it[Settlements.householdId] = householdId
        it[date] = sides.date
        it[fromUserId] = from
        it[toUserId] = to
        it[amountMinor] = amount
        it[Settlements.notes] = notes
        it[createdBy] = userId
    }
    return id
}

private fun match(householdId: Uuid, id: Uuid) = (Transfers.id eq id) and (Transfers.householdId eq householdId)

private fun rowIdsByTransfer(ids: List<Uuid>): Map<Uuid, List<Uuid>> {
    if (ids.isEmpty()) return emptyMap()
    return BankRows.select(BankRows.id, BankRows.transferId).where { BankRows.transferId inList ids }
        .toList().sortedBy { it[BankRows.id] }
        .groupBy({ it[BankRows.transferId]!! }, { it[BankRows.id] })
}

private fun ResultRow.toTransfer(rowIds: List<Uuid>) = Transfer(
    id = this[Transfers.id].toString(),
    date = this[Transfers.date],
    amountMinor = this[Transfers.amountMinor],
    fromAccountId = this[Transfers.fromAccountId].toString(),
    toAccountId = this[Transfers.toAccountId].toString(),
    notes = this[Transfers.notes],
    version = this[Transfers.version],
    rowIds = rowIds.map { it.toString() },
)

/** Brings the exchange rates for the foreign rows' dates up to date, before they're converted. */
private suspend fun RoutingCall.refreshRates(refs: List<BankRowRef>) {
    val household = household
    val ids = refs.mapNotNull { parseId(it.id, "rows") }
    if (ids.isEmpty()) return
    val dates = dbQuery {
        BankRows.select(BankRows.date).where {
            (BankRows.householdId eq household.id) and (BankRows.id inList ids) and (BankRows.currency neq household.currency)
        }.map { it[BankRows.date] }.distinct()
    }
    application.market.refreshFx(dates)
}

/** The one or two pending rows a transfer or settlement is made from, told apart by direction. */
private class Sides(val rows: List<ResultRow>, val out: ResultRow?, val inn: ResultRow?) {
    /** The outgoing row's day, else the only row's. */
    val date: LocalDate get() = (out ?: inn!!)[BankRows.date]

    /** What moved, in the household currency: the outgoing row's amount, else the incoming one's. */
    fun amount(householdCurrency: String): Long {
        val row = out ?: inn!!
        val native = abs(row[BankRows.amountMinor])
        val currency = row[BankRows.currency]
        val converted = if (currency == householdCurrency) native else
            FxTable.load(setOf(currency, householdCurrency)).convert(native, currency, householdCurrency, row[BankRows.date])
                ?: throw badRequest("no_rate", "There's no exchange rate for $currency on ${row[BankRows.date]} yet", "rows")
        if (converted <= 0) throw badRequest("invalid_amount", "The amount has to be more than zero", "rows")
        return converted
    }

    companion object {
        fun of(rows: List<ResultRow>): Sides {
            if (rows.size > 2) throw badRequest("too_many_rows", "Pick one row, or an outgoing and an incoming one", "rows")
            if (rows.any { !it.isPending() }) throw conflict("already_decided", "A bank row was already decided. Reload the inbox.", "rows")
            if (rows.any { it[BankRows.amountMinor] == 0L }) throw badRequest("invalid_amount", "A row without an amount can't be moved", "rows")
            val out = rows.filter { it[BankRows.amountMinor] < 0 }
            val inn = rows.filter { it[BankRows.amountMinor] > 0 }
            if (rows.size == 2) {
                if (out.size != 1) throw badRequest("not_a_pair", "Pick one outgoing and one incoming row", "rows")
                if (rows[0][BankRows.feedId] == rows[1][BankRows.feedId]) {
                    throw badRequest("same_feed", "Both rows are from the same feed", "rows")
                }
            }
            return Sides(rows, out.singleOrNull(), inn.singleOrNull())
        }
    }
}
