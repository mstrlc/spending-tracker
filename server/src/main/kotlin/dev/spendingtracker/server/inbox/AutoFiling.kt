package dev.spendingtracker.server.inbox

import dev.spendingtracker.server.common.ApiException
import dev.spendingtracker.server.db.Accounts
import dev.spendingtracker.server.db.BankFeeds
import dev.spendingtracker.server.db.BankRows
import dev.spendingtracker.server.db.Expenses
import dev.spendingtracker.server.db.Households
import dev.spendingtracker.server.expense.createExpense
import dev.spendingtracker.server.income.createIncome
import dev.spendingtracker.shared.api.ApplyRulesResult
import dev.spendingtracker.shared.api.BankRowRef
import dev.spendingtracker.shared.api.BankRowSuggestion
import dev.spendingtracker.shared.api.ExpenseInput
import dev.spendingtracker.shared.api.IncomeInput
import dev.spendingtracker.shared.api.RuleOutcome
import dev.spendingtracker.shared.api.SuggestedRecord
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.math.abs
import kotlin.uuid.Uuid

/**
 * The matcher's thresholds are strict on purpose (the old app's automatic pairings were wrong often enough to matter):
 * a transfer pair is exactly opposite amounts in one currency within [PAIR_DAYS], and a duplicate of a hand-entered
 * expense is the exact amount on the same account within [DUPLICATE_DAYS_STRICT]. Either has to be the only candidate
 * for both sides, or it is left to a person.
 */
private const val PAIR_DAYS = 2
private const val DUPLICATE_DAYS_STRICT = 1

/** Rows of [rowsWithFeed] ordered the way they are processed: oldest first, so results don't depend on the listing. */
private val PROCESSING_ORDER = compareBy<ResultRow>({ it[BankRows.date] }, { it[BankRows.externalId] }, { it[BankRows.id].toString() })

/**
 * Files what can be filed without asking: pending rows matching a rule with auto go where the rule says, and, when the
 * household allows it (`auto_match`), the matcher files transfer pairs and duplicates of hand-entered expenses it is sure
 * of. Works on the pending rows among [onlyRowIds], or on all pending rows when it is null. Rows it can't file
 * (no exchange rate yet, an account that no longer fits) stay in the inbox. Call inside a transaction, after the
 * exchange rates of the rows' years are fresh; whoever made the request is the audit actor.
 */
fun applyRules(householdId: Uuid, householdCurrency: String, userId: Uuid, onlyRowIds: Set<Uuid>? = null): ApplyRulesResult {
    val autoMatch = Households.select(Households.autoMatch).where { Households.id eq householdId }.single()[Households.autoMatch]
    val rules = loadRules(householdId)
    val pending = rowsWithFeed().where { (BankRows.householdId eq householdId) and pendingRows() }.toList()
    val subject = pending.filter { onlyRowIds == null || it[BankRows.id] in onlyRowIds }.sortedWith(PROCESSING_ORDER)
    if (subject.isEmpty() || (rules.isEmpty() && !autoMatch)) return ApplyRulesResult(subject.size, 0, 0, 0, 0)

    val ruleOf = pending.associate { it[BankRows.id] to rules.firstMatch(it) }
    val engine = Engine(householdId, householdCurrency, userId, pending.associateBy { it[BankRows.id] }, ruleOf)
    if (rules.any { it.auto }) {
        val autoRows = subject.filter { ruleOf[it[BankRows.id]]?.auto == true }
        val suggestions = suggestFor(householdId, householdCurrency, autoRows, rules)
        for (row in autoRows) {
            if (row[BankRows.id] in engine.filed) continue
            engine.fileByRule(row, ruleOf.getValue(row[BankRows.id])!!, suggestions.getValue(row[BankRows.id]))
        }
    }
    if (autoMatch) {
        engine.pairTransfers(subject)
        engine.linkDuplicates(subject)
    }
    return ApplyRulesResult(subject.size, engine.byRules, engine.linkedByRules, engine.pairedByMatcher, engine.duplicatesByMatcher)
}

private class Engine(
    val householdId: Uuid,
    val currency: String,
    val userId: Uuid,
    val pending: Map<Uuid, ResultRow>,
    val ruleOf: Map<Uuid, RuleDef?>,
) {
    /** Rows filed in this run: no longer pending, whatever [pending] says. */
    val filed = mutableSetOf<Uuid>()

    /** Hand-entered records some row was linked to in this run. */
    private val linked = mutableSetOf<Uuid>()

    var byRules = 0
    var linkedByRules = 0
    var pairedByMatcher = 0
    var duplicatesByMatcher = 0

    private fun ResultRow.ref() = BankRowRef(this[BankRows.id].toString(), this[BankRows.version])

    /** Files [row] where [rule] says, linking it to a record already there when one looks the same. Leaves it pending on any trouble. */
    fun fileByRule(row: ResultRow, rule: RuleDef, suggestion: BankRowSuggestion) {
        try {
            when (rule.outcome) {
                RuleOutcome.SKIP -> {
                    fileBankRows(householdId, listOf(row.ref()), userId, skipReason = rule.skipReason, auto = true, ruleId = rule.id)
                    done(row)
                }
                RuleOutcome.EXPENSE -> expense(row, rule, suggestion)
                RuleOutcome.INCOME -> income(row, rule, suggestion)
                RuleOutcome.DEDUCTION -> income(row, rule, suggestion)
                RuleOutcome.TRANSFER -> transfer(row, rule, suggestion)
                RuleOutcome.SETTLEMENT -> settlement(row, rule, suggestion)
            }
        } catch (e: ApiException) {
            // Something about this row or rule doesn't fit (no rate, an unknown reference): it stays in the inbox for a person.
        }
    }

    private fun done(vararg rows: ResultRow) {
        rows.forEach { filed += it[BankRows.id] }
        byRules += rows.size
    }

    private fun expense(row: ResultRow, rule: RuleDef, s: BankRowSuggestion) {
        val twin = s.duplicates.firstOrNull { it.record == SuggestedRecord.EXPENSE && Uuid.parse(it.id) !in linked }
        if (twin != null) {
            val id = Uuid.parse(twin.id)
            fileBankRows(householdId, listOf(row.ref()), userId, expenseId = id, auto = true, ruleId = rule.id)
            linked += id
            linkedByRules++
            done(row)
            return
        }
        val amount = s.amountMinor ?: return
        // A foreign row keeps what it cost in its own currency, signed like the expense.
        val original = s.originalAmountMinor?.let { if (amount < 0) -it else it }
        val id = createExpense(
            householdId, userId,
            ExpenseInput(
                date = row[BankRows.date],
                amountMinor = amount,
                merchant = s.merchant,
                categoryId = s.categoryId,
                accountId = row[BankFeeds.accountId].toString(),
                paidByUserId = s.paidByUserId,
                originalAmountMinor = original,
                originalCurrency = s.originalCurrency.takeIf { original != null },
            ),
            importId = row[BankRows.importId],
        )
        fileBankRows(householdId, listOf(row.ref()), userId, expenseId = id, created = true, auto = true, ruleId = rule.id)
        done(row)
    }

    /** An income line, or for a deduction rule a deduction: the suggestion carries which, its amount and its month. */
    private fun income(row: ResultRow, rule: RuleDef, s: BankRowSuggestion) {
        val twin = s.duplicates.firstOrNull { it.record == SuggestedRecord.INCOME && Uuid.parse(it.id) !in linked }
        if (twin != null) {
            val id = Uuid.parse(twin.id)
            fileBankRows(householdId, listOf(row.ref()), userId, incomeId = id, auto = true, ruleId = rule.id)
            linked += id
            linkedByRules++
            done(row)
            return
        }
        val amount = s.amountMinor ?: return
        val id = createIncome(
            householdId, userId,
            IncomeInput(
                date = row[BankRows.date],
                amountMinor = amount,
                source = s.incomeSource,
                accountId = row[BankFeeds.accountId].toString(),
                recipientUserId = s.paidByUserId,
                // A deduction rule's suggestion is minus the row's amount, earned in the month the rule says.
                earnedMonth = s.earnedMonth,
                kind = s.incomeKind,
            ),
        )
        fileBankRows(householdId, listOf(row.ref()), userId, incomeId = id, created = true, auto = true, ruleId = rule.id)
        done(row)
    }

    /** With exactly one pending row that is the other side, both become the transfer; otherwise this row alone, against the rule's account. */
    private fun transfer(row: ResultRow, rule: RuleDef, s: BankRowSuggestion) {
        val partner = s.transferCandidates.singleOrNull()
            ?.let { pending[Uuid.parse(it.rowId)] }
            ?.takeIf { it[BankRows.id] !in filed && ruleOf[it[BankRows.id]].let { r -> r == null || r.outcome == RuleOutcome.TRANSFER } }
        val rows = listOfNotNull(row, partner)
        val id = createTransfer(householdId, userId, currency, rows, rule.otherAccountId.takeIf { partner == null }, "")
        fileBankRows(householdId, rows.map { it.ref() }, userId, transferId = id, created = true, auto = true, ruleId = rule.id)
        done(*rows.toTypedArray())
    }

    /** With exactly one pending row that is the other side, both become the settlement; otherwise this row alone. */
    private fun settlement(row: ResultRow, rule: RuleDef, s: BankRowSuggestion) {
        val from = rule.settleFromUserId ?: return
        val to = rule.settleToUserId ?: return
        val partner = s.transferCandidates.singleOrNull()
            ?.let { pending[Uuid.parse(it.rowId)] }
            ?.takeIf { it[BankRows.id] !in filed && ruleOf[it[BankRows.id]].let { r -> r == null || r.outcome == RuleOutcome.SETTLEMENT } }
        val rows = listOfNotNull(row, partner)
        val id = createSettlement(householdId, userId, currency, rows, from, to, "")
        fileBankRows(householdId, rows.map { it.ref() }, userId, settlementId = id, created = true, auto = true, ruleId = rule.id)
        done(*rows.toTypedArray())
    }

    /** Transfer pairs among the pending rows that no rule speaks for: each the only candidate of the other. */
    fun pairTransfers(subject: List<ResultRow>) {
        val open = pending.values.filter { it[BankRows.id] !in filed && ruleOf[it[BankRows.id]] == null }
        val owners = Accounts.select(Accounts.id, Accounts.ownerUserId).where { Accounts.householdId eq householdId }
            .associate { it[Accounts.id] to it[Accounts.ownerUserId] }
        // Money from one member's own account to another's may be a settlement or a gift, not a transfer: a person decides.
        fun betweenMembers(a: ResultRow, b: ResultRow): Boolean {
            val ownerA = owners[a[BankFeeds.accountId]]
            val ownerB = owners[b[BankFeeds.accountId]]
            return ownerA != null && ownerB != null && ownerA != ownerB
        }
        fun partners(row: ResultRow) = open.filter { other ->
            other[BankRows.feedId] != row[BankRows.feedId] && !betweenMembers(row, other) &&
                other[BankRows.currency] == row[BankRows.currency] &&
                other[BankRows.amountMinor] == -row[BankRows.amountMinor] && row[BankRows.amountMinor] != 0L &&
                abs(row[BankRows.date].daysUntil(other[BankRows.date])) <= PAIR_DAYS
        }
        val subjectIds = subject.map { it[BankRows.id] }.toSet()
        val seen = mutableSetOf<Uuid>()
        for (row in open.sortedWith(PROCESSING_ORDER)) {
            val id = row[BankRows.id]
            if (id !in seen && id !in filed) {
                val other = partners(row).singleOrNull()
                if (other != null && partners(other).singleOrNull()?.get(BankRows.id) == id) {
                    seen += id
                    seen += other[BankRows.id]
                    // Only pairs with a row of this run: older pending rows aren't paired with each other behind a person's back.
                    if (id in subjectIds || other[BankRows.id] in subjectIds) pair(row, other)
                }
            }
        }
    }

    private fun pair(a: ResultRow, b: ResultRow) {
        try {
            val rows = listOf(a, b)
            val id = createTransfer(householdId, userId, currency, rows, null, "")
            fileBankRows(householdId, rows.map { it.ref() }, userId, transferId = id, created = true, auto = true)
            filed += a[BankRows.id]
            filed += b[BankRows.id]
            pairedByMatcher += 2
        } catch (e: ApiException) {
            // No exchange rate yet, say: left for a person.
        }
    }

    /** Pending money-out rows in the household currency with exactly one hand-entered expense of the same amount, account and (nearly) day, and no other such row. */
    fun linkDuplicates(subject: List<ResultRow>) {
        val open = pending.values.filter {
            it[BankRows.id] !in filed && ruleOf[it[BankRows.id]] == null &&
                it[BankRows.currency] == currency && it[BankRows.amountMinor] < 0
        }
        if (open.isEmpty()) return
        val first = open.minOf { it[BankRows.date] }.minus(DatePeriod(days = DUPLICATE_DAYS_STRICT))
        val last = open.maxOf { it[BankRows.date] }.plus(DatePeriod(days = DUPLICATE_DAYS_STRICT))
        val (taken, _) = linkedRecords(householdId)
        val expenses = Expenses.select(Expenses.id, Expenses.date, Expenses.amountMinor, Expenses.accountId)
            .where { (Expenses.householdId eq householdId) and (Expenses.date greaterEq first) and (Expenses.date lessEq last) }
            .filter { it[Expenses.id] !in taken && it[Expenses.id] !in linked && it[Expenses.accountId] != null }
        fun twins(row: ResultRow) = expenses.filter {
            it[Expenses.accountId] == row[BankFeeds.accountId] && it[Expenses.amountMinor] == -row[BankRows.amountMinor] &&
                abs(row[BankRows.date].daysUntil(it[Expenses.date])) <= DUPLICATE_DAYS_STRICT
        }
        fun rivals(expense: ResultRow) = open.filter { row ->
            row[BankFeeds.accountId] == expense[Expenses.accountId] && expense[Expenses.amountMinor] == -row[BankRows.amountMinor] &&
                abs(row[BankRows.date].daysUntil(expense[Expenses.date])) <= DUPLICATE_DAYS_STRICT
        }
        val subjectIds = subject.map { it[BankRows.id] }.toSet()
        for (row in open.sortedWith(PROCESSING_ORDER)) {
            if (row[BankRows.id] !in subjectIds || row[BankRows.id] in filed) continue
            val expense = twins(row).singleOrNull() ?: continue
            if (rivals(expense).size != 1) continue
            try {
                fileBankRows(householdId, listOf(row.ref()), userId, expenseId = expense[Expenses.id], auto = true)
                filed += row[BankRows.id]
                linked += expense[Expenses.id]
                duplicatesByMatcher++
            } catch (e: ApiException) {
                // Decided meanwhile: leave it.
            }
        }
    }
}

/** The rows added to [feedId] by a statement (their external ids), as [id, date, currency] rows. Call inside a transaction. */
fun addedRows(feedId: Uuid, externalIds: Collection<String>): List<ResultRow> =
    externalIds.chunked(5_000).flatMap { chunk ->
        BankRows.select(BankRows.id, BankRows.date, BankRows.currency)
            .where { (BankRows.feedId eq feedId) and (BankRows.externalId inList chunk) }
            .toList()
    }
