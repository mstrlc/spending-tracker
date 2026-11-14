package dev.spendingtracker.server.overview

import dev.spendingtracker.server.common.badRequest
import dev.spendingtracker.server.common.dateParam
import dev.spendingtracker.server.db.Categories
import dev.spendingtracker.server.db.Expenses
import dev.spendingtracker.server.db.Income
import dev.spendingtracker.server.income.dbValue
import dev.spendingtracker.server.income.netIncomeMinor
import dev.spendingtracker.server.db.AssetContributions
import dev.spendingtracker.server.db.dbQuery
import dev.spendingtracker.server.expense.loadExpenseLines
import dev.spendingtracker.server.household.household
import dev.spendingtracker.server.merchant.merchantNames
import dev.spendingtracker.server.asset.Dated
import dev.spendingtracker.shared.api.ApiError
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
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.between
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.min
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Must be inside a HouseholdAccess scope. */
fun Route.overviewRoutes() = route("/overview") {
    get({
        operationId = "getOverview"
        description = "Cash flow, typical month, spending anomalies, subscriptions and top lists for one month."
        request {
            queryParameter<String>("month") { description = "YYYY-MM; defaults to the month of today" }
            queryParameter<String>("today") {
                description = "The caller's date, YYYY-MM-DD, so a month in progress is measured in their time zone; " +
                    "defaults to the server's"
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
        // The longest look back: anomalies of the month before, against the 12 months before that.
        val windowStart = month.shift(-BASELINE_MONTHS - 1)

        val (flows, firstMonth) = dbQuery {
            val merchants = merchantNames(householdId)
            val categories = Categories.selectAll().where { Categories.householdId eq householdId }.associateBy { it[Categories.id] }
            val expenseRows = Expenses.selectAll()
                .where { (Expenses.householdId eq householdId) and (Expenses.date.between(windowStart.firstDay, month.lastDay)) }
                .orderBy(Expenses.date to SortOrder.DESC)
                .toList()
            val lines = loadExpenseLines(householdId, expenseRows.map { it[Expenses.id] })
            val spends = expenseRows.flatMap { row ->
                val date = row[Expenses.date]
                val merchant = row[Expenses.merchantId]?.let(merchants::get).orEmpty()
                val expenseLines = lines[row[Expenses.id]].orEmpty()
                val parts = if (expenseLines.isEmpty()) {
                    listOf(row[Expenses.categoryId] to row[Expenses.amountMinor])
                } else {
                    expenseLines.map { it.categoryId?.let(Uuid::parse) to it.amountMinor }
                }
                parts.map { (catId, amount) ->
                    val category = catId?.let(categories::get)
                    val top = category?.get(Categories.parentId)?.let(categories::get) ?: category
                    Spend(
                        date = date,
                        amountMinor = amount,
                        merchant = merchant,
                        topCategoryId = top?.get(Categories.id),
                        investment = top?.get(Categories.kind) == "investment",
                    )
                }
            }
            val earnings = Income.selectAll()
                .where { (Income.householdId eq householdId) and (Income.earnedMonth.between(windowStart.firstDay, month.lastDay)) }
                .orderBy(Income.earnedMonth to SortOrder.DESC)
                // Counted in the month it was earned, whenever it arrived.
                .map {
                    val deduction = it[Income.kind] == IncomeKind.DEDUCTION.dbValue
                    Earning(it[Income.earnedMonth], it.netIncomeMinor(), it[Income.sourceName], deduction)
                }
            val contributions = AssetContributions.selectAll()
                .where {
                    (AssetContributions.householdId eq householdId) and
                        (AssetContributions.date.between(windowStart.firstDay, month.lastDay))
                }
                .map { Dated(it[AssetContributions.date], it[AssetContributions.amountMinor]) }
            // Where the ledger starts: months before it are unknown, not empty.
            val firstExpense = Expenses.select(Expenses.date.min()).where { Expenses.householdId eq householdId }.single()[Expenses.date.min()]
            val firstIncome = Income.select(Income.earnedMonth.min()).where { Income.householdId eq householdId }.single()[Income.earnedMonth.min()]
            val firstContribution = AssetContributions.select(AssetContributions.date.min())
                .where { AssetContributions.householdId eq householdId }.single()[AssetContributions.date.min()]
            Flows(spends, earnings, contributions) to listOfNotNull(firstExpense, firstIncome, firstContribution).minOrNull()?.yearMonth
        }
        val (spends, earnings, contributions) = flows

        val start = maxOf(firstMonth ?: month, month.shift(-(BASELINE_MONTHS - 1)))
        val history = monthsBetween(minOf(start, month), month).map { monthFlow(it, spends, earnings, contributions) }
        val complete = history.dropLast(1).takeLast(TYPICAL_WINDOW)
        val anomalyMonth = anomalyMonth(month, today)
        val overview = Overview(
            month = month.toString(),
            daysElapsed = daysElapsed(month, today),
            daysInMonth = month.numberOfDays,
            thisMonth = history.last(),
            typicalMonth = typicalMonth(complete),
            history = history,
            anomalyMonth = anomalyMonth.toString(),
            anomalies = firstMonth?.let { spendingAnomalies(spends, anomalyMonth, it, today) }.orEmpty(),
            subscriptions = subscriptions(spends, month),
            topCategories = topCategories(spends, month),
            topMerchants = topMerchants(spends, month),
            topSources = topSources(earnings, month),
        )
        call.respond(overview)
    }
}

private data class Flows(val spends: List<Spend>, val earnings: List<Earning>, val contributions: List<Dated>)
