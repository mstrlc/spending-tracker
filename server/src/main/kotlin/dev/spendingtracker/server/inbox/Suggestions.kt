package dev.spendingtracker.server.inbox

import dev.spendingtracker.server.db.Accounts
import dev.spendingtracker.server.db.BankFeeds
import dev.spendingtracker.server.db.BankRows
import dev.spendingtracker.server.db.Expenses
import dev.spendingtracker.server.db.FxRates
import dev.spendingtracker.server.db.Income
import dev.spendingtracker.server.db.Merchants
import dev.spendingtracker.shared.api.BankRowSuggestion
import dev.spendingtracker.shared.api.DuplicateCandidate
import dev.spendingtracker.shared.api.RuleOutcome
import dev.spendingtracker.shared.api.SuggestedRecord
import dev.spendingtracker.shared.api.TransferCandidate
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.util.Currency
import java.util.TreeMap
import kotlin.math.abs
import kotlin.uuid.Uuid

/** Hints that don't say the money stayed in the household. */
private val NOT_MOVEMENT = setOf("Refund", "Money in", "Money in on a card: a refund?")

/** Hints that say the money moved between the household's own accounts (the old app's carry an "Old app: " prefix). */
private val MOVEMENT_HINTS = setOf(
    "Between your own accounts", "Revolut top-up", "Top-up", "Currency exchange", "Card repayment",
    "To or from someone in the household",
)

/** How far the two sides of a transfer may be apart, and how far their converted amounts may differ. */
private const val TRANSFER_DAYS = 4
private const val TRANSFER_PERCENT = 2

/** How far a hand-entered expense's date may be from the bank's (booking vs. purchase date). */
private const val DUPLICATE_DAYS = 3

/** How far an income line's date may be; one dated the 1st (a month known, not the day) may be up to this far before. */
private const val INCOME_DAYS = 5
private const val INCOME_MONTH_DAYS = 40

/**
 * Suggestions for pending [rows] (rows of [rowsWithFeed]): what each probably becomes, filled in from the household's
 * past decisions, and the hand-entered records it may duplicate. A matching rule of [rules] takes precedence over what
 * the past decisions say. Call inside a transaction, after the exchange rates of the rows' years are fresh.
 */
fun suggestFor(
    householdId: Uuid,
    householdCurrency: String,
    rows: List<ResultRow>,
    rules: List<RuleDef> = loadRules(householdId),
): Map<Uuid, BankRowSuggestion> {
    if (rows.isEmpty()) return emptyMap()
    val accountRows = Accounts.select(Accounts.id, Accounts.ownerUserId, Accounts.kind, Accounts.iban).where { Accounts.householdId eq householdId }.toList()
    val owners = accountRows.associate { it[Accounts.id] to it[Accounts.ownerUserId] }
    val cards = accountRows.filter { it[Accounts.kind] == "credit_card" }.map { it[Accounts.id] }.toSet()
    // The account an IBAN belongs to: an account's own, else a feed's.
    val accountByIban = BankFeeds.select(BankFeeds.iban, BankFeeds.accountId).where { BankFeeds.householdId eq householdId }
        .mapNotNull { r -> r[BankFeeds.iban]?.let { it to r[BankFeeds.accountId] } }.toMap() +
        accountRows.mapNotNull { r -> r[Accounts.iban]?.let { it to r[Accounts.id] } }.toMap()
    val merchants = Merchants.selectAll().where { Merchants.householdId eq householdId }.toList()
    val merchantsByName = merchants.associateBy { it[Merchants.name].lowercase() }
    val merchantNames = merchants.associate { it[Merchants.id] to it[Merchants.name] }

    // The latest decision per descriptor: what its expense or income line said.
    val pastExpenses = BankRows.join(Expenses, JoinType.INNER, BankRows.expenseId, Expenses.id)
        .select(BankRows.merchantKey, Expenses.merchantId, Expenses.categoryId, Expenses.paidByUserId, Expenses.date)
        .where { (BankRows.householdId eq householdId) and BankRows.expenseId.isNotNull() }
        .orderBy(Expenses.date to SortOrder.DESC)
        .groupBy { it[BankRows.merchantKey] }.mapValues { it.value.first() }
    val pastIncome = BankRows.join(Income, JoinType.INNER, BankRows.incomeId, Income.id)
        .select(BankRows.merchantKey, Income.sourceName, Income.recipientUserId, Income.date)
        .where { (BankRows.householdId eq householdId) and BankRows.incomeId.isNotNull() }
        .orderBy(Income.date to SortOrder.DESC)
        .groupBy { it[BankRows.merchantKey] }.mapValues { it.value.first() }

    val dates = rows.map { it[BankRows.date] }
    // The other sides of transfers: pending rows around these dates, loaded once for the whole list.
    val pool = rowsWithFeed().where {
        (BankRows.householdId eq householdId) and pendingRows() and
            (BankRows.date greaterEq dates.min().minus(DatePeriod(days = TRANSFER_DAYS))) and
            (BankRows.date lessEq dates.max().plus(DatePeriod(days = TRANSFER_DAYS)))
    }.toList()
    val fx = FxTable.load((rows + pool).map { it[BankRows.currency] }.filter { it != householdCurrency }.toSet() + householdCurrency)
    val (linkedExpenses, linkedIncome) = linkedRecords(householdId)
    val first = dates.min().minus(DatePeriod(days = INCOME_MONTH_DAYS))
    val last = dates.max().plus(DatePeriod(days = INCOME_MONTH_DAYS))
    val expenses = Expenses.join(Merchants, JoinType.LEFT, Expenses.merchantId, Merchants.id)
        .select(Expenses.id, Expenses.date, Expenses.amountMinor, Expenses.accountId, Merchants.name)
        .where { (Expenses.householdId eq householdId) and (Expenses.date greaterEq first) and (Expenses.date lessEq last) }
        .filter { it[Expenses.id] !in linkedExpenses }
    val incomeLines = Income.selectAll()
        .where { (Income.householdId eq householdId) and (Income.date greaterEq first) and (Income.date lessEq last) }
        .filter { it[Income.id] !in linkedIncome }

    return rows.associate { row ->
        val accountId = row[BankFeeds.accountId]
        val date = row[BankRows.date]
        val native = row[BankRows.amountMinor]
        val currency = row[BankRows.currency]
        val converted = if (currency == householdCurrency) native else fx.convert(native, currency, householdCurrency, date)
        val key = row[BankRows.merchantKey]
        // Money in is income, unless it's a refund: a Revolut card refund, or money back on a card or from where you
        // only ever spent, that isn't money moving between your own accounts (a card repayment, a top-up).
        val movement = row[BankRows.hint].let { it.isNotEmpty() && it !in NOT_MOVEMENT }
        val refund = row[BankRows.code] == "CARD_REFUND" ||
            (!movement && (accountId in cards || (key in pastExpenses && key !in pastIncome)))
        val rule = rules.firstMatch(row)
        // A rule can say the row is the other kind than the guess: money in as an expense (a refund), or out as income.
        val expenseOrIncome = when (rule?.outcome) {
            RuleOutcome.EXPENSE -> SuggestedRecord.EXPENSE
            RuleOutcome.INCOME -> SuggestedRecord.INCOME
            else -> if (native < 0 || refund) SuggestedRecord.EXPENSE else SuggestedRecord.INCOME
        }
        val text = row[BankRows.text]
        val owner = owners[accountId]

        val duplicates = when (expenseOrIncome) {
            SuggestedRecord.EXPENSE -> {
                val amount = converted?.let { -it }
                expenses.filter { e ->
                    (e[Expenses.accountId] == null || e[Expenses.accountId] == accountId) &&
                        abs(date.daysUntil(e[Expenses.date])) <= DUPLICATE_DAYS &&
                        amount != null && closeEnough(e[Expenses.amountMinor], amount, currency != householdCurrency)
                }.sortedBy { abs(date.daysUntil(it[Expenses.date])) }.take(3).map { e ->
                    DuplicateCandidate(SuggestedRecord.EXPENSE, e[Expenses.id].toString(), e[Expenses.date], e[Expenses.amountMinor], e.getOrNull(Merchants.name).orEmpty())
                }
            }
            else -> incomeLines.filter { i ->
                val gap = date.daysUntil(i[Income.date])
                (i[Income.accountId] == null || i[Income.accountId] == accountId) &&
                    (abs(gap) <= INCOME_DAYS || (i[Income.date].day == 1 && gap <= 0 && -gap <= INCOME_MONTH_DAYS)) &&
                    converted != null && closeEnough(i[Income.amountMinor], converted, currency != householdCurrency)
            }.sortedBy { abs(date.daysUntil(it[Income.date])) }.take(3).map { i ->
                DuplicateCandidate(SuggestedRecord.INCOME, i[Income.id].toString(), i[Income.date], i[Income.amountMinor], i[Income.sourceName])
            }
        }

        val past = pastExpenses[key]
        val guessName = cleanDescriptor(text).ifEmpty { text }
        val known = merchantsByName[guessName.lowercase()]
        val pastSource = pastIncome[key]
        val candidates = pool.asSequence()
            .filter { other ->
                other[BankRows.id] != row[BankRows.id] && other[BankRows.feedId] != row[BankRows.feedId] &&
                    (other[BankRows.amountMinor] > 0) != (native > 0) &&
                    abs(date.daysUntil(other[BankRows.date])) <= TRANSFER_DAYS &&
                    sameMoney(row, other, householdCurrency, fx)
            }
            .sortedWith(compareBy({ abs(date.daysUntil(it[BankRows.date])) }, { it[BankRows.id].toString() }))
            .take(3)
            .map { other ->
                TransferCandidate(
                    other[BankRows.id].toString(), other[BankRows.version], other[BankFeeds.accountId].toString(),
                    other[BankRows.date], other[BankRows.amountMinor], other[BankRows.currency], other[BankRows.text],
                )
            }.toList()
        val ownAccount = row[BankRows.counterpartyIban]?.let(accountByIban::get)?.takeIf { it != accountId && candidates.isEmpty() }
        val isMovement = row[BankRows.hint].removePrefix("Old app: ") in MOVEMENT_HINTS
        val guessedMerchant = past?.get(Expenses.merchantId)?.let(merchantNames::get) ?: known?.get(Merchants.name) ?: guessName
        val guessedCategory = (past?.get(Expenses.categoryId) ?: known?.get(Merchants.defaultCategoryId))
        // What a rule fills in: its merchant and category (else that merchant's default), and whose from the account unless it names a person.
        val forExpense = rule?.outcome == RuleOutcome.EXPENSE
        val forIncome = rule?.outcome == RuleOutcome.INCOME
        val ruleMerchant = if (forExpense && rule!!.merchant.isNotBlank()) rule.merchant else null
        val merchant = ruleMerchant ?: guessedMerchant
        val category = when {
            !forExpense -> guessedCategory
            rule!!.categoryId != null -> rule.categoryId
            ruleMerchant != null -> merchantsByName[ruleMerchant.lowercase()]?.get(Merchants.defaultCategoryId)
            else -> guessedCategory
        }
        row[BankRows.id] to BankRowSuggestion(
            record = when (rule?.outcome) {
                RuleOutcome.TRANSFER -> SuggestedRecord.TRANSFER
                RuleOutcome.EXPENSE, RuleOutcome.INCOME -> expenseOrIncome
                else -> if (candidates.isNotEmpty() || ownAccount != null || isMovement) SuggestedRecord.TRANSFER else expenseOrIncome
            },
            amountMinor = converted?.let { if (expenseOrIncome == SuggestedRecord.EXPENSE) -it else it },
            originalAmountMinor = if (currency == householdCurrency) null else abs(native),
            originalCurrency = if (currency == householdCurrency) null else currency,
            merchant = merchant,
            categoryId = category?.toString(),
            paidByUserId = (if (forExpense || forIncome) rule!!.personUserId ?: owner else if (past != null) past[Expenses.paidByUserId] else owner)?.toString(),
            incomeSource = (if (forIncome) rule!!.incomeSource.ifBlank { null } else null)
                ?: pastSource?.get(Income.sourceName) ?: row[BankRows.counterpartyName].ifEmpty { text },
            duplicates = duplicates,
            transferCandidates = candidates,
            transferAccountId = (if (rule?.outcome == RuleOutcome.TRANSFER) rule.otherAccountId?.takeIf { it != accountId } else null)?.toString()
                ?: ownAccount?.toString(),
            ruleId = rule?.id?.toString(),
            ruleName = rule?.name,
            skipReason = rule?.takeIf { it.outcome == RuleOutcome.SKIP }?.skipReason,
        )
    }
}

/** The same amount, one way out and the other in: exact in one currency, else both in the household currency within [TRANSFER_PERCENT]. */
private fun sameMoney(a: ResultRow, b: ResultRow, householdCurrency: String, fx: FxTable): Boolean {
    val x = a[BankRows.amountMinor]
    val y = b[BankRows.amountMinor]
    if (a[BankRows.currency] == b[BankRows.currency]) return x == -y
    val cx = abs(if (a[BankRows.currency] == householdCurrency) x else fx.convert(x, a[BankRows.currency], householdCurrency, a[BankRows.date]) ?: return false)
    val cy = abs(if (b[BankRows.currency] == householdCurrency) y else fx.convert(y, b[BankRows.currency], householdCurrency, b[BankRows.date]) ?: return false)
    return abs(cx - cy) * 100 <= maxOf(cx, cy) * TRANSFER_PERCENT
}

/** Within 1 unit of the household currency, or 3% when the bank's amount was converted (card rate vs. ČNB fixing). */
private fun closeEnough(recorded: Long, bank: Long, converted: Boolean): Boolean {
    if ((recorded > 0) != (bank > 0)) return false
    val diff = abs(recorded - bank)
    return if (converted) diff <= abs(bank) * 3 / 100 else diff < 100
}

/** ČNB fixings (CZK per unit) by currency and day; a day without one uses the latest before it. */
class FxTable(private val czk: Map<String, TreeMap<LocalDate, BigDecimal>>) {
    /** [amountMinor] of [from] in minor units of [to] on [on]; null without a fixing. */
    fun convert(amountMinor: Long, from: String, to: String, on: LocalDate): Long? {
        val fromRate = czkFor(from, on) ?: return null
        val toRate = czkFor(to, on) ?: return null
        val major = BigDecimal(amountMinor).movePointLeft(digits(from))
        val result = major.multiply(fromRate).divide(toRate, MathContext.DECIMAL64)
        return result.movePointRight(digits(to)).setScale(0, RoundingMode.HALF_UP).toLong()
    }

    private fun czkFor(currency: String, on: LocalDate) =
        if (currency == "CZK") BigDecimal.ONE else czk[currency]?.floorEntry(on)?.value

    private fun digits(currency: String) = runCatching { Currency.getInstance(currency).defaultFractionDigits }.getOrDefault(2).coerceAtLeast(0)

    companion object {
        fun load(currencies: Set<String>): FxTable {
            val wanted = currencies.filter { it != "CZK" }
            val czk = HashMap<String, TreeMap<LocalDate, BigDecimal>>()
            if (wanted.isNotEmpty()) {
                FxRates.selectAll().where { FxRates.currency inList wanted }.forEach {
                    czk.getOrPut(it[FxRates.currency]) { TreeMap() }[it[FxRates.date]] = it[FxRates.czk]
                }
            }
            return FxTable(czk)
        }
    }
}
