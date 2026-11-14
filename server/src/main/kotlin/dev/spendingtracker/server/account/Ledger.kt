package dev.spendingtracker.server.account

import dev.spendingtracker.server.db.AccountBalances
import dev.spendingtracker.server.db.AssetContributions
import dev.spendingtracker.server.db.Expenses
import dev.spendingtracker.server.db.Income
import dev.spendingtracker.server.db.Transfers
import kotlinx.datetime.LocalDate
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.uuid.Uuid

/** Money in (positive) or out of an account on a day, in the household's currency. */
internal data class Flow(val date: LocalDate, val amountMinor: Long)

/** A balance worked out from the ledger: its value on a day and the day of the balance check it starts from. */
internal data class LedgerValue(val valueMinor: Long, val checkedOn: LocalDate)

/**
 * Balances of accounts the bank doesn't report, worked out from their balance checks and the ledger. On a day, an
 * account holds what its latest check on or before that day said, plus what came in and less what went out after
 * it; before its first check, what that check said less what came in and plus what went out from the day after
 * until the check. A check covers the whole of its day. Accounts without a check have no balance.
 */
internal class Ledger(
    private val checks: Map<Uuid, List<Flow>>,
    private val flows: Map<Uuid, List<Flow>>,
) {
    val accountIds: Set<Uuid> get() = checks.keys

    /** This ledger without [accountIds]: the bank reports their balances, so their checks don't count. */
    fun without(accountIds: Set<Uuid>) = Ledger(checks - accountIds, flows - accountIds)

    fun valueOn(accountId: Uuid, on: LocalDate): LedgerValue? {
        val accountChecks = checks[accountId].orEmpty()
        val accountFlows = flows[accountId].orEmpty()
        val before = accountChecks.filter { it.date <= on }.maxByOrNull { it.date }
        if (before != null) {
            val moved = accountFlows.filter { it.date > before.date && it.date <= on }.sumOf { it.amountMinor }
            return LedgerValue(before.amountMinor + moved, before.date)
        }
        val after = accountChecks.minByOrNull { it.date } ?: return null
        val moved = accountFlows.filter { it.date > on && it.date <= after.date }.sumOf { it.amountMinor }
        return LedgerValue(after.amountMinor - moved, after.date)
    }

    companion object {
        val EMPTY = Ledger(emptyMap(), emptyMap())
    }
}

/**
 * Loads the balance checks of [accountIds] (all of the household's accounts when null) and the money the ledger
 * moved on them: transfers in and out, earnings in, deductions (on the account, or on an earning paid into it) and
 * expenses out (a refund is a negative expense, so it comes back in), and asset contributions paid from them (a
 * withdrawal, negative, comes back in). Call inside a transaction.
 */
internal fun loadLedger(householdId: Uuid, accountIds: Collection<Uuid>? = null): Ledger {
    if (accountIds != null && accountIds.isEmpty()) return Ledger.EMPTY
    val checks = AccountBalances.select(AccountBalances.accountId, AccountBalances.date, AccountBalances.amountMinor)
        .where {
            val household = AccountBalances.householdId eq householdId
            if (accountIds == null) household else household and (AccountBalances.accountId inList accountIds)
        }
        .groupBy({ it[AccountBalances.accountId] }) { Flow(it[AccountBalances.date], it[AccountBalances.amountMinor]) }
    if (checks.isEmpty()) return Ledger.EMPTY
    val ids = checks.keys
    val flows = mutableListOf<Pair<Uuid, Flow>>()

    Transfers.select(Transfers.date, Transfers.amountMinor, Transfers.fromAccountId, Transfers.toAccountId)
        .where { (Transfers.householdId eq householdId) and ((Transfers.fromAccountId inList ids) or (Transfers.toAccountId inList ids)) }
        .forEach {
            val date = it[Transfers.date]
            val amount = it[Transfers.amountMinor]
            if (it[Transfers.fromAccountId] in ids) flows += it[Transfers.fromAccountId] to Flow(date, -amount)
            if (it[Transfers.toAccountId] in ids) flows += it[Transfers.toAccountId] to Flow(date, amount)
        }

    Expenses.select(Expenses.accountId, Expenses.date, Expenses.amountMinor)
        .where { (Expenses.householdId eq householdId) and (Expenses.accountId inList ids) }
        .forEach { flows += it[Expenses.accountId]!! to Flow(it[Expenses.date], -it[Expenses.amountMinor]) }

    AssetContributions.select(AssetContributions.accountId, AssetContributions.date, AssetContributions.amountMinor)
        .where { (AssetContributions.householdId eq householdId) and (AssetContributions.accountId inList ids) }
        .forEach { flows += it[AssetContributions.accountId]!! to Flow(it[AssetContributions.date], -it[AssetContributions.amountMinor]) }

    // Deductions often carry no account of their own: they leave the account their earning was paid into.
    val income = Income.select(Income.id, Income.kind, Income.date, Income.amountMinor, Income.accountId, Income.deductedFrom)
        .where { Income.householdId eq householdId }
        .toList()
    val earningAccount = income.filter { it[Income.kind] == "earning" }.associate { it[Income.id] to it[Income.accountId] }
    for (row in income) {
        val deduction = row[Income.kind] == "deduction"
        val account = row[Income.accountId] ?: if (deduction) row[Income.deductedFrom]?.let { earningAccount[it] } else null
        if (account == null || account !in ids) continue
        val amount = row[Income.amountMinor]
        flows += account to Flow(row[Income.date], if (deduction) -amount else amount)
    }

    return Ledger(checks, flows.groupBy({ it.first }) { it.second })
}
