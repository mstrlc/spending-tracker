package dev.spendingtracker.server.investment

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
import dev.spendingtracker.server.db.InvestmentContributions
import dev.spendingtracker.server.db.InvestmentFees
import dev.spendingtracker.server.db.InvestmentValuations
import dev.spendingtracker.server.db.Investments
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
import dev.spendingtracker.shared.api.Investment
import dev.spendingtracker.shared.api.InvestmentHistory
import dev.spendingtracker.shared.api.InvestmentInput
import dev.spendingtracker.shared.api.InvestmentKind
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
fun Route.investmentRoutes() = route("/investments") {
    get({
        operationId = "listInvestments"
        description = "Holdings by name, each with what was put in, what it's worth and the gain as of today."
        response {
            code(HttpStatusCode.OK) { body<List<Investment>>() }
        }
    }) {
        val book = call.book()
        call.respond(book.holdings.map { book.investment(it) })
    }

    get("/history", {
        operationId = "getInvestmentHistory"
        description = "What the holdings (or one of them) had put in and were worth at the end of each month " +
            "since the first contribution, then today."
        request {
            queryParameter<String>("investmentId") { description = "Only this holding" }
        }
        response {
            code(HttpStatusCode.OK) { body<InvestmentHistory>() }
            code(HttpStatusCode.BadRequest) { body<ApiError>() }
        }
    }) {
        val investmentId = parseId(call.request.queryParameters["investmentId"], "investmentId")
        val book = call.book(investmentId)
        val first = book.lots.values.flatten().minOfOrNull { it.date }
        val points = if (first == null) {
            emptyList()
        } else {
            monthEnds(first, book.today).map { day ->
                val standings = book.holdings.map { book.standing(it, day) }
                HistoryPoint(day, standings.sumOf { it.contributedMinor }, standings.sumOf { it.valueMinor })
            }
        }
        call.respond(InvestmentHistory(points))
    }

    post({
        operationId = "createInvestment"
        request { body<InvestmentInput>() }
        response {
            code(HttpStatusCode.Created) { body<Investment>() }
            code(HttpStatusCode.BadRequest) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val input = call.receive<InvestmentInput>().validated()
        call.registerSymbol(input.symbol)
        val id = Uuid.random()
        dbQuery {
            input.checkOwner(householdId)
            Investments.insert {
                it[Investments.id] = id
                it[Investments.householdId] = householdId
                it.write(input)
                it[version] = 1
                it[createdBy] = call.userId
            }
        }
        val book = call.book(id)
        call.respond(HttpStatusCode.Created, book.investment(book.holdings.single()))
    }

    put("/{investmentId}", {
        operationId = "updateInvestment"
        request {
            pathParameter<String>("investmentId")
            body<InvestmentInput>()
        }
        response {
            code(HttpStatusCode.OK) { body<Investment>() }
            code(HttpStatusCode.BadRequest) { body<ApiError>() }
            code(HttpStatusCode.NotFound) { body<ApiError>() }
            code(HttpStatusCode.Conflict) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val id = call.pathId("investmentId", "Investment")
        val raw = call.receive<InvestmentInput>()
        val version = requireVersion(raw.version)
        val input = raw.validated()
        call.registerSymbol(input.symbol)
        dbQuery {
            input.checkOwner(householdId)
            val affected = Investments.update({ matchInvestment(householdId, id) and (Investments.version eq version) }) {
                it.write(input)
                it[Investments.version] = version + 1
                it[updatedAt] = Clock.System.now()
            }
            checkVersioned(affected, { investmentExists(householdId, id) }, "Investment")
        }
        val book = call.book(id)
        call.respond(book.investment(book.holdings.single()))
    }

    delete("/{investmentId}", {
        operationId = "deleteInvestment"
        description = "Deletes the holding with its contributions and valuations."
        request {
            pathParameter<String>("investmentId")
            queryParameter<Int>("version") { required = true }
        }
        response {
            code(HttpStatusCode.NoContent) {}
            code(HttpStatusCode.NotFound) { body<ApiError>() }
            code(HttpStatusCode.Conflict) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val id = call.pathId("investmentId", "Investment")
        val version = call.versionParam()
        dbQuery {
            val affected = Investments.deleteWhere { matchInvestment(householdId, id) and (Investments.version eq version) }
            checkVersioned(affected, { investmentExists(householdId, id) }, "Investment")
        }
        call.respond(HttpStatusCode.NoContent)
    }

    route("/{investmentId}/contributions") { contributionRoutes() }
    route("/{investmentId}/valuations") { valuationRoutes() }
    route("/{investmentId}/fees") { feeRoutes() }
}

private fun Route.contributionRoutes() {
    get({
        operationId = "listContributions"
        description = "The holding's contributions, newest first."
        request { pathParameter<String>("investmentId") }
        response {
            code(HttpStatusCode.OK) { body<List<Contribution>>() }
            code(HttpStatusCode.NotFound) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val investmentId = call.investmentId()
        val contributions = dbQuery {
            requireInvestment(householdId, investmentId)
            InvestmentContributions.selectAll()
                .where { InvestmentContributions.investmentId eq investmentId }
                .orderBy(InvestmentContributions.date to SortOrder.DESC, InvestmentContributions.id to SortOrder.ASC)
                .map { it.toContribution() }
        }
        call.respond(contributions)
    }

    post({
        operationId = "createContribution"
        request {
            pathParameter<String>("investmentId")
            body<ContributionInput>()
        }
        response {
            code(HttpStatusCode.Created) { body<Contribution>() }
            code(HttpStatusCode.BadRequest) { body<ApiError>() }
            code(HttpStatusCode.NotFound) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val investmentId = call.investmentId()
        val input = call.receive<ContributionInput>().validated()
        val id = Uuid.random()
        val contribution = dbQuery {
            requireInvestment(householdId, investmentId)
            input.checkAccount(householdId)
            InvestmentContributions.insert {
                it[InvestmentContributions.id] = id
                it[InvestmentContributions.householdId] = householdId
                it[InvestmentContributions.investmentId] = investmentId
                it.write(input)
                it[version] = 1
                it[createdBy] = call.userId
            }
            InvestmentContributions.selectAll().where { InvestmentContributions.id eq id }.single().toContribution()
        }
        call.respond(HttpStatusCode.Created, contribution)
    }

    put("/{contributionId}", {
        operationId = "updateContribution"
        request {
            pathParameter<String>("investmentId")
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
        val investmentId = call.investmentId()
        val id = call.pathId("contributionId", "Contribution")
        val raw = call.receive<ContributionInput>()
        val version = requireVersion(raw.version)
        val input = raw.validated()
        val contribution = dbQuery {
            input.checkAccount(householdId)
            val match = matchContribution(householdId, investmentId, id)
            val affected = InvestmentContributions.update({ match and (InvestmentContributions.version eq version) }) {
                it.write(input)
                it[InvestmentContributions.version] = version + 1
                it[updatedAt] = Clock.System.now()
            }
            checkVersioned(affected, { !InvestmentContributions.selectAll().where(match).empty() }, "Contribution")
            InvestmentContributions.selectAll().where { InvestmentContributions.id eq id }.single().toContribution()
        }
        call.respond(contribution)
    }

    delete("/{contributionId}", {
        operationId = "deleteContribution"
        request {
            pathParameter<String>("investmentId")
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
        val investmentId = call.investmentId()
        val id = call.pathId("contributionId", "Contribution")
        val version = call.versionParam()
        dbQuery {
            val match = matchContribution(householdId, investmentId, id)
            val affected = InvestmentContributions.deleteWhere { match and (InvestmentContributions.version eq version) }
            checkVersioned(affected, { !InvestmentContributions.selectAll().where(match).empty() }, "Contribution")
        }
        call.respond(HttpStatusCode.NoContent)
    }
}

private fun Route.valuationRoutes() {
    get({
        operationId = "listValuations"
        description = "The holding's valuations, newest first."
        request { pathParameter<String>("investmentId") }
        response {
            code(HttpStatusCode.OK) { body<List<Valuation>>() }
            code(HttpStatusCode.NotFound) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val investmentId = call.investmentId()
        val valuations = dbQuery {
            requireInvestment(householdId, investmentId)
            InvestmentValuations.selectAll()
                .where { InvestmentValuations.investmentId eq investmentId }
                .orderBy(InvestmentValuations.date to SortOrder.DESC)
                .map { it.toValuation() }
        }
        call.respond(valuations)
    }

    post({
        operationId = "createValuation"
        description = "Fails with 409 valuation_exists when the holding already has a valuation on that day."
        request {
            pathParameter<String>("investmentId")
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
        val investmentId = call.investmentId()
        val input = call.receive<ValuationInput>().validated()
        val id = Uuid.random()
        val valuation = oneValuationPerDay {
            dbQuery {
                requireInvestment(householdId, investmentId)
                InvestmentValuations.insert {
                    it[InvestmentValuations.id] = id
                    it[InvestmentValuations.householdId] = householdId
                    it[InvestmentValuations.investmentId] = investmentId
                    it.write(input)
                    it[version] = 1
                    it[createdBy] = call.userId
                }
                InvestmentValuations.selectAll().where { InvestmentValuations.id eq id }.single().toValuation()
            }
        }
        call.respond(HttpStatusCode.Created, valuation)
    }

    put("/{valuationId}", {
        operationId = "updateValuation"
        request {
            pathParameter<String>("investmentId")
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
        val investmentId = call.investmentId()
        val id = call.pathId("valuationId", "Valuation")
        val raw = call.receive<ValuationInput>()
        val version = requireVersion(raw.version)
        val input = raw.validated()
        val valuation = oneValuationPerDay {
            dbQuery {
                val match = matchValuation(householdId, investmentId, id)
                val affected = InvestmentValuations.update({ match and (InvestmentValuations.version eq version) }) {
                    it.write(input)
                    it[InvestmentValuations.version] = version + 1
                    it[updatedAt] = Clock.System.now()
                }
                checkVersioned(affected, { !InvestmentValuations.selectAll().where(match).empty() }, "Valuation")
                InvestmentValuations.selectAll().where { InvestmentValuations.id eq id }.single().toValuation()
            }
        }
        call.respond(valuation)
    }

    delete("/{valuationId}", {
        operationId = "deleteValuation"
        request {
            pathParameter<String>("investmentId")
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
        val investmentId = call.investmentId()
        val id = call.pathId("valuationId", "Valuation")
        val version = call.versionParam()
        dbQuery {
            val match = matchValuation(householdId, investmentId, id)
            val affected = InvestmentValuations.deleteWhere { match and (InvestmentValuations.version eq version) }
            checkVersioned(affected, { !InvestmentValuations.selectAll().where(match).empty() }, "Valuation")
        }
        call.respond(HttpStatusCode.NoContent)
    }
}

// ---- Holdings ----

private data class ValidInvestment(
    val name: String,
    val kind: InvestmentKind,
    val ownerUserId: Uuid?,
    val notes: String,
    val symbol: String?,
)

private fun InvestmentInput.validated() = ValidInvestment(
    name = requiredText(name, "name", max = 200),
    kind = kind,
    ownerUserId = parseId(ownerUserId, "ownerUserId"),
    notes = optionalText(notes, "notes", max = 2000),
    symbol = symbol?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }?.let { requiredText(it, "symbol", max = 40) },
)

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

private fun ValidInvestment.checkOwner(householdId: Uuid) {
    if (ownerUserId != null && !isMember(householdId, ownerUserId)) {
        throw badRequest("invalid_member", "Owner must be a member of the household", "ownerUserId")
    }
}

private fun UpdateBuilder<*>.write(input: ValidInvestment) {
    this[Investments.name] = input.name
    this[Investments.kind] = input.kind.dbValue
    this[Investments.ownerUserId] = input.ownerUserId
    this[Investments.notes] = input.notes
    this[Investments.symbol] = input.symbol
}

private val InvestmentKind.dbValue get() = name.lowercase()

private fun kindFrom(value: String) = InvestmentKind.entries.first { it.dbValue == value }

private fun matchInvestment(householdId: Uuid, id: Uuid) = (Investments.id eq id) and (Investments.householdId eq householdId)

private fun investmentExists(householdId: Uuid, id: Uuid) = !Investments.selectAll().where { matchInvestment(householdId, id) }.empty()

internal fun requireInvestment(householdId: Uuid, id: Uuid) {
    if (!investmentExists(householdId, id)) throw notFound("Investment")
}

internal fun RoutingCall.investmentId() = pathId("investmentId", "Investment")

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
        val id = row[Investments.id]
        val price = row[Investments.symbol]?.let { symbol -> { shares: BigDecimal, day: LocalDate -> prices.value(symbol, shares, day) } }
        return standingOn(lots[id].orEmpty(), valuations[id].orEmpty(), fees[id].orEmpty(), on, price)
    }

    fun investment(row: ResultRow): Investment {
        val standing = standing(row, today)
        val quantities = lots[row[Investments.id]].orEmpty().mapNotNull { it.quantity }
        val security = row[Investments.symbol]?.let { symbol ->
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
        return Investment(
            id = row[Investments.id].toString(),
            name = row[Investments.name],
            kind = kindFrom(row[Investments.kind]),
            ownerUserId = row[Investments.ownerUserId]?.toString(),
            notes = row[Investments.notes],
            version = row[Investments.version],
            security = security,
            shares = if (quantities.isEmpty()) null else quantities.sumOf { it }.toDouble(),
            marketValued = standing.marketValued,
            contributedMinor = standing.contributedMinor,
            valueMinor = standing.valueMinor,
            valuedOn = standing.valuedOn,
            gainMinor = standing.gainMinor,
            firstContributedOn = standing.firstContributedOn,
            feesMinor = fees[row[Investments.id]].orEmpty().sumOf { it.amountMinor },
        )
    }
}

/**
 * Loads the household's holdings, by name, or only [investmentId]. Market data they follow is refreshed first
 * where stale: prices of their securities, and the ČNB fixings back to the first contribution when a security
 * trades in another currency than the household's.
 */
private suspend fun RoutingCall.book(investmentId: Uuid? = null): Book {
    val householdId = household.id
    val currency = household.currency
    val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
    val filter = if (investmentId == null) {
        Investments.householdId eq householdId
    } else {
        matchInvestment(householdId, investmentId)
    }
    val contributionFilter = if (investmentId == null) {
        InvestmentContributions.householdId eq householdId
    } else {
        (InvestmentContributions.householdId eq householdId) and (InvestmentContributions.investmentId eq investmentId)
    }
    val (symbols, fxYears) = dbQuery {
        val symbols = Investments.select(Investments.symbol).where(filter).mapNotNull { it[Investments.symbol] }.distinct()
        val foreign = securities(symbols).values.any { it.currency != currency }
        val first = InvestmentContributions.select(InvestmentContributions.date.min()).where(contributionFilter)
            .single()[InvestmentContributions.date.min()]
        symbols to if (foreign && first != null) (first.year..today.year).toList() else emptyList()
    }
    application.market.refresh(symbols, fxYears)
    return dbQuery {
        val holdings = Investments.selectAll().where(filter)
            .orderBy(Investments.name to SortOrder.ASC, Investments.id to SortOrder.ASC)
            .toList()
        val lots = InvestmentContributions.selectAll().where(contributionFilter).groupBy({ it[InvestmentContributions.investmentId] }) {
            Lot(it[InvestmentContributions.date], it[InvestmentContributions.amountMinor], it[InvestmentContributions.quantity])
        }
        val valuationFilter = if (investmentId == null) {
            InvestmentValuations.householdId eq householdId
        } else {
            (InvestmentValuations.householdId eq householdId) and (InvestmentValuations.investmentId eq investmentId)
        }
        val valuations = InvestmentValuations.selectAll().where(valuationFilter).groupBy({ it[InvestmentValuations.investmentId] }) {
            Dated(it[InvestmentValuations.date], it[InvestmentValuations.valueMinor])
        }
        val feeFilter = if (investmentId == null) {
            InvestmentFees.householdId eq householdId
        } else {
            (InvestmentFees.householdId eq householdId) and (InvestmentFees.investmentId eq investmentId)
        }
        val fees = InvestmentFees.selectAll().where(feeFilter)
            .groupBy({ it[InvestmentFees.investmentId] }) { Dated(it[InvestmentFees.date], it[InvestmentFees.amountMinor]) }
        Book(holdings, lots, valuations, fees, loadPrices(currency, symbols), today)
    }.also { if (investmentId != null && it.holdings.isEmpty()) throw notFound("Investment") }
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
    this[InvestmentContributions.date] = input.date
    this[InvestmentContributions.amountMinor] = input.amountMinor
    this[InvestmentContributions.quantity] = input.quantity
    this[InvestmentContributions.accountId] = input.accountId
    this[InvestmentContributions.notes] = input.notes
}

private fun matchContribution(householdId: Uuid, investmentId: Uuid, id: Uuid) =
    (InvestmentContributions.id eq id) and (InvestmentContributions.householdId eq householdId) and
        (InvestmentContributions.investmentId eq investmentId)

private fun ResultRow.toContribution() = Contribution(
    id = this[InvestmentContributions.id].toString(),
    investmentId = this[InvestmentContributions.investmentId].toString(),
    date = this[InvestmentContributions.date],
    amountMinor = this[InvestmentContributions.amountMinor],
    quantity = this[InvestmentContributions.quantity]?.toDouble(),
    accountId = this[InvestmentContributions.accountId]?.toString(),
    notes = this[InvestmentContributions.notes],
    version = this[InvestmentContributions.version],
)

// ---- Valuations ----

private data class ValidValuation(val date: LocalDate, val valueMinor: Long, val notes: String)

private fun ValuationInput.validated() = ValidValuation(
    date = date,
    valueMinor = nonNegativeAmount(valueMinor, "valueMinor"),
    notes = optionalText(notes, "notes", max = 2000),
)

private fun UpdateBuilder<*>.write(input: ValidValuation) {
    this[InvestmentValuations.date] = input.date
    this[InvestmentValuations.valueMinor] = input.valueMinor
    this[InvestmentValuations.notes] = input.notes
}

private fun matchValuation(householdId: Uuid, investmentId: Uuid, id: Uuid) =
    (InvestmentValuations.id eq id) and (InvestmentValuations.householdId eq householdId) and
        (InvestmentValuations.investmentId eq investmentId)

private suspend fun <T> oneValuationPerDay(block: suspend () -> T): T =
    try {
        block()
    } catch (e: Exception) {
        if (e.isUniqueViolation()) throw conflict("valuation_exists", "This holding already has a value for that day", "date")
        throw e
    }

private fun ResultRow.toValuation() = Valuation(
    id = this[InvestmentValuations.id].toString(),
    investmentId = this[InvestmentValuations.investmentId].toString(),
    date = this[InvestmentValuations.date],
    valueMinor = this[InvestmentValuations.valueMinor],
    notes = this[InvestmentValuations.notes],
    version = this[InvestmentValuations.version],
)
