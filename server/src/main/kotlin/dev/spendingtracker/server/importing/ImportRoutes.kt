package dev.spendingtracker.server.importing

import dev.spendingtracker.server.account.normalizeIban
import dev.spendingtracker.server.auth.userId
import dev.spendingtracker.server.common.ApiException
import dev.spendingtracker.server.common.badRequest
import dev.spendingtracker.server.common.nonZeroAmount
import dev.spendingtracker.server.common.optionalText
import dev.spendingtracker.server.common.pathId
import dev.spendingtracker.server.common.requiredText
import dev.spendingtracker.server.db.Accounts
import dev.spendingtracker.server.db.BankFeeds
import dev.spendingtracker.server.db.BankRows
import dev.spendingtracker.server.db.Imports
import dev.spendingtracker.server.inbox.FeedShape
import dev.spendingtracker.server.inbox.dbValue
import dev.spendingtracker.server.inbox.feedShape
import dev.spendingtracker.server.inbox.loadHintContext
import dev.spendingtracker.server.inbox.parseGoCardless
import dev.spendingtracker.server.inbox.applyRules
import dev.spendingtracker.server.inbox.storeRows
import dev.spendingtracker.server.db.AssetContributions
import dev.spendingtracker.server.db.Assets
import dev.spendingtracker.server.db.Categories
import dev.spendingtracker.server.db.Expenses
import dev.spendingtracker.server.db.HouseholdMembers
import dev.spendingtracker.server.db.Households
import dev.spendingtracker.server.db.Income
import dev.spendingtracker.server.income.dbValue
import dev.spendingtracker.server.db.Merchants
import dev.spendingtracker.server.db.Trips
import dev.spendingtracker.server.db.Users
import dev.spendingtracker.server.db.dbQuery
import dev.spendingtracker.server.household.household
import dev.spendingtracker.server.household.requireOwner
import dev.spendingtracker.server.merchant.resolveMerchant
import dev.spendingtracker.shared.api.AccountKind
import dev.spendingtracker.shared.api.AccountStatus
import dev.spendingtracker.shared.api.ApiError
import dev.spendingtracker.shared.api.CategoryKind
import dev.spendingtracker.shared.api.HouseholdImport
import dev.spendingtracker.shared.api.ImportCounts
import dev.spendingtracker.shared.api.ImportResult
import dev.spendingtracker.shared.api.ImportSummary
import dev.spendingtracker.shared.api.ImportUndoResult
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.yearMonth
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Must be inside a HouseholdAccess scope. */
fun Route.importRoutes() {
    post("/import", {
        operationId = "importHouseholdData"
        description = "Imports people, accounts, categories, trips, expenses, income, assets and bank rows in one transaction: " +
            "all of it or, on any error, nothing. Names are matched to what the household has; expenses and income " +
            "already imported (same externalRef) are skipped. With dryRun=true it reports what it would do and saves nothing. Owners only."
        request {
            queryParameter<Boolean>("dryRun")
            body<HouseholdImport>()
        }
        response {
            code(HttpStatusCode.OK) { body<ImportResult>() }
            code(HttpStatusCode.BadRequest) { body<ApiError>() }
            code(HttpStatusCode.Forbidden) { body<ApiError>() }
        }
    }) {
        call.requireOwner()
        val dryRun = call.request.queryParameters["dryRun"] == "true"
        val input = call.receive<HouseholdImport>()
        val household = call.household
        if (input.currency.trim().uppercase() != household.currency) {
            throw badRequest("currency_mismatch", "The file is in ${input.currency}, the household in ${household.currency}", "currency")
        }
        val result = dbQuery {
            val counts = Importer(household.id, household.currency, call.userId).run(input)
            val debtsFrom = input.debtsFrom?.takeIf {
                Households.select(Households.debtsFrom).where { Households.id eq household.id }.single()[Households.debtsFrom] == null
            }?.also { date ->
                Households.update({ Households.id eq household.id }) { it[Households.debtsFrom] = date }
            }
            if (dryRun) rollback()
            ImportResult(dryRun, created = counts.first, existing = counts.second, debtsFrom = debtsFrom)
        }
        call.respond(result)
    }

    get("/imports", {
        operationId = "listImports"
        description = "Lists the household's import batches, newest first. Owners only."
        response {
            code(HttpStatusCode.OK) { body<List<ImportSummary>>() }
            code(HttpStatusCode.Forbidden) { body<ApiError>() }
        }
    }) {
        call.requireOwner()
        val householdId = call.household.id
        val list = dbQuery { listImports(householdId) }
        call.respond(list)
    }

    post("/imports/{id}/undo", {
        operationId = "undoImport"
        description = "Undoes an import as a whole in one transaction: removes leaf records it created, then unused containers, " +
            "keeping anything still referenced or any member with a login. With dryRun=true it reports what it would do and saves nothing. Owners only."
        request {
            pathParameter<String>("id")
            queryParameter<Boolean>("dryRun")
        }
        response {
            code(HttpStatusCode.OK) { body<ImportUndoResult>() }
            code(HttpStatusCode.BadRequest) { body<ApiError>() }
            code(HttpStatusCode.Forbidden) { body<ApiError>() }
            code(HttpStatusCode.NotFound) { body<ApiError>() }
            code(HttpStatusCode.Conflict) { body<ApiError>() }
        }
    }) {
        call.requireOwner()
        val importId = call.pathId("id", "Import")
        val dryRun = call.request.queryParameters["dryRun"] == "true"
        val householdId = call.household.id
        val userId = call.userId
        val result = dbQuery {
            val out = undoImport(householdId, importId, userId, dryRun)
            if (dryRun) rollback()
            out
        }
        call.respond(result)
    }
}

private fun key(name: String) = name.trim().lowercase()

/** Runs one import inside a transaction. Every error names the entry it's about, e.g. `expenses[3].account`. */
private class Importer(private val householdId: Uuid, private val householdCurrency: String, private val userId: Uuid) {
    private val importId = Uuid.random()
    private val created = Tally()
    private val matched = Tally()

    private val members = mutableMapOf<String, Uuid>()
    private val memberEmails = mutableMapOf<String, Uuid>()
    private val accounts = mutableMapOf<String, Uuid>()
    private val accountsByIban = mutableMapOf<String, Uuid>()
    // Keyed by "parent/name" ("/name" for a top-level category).
    private val categories = mutableMapOf<String, Uuid>()
    private val trips = mutableMapOf<String, Uuid>()
    private val assets = mutableMapOf<String, Uuid>()

    fun run(input: HouseholdImport): Pair<ImportCounts, ImportCounts> {
        loadExisting()
        input.people.forEachIndexed { i, person -> addPerson("people[$i]", person.name, person.email) }
        input.accounts.forEachIndexed { i, account ->
            val path = "accounts[$i]"
            val name = requiredText(account.name, "$path.name", max = 100)
            val iban = try {
                normalizeIban(account.iban)
            } catch (e: ApiException) {
                throw badRequest(e.code, e.message, "$path.iban")
            }
            val existing = accounts[key(name)] ?: iban?.let { accountsByIban[it] }
            if (existing != null) {
                matched.accounts++
                accounts[key(name)] = existing
                return@forEachIndexed
            }
            val id = Uuid.random()
            Accounts.insert {
                it[Accounts.id] = id
                it[Accounts.householdId] = this@Importer.householdId
                it[Accounts.name] = name
                it[ownerUserId] = person(account.owner, "$path.owner")
                it[status] = if (account.status == AccountStatus.ACTIVE) "active" else "closed"
                it[kind] = account.kind.dbValue
                it[Accounts.iban] = iban
                it[Accounts.importId] = this@Importer.importId
                it[version] = 1
            }
            accounts[key(name)] = id
            if (iban != null) accountsByIban[iban] = id
            created.accounts++
        }
        // Top-level categories first, so subcategories listed before their parent still find it.
        val categoryInputs = input.categories.withIndex().sortedBy { it.value.parent != null }
        for ((i, category) in categoryInputs) {
            val path = "categories[$i]"
            val name = requiredText(category.name, "$path.name", max = 100)
            val parentId = category.parent?.let { category(it, null, "$path.parent") }
            val categoryKey = "${category.parent?.let(::key).orEmpty()}/${key(name)}"
            if (categoryKey in categories) {
                matched.categories++
                continue
            }
            val id = Uuid.random()
            val kind = if (parentId == null) category.kind.dbValue else Categories.select(Categories.kind)
                .where { Categories.id eq parentId }.single()[Categories.kind]
            Categories.insert {
                it[Categories.id] = id
                it[Categories.householdId] = this@Importer.householdId
                it[Categories.parentId] = parentId
                it[Categories.name] = name
                it[Categories.kind] = kind
                it[archived] = false
                it[sortOrder] = 0
                it[Categories.importId] = this@Importer.importId
                it[version] = 1
            }
            categories[categoryKey] = id
            created.categories++
        }
        input.trips.forEachIndexed { i, trip ->
            val path = "trips[$i]"
            val name = requiredText(trip.name, "$path.name", max = 100)
            if (key(name) in trips) {
                matched.trips++
                return@forEachIndexed
            }
            if (trip.startDate != null && trip.endDate != null && trip.endDate!! < trip.startDate!!) {
                throw badRequest("invalid_dates", "A trip can't end before it starts", "$path.endDate")
            }
            val id = Uuid.random()
            Trips.insert {
                it[Trips.id] = id
                it[Trips.householdId] = this@Importer.householdId
                it[Trips.name] = name
                it[startDate] = trip.startDate
                it[endDate] = trip.endDate
                it[notes] = optionalText(trip.notes, "$path.notes")
                it[Trips.importId] = this@Importer.importId
                it[createdBy] = userId
            }
            trips[key(name)] = id
            created.trips++
        }
        importExpenses(input)
        importIncome(input)
        importAssets(input)
        importBank(input)
        val createdCounts = created.counts()
        Imports.insert {
            it[id] = importId
            it[Imports.householdId] = this@Importer.householdId
            it[createdBy] = userId
            it[counts] = Json.encodeToString(ImportCounts.serializer(), createdCounts)
        }
        return createdCounts to matched.counts()
    }

    private fun loadExisting() {
        HouseholdMembers.join(Users, JoinType.INNER, HouseholdMembers.userId, Users.id)
            .selectAll().where { HouseholdMembers.householdId eq householdId }
            .forEach { row ->
                members.putIfAbsent(key(row[Users.displayName]), row[Users.id])
                row[Users.email]?.let { memberEmails[key(it)] = row[Users.id] }
            }
        Accounts.selectAll().where { Accounts.householdId eq householdId }.forEach { row ->
            accounts[key(row[Accounts.name])] = row[Accounts.id]
            row[Accounts.iban]?.let { accountsByIban[it] = row[Accounts.id] }
        }
        val categoryRows = Categories.selectAll().where { Categories.householdId eq householdId }.toList()
        val names = categoryRows.associate { it[Categories.id] to it[Categories.name] }
        categoryRows.forEach { row ->
            val parent = row[Categories.parentId]?.let { names[it] }?.let(::key).orEmpty()
            categories.putIfAbsent("$parent/${key(row[Categories.name])}", row[Categories.id])
        }
        Trips.selectAll().where { Trips.householdId eq householdId }.forEach { trips[key(it[Trips.name])] = it[Trips.id] }
        Assets.selectAll().where { Assets.householdId eq householdId }.forEach { assets[key(it[Assets.name])] = it[Assets.id] }
    }

    private fun addPerson(path: String, rawName: String, email: String?) {
        val name = requiredText(rawName, "$path.name", max = 100)
        val existing = email?.takeIf { it.isNotBlank() }?.let { memberEmails[key(it)] } ?: members[key(name)]
        if (existing != null) {
            matched.members++
            members[key(name)] = existing
            return
        }
        val id = Uuid.random()
        Users.insert {
            it[Users.id] = id
            it[displayName] = name
        }
        HouseholdMembers.insert {
            it[HouseholdMembers.householdId] = this@Importer.householdId
            it[HouseholdMembers.userId] = id
            it[role] = "member"
            it[HouseholdMembers.importId] = this@Importer.importId
        }
        members[key(name)] = id
        created.members++
    }

    private fun person(name: String?, path: String): Uuid? {
        if (name.isNullOrBlank()) return null
        return members[key(name)] ?: throw badRequest("unknown_person", "No member or listed person called \"$name\"", path)
    }

    private fun account(name: String?, path: String): Uuid? {
        if (name.isNullOrBlank()) return null
        return accounts[key(name)] ?: throw badRequest("unknown_account", "No account called \"$name\"", path)
    }

    private fun category(name: String, parent: String?, path: String): Uuid {
        val categoryKey = "${parent?.let(::key).orEmpty()}/${key(name)}"
        return categories[categoryKey] ?: throw badRequest(
            "unknown_category",
            if (parent == null) "No category called \"$name\"" else "No subcategory \"$name\" under \"$parent\"",
            path,
        )
    }

    private fun trip(name: String?, path: String): Uuid? {
        if (name.isNullOrBlank()) return null
        return trips[key(name)] ?: throw badRequest("unknown_trip", "No trip called \"$name\"", path)
    }

    private fun existingRefs(column: org.jetbrains.exposed.v1.core.Column<String?>, household: org.jetbrains.exposed.v1.core.Column<Uuid>) =
        column.table.select(column).where { household eq householdId }.mapNotNull { it[column] }.toSet()

    private fun importExpenses(input: HouseholdImport) {
        val imported = existingRefs(Expenses.externalRef, Expenses.householdId)
        val seen = mutableSetOf<String>()
        data class Row(val path: String, val categoryId: Uuid?, val expense: dev.spendingtracker.shared.api.ImportExpense)

        val rows = input.expenses.mapIndexedNotNull { i, expense ->
            val path = "expenses[$i]"
            val ref = requiredText(expense.externalRef, "$path.externalRef", max = 200)
            if (!seen.add(ref)) throw badRequest("duplicate_ref", "externalRef \"$ref\" appears twice", "$path.externalRef")
            if (ref in imported) {
                matched.expenses++
                return@mapIndexedNotNull null
            }
            val categoryId = expense.category?.takeIf { it.isNotBlank() }?.let { parent ->
                val top = category(parent, null, "$path.category")
                expense.subcategory?.takeIf { it.isNotBlank() }?.let { category(it, parent, "$path.subcategory") } ?: top
            }
            Row(path, categoryId, expense)
        }

        // A new merchant is filed under the category its expenses use most.
        val usualCategory = rows.filter { it.expense.merchant.isNotBlank() }
            .groupBy { key(it.expense.merchant) }
            .mapValues { (_, list) -> list.mapNotNull { it.categoryId }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key }
        val knownMerchants = Merchants.select(Merchants.name).where { Merchants.householdId eq householdId }
            .map { key(it[Merchants.name]) }.toMutableSet()

        for ((path, categoryId, expense) in rows) {
            val merchant = optionalText(expense.merchant, "$path.merchant", max = 200)
            if (merchant.isNotEmpty() && knownMerchants.add(key(merchant))) created.merchants++
            val merchantId = resolveMerchant(householdId, merchant, usualCategory[key(merchant)], userId, importId)
            val originalCurrency = expense.originalCurrency?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
            if ((expense.originalAmountMinor == null) != (originalCurrency == null)) {
                throw badRequest("original_incomplete", "Give both the original amount and its currency, or neither", "$path.originalCurrency")
            }
            if (originalCurrency != null && !Regex("^[A-Z]{3}$").matches(originalCurrency)) {
                throw badRequest("invalid_currency", "Use a 3-letter currency code", "$path.originalCurrency")
            }
            Expenses.insert {
                it[id] = Uuid.random()
                it[householdId] = this@Importer.householdId
                it[date] = expense.date
                it[amountMinor] = nonZeroAmount(expense.amountMinor, "$path.amountMinor")
                it[Expenses.merchantId] = merchantId
                it[Expenses.categoryId] = categoryId
                it[accountId] = account(expense.account, "$path.account")
                it[paidByUserId] = person(expense.person, "$path.person")
                it[notes] = optionalText(expense.notes, "$path.notes")
                it[tripId] = trip(expense.trip, "$path.trip")
                it[originalAmountMinor] = expense.originalAmountMinor?.let { amount -> nonZeroAmount(amount, "$path.originalAmountMinor") }
                it[Expenses.originalCurrency] = originalCurrency
                it[createsDebt] = expense.createsDebt
                it[externalRef] = expense.externalRef.trim()
                it[Expenses.importId] = this@Importer.importId
                it[version] = 1
                it[createdBy] = userId
            }
            created.expenses++
        }
    }

    private fun importIncome(input: HouseholdImport) {
        val imported = existingRefs(Income.externalRef, Income.householdId)
        val seen = mutableSetOf<String>()
        input.income.forEachIndexed { i, line ->
            val path = "income[$i]"
            val ref = requiredText(line.externalRef, "$path.externalRef", max = 200)
            if (!seen.add(ref)) throw badRequest("duplicate_ref", "externalRef \"$ref\" appears twice", "$path.externalRef")
            if (ref in imported) {
                matched.income++
                return@forEachIndexed
            }
            val earned = line.earnedMonth?.trim()?.takeIf { it.isNotEmpty() }?.let {
                runCatching { YearMonth.parse(it) }.getOrElse { throw badRequest("invalid_month", "earnedMonth must be YYYY-MM", "$path.earnedMonth") }
            } ?: line.date.yearMonth
            Income.insert {
                it[id] = Uuid.random()
                it[householdId] = this@Importer.householdId
                it[date] = line.date
                it[earnedMonth] = earned.firstDay
                it[amountMinor] = nonZeroAmount(line.amountMinor, "$path.amountMinor")
                it[sourceName] = optionalText(line.source, "$path.source", max = 200)
                it[accountId] = account(line.account, "$path.account")
                it[recipientUserId] = person(line.recipient, "$path.recipient")
                it[notes] = optionalText(line.notes, "$path.notes")
                it[kind] = line.kind.dbValue
                it[externalRef] = ref
                it[Income.importId] = this@Importer.importId
                it[version] = 1
                it[createdBy] = userId
            }
            created.income++
        }
    }

    private fun importAssets(input: HouseholdImport) {
        input.assets.forEachIndexed { i, asset ->
            val path = "assets[$i]"
            val name = requiredText(asset.name, "$path.name", max = 100)
            val assetId = assets[key(name)]?.also { matched.assets++ } ?: Uuid.random().also { id ->
                Assets.insert {
                    it[Assets.id] = id
                    it[householdId] = this@Importer.householdId
                    it[Assets.name] = name
                    it[kind] = asset.kind.name.lowercase()
                    it[ownerUserId] = person(asset.owner, "$path.owner")
                    it[notes] = optionalText(asset.notes, "$path.notes")
                    it[Assets.importId] = this@Importer.importId
                    it[version] = 1
                    it[createdBy] = userId
                }
                assets[key(name)] = id
                created.assets++
            }
            val recorded = AssetContributions.select(AssetContributions.date, AssetContributions.amountMinor)
                .where { AssetContributions.assetId eq assetId }
                .map { it[AssetContributions.date] to it[AssetContributions.amountMinor] }
                .toMutableSet()
            asset.contributions.forEachIndexed { j, contribution ->
                val entry = "$path.contributions[$j]"
                val amount = nonZeroAmount(contribution.amountMinor, "$entry.amountMinor")
                if (!recorded.add(contribution.date to amount)) {
                    matched.contributions++
                    return@forEachIndexed
                }
                AssetContributions.insert {
                    it[id] = Uuid.random()
                    it[householdId] = this@Importer.householdId
                    it[AssetContributions.assetId] = assetId
                    it[date] = contribution.date
                    it[amountMinor] = amount
                    it[accountId] = account(contribution.account, "$entry.account")
                    it[notes] = optionalText(contribution.notes, "$entry.notes")
                    it[AssetContributions.importId] = this@Importer.importId
                    it[version] = 1
                    it[createdBy] = userId
                }
                created.contributions++
            }
        }
    }

    private fun importBank(input: HouseholdImport) {
        if (input.bankFeeds.isEmpty() && input.bankRows.isEmpty()) return
        data class Feed(val id: Uuid, val shape: FeedShape, val currency: String, val importFrom: LocalDate?)

        val feeds = BankFeeds.selectAll().where { BankFeeds.householdId eq householdId }.associate {
            key(it[BankFeeds.name]) to Feed(it[BankFeeds.id], feedShape(it[BankFeeds.institution], it[BankFeeds.accountId]), it[BankFeeds.currency], it[BankFeeds.importFrom])
        }.toMutableMap()
        input.bankFeeds.forEachIndexed { i, feed ->
            val path = "bankFeeds[$i]"
            val name = requiredText(feed.name, "$path.name", max = 100)
            if (key(name) in feeds) {
                matched.bankFeeds++
                return@forEachIndexed
            }
            val currency = feed.currency.trim().uppercase()
            if (!Regex("^[A-Z]{3}$").matches(currency)) throw badRequest("invalid_currency", "Use a 3-letter currency code", "$path.currency")
            val accountId = account(feed.account, "$path.account") ?: throw badRequest("required", "account is required", "$path.account")
            val iban = try {
                normalizeIban(feed.iban)
            } catch (e: ApiException) {
                throw badRequest(e.code, e.message, "$path.iban")
            }
            val id = Uuid.random()
            BankFeeds.insert {
                it[BankFeeds.id] = id
                it[BankFeeds.householdId] = this@Importer.householdId
                it[BankFeeds.accountId] = accountId
                it[BankFeeds.name] = name
                it[institution] = feed.institution.dbValue
                it[BankFeeds.iban] = iban
                it[BankFeeds.currency] = currency
                it[importFrom] = feed.importFrom
                it[BankFeeds.importId] = this@Importer.importId
                it[createdBy] = userId
            }
            feeds[key(name)] = Feed(id, feedShape(feed.institution.dbValue, accountId), currency, feed.importFrom)
            created.bankFeeds++
        }

        val expenseIds = refs(Expenses.externalRef, Expenses.id, Expenses.householdId)
        val incomeIds = refs(Income.externalRef, Income.id, Income.householdId)
        val context = loadHintContext(householdId)
        data class Decision(val expenseId: Uuid?, val incomeId: Uuid?, val skipped: String?)

        val addedRows = mutableSetOf<Uuid>()
        val byFeed = input.bankRows.withIndex().groupBy { (i, row) -> key(row.feed).also { if (it !in feeds) throw badRequest("unknown_feed", "No bank feed called \"${row.feed}\"", "bankRows[$i].feed") } }
        for ((feedKey, entries) in byFeed) {
            val feed = feeds.getValue(feedKey)
            val parsed = entries.map { (i, row) ->
                val path = "bankRows[$i]"
                val decided = listOfNotNull(row.expense, row.income, row.skipped).count { it.isNotBlank() }
                if (decided > 1) throw badRequest("too_many_outcomes", "A row can be an expense, income or skipped, not several", path)
                val parsedRow = parseGoCardless(row.raw, feed.shape) ?: throw badRequest("unreadable_row", "No transaction id or date in raw", "$path.raw")
                if (parsedRow.currency != feed.currency) {
                    throw badRequest("currency_mismatch", "The row is in ${parsedRow.currency}, the feed in ${feed.currency}", "$path.raw")
                }
                val decision = Decision(
                    expenseId = row.expense?.takeIf { it.isNotBlank() }?.let { expenseIds[it.trim()] ?: throw badRequest("unknown_expense", "No expense with externalRef \"$it\"", "$path.expense") },
                    incomeId = row.income?.takeIf { it.isNotBlank() }?.let { incomeIds[it.trim()] ?: throw badRequest("unknown_income", "No income with externalRef \"$it\"", "$path.income") },
                    skipped = row.skipped?.takeIf { it.isNotBlank() }?.let { optionalText(it, "$path.skipped", max = 200) },
                )
                Triple(parsedRow, decision, row.hint.trim())
            }
            val hints = parsed.filter { it.third.isNotEmpty() }.associate { it.first.externalId to it.third }
            val counts = storeRows(householdId, feed.id, feed.shape, feed.importFrom, parsed.map { it.first }, context, hints, importId)
            created.bankRows += counts.added
            matched.bankRows += counts.existing
            // Decisions apply to rows added just now: an earlier import's rows keep what was decided since.
            val fresh = BankRows.select(BankRows.id, BankRows.externalId)
                .where { BankRows.feedId eq feed.id }
                .filter { it[BankRows.externalId] in counts.addedIds }
                .associate { it[BankRows.externalId] to it[BankRows.id] }
            addedRows += fresh.values
            val now = Clock.System.now()
            for ((row, decision, _) in parsed) {
                if (decision.expenseId == null && decision.incomeId == null && decision.skipped == null) continue
                val rowId = fresh[row.externalId] ?: continue
                BankRows.update({ BankRows.id eq rowId }) {
                    it[BankRows.expenseId] = decision.expenseId
                    it[BankRows.incomeId] = decision.incomeId
                    it[skipReason] = decision.skipped
                    it[decidedBy] = userId
                    it[decidedAt] = now
                }
            }
        }
        // Rows nothing was decided about go through the rules like a statement's, at the fixings already stored.
        if (addedRows.isNotEmpty()) applyRules(householdId, householdCurrency, userId, addedRows)
    }

    private fun refs(
        ref: org.jetbrains.exposed.v1.core.Column<String?>,
        id: org.jetbrains.exposed.v1.core.Column<Uuid>,
        household: org.jetbrains.exposed.v1.core.Column<Uuid>,
    ): Map<String, Uuid> =
        ref.table.select(ref, id).where { (household eq householdId) and ref.isNotNull() }.associate { it[ref]!! to it[id] }
}

private val AccountKind.dbValue get() = name.lowercase()



private val CategoryKind.dbValue get() = if (this == CategoryKind.INVESTMENT) "investment" else "consumption"
