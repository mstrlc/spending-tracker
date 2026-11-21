package dev.spendingtracker.server.overview

import dev.spendingtracker.server.common.badRequest
import dev.spendingtracker.server.common.dateParam
import dev.spendingtracker.server.db.Categories
import dev.spendingtracker.server.db.Expenses
import dev.spendingtracker.server.db.HouseholdMembers
import dev.spendingtracker.server.db.Income
import dev.spendingtracker.server.income.dbValue
import dev.spendingtracker.server.income.netIncomeMinor
import dev.spendingtracker.server.db.AssetContributions
import dev.spendingtracker.server.db.Assets
import dev.spendingtracker.server.db.dbQuery
import dev.spendingtracker.server.expense.loadSpendingParts
import dev.spendingtracker.server.household.household
import dev.spendingtracker.server.merchant.merchantNames
import dev.spendingtracker.server.asset.Dated
import dev.spendingtracker.server.asset.wealthOn
import dev.spendingtracker.server.db.BankConnections
import dev.spendingtracker.server.db.BankRows
import dev.spendingtracker.server.inbox.loadFeeds
import dev.spendingtracker.server.inbox.pendingRows
import dev.spendingtracker.shared.api.FeedFreshness
import dev.spendingtracker.shared.api.Freshness
import dev.spendingtracker.shared.api.NetWorth
import dev.spendingtracker.shared.api.ApiError
import dev.spendingtracker.shared.api.AssetKind
import dev.spendingtracker.shared.api.IncomeKind
import dev.spendingtracker.shared.api.Overview
import io.github.smiley4.ktoropenapi.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.datetime.todayIn
import kotlinx.datetime.yearMonth
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.between
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.min
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Must be inside a HouseholdAccess scope. */
fun Route.overviewRoutes() = route("/overview") {
    get({
        operationId = "getOverview"
        description = "Cash flow, typical month, spending anomalies, subscriptions and top lists for one month, " +
            "net worth and runway at its end (or today), and how current the bank data is."
        request {
            queryParameter<String>("month") { description = "YYYY-MM; defaults to the month of today" }
            queryParameter<String>("today") {
                description = "The caller's date, YYYY-MM-DD, so a month in progress is measured in their time zone; " +
                    "defaults to the server's"
            }
            queryParameter<String>("whose") {
                description = "Only what's joint (`joint`) or one member's (their user id): spending by whose each " +
                    "expense or line is, income by recipient, investing by the asset's owner. Net worth, money at hand " +
                    "and the bank data stay the whole household's. Omitted: everything."
            }
        }
        response {
            code(HttpStatusCode.OK) { body<Overview>() }
            code(HttpStatusCode.BadRequest) { body<ApiError>() }
        }
    }) {
        val today = call.dateParam("today") ?: Clock.System.todayIn(TimeZone.currentSystemDefault())
        val month = call.request.queryParameters["month"]?.let {
            runCatching { YearMonth.parse(it) }.getOrElse { throw badRequest("invalid_month", "month must be YYYY-MM", "month") }
        } ?: today.yearMonth
        val householdId = call.household.id
        val whose = call.request.queryParameters["whose"]?.takeIf { it.isNotBlank() }
        val member = whose?.takeIf { it != "joint" }?.let {
            runCatching { Uuid.parse(it) }.getOrNull() ?: throw badRequest("invalid_whose", "whose must be joint or a member's id", "whose")
        }
        if (member != null) {
            val isMember = dbQuery {
                HouseholdMembers.selectAll()
                    .where { (HouseholdMembers.householdId eq householdId) and (HouseholdMembers.userId eq member) }
                    .any()
            }
            if (!isMember) throw badRequest("unknown_member", "No such member in this household", "whose")
        }
        val keep: (Uuid?) -> Boolean = when {
            whose == null -> { _ -> true }
            member == null -> { owner -> owner == null }
            else -> { owner -> owner == member }
        }
        // The longest look back: anomalies of the month before, against the 12 months before that.
        val windowStart = month.shift(-BASELINE_MONTHS - 1)

        val (flows, firstMonth) = dbQuery {
            val merchants = merchantNames(householdId)
            val categories = Categories.selectAll().where { Categories.householdId eq householdId }.associateBy { it[Categories.id] }
            // Refunds are counted in their original's month and category (see loadSpendingParts).
            val spends = loadSpendingParts(householdId, windowStart.firstDay, month.lastDay).map { part ->
                val category = part.categoryId?.let(categories::get)
                val top = category?.get(Categories.parentId)?.let(categories::get) ?: category
                Spend(
                    date = part.date,
                    amountMinor = part.amountMinor,
                    merchant = part.merchantId?.let(merchants::get).orEmpty(),
                    topCategoryId = top?.get(Categories.id),
                    investment = top?.get(Categories.kind) == "investment",
                    ownerUserId = part.ownerUserId,
                )
            }
            val earnings = Income.selectAll()
                .where { (Income.householdId eq householdId) and (Income.earnedMonth.between(windowStart.firstDay, month.lastDay)) }
                .orderBy(Income.earnedMonth to SortOrder.DESC)
                // Counted in the month it was earned, whenever it arrived.
                .map {
                    val deduction = it[Income.kind] == IncomeKind.DEDUCTION.dbValue
                    Earning(it[Income.earnedMonth], it.netIncomeMinor(), it[Income.sourceName], deduction, it[Income.recipientUserId])
                }
            // Money borrowed on or repaid to a liability (a loan, card debt) isn't invested.
            val liabilityKinds = AssetKind.entries.filter { it.liability }.map { it.name.lowercase() }
            val contributions = AssetContributions.join(Assets, JoinType.INNER, AssetContributions.assetId, Assets.id)
                .select(AssetContributions.date, AssetContributions.amountMinor, Assets.ownerUserId)
                .where {
                    (AssetContributions.householdId eq householdId) and
                        (AssetContributions.date.between(windowStart.firstDay, month.lastDay)) and
                        (Assets.kind notInList liabilityKinds)
                }
                .map { Dated(it[AssetContributions.date], it[AssetContributions.amountMinor]) to it[Assets.ownerUserId] }
            // Where the ledger starts: months before it are unknown, not empty. Spending and income mark it; an asset's
            // history can reach back years before (statements entered for its cost and return), so its contributions
            // set the start only when nothing else has been recorded.
            val firstExpense = Expenses.select(Expenses.date.min()).where { Expenses.householdId eq householdId }.single()[Expenses.date.min()]
            val firstIncome = Income.select(Income.earnedMonth.min()).where { Income.householdId eq householdId }.single()[Income.earnedMonth.min()]
            val firstContribution by lazy {
                AssetContributions.join(Assets, JoinType.INNER, AssetContributions.assetId, Assets.id)
                    .select(AssetContributions.date.min())
                    .where { (AssetContributions.householdId eq householdId) and (Assets.kind notInList liabilityKinds) }
                    .single()[AssetContributions.date.min()]
            }
            val ledgerStart = listOfNotNull(firstExpense, firstIncome).minOrNull() ?: firstContribution
            Flows(spends, earnings, contributions) to ledgerStart?.yearMonth
        }
        val (allSpends, allEarnings, allContributions) = flows
        val spends = allSpends.filter { keep(it.ownerUserId) }
        val earnings = allEarnings.filter { keep(it.ownerUserId) }
        val contributions = allContributions.filter { keep(it.second) }.map { it.first }

        val start = maxOf(firstMonth ?: month, month.shift(-(BASELINE_MONTHS - 1)))
        val months = monthsBetween(minOf(start, month), month)
        val history = months.map { monthFlow(it, spends, earnings, contributions) }
        val anomalyMonth = anomalyMonth(month, today)
        val typical = typicalMonth(history.dropLast(1))
        // Runway measures the household's money at hand against the household's spending, whoever is filtered on.
        val householdTypical = if (whose == null) {
            typical
        } else {
            typicalMonth(months.dropLast(1).map { monthFlow(it, allSpends, allEarnings, allContributions.map { c -> c.first }) })
        }
        val on = minOf(month.lastDay, today)
        val wealth = call.wealthOn(on)
        val freshness = dbQuery { freshness(householdId, month) }
        val overview = Overview(
            month = month.toString(),
            daysElapsed = daysElapsed(month, today),
            daysInMonth = month.numberOfDays,
            thisMonth = history.last(),
            typicalMonth = typical,
            history = history,
            anomalyMonth = anomalyMonth.toString(),
            anomalies = firstMonth?.let { spendingAnomalies(spends, anomalyMonth, it, today) }.orEmpty(),
            subscriptions = subscriptions(spends, month),
            topCategories = topCategories(spends, month),
            topMerchants = topMerchants(spends, month),
            topSources = topSources(earnings, month),
            incomeSources = incomeSources(earnings, month),
            deductionsMinor = deductions(earnings, month),
            netWorth = NetWorth(
                on = on,
                netMinor = wealth.accountsMinor + wealth.assetsMinor + wealth.liabilitiesMinor,
                accountsMinor = wealth.accountsMinor,
                assetsMinor = wealth.assetsMinor,
                liabilitiesMinor = wealth.liabilitiesMinor,
                liquidMinor = wealth.liquidMinor,
                runwayMonths = householdTypical?.spendingMinor?.takeIf { it > 0 }?.let { wealth.liquidMinor.coerceAtLeast(0).toDouble() / it },
                accountsRead = wealth.accountsRead,
            ),
            freshness = freshness,
        )
        call.respond(overview)
    }
}

/** Each feed's reach and last sync, the one reaching least far first, and the pending rows. Call inside a transaction. */
private fun freshness(householdId: Uuid, month: YearMonth): Freshness {
    val consents = BankConnections.select(BankConnections.id, BankConnections.consentExpiresAt)
        .where { BankConnections.householdId eq householdId }
        .associate { it[BankConnections.id].toString() to it[BankConnections.consentExpiresAt]?.toString() }
    val feeds = loadFeeds(householdId)
    val pendingInMonth = BankRows.selectAll()
        .where { (BankRows.householdId eq householdId) and pendingRows() and BankRows.date.between(month.firstDay, month.lastDay) }
        .count().toInt()
    return Freshness(
        feeds = feeds.sortedWith(compareBy(nullsFirst()) { it.lastDate })
            .map {
                FeedFreshness(
                    feedId = it.id,
                    accountId = it.accountId,
                    name = it.name,
                    live = it.connectionId != null,
                    lastDate = it.lastDate,
                    lastSyncedAt = it.lastSyncedAt,
                    consentExpiresAt = it.connectionId?.let(consents::get),
                )
            },
        pendingRows = feeds.sumOf { it.pendingCount },
        pendingInMonth = pendingInMonth,
    )
}

/** Contributions come with their asset's owner (null for joint). */
private data class Flows(val spends: List<Spend>, val earnings: List<Earning>, val contributions: List<Pair<Dated, Uuid?>>)
