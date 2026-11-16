package dev.spendingtracker.server.debt

import dev.spendingtracker.server.auth.userId
import dev.spendingtracker.server.category.dbValue
import dev.spendingtracker.server.common.badRequest
import dev.spendingtracker.server.common.checkVersioned
import dev.spendingtracker.server.common.conflict
import dev.spendingtracker.server.common.dateParam
import dev.spendingtracker.server.common.optionalText
import dev.spendingtracker.server.common.parseId
import dev.spendingtracker.server.common.pathId
import dev.spendingtracker.server.common.requireVersion
import dev.spendingtracker.server.common.versionParam
import dev.spendingtracker.server.db.Accounts
import dev.spendingtracker.server.db.Categories
import dev.spendingtracker.server.db.Expenses
import dev.spendingtracker.server.db.Merchants
import dev.spendingtracker.server.db.Settlements
import dev.spendingtracker.server.db.SplitRules
import dev.spendingtracker.server.db.SplitShares
import dev.spendingtracker.server.db.Transfers
import dev.spendingtracker.server.db.dbQuery
import dev.spendingtracker.server.db.isUniqueViolation
import dev.spendingtracker.server.expense.loadExpenseLines
import dev.spendingtracker.server.household.household
import dev.spendingtracker.server.household.isMember
import dev.spendingtracker.shared.api.ApiError
import dev.spendingtracker.shared.api.BalanceEntry
import dev.spendingtracker.shared.api.BalanceEntryKind
import dev.spendingtracker.shared.api.Balances
import dev.spendingtracker.shared.api.CategoryKind
import dev.spendingtracker.shared.api.Debt
import dev.spendingtracker.shared.api.ExpenseLine
import dev.spendingtracker.shared.api.JointContribution
import dev.spendingtracker.shared.api.JointFunding
import dev.spendingtracker.shared.api.Settlement
import dev.spendingtracker.shared.api.SettlementInput
import dev.spendingtracker.shared.api.SplitRule
import dev.spendingtracker.shared.api.SplitRuleInput
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
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Must be inside a HouseholdAccess scope. */
fun Route.debtRoutes() {
    splitRuleRoutes()
    settlementRoutes()

    get("/balances", {
        operationId = "getBalances"
        description = "Who owes whom after every expense and settlement so far, and what moved the balance between from and to " +
            "(both inclusive and optional)."
        request {
            queryParameter<String>("from") { description = "Inclusive start date, YYYY-MM-DD" }
            queryParameter<String>("to") { description = "Inclusive end date, YYYY-MM-DD" }
        }
        response {
            code(HttpStatusCode.OK) { body<Balances>() }
            code(HttpStatusCode.BadRequest) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val from = call.dateParam("from")
        val to = call.dateParam("to")
        val balances = dbQuery { computeBalances(householdId, from, to) }
        call.respond(balances)
    }
}

private data class TopUpRow(
    val id: Uuid,
    val date: LocalDate,
    val userId: Uuid,
    val jointAccountId: Uuid,
    val signedAmount: Long,
)

/**
 * Computes net [Balances] for [householdId] across all history from `debtsFrom`, filtering returned entries and
 * [Balances.jointContributions] to `[from]..[to]`. Call inside a transaction.
 */
fun computeBalances(householdId: Uuid, from: LocalDate? = null, to: LocalDate? = null): Balances {
    val book = loadSplitBook(householdId)
    val debtsFrom = book.debtsFrom
    var expenseFilter: Op<Boolean> = (Expenses.householdId eq householdId) and Expenses.accountId.isNotNull() and (Expenses.createsDebt eq true)
    if (debtsFrom != null) expenseFilter = expenseFilter and (Expenses.date greaterEq debtsFrom)
    val expenseRows = Expenses.join(Merchants, JoinType.LEFT, Expenses.merchantId, Merchants.id)
        .selectAll()
        .where(expenseFilter)
        .toList()
    val lines = loadExpenseLines(householdId, expenseRows.map { it[Expenses.id] })
    val expenseEntries = expenseRows.mapNotNull { row ->
        val debts = book.debtsFor(row.toExpenseFacts(lines[row[Expenses.id]].orEmpty()))
        if (debts.isEmpty()) return@mapNotNull null
        BalanceEntry(
            kind = BalanceEntryKind.EXPENSE,
            id = row[Expenses.id].toString(),
            date = row[Expenses.date],
            label = row.getOrNull(Merchants.name).orEmpty(),
            amountMinor = row[Expenses.amountMinor],
            debts = debts,
        )
    }
    var settlementFilter: Op<Boolean> = Settlements.householdId eq householdId
    if (debtsFrom != null) settlementFilter = settlementFilter and (Settlements.date greaterEq debtsFrom)
    val settlementEntries = Settlements.selectAll().where(settlementFilter).map { row ->
        val settlement = row.toSettlement()
        BalanceEntry(
            kind = BalanceEntryKind.SETTLEMENT,
            id = settlement.id,
            date = settlement.date,
            label = settlement.notes,
            amountMinor = settlement.amountMinor,
            // Paying back is a debt the other way.
            debts = listOf(Debt(settlement.toUserId, settlement.fromUserId, settlement.amountMinor)),
        )
    }
    val (topUpEntries, jointLeftover) = if (book.jointFunding == JointFunding.CONTRIBUTIONS) {
        computeTopUpEntries(householdId, book, expenseRows, lines, debtsFrom)
    } else {
        emptyList<BalanceEntry>() to emptyList()
    }
    val all = expenseEntries + settlementEntries + topUpEntries
    return Balances(
        balances = netBalances(all.asSequence().flatMap { it.debts }),
        entries = all
            .filter { (from == null || it.date >= from) && (to == null || it.date <= to) }
            .sortedWith(compareByDescending<BalanceEntry> { it.date }.thenBy { it.kind }.thenBy { it.id }),
        jointContributions = jointContributions(householdId, from, to),
        jointLeftover = jointLeftover,
    )
}

private fun computeTopUpEntries(
    householdId: Uuid,
    book: SplitBook,
    expenseRows: List<ResultRow>,
    lines: Map<Uuid, List<ExpenseLine>>,
    debtsFrom: LocalDate?,
): Pair<List<BalanceEntry>, List<JointContribution>> {
    val accounts = Accounts.select(Accounts.id, Accounts.name, Accounts.ownerUserId)
        .where { Accounts.householdId eq householdId }
        .toList()
    val owners = accounts.associate { it[Accounts.id] to it[Accounts.ownerUserId] }
    val accountNames = accounts.associate { it[Accounts.id] to it[Accounts.name] }
    var transferFilter: Op<Boolean> = Transfers.householdId eq householdId
    if (debtsFrom != null) transferFilter = transferFilter and (Transfers.date greaterEq debtsFrom)
    val topUpRows = Transfers.selectAll().where(transferFilter).mapNotNull { t ->
        val fromAccount = t[Transfers.fromAccountId]
        val toAccount = t[Transfers.toAccountId]
        val fromOwner = owners[fromAccount]
        val toOwner = owners[toAccount]
        val amount = t[Transfers.amountMinor]
        when {
            fromOwner != null && toOwner == null ->
                TopUpRow(t[Transfers.id], t[Transfers.date], fromOwner, toAccount, amount)
            fromOwner == null && toOwner != null ->
                TopUpRow(t[Transfers.id], t[Transfers.date], toOwner, fromAccount, -amount)
            else -> null
        }
    }
    if (topUpRows.isEmpty()) return emptyList<BalanceEntry>() to emptyList()

    val jointExpenses = expenseRows.filter { book.isJointAccount(it[Expenses.accountId]) }
    val jointSpent = jointExpenses.sumOf { it[Expenses.amountMinor] }
    val totalTopUps = topUpRows.sumOf { it.signedAmount }
    val cappedAmounts = mutableMapOf<Uuid, Long>()
    val leftover = mutableListOf<JointContribution>()

    if (totalTopUps > jointSpent) {
        // Only members who put in more than their share of joint-account spending have unspent money left in joint accounts.
        val charges = HashMap<Uuid, Long>()
        for (row in jointExpenses) {
            for (facts in book.parts(row.toExpenseFacts(lines[row[Expenses.id]].orEmpty()))) {
                val owner = facts.ownerUserId
                if (owner != null) {
                    charges.merge(owner, facts.amountMinor, Long::plus)
                } else {
                    for ((member, part) in book.allocateJointShare(facts)) {
                        charges.merge(member, part, Long::plus)
                    }
                }
            }
        }
        val netByMember = HashMap<Uuid, Long>()
        for (row in topUpRows) netByMember.merge(row.userId, row.signedAmount, Long::plus)
        val excessByMember = netByMember.mapValues { (m, net) -> maxOf(0L, net - (charges[m] ?: 0L)) }
            .filterValues { it > 0L }
        val leftoverTotal = totalTopUps - jointSpent
        val leftoverByMember = if (excessByMember.isEmpty()) {
            emptyMap()
        } else {
            allocateByWeights(leftoverTotal, excessByMember.mapKeys { it.key.toString() })
                .mapKeys { Uuid.parse(it.key) }
        }
        val leftoverNet = HashMap<Pair<Uuid, Uuid>, Long>()
        for ((member, rows) in topUpRows.groupBy { it.userId }) {
            val memberLeftover = leftoverByMember[member] ?: 0L
            val positiveRows = rows.filter { it.signedAmount > 0 }
            val positiveSum = positiveRows.sumOf { it.signedAmount }
            val targetPositive = maxOf(0L, positiveSum - memberLeftover)
            val cappedPositives = if (memberLeftover == 0L || positiveRows.isEmpty()) {
                positiveRows.associate { it.id.toString() to it.signedAmount }
            } else if (targetPositive == 0L) {
                emptyMap()
            } else {
                allocateByWeights(targetPositive, positiveRows.associate { it.id.toString() to it.signedAmount })
            }
            for (row in rows) {
                val capped = if (row.signedAmount < 0) row.signedAmount else (cappedPositives[row.id.toString()] ?: 0L)
                cappedAmounts[row.id] = capped
                val diff = row.signedAmount - capped
                if (diff != 0L) leftoverNet.merge(row.userId to row.jointAccountId, diff, Long::plus)
            }
        }
        leftover += leftoverNet.filterValues { it != 0L }
            .map { (key, amount) -> JointContribution(key.first.toString(), key.second.toString(), amount) }
            .sortedWith(compareBy({ it.userId }, { it.accountId }))
    } else {
        for (row in topUpRows) cappedAmounts[row.id] = row.signedAmount
    }
    val entries = topUpRows.mapNotNull { row ->
        val debts = book.debtsForTopUp(row.userId, cappedAmounts.getValue(row.id), row.date)
        if (debts.isEmpty()) return@mapNotNull null
        BalanceEntry(
            kind = BalanceEntryKind.TOP_UP,
            id = row.id.toString(),
            date = row.date,
            label = accountNames[row.jointAccountId].orEmpty(),
            amountMinor = row.signedAmount,
            debts = debts,
        )
    }
    return entries to leftover
}

/**
 * What each member put into joint accounts by transfers between [from] and [to]: from an account owned by a member
 * into a joint one (no owner) counts for that member and that account, from a joint one into theirs against it.
 * Transfers between two joint or two personal accounts don't count. Call inside a transaction.
 */
private fun jointContributions(householdId: Uuid, from: LocalDate?, to: LocalDate?): List<JointContribution> {
    val owners = Accounts.select(Accounts.id, Accounts.ownerUserId).where { Accounts.householdId eq householdId }
        .associate { it[Accounts.id] to it[Accounts.ownerUserId] }
    var filter: Op<Boolean> = Transfers.householdId eq householdId
    if (from != null) filter = filter and (Transfers.date greaterEq from)
    if (to != null) filter = filter and (Transfers.date lessEq to)
    val net = HashMap<Pair<Uuid, Uuid>, Long>()
    for (t in Transfers.selectAll().where(filter)) {
        val fromAccount = t[Transfers.fromAccountId]
        val toAccount = t[Transfers.toAccountId]
        val fromOwner = owners[fromAccount]
        val toOwner = owners[toAccount]
        val amount = t[Transfers.amountMinor]
        if (fromOwner != null && toOwner == null) net.merge(fromOwner to toAccount, amount, Long::plus)
        if (fromOwner == null && toOwner != null) net.merge(toOwner to fromAccount, -amount, Long::plus)
    }
    return net.filterValues { it != 0L }
        .map { (key, amount) -> JointContribution(key.first.toString(), key.second.toString(), amount) }
        .sortedWith(compareBy({ it.userId }, { it.accountId }))
}

/** A row of the expenses table. */
fun ResultRow.toExpenseFacts(lines: List<ExpenseLine> = emptyList()) = ExpenseFacts(
    id = this[Expenses.id],
    date = this[Expenses.date],
    amountMinor = this[Expenses.amountMinor],
    categoryId = this[Expenses.categoryId],
    accountId = this[Expenses.accountId],
    ownerUserId = this[Expenses.paidByUserId],
    createsDebt = this[Expenses.createsDebt],
    lines = lines.map { ExpenseLineFacts(it.categoryId?.let(Uuid::parse), it.amountMinor, it.paidByUserId?.let(Uuid::parse)) },
)

// ---- Split rules ----

private fun Route.splitRuleRoutes() = route("/split-rules") {
    get({
        operationId = "listSplitRules"
        description = "The household default and category split rules, by category (the default first), latest first. " +
            "Expenses' own splits are on the expenses."
        response { code(HttpStatusCode.OK) { body<List<SplitRule>>() } }
    }) {
        val rules = dbQuery { loadRules(call.household.id) }
        call.respond(rules)
    }

    post({
        operationId = "createSplitRule"
        request { body<SplitRuleInput>() }
        response {
            code(HttpStatusCode.Created) { body<SplitRule>() }
            code(HttpStatusCode.BadRequest) { body<ApiError>() }
            code(HttpStatusCode.Conflict) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val input = call.receive<SplitRuleInput>().validated()
        val id = Uuid.random()
        val rule = uniqueScope {
            dbQuery {
                input.checkReferences(householdId)
                SplitRules.insert {
                    it[SplitRules.id] = id
                    it[SplitRules.householdId] = householdId
                    it[categoryId] = input.categoryId
                    it[validFrom] = input.validFrom
                    it[createdBy] = call.userId
                }
                writeShares(householdId, id, input.weights)
                loadRules(householdId, id).single()
            }
        }
        call.respond(HttpStatusCode.Created, rule)
    }

    put("/{ruleId}", {
        operationId = "updateSplitRule"
        request {
            pathParameter<String>("ruleId")
            body<SplitRuleInput>()
        }
        response {
            code(HttpStatusCode.OK) { body<SplitRule>() }
            code(HttpStatusCode.BadRequest) { body<ApiError>() }
            code(HttpStatusCode.NotFound) { body<ApiError>() }
            code(HttpStatusCode.Conflict) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val id = call.pathId("ruleId", "Split rule")
        val raw = call.receive<SplitRuleInput>()
        val version = requireVersion(raw.version)
        val input = raw.validated()
        val rule = uniqueScope {
            dbQuery {
                input.checkReferences(householdId)
                val affected = SplitRules.update({ matchRule(householdId, id) and (SplitRules.version eq version) }) {
                    it[categoryId] = input.categoryId
                    it[validFrom] = input.validFrom
                    it[SplitRules.version] = version + 1
                    it[updatedAt] = Clock.System.now()
                }
                checkVersioned(affected, { ruleExists(householdId, id) }, "Split rule")
                writeShares(householdId, id, input.weights)
                loadRules(householdId, id).single()
            }
        }
        call.respond(rule)
    }

    delete("/{ruleId}", {
        operationId = "deleteSplitRule"
        request {
            pathParameter<String>("ruleId")
            queryParameter<Int>("version") { required = true }
        }
        response {
            code(HttpStatusCode.NoContent) {}
            code(HttpStatusCode.NotFound) { body<ApiError>() }
            code(HttpStatusCode.Conflict) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val id = call.pathId("ruleId", "Split rule")
        val version = call.versionParam()
        dbQuery {
            val affected = SplitRules.deleteWhere { matchRule(householdId, id) and (SplitRules.version eq version) }
            checkVersioned(affected, { ruleExists(householdId, id) }, "Split rule")
        }
        call.respond(HttpStatusCode.NoContent)
    }
}

private data class ValidRule(val validFrom: LocalDate, val categoryId: Uuid?, val weights: Weights)

private fun SplitRuleInput.validated() =
    ValidRule(validFrom, parseId(categoryId, "categoryId"), shares.validatedWeights())

private fun ValidRule.checkReferences(householdId: Uuid) {
    if (categoryId != null) {
        val cat = Categories.select(Categories.kind)
            .where { (Categories.id eq categoryId) and (Categories.householdId eq householdId) }
            .singleOrNull()
            ?: throw badRequest("invalid_category", "Unknown category", "categoryId")
        if (cat[Categories.kind] == CategoryKind.INCOME.dbValue) {
            throw badRequest("invalid_category", "Split rules cannot use an income category", "categoryId")
        }
    }
    weights.checkMembers(householdId)
}

/** Household and category rules only (or just [id]). Call inside a transaction. */
private fun loadRules(householdId: Uuid, id: Uuid? = null): List<SplitRule> {
    var filter = (SplitRules.householdId eq householdId) and SplitRules.expenseId.isNull()
    if (id != null) filter = filter and (SplitRules.id eq id)
    val rows = SplitRules.selectAll().where(filter).toList()
    val shares = SplitShares.selectAll().where { SplitShares.householdId eq householdId }
        .groupBy({ it[SplitShares.ruleId] }, { it[SplitShares.userId] to it[SplitShares.weight] })
    return rows
        .sortedWith(compareBy<ResultRow> { it[SplitRules.categoryId] != null }.thenBy { it[SplitRules.categoryId]?.toString() }
            .thenByDescending { it[SplitRules.validFrom] })
        .map { row ->
            SplitRule(
                id = row[SplitRules.id].toString(),
                categoryId = row[SplitRules.categoryId]?.toString(),
                validFrom = row[SplitRules.validFrom]!!,
                shares = shares[row[SplitRules.id]].orEmpty().toMap().toShares(),
                version = row[SplitRules.version],
            )
        }
}

private suspend fun <T> uniqueScope(block: suspend () -> T): T =
    try {
        block()
    } catch (e: Exception) {
        if (e.isUniqueViolation()) {
            throw conflict("rule_exists", "There's already a split for this from the same date. Edit that one instead.", "validFrom")
        }
        throw e
    }

private fun matchRule(householdId: Uuid, id: Uuid) =
    (SplitRules.id eq id) and (SplitRules.householdId eq householdId) and SplitRules.expenseId.isNull()

private fun ruleExists(householdId: Uuid, id: Uuid) = !SplitRules.selectAll().where { matchRule(householdId, id) }.empty()

// ---- Settlements ----

private fun Route.settlementRoutes() = route("/settlements") {
    get({
        operationId = "listSettlements"
        description = "Settlements newest first, filtered by inclusive date range."
        request {
            queryParameter<String>("from") { description = "Inclusive start date, YYYY-MM-DD" }
            queryParameter<String>("to") { description = "Inclusive end date, YYYY-MM-DD" }
        }
        response {
            code(HttpStatusCode.OK) { body<List<Settlement>>() }
            code(HttpStatusCode.BadRequest) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val from = call.dateParam("from")
        val to = call.dateParam("to")
        val settlements = dbQuery {
            var filter: Op<Boolean> = Settlements.householdId eq householdId
            if (from != null) filter = filter and (Settlements.date greaterEq from)
            if (to != null) filter = filter and (Settlements.date lessEq to)
            Settlements.selectAll().where(filter)
                .orderBy(Settlements.date to SortOrder.DESC, Settlements.id to SortOrder.ASC)
                .map { it.toSettlement() }
        }
        call.respond(settlements)
    }

    post({
        operationId = "createSettlement"
        request { body<SettlementInput>() }
        response {
            code(HttpStatusCode.Created) { body<Settlement>() }
            code(HttpStatusCode.BadRequest) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val input = call.receive<SettlementInput>().validated()
        val id = Uuid.random()
        val settlement = dbQuery {
            input.checkMembers(householdId)
            Settlements.insert {
                it[Settlements.id] = id
                it[Settlements.householdId] = householdId
                it.write(input)
                it[createdBy] = call.userId
            }
            Settlements.selectAll().where { Settlements.id eq id }.single().toSettlement()
        }
        call.respond(HttpStatusCode.Created, settlement)
    }

    put("/{settlementId}", {
        operationId = "updateSettlement"
        request {
            pathParameter<String>("settlementId")
            body<SettlementInput>()
        }
        response {
            code(HttpStatusCode.OK) { body<Settlement>() }
            code(HttpStatusCode.BadRequest) { body<ApiError>() }
            code(HttpStatusCode.NotFound) { body<ApiError>() }
            code(HttpStatusCode.Conflict) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val id = call.pathId("settlementId", "Settlement")
        val raw = call.receive<SettlementInput>()
        val version = requireVersion(raw.version)
        val input = raw.validated()
        val settlement = dbQuery {
            input.checkMembers(householdId)
            val affected = Settlements.update({ matchSettlement(householdId, id) and (Settlements.version eq version) }) {
                it.write(input)
                it[Settlements.version] = version + 1
                it[updatedAt] = Clock.System.now()
            }
            checkVersioned(affected, { settlementExists(householdId, id) }, "Settlement")
            Settlements.selectAll().where { Settlements.id eq id }.single().toSettlement()
        }
        call.respond(settlement)
    }

    delete("/{settlementId}", {
        operationId = "deleteSettlement"
        request {
            pathParameter<String>("settlementId")
            queryParameter<Int>("version") { required = true }
        }
        response {
            code(HttpStatusCode.NoContent) {}
            code(HttpStatusCode.NotFound) { body<ApiError>() }
            code(HttpStatusCode.Conflict) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val id = call.pathId("settlementId", "Settlement")
        val version = call.versionParam()
        dbQuery {
            val affected = Settlements.deleteWhere { matchSettlement(householdId, id) and (Settlements.version eq version) }
            checkVersioned(affected, { settlementExists(householdId, id) }, "Settlement")
        }
        call.respond(HttpStatusCode.NoContent)
    }
}

private data class ValidSettlement(val date: LocalDate, val from: Uuid, val to: Uuid, val amountMinor: Long, val notes: String)

private fun SettlementInput.validated(): ValidSettlement {
    val from = parseId(fromUserId, "fromUserId") ?: throw badRequest("invalid_member", "Say who paid", "fromUserId")
    val to = parseId(toUserId, "toUserId") ?: throw badRequest("invalid_member", "Say who was paid", "toUserId")
    if (from == to) throw badRequest("same_member", "Someone can't pay themselves back", "toUserId")
    if (amountMinor <= 0) throw badRequest("invalid_amount", "The amount has to be more than zero", "amountMinor")
    if (amountMinor > 1_000_000_000_00L) throw badRequest("invalid_amount", "Amount is too large", "amountMinor")
    return ValidSettlement(date, from, to, amountMinor, optionalText(notes, "notes", max = 2000))
}

private fun ValidSettlement.checkMembers(householdId: Uuid) {
    if (!isMember(householdId, from)) throw badRequest("invalid_member", "Must be a member of the household", "fromUserId")
    if (!isMember(householdId, to)) throw badRequest("invalid_member", "Must be a member of the household", "toUserId")
}

private fun org.jetbrains.exposed.v1.core.statements.UpdateBuilder<*>.write(input: ValidSettlement) {
    this[Settlements.date] = input.date
    this[Settlements.fromUserId] = input.from
    this[Settlements.toUserId] = input.to
    this[Settlements.amountMinor] = input.amountMinor
    this[Settlements.notes] = input.notes
}

private fun ResultRow.toSettlement() = Settlement(
    id = this[Settlements.id].toString(),
    date = this[Settlements.date],
    fromUserId = this[Settlements.fromUserId].toString(),
    toUserId = this[Settlements.toUserId].toString(),
    amountMinor = this[Settlements.amountMinor],
    notes = this[Settlements.notes],
    version = this[Settlements.version],
)

private fun matchSettlement(householdId: Uuid, id: Uuid) = (Settlements.id eq id) and (Settlements.householdId eq householdId)

private fun settlementExists(householdId: Uuid, id: Uuid) =
    !Settlements.selectAll().where { matchSettlement(householdId, id) }.empty()
