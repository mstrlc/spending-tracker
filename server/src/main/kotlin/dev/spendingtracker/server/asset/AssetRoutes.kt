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
import dev.spendingtracker.shared.api.SecurityQuote
import dev.spendingtracker.shared.api.Valuation
import dev.spendingtracker.shared.api.ValuationInput
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
import kotlinx.datetime.todayIn
import kotlinx.datetime.yearMonth
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
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
        val first = book.lots.values.flatten().minOfOrNull { it.date }
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
            Assets.insert {
                it[Assets.id] = id
                it[Assets.householdId] = householdId
                it.write(input)
                it[version] = 1
                it[createdBy] = call.userId
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
            val affected = Assets.update({ matchAsset(householdId, id) and (Assets.version eq version) }) {
                it.write(input)
                it[Assets.version] = version + 1
                it[updatedAt] = Clock.System.now()
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
        val input = call.receive<ContributionInput>().validated()
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
        val input = raw.validated()
        val contribution = dbQuery {
            input.checkAccount(householdId)
            val match = matchContribution(householdId, assetId, id)
            val affected = AssetContributions.update({ match and (AssetContributions.version eq version) }) {
                it.write(input)
                it[AssetContributions.version] = version + 1
                it[updatedAt] = Clock.System.now()
            }
            checkVersioned(affected, { !AssetContributions.selectAll().where(match).empty() }, "Contribution")
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
            val affected = AssetContributions.deleteWhere { match and (AssetContributions.version eq version) }
            checkVersioned(affected, { !AssetContributions.selectAll().where(match).empty() }, "Contribution")
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
)

private fun AssetInput.validated() = ValidAsset(
    name = requiredText(name, "name", max = 200),
    kind = kind,
    ownerUserId = parseId(ownerUserId, "ownerUserId"),
    notes = optionalText(notes, "notes", max = 2000),
    symbol = symbol?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }?.let { requiredText(it, "symbol", max = 40) },
).also {
    if (it.symbol != null && !it.kind.market) {
        throw badRequest("symbol_not_market", "Only funds, stocks, bonds and crypto can follow a listed security", "symbol")
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
}

private val AssetKind.dbValue get() = name.lowercase()

private fun kindFrom(value: String) = AssetKind.entries.first { it.dbValue == value }

private fun matchAsset(householdId: Uuid, id: Uuid) = (Assets.id eq id) and (Assets.householdId eq householdId)

private fun assetExists(householdId: Uuid, id: Uuid) = !Assets.selectAll().where { matchAsset(householdId, id) }.empty()

internal fun requireAsset(householdId: Uuid, id: Uuid) {
    if (!assetExists(householdId, id)) throw notFound("Asset")
}

internal fun RoutingCall.assetId() = pathId("assetId", "Asset")

/**
 * A household's holdings (or one of them) with their contributions, valuations and market data, ready to value
 * on any day up to [today].
 */
private class Book(
    val holdings: List<ResultRow>,
    val lots: Map<Uuid, List<Lot>>,
    val valuations: Map<Uuid, List<Dated>>,
    val fees: Map<Uuid, List<Dated>>,
    val prices: Prices,
    val today: LocalDate,
) {
    fun standing(row: ResultRow, on: LocalDate): Standing {
        val id = row[Assets.id]
        val price = row[Assets.symbol]?.let { symbol -> { shares: BigDecimal, day: LocalDate -> prices.value(symbol, shares, day) } }
        return standingOn(lots[id].orEmpty(), valuations[id].orEmpty(), fees[id].orEmpty(), on, price)
    }

    fun asset(row: ResultRow): Asset {
        val standing = standing(row, today)
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
            contributedMinor = standing.contributedMinor,
            valueMinor = standing.valueMinor,
            valuedOn = standing.valuedOn,
            gainMinor = standing.gainMinor,
            firstContributedOn = standing.firstContributedOn,
            feesMinor = fees[row[Assets.id]].orEmpty().sumOf { it.amountMinor },
        )
    }
}

/**
 * Loads the household's holdings, by name, or only [assetId]. Market data they follow is refreshed first
 * where stale: prices of their securities, and the ČNB fixings back to the first contribution when a security
 * trades in another currency than the household's.
 */
private suspend fun RoutingCall.book(assetId: Uuid? = null): Book {
    val householdId = household.id
    val currency = household.currency
    val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
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
    val (symbols, fxYears) = dbQuery {
        val symbols = Assets.select(Assets.symbol).where(filter).mapNotNull { it[Assets.symbol] }.distinct()
        val foreign = securities(symbols).values.any { it.currency != currency }
        val first = AssetContributions.select(AssetContributions.date.min()).where(contributionFilter)
            .single()[AssetContributions.date.min()]
        symbols to if (foreign && first != null) (first.year..today.year).toList() else emptyList()
    }
    application.market.refresh(symbols, fxYears)
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
        Book(holdings, lots, valuations, fees, loadPrices(currency, symbols), today)
    }.also { if (assetId != null && it.holdings.isEmpty()) throw notFound("Asset") }
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
    val quantity: BigDecimal?,
    val accountId: Uuid?,
    val notes: String,
)

private fun ContributionInput.validated(): ValidContribution {
    val amount = nonZeroAmount(amountMinor)
    // Shares go the way the money does: bought with money put in, sold for money taken out.
    val quantity = quantity
    if (quantity != null && (!quantity.isFinite() || quantity == 0.0 || (quantity > 0) != (amount > 0))) {
        throw badRequest("invalid_quantity", "Shares must be non-zero, and negative only for money taken out", "quantity")
    }
    return ValidContribution(
        date = date,
        amountMinor = amount,
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
    quantity = this[AssetContributions.quantity]?.toDouble(),
    accountId = this[AssetContributions.accountId]?.toString(),
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
