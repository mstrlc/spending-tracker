package dev.spendingtracker.server.inbox

import dev.spendingtracker.server.account.normalizeIban
import dev.spendingtracker.server.auth.userId
import dev.spendingtracker.server.common.ApiException
import dev.spendingtracker.server.common.badRequest
import dev.spendingtracker.server.common.checkVersioned
import dev.spendingtracker.server.common.conflict
import dev.spendingtracker.server.common.dateParam
import dev.spendingtracker.server.common.notFound
import dev.spendingtracker.server.common.parseId
import dev.spendingtracker.server.common.pathId
import dev.spendingtracker.server.common.requireVersion
import dev.spendingtracker.server.common.requiredText
import dev.spendingtracker.server.common.versionParam
import dev.spendingtracker.server.db.Accounts
import dev.spendingtracker.server.bank.startOfPragueDay
import dev.spendingtracker.server.db.BankBalances
import dev.spendingtracker.server.db.BankFeeds
import dev.spendingtracker.server.db.BankSyncs
import dev.spendingtracker.server.db.BankRows
import dev.spendingtracker.server.db.Expenses
import dev.spendingtracker.server.db.Income
import dev.spendingtracker.server.db.Merchants
import dev.spendingtracker.server.db.Settlements
import dev.spendingtracker.server.db.Transfers
import dev.spendingtracker.server.db.Users
import dev.spendingtracker.server.db.dbQuery
import dev.spendingtracker.server.db.isUniqueViolation
import dev.spendingtracker.server.household.household
import dev.spendingtracker.server.market.market
import dev.spendingtracker.shared.api.ApiError
import dev.spendingtracker.shared.api.BankFeed
import dev.spendingtracker.shared.api.BankFeedInput
import dev.spendingtracker.shared.api.BankRow
import dev.spendingtracker.shared.api.FeedBalance
import dev.spendingtracker.shared.api.LinkBankRows
import dev.spendingtracker.shared.api.SkipBankRows
import dev.spendingtracker.shared.api.StatementResult
import dev.spendingtracker.shared.api.StatementUpload
import dev.spendingtracker.shared.api.UndoBankRows
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
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.not
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val ROW_LIMIT = 2_000

/** Must be inside a HouseholdAccess scope. */
fun Route.inboxRoutes() {
    feedRoutes()
    bankRowRoutes()
    transferRoutes()
    ruleRoutes()
}

private fun Route.feedRoutes() = route("/bank-feeds") {
    get({
        operationId = "listBankFeeds"
        response { code(HttpStatusCode.OK) { body<List<BankFeed>>() } }
    }) {
        val householdId = call.household.id
        call.respond(dbQuery { loadFeeds(householdId) })
    }

    post({
        operationId = "createBankFeed"
        request { body<BankFeedInput>() }
        response {
            code(HttpStatusCode.Created) { body<BankFeed>() }
            code(HttpStatusCode.BadRequest) { body<ApiError>() }
            code(HttpStatusCode.Conflict) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val input = call.receive<BankFeedInput>().validated()
        val id = Uuid.random()
        val feed = uniqueName {
            dbQuery {
                input.checkAccount(householdId)
                BankFeeds.insert {
                    it[BankFeeds.id] = id
                    it[BankFeeds.householdId] = householdId
                    it.write(input)
                    it[feedSource] = "file"
                    it[createdBy] = call.userId
                }
                loadFeeds(householdId, id).single()
            }
        }
        call.respond(HttpStatusCode.Created, feed)
    }

    put("/{feedId}", {
        operationId = "updateBankFeed"
        request {
            pathParameter<String>("feedId")
            body<BankFeedInput>()
        }
        response {
            code(HttpStatusCode.OK) { body<BankFeed>() }
            code(HttpStatusCode.BadRequest) { body<ApiError>() }
            code(HttpStatusCode.NotFound) { body<ApiError>() }
            code(HttpStatusCode.Conflict) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val id = call.pathId("feedId", "Bank feed")
        val raw = call.receive<BankFeedInput>()
        val version = requireVersion(raw.version)
        val input = raw.validated()
        val feed = uniqueName {
            dbQuery {
                input.checkAccount(householdId)
                val affected = BankFeeds.update({ match(householdId, id) and (BankFeeds.version eq version) }) {
                    it.write(input)
                    it[BankFeeds.version] = version + 1
                    it[updatedAt] = Clock.System.now()
                }
                checkVersioned(affected, { exists(householdId, id) }, "Bank feed")
                loadFeeds(householdId, id).single()
            }
        }
        call.respond(feed)
    }

    delete("/{feedId}", {
        operationId = "deleteBankFeed"
        description = "Deletes a feed with its rows. A feed with decided rows can't be deleted: undo them first."
        request {
            pathParameter<String>("feedId")
            queryParameter<Int>("version") { required = true }
        }
        response {
            code(HttpStatusCode.NoContent) {}
            code(HttpStatusCode.NotFound) { body<ApiError>() }
            code(HttpStatusCode.Conflict) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val id = call.pathId("feedId", "Bank feed")
        val version = call.versionParam()
        dbQuery {
            val decided = BankRows.selectAll().where { (BankRows.feedId eq id) and not(pendingRows()) }.count()
            if (decided > 0) throw conflict("feed_in_use", "$decided of this feed's rows are decided. Undo them first.")
            val affected = BankFeeds.deleteWhere { match(householdId, id) and (BankFeeds.version eq version) }
            checkVersioned(affected, { exists(householdId, id) }, "Bank feed")
        }
        call.respond(HttpStatusCode.NoContent)
    }

    post("/{feedId}/statements", {
        operationId = "uploadStatement"
        description = "Reads a statement into the feed: a bank's CSV export or GoCardless transactions JSON. Rows the feed has already are left as they are."
        request {
            pathParameter<String>("feedId")
            body<StatementUpload>()
        }
        response {
            code(HttpStatusCode.OK) { body<StatementResult>() }
            code(HttpStatusCode.BadRequest) { body<ApiError>() }
            code(HttpStatusCode.NotFound) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val id = call.pathId("feedId", "Bank feed")
        val upload = call.receive<StatementUpload>()
        val household = call.household
        val (result, added) = dbQuery {
            val feed = BankFeeds.selectAll().where { match(householdId, id) }.singleOrNull() ?: throw notFound("Bank feed")
            val shape = feedShape(feed[BankFeeds.institution], feed[BankFeeds.accountId])
            val statement = try {
                readStatement(upload.content, shape)
            } catch (e: StatementException) {
                throw badRequest("unreadable_statement", e.message, "content")
            }
            val foreign = statement.rows.map { it.currency }.filter { it != feed[BankFeeds.currency] }.distinct()
            if (foreign.isNotEmpty()) {
                throw badRequest(
                    "currency_mismatch",
                    "The statement has rows in ${foreign.joinToString()}; this feed is ${feed[BankFeeds.currency]}. Add a feed for each currency.",
                    "content",
                )
            }
            val counts = storeRows(householdId, id, shape, feed[BankFeeds.importFrom], statement.rows, loadHintContext(householdId))
            StatementResult(statement.format, counts.added, counts.existing, counts.beforeImportFrom, statement.notBooked) to
                addedRows(id, counts.addedIds)
        }
        // The rules file what they can of the new rows, with foreign amounts converted at the ČNB fixings.
        val years = added.filter { it[BankRows.currency] != household.currency }.map { it[BankRows.date].year }.distinct()
        if (years.isNotEmpty()) call.application.market.refresh(emptyList(), years)
        if (added.isNotEmpty()) {
            dbQuery { applyRules(householdId, household.currency, call.userId, added.map { it[BankRows.id] }.toSet()) }
        }
        call.respond(result)
    }
}

private fun Route.bankRowRoutes() = route("/bank-rows") {
    get({
        operationId = "listBankRows"
        description = "Bank rows newest first: pending ones (the inbox, with suggestions), decided ones, or all, " +
            "optionally of one feed and within an inclusive date range."
        request {
            queryParameter<String>("status") { description = "pending (default), decided or all" }
            queryParameter<String>("feedId")
            queryParameter<String>("from") { description = "Inclusive start date, YYYY-MM-DD" }
            queryParameter<String>("to") { description = "Inclusive end date, YYYY-MM-DD" }
        }
        response {
            code(HttpStatusCode.OK) { body<List<BankRow>>() }
            code(HttpStatusCode.BadRequest) { body<ApiError>() }
        }
    }) {
        val household = call.household
        val status = call.request.queryParameters["status"] ?: "pending"
        if (status !in setOf("pending", "decided", "all")) throw badRequest("invalid_status", "status must be pending, decided or all", "status")
        val feedId = parseId(call.request.queryParameters["feedId"], "feedId")
        val from = call.dateParam("from")
        val to = call.dateParam("to")
        val pending = pendingRows()
        var filter: Op<Boolean> = BankRows.householdId eq household.id
        if (status == "pending") filter = filter and pending
        if (status == "decided") filter = filter and not(pending)
        if (feedId != null) filter = filter and (BankRows.feedId eq feedId)
        if (from != null) filter = filter and (BankRows.date greaterEq from)
        if (to != null) filter = filter and (BankRows.date lessEq to)

        // Foreign rows are converted at ČNB fixings: bring the years in question up to date first.
        val years = dbQuery {
            BankRows.select(BankRows.date, BankRows.currency).where(filter and pending and (BankRows.currency neq household.currency))
                .map { it[BankRows.date].year }.distinct()
        }
        if (years.isNotEmpty()) call.application.market.refresh(emptyList(), years)

        val rows = dbQuery {
            val found = rowsWithFeed().where(filter)
                .orderBy(BankRows.date to SortOrder.DESC, BankRows.externalId to SortOrder.ASC)
                .limit(ROW_LIMIT)
                .toList()
            val suggestions = suggestFor(household.id, household.currency, found.filter { it.isPending() })
            val labels = outcomeLabels(household.id, found)
            found.map { it.toBankRow(suggestions[it[BankRows.id]], labels[it[BankRows.id]]) }
        }
        call.respond(rows)
    }

    post("/link", {
        operationId = "linkBankRows"
        description = "Files pending rows under an existing expense or income line (exactly one)."
        request { body<LinkBankRows>() }
        response {
            code(HttpStatusCode.OK) { body<List<BankRow>>() }
            code(HttpStatusCode.BadRequest) { body<ApiError>() }
            code(HttpStatusCode.NotFound) { body<ApiError>() }
            code(HttpStatusCode.Conflict) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val input = call.receive<LinkBankRows>()
        val expenseId = parseId(input.expenseId, "expenseId")
        val incomeId = parseId(input.incomeId, "incomeId")
        if ((expenseId == null) == (incomeId == null)) {
            throw badRequest("link_target", "Give an expense or an income line to file the rows under", "expenseId")
        }
        val rows = dbQuery {
            if (expenseId != null && Expenses.selectAll().where { (Expenses.householdId eq householdId) and (Expenses.id eq expenseId) }.empty()) {
                throw badRequest("invalid_expense", "Unknown expense", "expenseId")
            }
            if (incomeId != null && Income.selectAll().where { (Income.householdId eq householdId) and (Income.id eq incomeId) }.empty()) {
                throw badRequest("invalid_income", "Unknown income line", "incomeId")
            }
            fileBankRows(householdId, input.rows, call.userId, expenseId = expenseId, incomeId = incomeId)
            reload(householdId, input.rows.map { Uuid.parse(it.id) })
        }
        call.respond(rows)
    }

    post("/skip", {
        operationId = "skipBankRows"
        description = "Skips pending rows with a reason: not spending or income (money between own accounts, a top-up), or already covered."
        request { body<SkipBankRows>() }
        response {
            code(HttpStatusCode.OK) { body<List<BankRow>>() }
            code(HttpStatusCode.BadRequest) { body<ApiError>() }
            code(HttpStatusCode.NotFound) { body<ApiError>() }
            code(HttpStatusCode.Conflict) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val input = call.receive<SkipBankRows>()
        val reason = requiredText(input.reason, "reason", max = 200)
        val rows = dbQuery {
            val locked = lockRows(householdId, input.rows)
            if (locked.any { !it.isPending() }) throw conflict("already_decided", "A bank row was already decided. Reload the inbox.", "rows")
            val now = Clock.System.now()
            BankRows.update({ BankRows.id inList locked.map { it[BankRows.id] } }) {
                it[skipReason] = reason
                it[decidedBy] = call.userId
                it[decidedAuto] = false
                it[decidedAt] = now
                it[version] = BankRows.version + 1
                it[updatedAt] = now
            }
            reload(householdId, locked.map { it[BankRows.id] })
        }
        call.respond(rows)
    }

    post("/undo", {
        operationId = "undoBankRows"
        description = "Puts decided rows back in the inbox. An expense, income line, transfer or settlement made from them is deleted once none of its rows is left."
        request { body<UndoBankRows>() }
        response {
            code(HttpStatusCode.OK) { body<List<BankRow>>() }
            code(HttpStatusCode.BadRequest) { body<ApiError>() }
            code(HttpStatusCode.NotFound) { body<ApiError>() }
            code(HttpStatusCode.Conflict) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val input = call.receive<UndoBankRows>()
        val rows = dbQuery {
            undoBankRows(householdId, input.rows)
            reload(householdId, input.rows.map { Uuid.parse(it.id) })
        }
        call.respond(rows)
    }
}

fun reload(householdId: Uuid, ids: List<Uuid>): List<BankRow> {
    val rows = rowsWithFeed().where { (BankRows.householdId eq householdId) and (BankRows.id inList ids) }.associateBy { it[BankRows.id] }
    val labels = outcomeLabels(householdId, rows.values.toList())
    return ids.mapNotNull { id -> rows[id]?.let { it.toBankRow(outcomeLabel = labels[id]) } }
}

private fun outcomeLabels(householdId: Uuid, rows: List<ResultRow>): Map<Uuid, String> {
    val expenseIds = rows.mapNotNull { it[BankRows.expenseId] }.distinct()
    val incomeIds = rows.mapNotNull { it[BankRows.incomeId] }.distinct()
    val transferIds = rows.mapNotNull { it[BankRows.transferId] }.distinct()
    val settlementIds = rows.mapNotNull { it[BankRows.settlementId] }.distinct()
    if (expenseIds.isEmpty() && incomeIds.isEmpty() && transferIds.isEmpty() && settlementIds.isEmpty()) return emptyMap()

    val expenses = if (expenseIds.isEmpty()) emptyMap() else {
        val expMerchants = Expenses
            .select(Expenses.id, Expenses.merchantId)
            .where { (Expenses.householdId eq householdId) and (Expenses.id inList expenseIds) }
            .associate { it[Expenses.id] to it[Expenses.merchantId] }
        val merchantIds = expMerchants.values.filterNotNull().distinct()
        val merchantNames = if (merchantIds.isEmpty()) emptyMap() else Merchants
            .select(Merchants.id, Merchants.name)
            .where { Merchants.id inList merchantIds }
            .associate { it[Merchants.id] to it[Merchants.name] }
        expMerchants.mapValues { (_, merchantId) -> merchantId?.let(merchantNames::get).orEmpty() }
    }
    val incomes = if (incomeIds.isEmpty()) emptyMap() else Income
        .select(Income.id, Income.sourceName)
        .where { (Income.householdId eq householdId) and (Income.id inList incomeIds) }
        .associate { it[Income.id] to it[Income.sourceName] }
    val accountNames = if (transferIds.isEmpty()) emptyMap() else Accounts
        .select(Accounts.id, Accounts.name)
        .where { Accounts.householdId eq householdId }
        .associate { it[Accounts.id] to it[Accounts.name] }
    val transfers = if (transferIds.isEmpty()) emptyMap() else Transfers
        .select(Transfers.id, Transfers.fromAccountId, Transfers.toAccountId)
        .where { (Transfers.householdId eq householdId) and (Transfers.id inList transferIds) }
        .associate {
            val from = accountNames[it[Transfers.fromAccountId]].orEmpty()
            val to = accountNames[it[Transfers.toAccountId]].orEmpty()
            it[Transfers.id] to "$from → $to"
        }
    val memberNames = if (settlementIds.isEmpty()) emptyMap() else Users
        .select(Users.id, Users.displayName)
        .associate { it[Users.id] to it[Users.displayName] }
    val settlements = if (settlementIds.isEmpty()) emptyMap() else Settlements
        .select(Settlements.id, Settlements.fromUserId, Settlements.toUserId)
        .where { (Settlements.householdId eq householdId) and (Settlements.id inList settlementIds) }
        .associate {
            val from = memberNames[it[Settlements.fromUserId]].orEmpty()
            val to = memberNames[it[Settlements.toUserId]].orEmpty()
            it[Settlements.id] to "$from → $to"
        }

    return rows.mapNotNull { row ->
        val label = row[BankRows.expenseId]?.let(expenses::get)
            ?: row[BankRows.incomeId]?.let(incomes::get)
            ?: row[BankRows.transferId]?.let(transfers::get)
            ?: row[BankRows.settlementId]?.let(settlements::get)
        label?.let { row[BankRows.id] to it }
    }.toMap()
}

private data class ValidFeed(
    val accountId: Uuid,
    val name: String,
    val institution: String,
    val iban: String?,
    val currency: String,
    val importFrom: LocalDate?,
)

private fun BankFeedInput.validated(): ValidFeed {
    val code = currency.trim().uppercase()
    if (!Regex("^[A-Z]{3}$").matches(code)) throw badRequest("invalid_currency", "Use a 3-letter currency code", "currency")
    return ValidFeed(
        accountId = parseId(accountId, "accountId") ?: throw badRequest("required", "accountId is required", "accountId"),
        name = requiredText(name, "name", max = 100),
        institution = institution.dbValue,
        iban = try {
            normalizeIban(iban)
        } catch (e: ApiException) {
            throw badRequest(e.code, e.message, "iban")
        },
        currency = code,
        importFrom = importFrom,
    )
}

private fun ValidFeed.checkAccount(householdId: Uuid) {
    if (Accounts.selectAll().where { (Accounts.id eq accountId) and (Accounts.householdId eq householdId) }.empty()) {
        throw badRequest("invalid_account", "Unknown account", "accountId")
    }
}

private fun org.jetbrains.exposed.v1.core.statements.UpdateBuilder<*>.write(input: ValidFeed) {
    this[BankFeeds.accountId] = input.accountId
    this[BankFeeds.name] = input.name
    this[BankFeeds.institution] = input.institution
    this[BankFeeds.iban] = input.iban
    this[BankFeeds.currency] = input.currency
    this[BankFeeds.importFrom] = input.importFrom
}

private fun match(householdId: Uuid, id: Uuid) = (BankFeeds.id eq id) and (BankFeeds.householdId eq householdId)

private fun exists(householdId: Uuid, id: Uuid) = !BankFeeds.selectAll().where { match(householdId, id) }.empty()

private suspend fun <T> uniqueName(block: suspend () -> T): T =
    try {
        block()
    } catch (e: Exception) {
        if (e.isUniqueViolation()) throw conflict("name_taken", "A feed with this name already exists", "name")
        throw e
    }

/** The household's feeds (or just [id]) with their row counts. Call inside a transaction. */
fun loadFeeds(householdId: Uuid, id: Uuid? = null): List<BankFeed> {
    val total = BankRows.id.count()
    val lastDate = BankRows.date.max()
    val stats = BankRows.select(BankRows.feedId, total, lastDate)
        .where { BankRows.householdId eq householdId }
        .groupBy(BankRows.feedId)
        .associate { it[BankRows.feedId] to (it[total].toInt() to it[lastDate]) }
    val pendingCount = BankRows.id.count()
    val pending = BankRows.select(BankRows.feedId, pendingCount)
        .where { (BankRows.householdId eq householdId) and pendingRows() }
        .groupBy(BankRows.feedId)
        .associate { it[BankRows.feedId] to it[pendingCount].toInt() }
    val syncCount = BankSyncs.id.count()
    val syncs = BankSyncs.select(BankSyncs.feedId, syncCount)
        .where { (BankSyncs.householdId eq householdId) and (BankSyncs.startedAt greaterEq startOfPragueDay(Clock.System.now())) }
        .groupBy(BankSyncs.feedId)
        .associate { it[BankSyncs.feedId] to it[syncCount].toInt() }
    val latestRead = BankBalances.readAt.max()
    val latest = BankBalances.select(BankBalances.feedId, latestRead).where { BankBalances.householdId eq householdId }
        .groupBy(BankBalances.feedId).associate { it[BankBalances.feedId] to it[latestRead] }
    val balances = if (latest.isEmpty()) emptyMap() else BankBalances.selectAll()
        .where { (BankBalances.householdId eq householdId) and (BankBalances.readAt inList latest.values.filterNotNull()) }
        .filter { latest[it[BankBalances.feedId]] == it[BankBalances.readAt] }
        .associate {
            it[BankBalances.feedId] to FeedBalance(
                it[BankBalances.amountMinor], it[BankBalances.currency], it[BankBalances.readAt].toString(),
                it[BankBalances.balanceDate], it[BankBalances.balanceType],
            )
        }
    var filter: Op<Boolean> = BankFeeds.householdId eq householdId
    if (id != null) filter = filter and (BankFeeds.id eq id)
    return BankFeeds.selectAll().where(filter).orderBy(BankFeeds.name to SortOrder.ASC).map {
        val feedId = it[BankFeeds.id]
        BankFeed(
            id = feedId.toString(),
            accountId = it[BankFeeds.accountId].toString(),
            name = it[BankFeeds.name],
            institution = bankInstitution(it[BankFeeds.institution]),
            source = it[BankFeeds.feedSource],
            iban = it[BankFeeds.iban],
            currency = it[BankFeeds.currency],
            importFrom = it[BankFeeds.importFrom],
            version = it[BankFeeds.version],
            rowCount = stats[feedId]?.first ?: 0,
            pendingCount = pending[feedId] ?: 0,
            lastDate = stats[feedId]?.second,
            connectionId = it[BankFeeds.connectionId]?.toString(),
            lastSyncedAt = it[BankFeeds.lastSyncedAt]?.toString(),
            syncsToday = syncs[feedId] ?: 0,
            balance = balances[feedId],
        )
    }
}
