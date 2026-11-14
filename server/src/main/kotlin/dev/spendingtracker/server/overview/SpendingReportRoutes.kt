package dev.spendingtracker.server.overview

import dev.spendingtracker.server.common.badRequest
import dev.spendingtracker.server.common.dateParam
import dev.spendingtracker.server.common.parseId
import dev.spendingtracker.server.db.AssetContributions
import dev.spendingtracker.server.db.Assets
import dev.spendingtracker.server.db.Categories
import dev.spendingtracker.server.db.Expenses
import dev.spendingtracker.server.db.HouseholdMembers
import dev.spendingtracker.server.db.dbQuery
import dev.spendingtracker.server.expense.SpendingPart
import dev.spendingtracker.server.expense.loadSpendingParts
import dev.spendingtracker.server.household.household
import dev.spendingtracker.server.merchant.merchantNames
import dev.spendingtracker.shared.api.ApiError
import dev.spendingtracker.shared.api.AssetKind
import dev.spendingtracker.shared.api.ReportCategory
import dev.spendingtracker.shared.api.ReportExpense
import dev.spendingtracker.shared.api.ReportMerchant
import dev.spendingtracker.shared.api.ReportMonth
import dev.spendingtracker.shared.api.ReportPeriod
import dev.spendingtracker.shared.api.SpendingReport
import io.github.smiley4.ktoropenapi.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.todayIn
import kotlinx.datetime.yearMonth
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.between
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.min
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.math.roundToLong
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Must be inside a HouseholdAccess scope. */
fun Route.spendingReportRoutes() = route("/spending-report") {
    get({
        operationId = "getSpendingReport"
        description = "Spending over a period, optionally narrowed to a category (subcategories included), merchant, " +
            "account and whose: the total, each month, the same dates a year earlier, and breakdowns by category and merchant."
        request {
            queryParameter<String>("from") { description = "Inclusive start, YYYY-MM-DD; defaults to where the ledger starts" }
            queryParameter<String>("to") { description = "Inclusive end, YYYY-MM-DD; defaults to today" }
            queryParameter<String>("today") { description = "The caller's date, YYYY-MM-DD; defaults to the server's" }
            queryParameter<String>("categoryId") { description = "A category's id, or `none` for uncategorized spending" }
            queryParameter<List<String>>("excludeCategoryId") {
                description = "Categories to leave out, their subcategories with them (repeatable), e.g. a subcategory of [categoryId]"
            }
            queryParameter<String>("merchantId") { description = "A merchant's id, or `none` for expenses without one" }
            queryParameter<String>("accountId")
            queryParameter<String>("whose") { description = "`joint` or a member's user id, by whose each expense or line is" }
        }
        response {
            code(HttpStatusCode.OK) { body<SpendingReport>() }
            code(HttpStatusCode.BadRequest) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val params = call.request.queryParameters
        val today = call.dateParam("today") ?: Clock.System.todayIn(TimeZone.currentSystemDefault())
        val askedFrom = call.dateParam("from")
        val askedTo = call.dateParam("to") ?: today
        if (askedFrom != null && askedFrom > askedTo) throw badRequest("invalid_range", "from must not be after to", "from")
        val noCategory = params["categoryId"] == "none"
        val categoryId = if (noCategory) null else parseId(params["categoryId"], "categoryId")
        val excluded = params.getAll("excludeCategoryId").orEmpty().mapNotNull { parseId(it, "excludeCategoryId") }.toSet()
        val noMerchant = params["merchantId"] == "none"
        val merchantId = if (noMerchant) null else parseId(params["merchantId"], "merchantId")
        val accountId = parseId(params["accountId"], "accountId")
        val whose = params["whose"]?.takeIf { it.isNotBlank() }
        val member = whose?.takeIf { it != "joint" }?.let {
            runCatching { Uuid.parse(it) }.getOrNull() ?: throw badRequest("invalid_whose", "whose must be joint or a member's id", "whose")
        }

        val report = dbQuery {
            if (member != null) {
                val isMember = HouseholdMembers.selectAll()
                    .where { (HouseholdMembers.householdId eq householdId) and (HouseholdMembers.userId eq member) }
                    .any()
                if (!isMember) throw badRequest("unknown_member", "No such member in this household", "whose")
            }
            val categories = Categories.selectAll().where { Categories.householdId eq householdId }.associateBy { it[Categories.id] }
            val parentOf = categories.mapValues { it.value[Categories.parentId] }
            val topOf = { id: Uuid? -> id?.let { parentOf[it] ?: it } }
            if (categoryId != null && categoryId !in categories) throw badRequest("unknown_category", "No such category", "categoryId")
            if (excluded.any { it !in categories }) throw badRequest("unknown_category", "No such category", "excludeCategoryId")
            val investing = categories.filterValues { it[Categories.kind] == "investment" }.keys
            // Investing isn't spending, unless that's what was asked about.
            val includeInvesting = categoryId != null && topOf(categoryId) in investing

            val matches = { part: SpendingPart ->
                (includeInvesting || topOf(part.categoryId) !in investing) &&
                    part.categoryId !in excluded && parentOf[part.categoryId] !in excluded &&
                    when {
                        noCategory -> part.categoryId == null
                        categoryId != null -> part.categoryId == categoryId || parentOf[part.categoryId] == categoryId
                        else -> true
                    } &&
                    when {
                        noMerchant -> part.merchantId == null
                        merchantId != null -> part.merchantId == merchantId
                        else -> true
                    } &&
                    (accountId == null || part.accountId == accountId) &&
                    when {
                        whose == null -> true
                        member == null -> part.ownerUserId == null
                        else -> part.ownerUserId == member
                    }
            }

            // Before the ledger starts, spending is unknown, not zero.
            val ledgerStart = Expenses.select(Expenses.date.min()).where { Expenses.householdId eq householdId }.single()[Expenses.date.min()]
            val to = minOf(askedTo, today)
            val from = listOfNotNull(askedFrom, ledgerStart).maxOrNull() ?: to
            val parts = if (from <= to) loadSpendingParts(householdId, from, to).filter(matches) else emptyList()

            // Investing has no category or merchant, so it's only shown when neither is filtered on.
            val investedByMonth = if (categoryId == null && !noCategory && merchantId == null && !noMerchant && from <= to) {
                val liabilityKinds = AssetKind.entries.filter { it.liability }.map { it.name.lowercase() }
                AssetContributions.join(Assets, JoinType.INNER, AssetContributions.assetId, Assets.id)
                    .select(AssetContributions.date, AssetContributions.amountMinor, AssetContributions.accountId, Assets.ownerUserId)
                    .where {
                        (AssetContributions.householdId eq householdId) and
                            AssetContributions.date.between(from, to) and
                            (Assets.kind notInList liabilityKinds)
                    }
                    .filter { row ->
                        (accountId == null || row[AssetContributions.accountId] == accountId) &&
                            when {
                                whose == null -> true
                                member == null -> row[Assets.ownerUserId] == null
                                else -> row[Assets.ownerUserId] == member
                            }
                    }
                    .groupBy({ it[AssetContributions.date].yearMonth }, { it[AssetContributions.amountMinor] })
                    .mapValues { it.value.sum() }
            } else {
                null
            }

            val months = if (from <= to) monthsBetween(from.yearMonth, to.yearMonth) else emptyList()
            val byMonth = parts.groupBy { it.date.yearMonth }
            val total = parts.sumOf { it.amountMinor }

            val previous = run {
                val earlierFrom = from.minus(1, DateTimeUnit.YEAR)
                val earlierTo = to.minus(1, DateTimeUnit.YEAR)
                if (from > to || from.daysUntil(to) > 366 || ledgerStart == null || earlierFrom < ledgerStart) return@run null
                val earlier = loadSpendingParts(householdId, earlierFrom, earlierTo).filter(matches)
                ReportPeriod(earlierFrom, earlierTo, earlier.sumOf { it.amountMinor }, earlier.distinctCount())
            }

            // One level below the filter: top-level categories, a top-level one's own children, or the subcategory itself.
            val groupOf = { part: SpendingPart -> if (categoryId != null) part.categoryId else topOf(part.categoryId) }
            val byCategory = parts.groupBy(groupOf).map { (id, group) ->
                ReportCategory(id?.toString(), group.sumOf { it.amountMinor }, group.distinctCount())
            }.sortedByDescending { it.amountMinor }

            val names = merchantNames(householdId)
            val byMerchant = parts.groupBy { it.merchantId }.map { (id, group) ->
                ReportMerchant(id?.toString(), id?.let(names::get).orEmpty(), group.sumOf { it.amountMinor }, group.distinctCount())
            }.sortedByDescending { it.amountMinor }

            val expenses = parts.groupBy { it.expenseId }.values
                .map { group ->
                    val first = group.first()
                    ReportExpense(
                        expenseId = first.expenseId.toString(),
                        date = first.date,
                        merchant = first.merchantId?.let(names::get).orEmpty(),
                        categoryIds = group.sortedByDescending { it.amountMinor }.map { it.categoryId?.toString() }.distinct(),
                        accountId = first.accountId?.toString(),
                        amountMinor = group.sumOf { it.amountMinor },
                    )
                }
                .sortedWith(compareByDescending<ReportExpense> { it.date }.thenBy { it.expenseId })

            SpendingReport(
                from = from,
                to = to,
                totalMinor = total,
                count = expenses.size,
                averageMonthMinor = if (months.isEmpty()) 0 else (total.toDouble() / months.size).roundToLong(),
                months = months.map { month ->
                    val group = byMonth[month].orEmpty()
                    ReportMonth(month.toString(), group.sumOf { it.amountMinor }, group.distinctCount(), investedByMonth?.get(month) ?: 0)
                },
                investedMinor = investedByMonth?.values?.sum(),
                previous = previous,
                categories = byCategory,
                merchants = byMerchant,
                expenses = expenses.take(SpendingReport.EXPENSE_LIMIT),
            )
        }
        call.respond(report)
    }
}

private fun List<SpendingPart>.distinctCount() = distinctBy { it.expenseId }.size
