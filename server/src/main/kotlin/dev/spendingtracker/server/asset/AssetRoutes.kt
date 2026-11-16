package dev.spendingtracker.server.asset

import dev.spendingtracker.server.auth.userId
import dev.spendingtracker.server.common.ApiException
import dev.spendingtracker.server.common.badRequest
import dev.spendingtracker.server.common.checkVersioned
import dev.spendingtracker.server.common.conflict
import dev.spendingtracker.server.common.nonNegativeAmount
import dev.spendingtracker.server.common.nonZeroAmount
import dev.spendingtracker.server.common.notFound
import dev.spendingtracker.server.common.optionalText
import dev.spendingtracker.server.common.parseId
import dev.spendingtracker.server.common.pathId
import dev.spendingtracker.server.common.requireVersion
import dev.spendingtracker.server.common.requiredText
import dev.spendingtracker.server.common.versionParam
import dev.spendingtracker.server.db.Accounts
import dev.spendingtracker.server.db.AssetContributions
import dev.spendingtracker.server.db.AssetFees
import dev.spendingtracker.server.db.AssetValuations
import dev.spendingtracker.server.db.Assets
import dev.spendingtracker.server.db.BankBalances
import dev.spendingtracker.server.db.BankFeeds
import dev.spendingtracker.server.db.dbQuery
import dev.spendingtracker.server.db.isUniqueViolation
import dev.spendingtracker.server.household.household
import dev.spendingtracker.server.household.isMember
import dev.spendingtracker.server.market.market
import dev.spendingtracker.server.market.securities
import dev.spendingtracker.shared.api.ApiError
import dev.spendingtracker.shared.api.Contribution
import dev.spendingtracker.shared.api.HistoryPoint
import dev.spendingtracker.shared.api.ContributionInput
import dev.spendingtracker.shared.api.Asset
import dev.spendingtracker.shared.api.AssetHistory
import dev.spendingtracker.shared.api.AssetInput
import dev.spendingtracker.shared.api.AssetKind
import dev.spendingtracker.shared.api.AssetSummary
import dev.spendingtracker.shared.api.SecurityQuote
import dev.spendingtracker.shared.api.Valuation
import dev.spendingtracker.shared.api.ValuationInput
import dev.spendingtracker.shared.api.ValueSource
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
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import kotlinx.datetime.yearMonth
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.min
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Must be inside a HouseholdAccess scope. */
fun Route.assetRoutes() = route("/assets") {
    get({
        operationId = "listAssets"
        description = "Holdings by name, each with what was put in, what it's worth and the gain as of today."
        response {
            code(HttpStatusCode.OK) { body<List<Asset>>() }
        }
    }) {
        val book = call.book()
        call.respond(book.holdings.map { book.asset(it) })
    }

    get("/summary", {
        operationId = "getAssetSummary"
        description = "Totals across owned assets and liabilities, and net worth as of today."
        response {
            code(HttpStatusCode.OK) { body<AssetSummary>() }
        }
    }) {
        val book = call.book()
        val assets = book.holdings.map { it to book.asset(it) }
        val owned = assets.filter { !it.second.kind.liability }
        val owed = assets.filter { it.second.kind.liability }
        val assetsMinor = owned.sumOf { it.second.valueMinor }
        val liabilitiesMinor = owed.sumOf { it.second.valueMinor }
        // Only what money was put into: a car valued by hand or a savings account read from the bank, with nothing
        // recorded going in, would otherwise count its whole worth as return.
        val invested = owned.filter { (row, _) -> book.lots[row[Assets.id]].orEmpty().isNotEmpty() }
        val portfolioFlows = invested.flatMap { (row, asset) -> book.outflows(row, asset.marketValued) } +
            CashFlow(book.today, invested.sumOf { it.second.valueMinor }.toDouble())
        call.respond(
            AssetSummary(
                assetsMinor = assetsMinor,
                liabilitiesMinor = liabilitiesMinor,
                netMinor = assetsMinor + liabilitiesMinor,
                contributedMinor = owned.sumOf { it.second.contributedMinor },
                gainMinor = invested.sumOf { it.second.gainMinor ?: 0L },
                feesMinor = assets.sumOf { it.second.feesMinor },
                annualReturn = xirr(portfolioFlows),
            ),
        )
    }

    get("/history", {
        operationId = "getAssetHistory"
        description = "What the holdings (or one of them) had put in and were worth at the end of each month " +
            "since the first contribution, then today."
        request {
            queryParameter<String>("assetId") { description = "Only this holding" }
        }
        response {
            code(HttpStatusCode.OK) { body<AssetHistory>() }
            code(HttpStatusCode.BadRequest) { body<ApiError>() }
        }
    }) {
        val assetId = parseId(call.request.queryParameters["assetId"], "assetId")
        val book = call.book(assetId)
        val first = book.firstDate()
        val points = if (first == null) {
            emptyList()
        } else {
            monthEnds(first, book.today).map { day ->
                val standings = book.holdings.map { book.standing(it, day) }
                HistoryPoint(day, standings.sumOf { it.contributedMinor }, standings.sumOf { it.valueMinor })
            }
        }
        call.respond(AssetHistory(points))
    }

    post({
        operationId = "createAsset"
        request { body<AssetInput>() }
        response {
            code(HttpStatusCode.Created) { body<Asset>() }
            code(HttpStatusCode.BadRequest) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val input = call.receive<AssetInput>().validated()
        call.registerSymbol(input.symbol)
        val id = Uuid.random()
        dbQuery {
            input.checkOwner(householdId)
            input.checkAccount(householdId)
            oneAssetPerAccount {
                Assets.insert {
                    it[Assets.id] = id
                    it[Assets.householdId] = householdId
                    it.write(input)
                    it[version] = 1
                    it[createdBy] = call.userId
                }
            }
        }
        val book = call.book(id)
        call.respond(HttpStatusCode.Created, book.asset(book.holdings.single()))
    }

    put("/{assetId}", {
        operationId = "updateAsset"
        request {
            pathParameter<String>("assetId")
            body<AssetInput>()
        }
        response {
            code(HttpStatusCode.OK) { body<Asset>() }
            code(HttpStatusCode.BadRequest) { body<ApiError>() }
            code(HttpStatusCode.NotFound) { body<ApiError>() }
            code(HttpStatusCode.Conflict) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val id = call.pathId("assetId", "Asset")
        val raw = call.receive<AssetInput>()
        val version = requireVersion(raw.version)
        val input = raw.validated()
        call.registerSymbol(input.symbol)
        dbQuery {
            input.checkOwner(householdId)
            input.checkAccount(householdId)
            val affected = oneAssetPerAccount {
                Assets.update({ matchAsset(householdId, id) and (Assets.version eq version) }) {
                    it.write(input)
                    it[Assets.version] = version + 1
                    it[updatedAt] = Clock.System.now()
                }
            }
            checkVersioned(affected, { assetExists(householdId, id) }, "Asset")
        }
        val book = call.book(id)
        call.respond(book.asset(book.holdings.single()))
    }

    delete("/{assetId}", {
        operationId = "deleteAsset"
        description = "Deletes the holding with its contributions and valuations."
        request {
            pathParameter<String>("assetId")
            queryParameter<Int>("version") { required = true }
        }
        response {
            code(HttpStatusCode.NoContent) {}
            code(HttpStatusCode.NotFound) { body<ApiError>() }
            code(HttpStatusCode.Conflict) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val id = call.pathId("assetId", "Asset")
        val version = call.versionParam()
        dbQuery {
            val affected = Assets.deleteWhere { matchAsset(householdId, id) and (Assets.version eq version) }
            checkVersioned(affected, { assetExists(householdId, id) }, "Asset")
        }
        call.respond(HttpStatusCode.NoContent)
    }

    route("/{assetId}/contributions") { contributionRoutes() }
    route("/{assetId}/valuations") { valuationRoutes() }
    route("/{assetId}/fees") { feeRoutes() }
}

private fun Route.contributionRoutes() {
    get({
        operationId = "listContributions"
        description = "The holding's contributions, newest first."
        request { pathParameter<String>("assetId") }
        response {
            code(HttpStatusCode.OK) { body<List<Contribution>>() }
            code(HttpStatusCode.NotFound) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val assetId = call.assetId()
        val contributions = dbQuery {
            requireAsset(householdId, assetId)
            AssetContributions.selectAll()
                .where { AssetContributions.assetId eq assetId }
                .orderBy(AssetContributions.date to SortOrder.DESC, AssetContributions.id to SortOrder.ASC)
                .map { it.toContribution() }
        }
        call.respond(contributions)
    }

    post({
        operationId = "createContribution"
        request {
            pathParameter<String>("assetId")
            body<ContributionInput>()
        }
        response {
            code(HttpStatusCode.Created) { body<Contribution>() }
            code(HttpStatusCode.BadRequest) { body<ApiError>() }
            code(HttpStatusCode.NotFound) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val assetId = call.assetId()
        val input = call.receive<ContributionInput>().resolved(call)
        val id = Uuid.random()
        val contribution = dbQuery {
            requireAsset(householdId, assetId)
            input.checkAccount(householdId)
            AssetContributions.insert {
                it[AssetContributions.id] = id
                it[AssetContributions.householdId] = householdId
                it[AssetContributions.assetId] = assetId
                it.write(input)
                it[version] = 1
                it[createdBy] = call.userId
            }
            AssetContributions.selectAll().where { AssetContributions.id eq id }.single().toContribution()
        }
        call.respond(HttpStatusCode.Created, contribution)
    }

    put("/{contributionId}", {
        operationId = "updateContribution"
        request {
            pathParameter<String>("assetId")
            pathParameter<String>("contributionId")
            body<ContributionInput>()
        }
        response {
            code(HttpStatusCode.OK) { body<Contribution>() }
            code(HttpStatusCode.BadRequest) { body<ApiError>() }
            code(HttpStatusCode.NotFound) { body<ApiError>() }
            code(HttpStatusCode.Conflict) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val assetId = call.assetId()
        val id = call.pathId("contributionId", "Contribution")
        val raw = call.receive<ContributionInput>()
        val version = requireVersion(raw.version)
        val input = raw.resolved(call)
        val contribution = dbQuery {
            input.checkAccount(householdId)
            val match = matchContribution(householdId, assetId, id)
            val existing = AssetContributions.selectAll().where(match).singleOrNull()
                ?: throw notFound("Contribution")
            if (existing[AssetContributions.transferId] != null) {
                throw conflict("contribution_from_transfer", "This entry came from a transfer; edit or delete the transfer instead")
            }
            val affected = AssetContributions.update({ match and (AssetContributions.version eq version) }) {
                it.write(input)
                it[AssetContributions.version] = version + 1
                it[updatedAt] = Clock.System.now()
            }
            checkVersioned(affected, { true }, "Contribution")
            AssetContributions.selectAll().where { AssetContributions.id eq id }.single().toContribution()
        }
        call.respond(contribution)
    }

    delete("/{contributionId}", {
        operationId = "deleteContribution"
        request {
            pathParameter<String>("assetId")
            pathParameter<String>("contributionId")
            queryParameter<Int>("version") { required = true }
        }
        response {
            code(HttpStatusCode.NoContent) {}
            code(HttpStatusCode.NotFound) { body<ApiError>() }
            code(HttpStatusCode.Conflict) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val assetId = call.assetId()
        val id = call.pathId("contributionId", "Contribution")
        val version = call.versionParam()
        dbQuery {
            val match = matchContribution(householdId, assetId, id)
            val existing = AssetContributions.selectAll().where(match).singleOrNull()
                ?: throw notFound("Contribution")
            if (existing[AssetContributions.transferId] != null) {
                throw conflict("contribution_from_transfer", "This entry came from a transfer; edit or delete the transfer instead")
            }
            val affected = AssetContributions.deleteWhere { match and (AssetContributions.version eq version) }
            checkVersioned(affected, { true }, "Contribution")
        }
        call.respond(HttpStatusCode.NoContent)
    }
}

private fun Route.valuationRoutes() {
    get({
        operationId = "listValuations"
        description = "The holding's valuations, newest first."
        request { pathParameter<String>("assetId") }
        response {
            code(HttpStatusCode.OK) { body<List<Valuation>>() }
            code(HttpStatusCode.NotFound) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val assetId = call.assetId()
        val valuations = dbQuery {
            requireAsset(householdId, assetId)
            AssetValuations.selectAll()
                .where { AssetValuations.assetId eq assetId }
                .orderBy(AssetValuations.date to SortOrder.DESC)
                .map { it.toValuation() }
        }
        call.respond(valuations)
    }

    post({
        operationId = "createValuation"
        description = "Fails with 409 valuation_exists when the holding already has a valuation on that day."
        request {
            pathParameter<String>("assetId")
            body<ValuationInput>()
        }
        response {
            code(HttpStatusCode.Created) { body<Valuation>() }
            code(HttpStatusCode.BadRequest) { body<ApiError>() }
            code(HttpStatusCode.NotFound) { body<ApiError>() }
            code(HttpStatusCode.Conflict) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val assetId = call.assetId()
        val input = call.receive<ValuationInput>().validated()
        val id = Uuid.random()
        val valuation = oneValuationPerDay {
            dbQuery {
                requireAsset(householdId, assetId)
                AssetValuations.insert {
                    it[AssetValuations.id] = id
                    it[AssetValuations.householdId] = householdId
                    it[AssetValuations.assetId] = assetId
                    it.write(input)
                    it[version] = 1
                    it[createdBy] = call.userId
                }
                AssetValuations.selectAll().where { AssetValuations.id eq id }.single().toValuation()
            }
        }
        call.respond(HttpStatusCode.Created, valuation)
    }

    put("/{valuationId}", {
        operationId = "updateValuation"
        request {
            pathParameter<String>("assetId")
            pathParameter<String>("valuationId")
            body<ValuationInput>()
        }
        response {
            code(HttpStatusCode.OK) { body<Valuation>() }
            code(HttpStatusCode.BadRequest) { body<ApiError>() }
            code(HttpStatusCode.NotFound) { body<ApiError>() }
            code(HttpStatusCode.Conflict) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val assetId = call.assetId()
        val id = call.pathId("valuationId", "Valuation")
        val raw = call.receive<ValuationInput>()
        val version = requireVersion(raw.version)
        val input = raw.validated()
        val valuation = oneValuationPerDay {
            dbQuery {
                val match = matchValuation(householdId, assetId, id)
                val affected = AssetValuations.update({ match and (AssetValuations.version eq version) }) {
                    it.write(input)
                    it[AssetValuations.version] = version + 1
                    it[updatedAt] = Clock.System.now()
                }
                checkVersioned(affected, { !AssetValuations.selectAll().where(match).empty() }, "Valuation")
                AssetValuations.selectAll().where { AssetValuations.id eq id }.single().toValuation()
            }
        }
        call.respond(valuation)
    }

    delete("/{valuationId}", {
        operationId = "deleteValuation"
        request {
            pathParameter<String>("assetId")
            pathParameter<String>("valuationId")
            queryParameter<Int>("version") { required = true }
        }
        response {
            code(HttpStatusCode.NoContent) {}
            code(HttpStatusCode.NotFound) { body<ApiError>() }
            code(HttpStatusCode.Conflict) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val assetId = call.assetId()
        val id = call.pathId("valuationId", "Valuation")
        val version = call.versionParam()
        dbQuery {
            val match = matchValuation(householdId, assetId, id)
            val affected = AssetValuations.deleteWhere { match and (AssetValuations.version eq version) }
            checkVersioned(affected, { !AssetValuations.selectAll().where(match).empty() }, "Valuation")
        }
        call.respond(HttpStatusCode.NoContent)
    }
}

// ---- Holdings ----

private data class ValidAsset(
    val name: String,
    val kind: AssetKind,
    val ownerUserId: Uuid?,
    val notes: String,
    val symbol: String?,
    val accountId: Uuid?,
)

private fun AssetInput.validated() = ValidAsset(
    name = requiredText(name, "name", max = 200),
    kind = kind,
    ownerUserId = parseId(ownerUserId, "ownerUserId"),
    notes = optionalText(notes, "notes", max = 2000),
    symbol = symbol?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }?.let { requiredText(it, "symbol", max = 40) },
    accountId = parseId(accountId, "accountId"),
).also {
    if (it.symbol != null && !it.kind.market) {
        throw badRequest("symbol_not_market", "Only funds, stocks, bonds and crypto can follow a listed security", "symbol")
    }
    if (it.accountId != null && !it.kind.trackable) {
        throw badRequest("account_not_trackable", "Only savings, schemes, other assets and liabilities can track an account", "accountId")
    }
}

private inline fun <T> oneAssetPerAccount(block: () -> T): T =
    try {
        block()
    } catch (e: Exception) {
        if (e.isUniqueViolation()) throw conflict("account_already_tracked", "Another asset already tracks this account", "accountId")
        throw e
    }

private fun ValidAsset.checkAccount(householdId: Uuid) {
    if (accountId != null &&
        Accounts.selectAll().where { (Accounts.id eq accountId) and (Accounts.householdId eq householdId) }.empty()
    ) {
        throw badRequest("invalid_account", "Unknown account", "accountId")
    }
}

/** Makes sure the provider knows [symbol], fetching its prices the first time; unknown symbols are a 400. */
private suspend fun RoutingCall.registerSymbol(symbol: String?) {
    if (symbol == null) return
    val known = try {
        application.market.register(symbol)
    } catch (e: Exception) {
        throw ApiException(HttpStatusCode.ServiceUnavailable, "market_unavailable", "Couldn't reach the market data provider. Try again later.")
    }
    if (!known) throw badRequest("invalid_symbol", "No security with this symbol", "symbol")
}

private fun ValidAsset.checkOwner(householdId: Uuid) {
    if (ownerUserId != null && !isMember(householdId, ownerUserId)) {
        throw badRequest("invalid_member", "Owner must be a member of the household", "ownerUserId")
    }
}

private fun UpdateBuilder<*>.write(input: ValidAsset) {
    this[Assets.name] = input.name
    this[Assets.kind] = input.kind.dbValue
    this[Assets.ownerUserId] = input.ownerUserId
    this[Assets.notes] = input.notes
    this[Assets.symbol] = input.symbol
    this[Assets.accountId] = input.accountId
}

private val AssetKind.dbValue get() = name.lowercase()

private fun kindFrom(value: String) = AssetKind.entries.first { it.dbValue == value }

private fun matchAsset(householdId: Uuid, id: Uuid) = (Assets.id eq id) and (Assets.householdId eq householdId)

private fun assetExists(householdId: Uuid, id: Uuid) = !Assets.selectAll().where { matchAsset(householdId, id) }.empty()

internal fun requireAsset(householdId: Uuid, id: Uuid) {
    if (!assetExists(householdId, id)) throw notFound("Asset")
}

internal fun RoutingCall.assetId() = pathId("assetId", "Asset")

/** Preference order when a feed has multiple balance readings on the same day, matching GoCardlessBankData. */
private val BALANCE_TYPE_PREFERENCE = listOf("interimBooked", "closingBooked", "expected", "interimAvailable", "closingAvailable")

private data class BankReading(
    val date: LocalDate,
    val readAt: kotlin.time.Instant,
    val amountMinor: Long,
    val currency: String,
    val balanceType: String,
)

/**
 * A household's holdings (or one of them) with their contributions, valuations, bank balances and market data,
 * ready to value on any day up to [today].
 */
private class Book(
    val holdings: List<ResultRow>,
    val lots: Map<Uuid, List<Lot>>,
    val valuations: Map<Uuid, List<Dated>>,
    val fees: Map<Uuid, List<Dated>>,
    val feedsByAccount: Map<Uuid, List<Uuid>>,
    val readingsByFeed: Map<Uuid, List<BankReading>>,
    val prices: Prices,
    val today: LocalDate,
) {
    fun firstDate(): LocalDate? = listOfNotNull(
        lots.values.flatten().minOfOrNull { it.date },
        valuations.values.flatten().minOfOrNull { it.date },
        readingsByFeed.values.flatten().minOfOrNull { it.date },
    ).minOrNull()

    fun bankValue(accountId: Uuid, on: LocalDate, liability: Boolean = false): Priced? {
        val feedIds = feedsByAccount[accountId].orEmpty()
        if (feedIds.isEmpty()) return null
        val picked = feedIds.mapNotNull { feedId ->
            readingsByFeed[feedId].orEmpty()
                .filter { it.date <= on && (!liability || !it.balanceType.endsWith("Available", ignoreCase = true)) }
                .maxWithOrNull(
                    compareBy<BankReading> { it.date }
                        .thenBy { it.readAt }
                        .thenByDescending { BALANCE_TYPE_PREFERENCE.indexOf(it.balanceType).let { i -> if (i < 0) BALANCE_TYPE_PREFERENCE.size else i } },
                )
        }
        if (picked.isEmpty()) return null
        var sum = 0L
        var latestDate: LocalDate? = null
        for (reading in picked) {
            val converted = prices.convertMinor(reading.amountMinor, reading.currency, reading.date) ?: return null
            sum += converted
            if (latestDate == null || reading.date > latestDate) latestDate = reading.date
        }
        return latestDate?.let { Priced(sum, it) }
    }

    fun hasOnlyAvailableReadings(accountId: Uuid, on: LocalDate): Boolean {
        val readings = feedsByAccount[accountId].orEmpty()
            .flatMap { readingsByFeed[it].orEmpty() }
            .filter { it.date <= on }
        return readings.isNotEmpty() && readings.all { it.balanceType.endsWith("Available", ignoreCase = true) }
    }

    fun standing(row: ResultRow, on: LocalDate): Standing {
        val id = row[Assets.id]
        val kind = kindFrom(row[Assets.kind])
        val price = row[Assets.symbol]?.let { symbol -> { shares: BigDecimal, day: LocalDate -> prices.value(symbol, shares, day) } }
        val bank = row[Assets.accountId]?.let { accountId -> { day: LocalDate -> bankValue(accountId, day, liability = kind.liability) } }
        return standingOn(lots[id].orEmpty(), valuations[id].orEmpty(), fees[id].orEmpty(), on, price, liability = kind.liability, bankValue = bank)
    }

    fun outflows(row: ResultRow, marketValued: Boolean): List<CashFlow> {
        val id = row[Assets.id]
        val contributionFlows = lots[id].orEmpty().map { CashFlow(it.date, -it.amountMinor.toDouble()) }
        val feeFlows = if (marketValued) fees[id].orEmpty().map { CashFlow(it.date, -it.amountMinor.toDouble()) } else emptyList()
        return contributionFlows + feeFlows
    }

    fun asset(row: ResultRow): Asset {
        val standing = standing(row, today)
        val kind = kindFrom(row[Assets.kind])
        val annualReturn = if (kind.liability) {
            null
        } else {
            xirr(outflows(row, standing.marketValued) + CashFlow(today, standing.valueMinor.toDouble()))
        }
        val quantities = lots[row[Assets.id]].orEmpty().mapNotNull { it.quantity }
        val security = row[Assets.symbol]?.let { symbol ->
            val info = prices.securities[symbol]
            val close = prices.close(symbol, today)
            SecurityQuote(
                symbol = symbol,
                name = info?.name ?: symbol,
                exchange = info?.exchange.orEmpty(),
                currency = info?.currency.orEmpty(),
                close = close?.second?.toDouble(),
                closeOn = close?.first,
            )
        }
        val bankAvailableOnly = kind.liability &&
            standing.valueSource != ValueSource.BANK &&
            row[Assets.accountId]?.let { hasOnlyAvailableReadings(it, today) } == true
        return Asset(
            id = row[Assets.id].toString(),
            name = row[Assets.name],
            kind = kindFrom(row[Assets.kind]),
            ownerUserId = row[Assets.ownerUserId]?.toString(),
            notes = row[Assets.notes],
            version = row[Assets.version],
            security = security,
            shares = if (quantities.isEmpty()) null else quantities.sumOf { it }.toDouble(),
            marketValued = standing.marketValued,
            accountId = row[Assets.accountId]?.toString(),
            valueSource = standing.valueSource,
            contributedMinor = standing.contributedMinor,
            repaidMinor = standing.repaidMinor,
            borrowedMinor = standing.borrowedMinor,
            valueMinor = standing.valueMinor,
            valuedOn = standing.valuedOn,
            gainMinor = standing.gainMinor,
            annualReturn = annualReturn,
            firstContributedOn = standing.firstContributedOn,
            feesMinor = fees[row[Assets.id]].orEmpty().sumOf { it.amountMinor },
            bankAvailableOnly = bankAvailableOnly,
        )
    }
}

/**
 * Loads the household's holdings, by name, or only [assetId]. Market data they follow is refreshed first
 * where stale: prices of their securities, and the ČNB fixings back to the first contribution or foreign bank
 * balance when a security or tracked account trades in another currency than the household's. With [everyAccount],
 * the bank balances of every account are loaded, not only those of accounts a holding follows.
 */
private suspend fun RoutingCall.book(assetId: Uuid? = null, everyAccount: Boolean = false): Book {
    val householdId = household.id
    val currency = household.currency
    val tz = TimeZone.currentSystemDefault()
    val today = Clock.System.todayIn(tz)
    val filter = if (assetId == null) {
        Assets.householdId eq householdId
    } else {
        matchAsset(householdId, assetId)
    }
    val contributionFilter = if (assetId == null) {
        AssetContributions.householdId eq householdId
    } else {
        (AssetContributions.householdId eq householdId) and (AssetContributions.assetId eq assetId)
    }
    val (symbols, fxYears, fxDates) = dbQuery {
        val assetRows = Assets.select(Assets.symbol, Assets.accountId).where(filter).toList()
        val symbols = assetRows.mapNotNull { it[Assets.symbol] }.distinct()
        val trackedAccountIds = assetRows.mapNotNull { it[Assets.accountId] }.distinct()
        val foreignSecurity = securities(symbols).values.any { it.currency != currency }
        val firstContribution = AssetContributions.select(AssetContributions.date.min()).where(contributionFilter)
            .single()[AssetContributions.date.min()]
        val balanceDates = if (trackedAccountIds.isEmpty() && !everyAccount) {
            emptyList()
        } else {
            val feedIds = BankFeeds.select(BankFeeds.id)
                .where {
                    if (everyAccount) BankFeeds.householdId eq householdId
                    else (BankFeeds.householdId eq householdId) and (BankFeeds.accountId inList trackedAccountIds)
                }
                .map { it[BankFeeds.id] }
            if (feedIds.isEmpty()) emptyList()
            else BankBalances.select(BankBalances.currency, BankBalances.balanceDate, BankBalances.readAt)
                .where { (BankBalances.householdId eq householdId) and (BankBalances.feedId inList feedIds) }
                .filter { it[BankBalances.currency] != currency }
                .map { it[BankBalances.balanceDate] ?: it[BankBalances.readAt].toLocalDateTime(tz).date }
        }
        val years = if (foreignSecurity && firstContribution != null) (firstContribution.year..today.year).toList() else emptyList()
        val fxDates = buildList {
            if (foreignSecurity && firstContribution != null) add(firstContribution)
            addAll(balanceDates)
        }
        Triple(symbols, years, fxDates)
    }
    application.market.refresh(symbols, fxYears)
    application.market.refreshFx(fxDates)
    return dbQuery {
        val holdings = Assets.selectAll().where(filter)
            .orderBy(Assets.name to SortOrder.ASC, Assets.id to SortOrder.ASC)
            .toList()
        val lots = AssetContributions.selectAll().where(contributionFilter).groupBy({ it[AssetContributions.assetId] }) {
            Lot(it[AssetContributions.date], it[AssetContributions.amountMinor], it[AssetContributions.quantity])
        }
        val valuationFilter = if (assetId == null) {
            AssetValuations.householdId eq householdId
        } else {
            (AssetValuations.householdId eq householdId) and (AssetValuations.assetId eq assetId)
        }
        val valuations = AssetValuations.selectAll().where(valuationFilter).groupBy({ it[AssetValuations.assetId] }) {
            Dated(it[AssetValuations.date], it[AssetValuations.valueMinor])
        }
        val feeFilter = if (assetId == null) {
            AssetFees.householdId eq householdId
        } else {
            (AssetFees.householdId eq householdId) and (AssetFees.assetId eq assetId)
        }
        val fees = AssetFees.selectAll().where(feeFilter)
            .groupBy({ it[AssetFees.assetId] }) { Dated(it[AssetFees.date], it[AssetFees.amountMinor]) }
        val trackedAccountIds = holdings.mapNotNull { it[Assets.accountId] }.distinct()
        val feedsByAccount = if (trackedAccountIds.isEmpty() && !everyAccount) {
            emptyMap()
        } else {
            BankFeeds.select(BankFeeds.id, BankFeeds.accountId)
                .where {
                    if (everyAccount) BankFeeds.householdId eq householdId
                    else (BankFeeds.householdId eq householdId) and (BankFeeds.accountId inList trackedAccountIds)
                }
                .groupBy({ it[BankFeeds.accountId] }) { it[BankFeeds.id] }
        }
        val allFeedIds = feedsByAccount.values.flatten().distinct()
        val readingsByFeed = if (allFeedIds.isEmpty()) {
            emptyMap()
        } else {
            BankBalances.selectAll()
                .where { (BankBalances.householdId eq householdId) and (BankBalances.feedId inList allFeedIds) }
                .groupBy({ it[BankBalances.feedId] }) {
                    BankReading(
                        date = it[BankBalances.balanceDate] ?: it[BankBalances.readAt].toLocalDateTime(tz).date,
                        readAt = it[BankBalances.readAt],
                        amountMinor = it[BankBalances.amountMinor],
                        currency = it[BankBalances.currency],
                        balanceType = it[BankBalances.balanceType],
                    )
                }
        }
        val balanceCurrencies = readingsByFeed.values.flatten().map { it.currency }.distinct()
        Book(holdings, lots, valuations, fees, feedsByAccount, readingsByFeed, loadPrices(currency, symbols, balanceCurrencies), today)
    }.also { if (assetId != null && it.holdings.isEmpty()) throw notFound("Asset") }
}

/** What the household had on a day, in minor units of its currency. See [wealthOn]. */
internal data class Wealth(
    /** Bank balances of accounts no holding follows: current accounts, cash, credit cards (negative when owed). */
    val accountsMinor: Long,
    val assetsMinor: Long,
    /** Negative: what's owed. */
    val liabilitiesMinor: Long,
    /** Balances of current, savings and cash accounts read from the bank, and savings assets not counted through one. */
    val liquidMinor: Long,
    /** Accounts whose balance was read from the bank on or before the day. */
    val accountsRead: Int,
)

/**
 * Every holding's standing on [on], and the latest bank balance on or before it of each account with feeds. An
 * account a holding follows counts once, through the holding. A credit card's "available" balance includes the
 * unused credit line, so only booked balances count for it, as for liabilities.
 */
internal suspend fun RoutingCall.wealthOn(on: LocalDate): Wealth {
    val book = book(everyAccount = true)
    val householdId = household.id
    val accountKinds = dbQuery {
        Accounts.select(Accounts.id, Accounts.kind).where { Accounts.householdId eq householdId }
            .associate { it[Accounts.id] to it[Accounts.kind] }
    }
    val followed = book.holdings.mapNotNull { it[Assets.accountId] }.toSet()
    val balances = book.feedsByAccount.keys.mapNotNull { accountId ->
        val kind = accountKinds[accountId] ?: return@mapNotNull null
        book.bankValue(accountId, on, liability = kind == "credit_card")?.let { Triple(accountId, kind, it.valueMinor) }
    }
    val standings = book.holdings.map { row -> Triple(row, kindFrom(row[Assets.kind]), book.standing(row, on)) }
    val liquidBalances = balances.filter { (_, kind, _) -> kind == "bank" || kind == "savings" || kind == "cash" }
    val liquidAccountIds = liquidBalances.map { it.first }.toSet()
    // Savings counted through their account's balance aren't counted again.
    val otherSavings = standings
        .filter { (row, kind, _) -> kind == AssetKind.SAVINGS && row[Assets.accountId] !in liquidAccountIds }
        .sumOf { it.third.valueMinor }
    return Wealth(
        accountsMinor = balances.filter { it.first !in followed }.sumOf { it.third },
        assetsMinor = standings.filter { !it.second.liability }.sumOf { it.third.valueMinor },
        liabilitiesMinor = standings.filter { it.second.liability }.sumOf { it.third.valueMinor },
        liquidMinor = liquidBalances.sumOf { it.third } + otherSavings,
        accountsRead = balances.size,
    )
}

/** The last day of each month from [first]'s through the one before [today]'s, then [today]. */
private fun monthEnds(first: LocalDate, today: LocalDate): List<LocalDate> {
    val months = generateSequence(first.yearMonth) { it.plus(1, DateTimeUnit.MONTH) }.takeWhile { it < today.yearMonth }
    return months.map { it.lastDay }.toList() + today
}

// ---- Contributions ----

private data class ValidContribution(
    val date: LocalDate,
    val amountMinor: Long,
    val originalAmountMinor: Long?,
    val originalCurrency: String?,
    val quantity: BigDecimal?,
    val accountId: Uuid?,
    val notes: String,
)

private suspend fun ContributionInput.resolved(call: RoutingCall): ValidContribution {
    val origAmount = originalAmountMinor
    val origCurrency = originalCurrency?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
    if ((origAmount == null) != (origCurrency == null)) {
        throw badRequest(
            "incomplete_original",
            "Original amount and currency must both be set",
            if (origAmount == null) "originalAmountMinor" else "originalCurrency",
        )
    }
    if (origCurrency != null) {
        if (!Regex("^[A-Z]{3}$").matches(origCurrency) || runCatching { java.util.Currency.getInstance(origCurrency) }.isFailure) {
            throw badRequest("invalid_currency", "Invalid currency code", "originalCurrency")
        }
        nonZeroAmount(origAmount!!, "originalAmountMinor")
    }
    val resolvedAmount = if (amountMinor != null) {
        nonZeroAmount(amountMinor!!)
    } else {
        if (origAmount == null || origCurrency == null) {
            throw badRequest("required", "Amount is required", "amountMinor")
        }
        val householdCurrency = call.household.currency
        if (origCurrency != householdCurrency) {
            call.application.market.refreshFx(date)
        }
        val converted = dbQuery {
            loadPrices(householdCurrency, emptyList(), listOf(origCurrency)).convertMinor(origAmount, origCurrency, date)
        } ?: throw badRequest("fx_unavailable", "No exchange rate available for that date", "date")
        nonZeroAmount(converted)
    }
    if (origAmount != null && (origAmount > 0) != (resolvedAmount > 0)) {
        throw badRequest("invalid_amount", "Original amount and household amount must have the same sign", "originalAmountMinor")
    }
    // Shares go the way the money does: bought with money put in, sold for money taken out.
    val quantity = quantity
    if (quantity != null && (!quantity.isFinite() || quantity == 0.0 || (quantity > 0) != (resolvedAmount > 0))) {
        throw badRequest("invalid_quantity", "Shares must be non-zero, and negative only for money taken out", "quantity")
    }
    return ValidContribution(
        date = date,
        amountMinor = resolvedAmount,
        originalAmountMinor = origAmount,
        originalCurrency = origCurrency,
        // Through the decimal text, so 0.1 shares is stored as 0.1 rather than its binary approximation.
        quantity = quantity?.let { BigDecimal(it.toString()) },
        accountId = parseId(accountId, "accountId"),
        notes = optionalText(notes, "notes", max = 2000),
    )
}

/** A field-level error for an account the composite foreign key would otherwise reject opaquely. */
private fun ValidContribution.checkAccount(householdId: Uuid) {
    if (accountId != null &&
        Accounts.selectAll().where { (Accounts.id eq accountId) and (Accounts.householdId eq householdId) }.empty()
    ) {
        throw badRequest("invalid_account", "Unknown account", "accountId")
    }
}

private fun UpdateBuilder<*>.write(input: ValidContribution) {
    this[AssetContributions.date] = input.date
    this[AssetContributions.amountMinor] = input.amountMinor
    this[AssetContributions.originalAmountMinor] = input.originalAmountMinor
    this[AssetContributions.originalCurrency] = input.originalCurrency
    this[AssetContributions.quantity] = input.quantity
    this[AssetContributions.accountId] = input.accountId
    this[AssetContributions.notes] = input.notes
}

private fun matchContribution(householdId: Uuid, assetId: Uuid, id: Uuid) =
    (AssetContributions.id eq id) and (AssetContributions.householdId eq householdId) and
        (AssetContributions.assetId eq assetId)

private fun ResultRow.toContribution() = Contribution(
    id = this[AssetContributions.id].toString(),
    assetId = this[AssetContributions.assetId].toString(),
    date = this[AssetContributions.date],
    amountMinor = this[AssetContributions.amountMinor],
    originalAmountMinor = this[AssetContributions.originalAmountMinor],
    originalCurrency = this[AssetContributions.originalCurrency],
    quantity = this[AssetContributions.quantity]?.toDouble(),
    accountId = this[AssetContributions.accountId]?.toString(),
    transferId = this[AssetContributions.transferId]?.toString(),
    notes = this[AssetContributions.notes],
    version = this[AssetContributions.version],
)

// ---- Valuations ----

private data class ValidValuation(val date: LocalDate, val valueMinor: Long, val notes: String)

private fun ValuationInput.validated() = ValidValuation(
    date = date,
    valueMinor = nonNegativeAmount(valueMinor, "valueMinor"),
    notes = optionalText(notes, "notes", max = 2000),
)

private fun UpdateBuilder<*>.write(input: ValidValuation) {
    this[AssetValuations.date] = input.date
    this[AssetValuations.valueMinor] = input.valueMinor
    this[AssetValuations.notes] = input.notes
}

private fun matchValuation(householdId: Uuid, assetId: Uuid, id: Uuid) =
    (AssetValuations.id eq id) and (AssetValuations.householdId eq householdId) and
        (AssetValuations.assetId eq assetId)

private suspend fun <T> oneValuationPerDay(block: suspend () -> T): T =
    try {
        block()
    } catch (e: Exception) {
        if (e.isUniqueViolation()) throw conflict("valuation_exists", "This holding already has a value for that day", "date")
        throw e
    }

private fun ResultRow.toValuation() = Valuation(
    id = this[AssetValuations.id].toString(),
    assetId = this[AssetValuations.assetId].toString(),
    date = this[AssetValuations.date],
    valueMinor = this[AssetValuations.valueMinor],
    notes = this[AssetValuations.notes],
    version = this[AssetValuations.version],
)
