package dev.spendingtracker.server.inbox

import dev.spendingtracker.server.auth.userId
import dev.spendingtracker.server.common.badRequest
import dev.spendingtracker.server.common.checkVersioned
import dev.spendingtracker.server.common.conflict
import dev.spendingtracker.server.common.dateParam
import dev.spendingtracker.server.common.notFound
import dev.spendingtracker.server.common.optionalText
import dev.spendingtracker.server.common.parseId
import dev.spendingtracker.server.common.pathId
import dev.spendingtracker.server.common.requireVersion
import dev.spendingtracker.server.common.versionParam
import dev.spendingtracker.server.db.Accounts
import dev.spendingtracker.server.db.AssetContributions
import dev.spendingtracker.server.db.Assets
import dev.spendingtracker.server.db.BankFeeds
import dev.spendingtracker.server.db.BankRows
import dev.spendingtracker.server.db.Settlements
import dev.spendingtracker.server.db.Transfers
import dev.spendingtracker.server.db.dbQuery
import dev.spendingtracker.server.household.household
import dev.spendingtracker.server.household.isMember
import dev.spendingtracker.server.market.market
import dev.spendingtracker.shared.api.ApiError
import dev.spendingtracker.shared.api.AssetKind
import dev.spendingtracker.shared.api.BankRow
import dev.spendingtracker.shared.api.BankRowRef
import dev.spendingtracker.shared.api.SettleBankRows
import dev.spendingtracker.shared.api.Transfer
import dev.spendingtracker.shared.api.TransferBankRows
import dev.spendingtracker.shared.api.TransferInput
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
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
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
            description = "Transfers newest first, filtered by inclusive date range and optional account, with the bank rows they were made from."
            request {
                queryParameter<String>("from") { description = "Inclusive start date, YYYY-MM-DD" }
                queryParameter<String>("to") { description = "Inclusive end date, YYYY-MM-DD" }
                queryParameter<String>("accountId") { description = "Matches transfers from or to this account" }
            }
            response {
                code(HttpStatusCode.OK) { body<List<Transfer>>() }
                code(HttpStatusCode.BadRequest) { body<ApiError>() }
            }
        }) {
            val householdId = call.household.id
            val from = call.dateParam("from")
            val to = call.dateParam("to")
            val accountId = parseId(call.request.queryParameters["accountId"], "accountId")
            val transfers = dbQuery {
                var filter: Op<Boolean> = Transfers.householdId eq householdId
                if (from != null) filter = filter and (Transfers.date greaterEq from)
                if (to != null) filter = filter and (Transfers.date lessEq to)
                if (accountId != null) {
                    filter = filter and ((Transfers.fromAccountId eq accountId) or (Transfers.toAccountId eq accountId))
                }
                val found = Transfers.selectAll().where(filter)
                    .orderBy(Transfers.date to SortOrder.DESC, Transfers.id to SortOrder.ASC)
                    .toList()
                val rows = rowsByTransfer(found.map { it[Transfers.id] })
                found.map { it.toTransfer(rows[it[Transfers.id]]) }
            }
            call.respond(transfers)
        }

        post({
            operationId = "createTransfer"
            description = "Records a transfer between two distinct accounts of the household by hand."
            request { body<TransferInput>() }
            response {
                code(HttpStatusCode.Created) { body<Transfer>() }
                code(HttpStatusCode.BadRequest) { body<ApiError>() }
            }
        }) {
            val householdId = call.household.id
            val raw = call.receive<TransferInput>()
            val validated = raw.validated()
            val transfer = dbQuery {
                checkAccount(householdId, validated.fromId, "fromAccountId")
                checkAccount(householdId, validated.toId, "toAccountId")
                val id = insertTransfer(
                    householdId = householdId,
                    userId = call.userId,
                    date = validated.date,
                    amountMinor = validated.amountMinor,
                    fromAccountId = validated.fromId,
                    toAccountId = validated.toId,
                    notes = validated.notes,
                )
                Transfers.selectAll().where { match(householdId, id) }.single().toTransfer(null)
            }
            call.respond(HttpStatusCode.Created, transfer)
        }

        put("/{transferId}", {
            operationId = "updateTransfer"
            description = "Updates a transfer. On a transfer made from bank rows, only notes (and, for a one-row transfer, " +
                "the unread side's account) can change; anything else returns 409 transfer_from_bank_rows."
            request {
                pathParameter<String>("transferId")
                body<TransferInput>()
            }
            response {
                code(HttpStatusCode.OK) { body<Transfer>() }
                code(HttpStatusCode.BadRequest) { body<ApiError>() }
                code(HttpStatusCode.NotFound) { body<ApiError>() }
                code(HttpStatusCode.Conflict) { body<ApiError>() }
            }
        }) {
            val householdId = call.household.id
            val id = call.pathId("transferId", "Transfer")
            val raw = call.receive<TransferInput>()
            val version = requireVersion(raw.version)
            val validated = raw.validated(allowSameAccount = true)
            val transfer = dbQuery {
                val existing = Transfers.selectAll().where { match(householdId, id) }.singleOrNull()
                    ?: throw notFound("Transfer")
                val rows = rowsByTransfer(listOf(id))[id]
                if (rows != null && rows.rowIds.isNotEmpty()) {
                    if (validated.date != existing[Transfers.date] || validated.amountMinor != existing[Transfers.amountMinor]) {
                        throw conflict("transfer_from_bank_rows", "Date and amount come from the bank rows; only notes can change")
                    }
                    if (rows.rowIds.size >= 2) {
                        if (validated.fromId != existing[Transfers.fromAccountId] || validated.toId != existing[Transfers.toAccountId]) {
                            throw conflict("transfer_from_bank_rows", "Accounts come from the bank rows; only notes can change")
                        }
                    } else {
                        val rowAccount = rows.bankAccountIds.single()
                        val outgoingRow = rows.outgoing
                        if (outgoingRow && validated.fromId != existing[Transfers.fromAccountId]) {
                            throw conflict("transfer_from_bank_rows", "The outgoing account comes from the bank row")
                        }
                        if (!outgoingRow && validated.toId != existing[Transfers.toAccountId]) {
                            throw conflict("transfer_from_bank_rows", "The incoming account comes from the bank row")
                        }
                        val otherId = if (outgoingRow) validated.toId else validated.fromId
                        val otherField = if (outgoingRow) "toAccountId" else "fromAccountId"
                        if (otherId == rowAccount) {
                            throw badRequest("same_account", "The other side is the row's own account", otherField)
                        }
                        checkAccount(householdId, otherId, otherField)
                    }
                } else {
                    if (validated.fromId == validated.toId) {
                        throw badRequest("same_account", "Choose two different accounts", "toAccountId")
                    }
                    checkAccount(householdId, validated.fromId, "fromAccountId")
                    checkAccount(householdId, validated.toId, "toAccountId")
                }
                val affected = Transfers.update({ match(householdId, id) and (Transfers.version eq version) }) {
                    it[date] = validated.date
                    it[amountMinor] = validated.amountMinor
                    it[fromAccountId] = validated.fromId
                    it[toAccountId] = validated.toId
                    it[notes] = validated.notes
                    it[Transfers.version] = version + 1
                    it[updatedAt] = Clock.System.now()
                }
                checkVersioned(affected, { true }, "Transfer")
                syncLiabilityContribution(
                    householdId = householdId,
                    userId = call.userId,
                    transferId = id,
                    date = validated.date,
                    amountMinor = validated.amountMinor,
                    fromAccountId = validated.fromId,
                    toAccountId = validated.toId,
                    notes = validated.notes,
                )
                Transfers.selectAll().where { match(householdId, id) }.single().toTransfer(rows)
            }
            call.respond(transfer)
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
                val rowIds = rowsByTransfer(listOf(id))[id]?.rowIds.orEmpty()
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
    return insertTransfer(
        householdId = householdId,
        userId = userId,
        date = sides.date,
        amountMinor = amount,
        fromAccountId = from,
        toAccountId = to,
        notes = notes,
    )
}

private val liabilityDbKinds = AssetKind.entries.filter { it.liability }.map { it.name.lowercase() }

private fun insertTransfer(
    householdId: Uuid,
    userId: Uuid,
    date: LocalDate,
    amountMinor: Long,
    fromAccountId: Uuid,
    toAccountId: Uuid,
    notes: String,
): Uuid {
    val id = Uuid.random()
    Transfers.insert {
        it[Transfers.id] = id
        it[Transfers.householdId] = householdId
        it[Transfers.date] = date
        it[Transfers.amountMinor] = amountMinor
        it[Transfers.fromAccountId] = fromAccountId
        it[Transfers.toAccountId] = toAccountId
        it[Transfers.notes] = notes
        it[createdBy] = userId
    }
    syncLiabilityContribution(householdId, userId, id, date, amountMinor, fromAccountId, toAccountId, notes)
    return id
}

/**
 * Keeps the contribution on a liability linked to [fromAccountId] or [toAccountId] in sync with the transfer:
 * money into the liability's account is a repayment (positive), money out is borrowing (negative), with the other
 * side as the contribution's account.
 */
private fun syncLiabilityContribution(
    householdId: Uuid,
    userId: Uuid,
    transferId: Uuid,
    date: LocalDate,
    amountMinor: Long,
    fromAccountId: Uuid,
    toAccountId: Uuid,
    notes: String,
) {
    val linked = if (fromAccountId == toAccountId) {
        emptyMap()
    } else {
        Assets.select(Assets.id, Assets.accountId)
            .where {
                (Assets.householdId eq householdId) and
                    (Assets.kind inList liabilityDbKinds) and
                    (Assets.accountId inList listOf(fromAccountId, toAccountId))
            }
            .associate { it[Assets.accountId]!! to it[Assets.id] }
    }
    val target = when {
        toAccountId in linked -> Triple(linked.getValue(toAccountId), amountMinor, fromAccountId)
        fromAccountId in linked -> Triple(linked.getValue(fromAccountId), -amountMinor, toAccountId)
        else -> null
    }
    val existing = AssetContributions.select(AssetContributions.id, AssetContributions.assetId, AssetContributions.version)
        .where { (AssetContributions.householdId eq householdId) and (AssetContributions.transferId eq transferId) }
        .singleOrNull()
    if (target == null) {
        if (existing != null) {
            AssetContributions.deleteWhere {
                (AssetContributions.householdId eq householdId) and (AssetContributions.id eq existing[AssetContributions.id])
            }
        }
        return
    }
    val (assetId, signedAmount, otherAccountId) = target
    if (existing != null && existing[AssetContributions.assetId] == assetId) {
        AssetContributions.update({
            (AssetContributions.householdId eq householdId) and (AssetContributions.id eq existing[AssetContributions.id])
        }) {
            it[AssetContributions.date] = date
            it[AssetContributions.amountMinor] = signedAmount
            it[AssetContributions.accountId] = otherAccountId
            it[AssetContributions.notes] = notes
            it[AssetContributions.version] = existing[AssetContributions.version] + 1
            it[updatedAt] = Clock.System.now()
        }
        return
    }
    if (existing != null) {
        AssetContributions.deleteWhere {
            (AssetContributions.householdId eq householdId) and (AssetContributions.id eq existing[AssetContributions.id])
        }
    }
    AssetContributions.insert {
        it[AssetContributions.id] = Uuid.random()
        it[AssetContributions.householdId] = householdId
        it[AssetContributions.assetId] = assetId
        it[AssetContributions.date] = date
        it[AssetContributions.amountMinor] = signedAmount
        it[AssetContributions.accountId] = otherAccountId
        it[AssetContributions.transferId] = transferId
        it[AssetContributions.notes] = notes
        it[AssetContributions.version] = 1
        it[createdBy] = userId
    }
}

private data class ValidatedTransfer(
    val date: LocalDate,
    val amountMinor: Long,
    val fromId: Uuid,
    val toId: Uuid,
    val notes: String,
)

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

private fun TransferInput.validated(allowSameAccount: Boolean = false): ValidatedTransfer {
    if (amountMinor <= 0) throw badRequest("invalid_amount", "The amount has to be more than zero", "amountMinor")
    val fromId = parseId(fromAccountId, "fromAccountId")
        ?: throw badRequest("invalid_account", "Choose an account", "fromAccountId")
    val toId = parseId(toAccountId, "toAccountId")
        ?: throw badRequest("invalid_account", "Choose an account", "toAccountId")
    if (!allowSameAccount && fromId == toId) {
        throw badRequest("same_account", "Choose two different accounts", "toAccountId")
    }
    return ValidatedTransfer(date, amountMinor, fromId, toId, optionalText(notes, "notes", max = 2000))
}

private fun checkAccount(householdId: Uuid, accountId: Uuid, field: String) {
    if (Accounts.selectAll().where { (Accounts.id eq accountId) and (Accounts.householdId eq householdId) }.empty()) {
        throw badRequest("invalid_account", "Unknown account", field)
    }
}

private fun match(householdId: Uuid, id: Uuid) = (Transfers.id eq id) and (Transfers.householdId eq householdId)

private class TransferRows(val rowIds: List<Uuid>, val bankAccountIds: List<Uuid>, val outgoing: Boolean)

private fun rowsByTransfer(ids: List<Uuid>): Map<Uuid, TransferRows> {
    if (ids.isEmpty()) return emptyMap()
    return BankRows.join(BankFeeds, JoinType.INNER, BankRows.feedId, BankFeeds.id)
        .select(BankRows.id, BankRows.transferId, BankRows.amountMinor, BankFeeds.accountId)
        .where { BankRows.transferId inList ids }
        .toList()
        .sortedBy { it[BankRows.id] }
        .groupBy { it[BankRows.transferId]!! }
        .mapValues { (_, rows) ->
            TransferRows(
                rowIds = rows.map { it[BankRows.id] },
                bankAccountIds = rows.map { it[BankFeeds.accountId] }.distinct(),
                outgoing = rows.size == 1 && rows.single()[BankRows.amountMinor] < 0,
            )
        }
}

private fun ResultRow.toTransfer(rows: TransferRows?) = Transfer(
    id = this[Transfers.id].toString(),
    date = this[Transfers.date],
    amountMinor = this[Transfers.amountMinor],
    fromAccountId = this[Transfers.fromAccountId].toString(),
    toAccountId = this[Transfers.toAccountId].toString(),
    notes = this[Transfers.notes],
    version = this[Transfers.version],
    rowIds = rows?.rowIds.orEmpty().map { it.toString() },
    bankAccountIds = rows?.bankAccountIds.orEmpty().map { it.toString() },
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
