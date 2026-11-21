package dev.spendingtracker.server.inbox

import dev.spendingtracker.server.account.DEFAULT_INTEREST_TAX_PERCENT
import dev.spendingtracker.server.db.Accounts
import dev.spendingtracker.server.db.AssetContributions
import dev.spendingtracker.server.db.BankFeeds
import dev.spendingtracker.server.db.BankRows
import dev.spendingtracker.server.db.Expenses
import dev.spendingtracker.server.db.FxRates
import dev.spendingtracker.server.db.HouseholdMembers
import dev.spendingtracker.server.db.Categories
import dev.spendingtracker.server.db.Income
import dev.spendingtracker.server.db.Merchants
import dev.spendingtracker.server.db.Settlements
import dev.spendingtracker.server.db.Users
import dev.spendingtracker.server.debt.computeBalances
import dev.spendingtracker.server.debt.netBalances
import dev.spendingtracker.server.debt.toExpenseFacts
import dev.spendingtracker.server.expense.loadExpenseLines
import dev.spendingtracker.server.expense.loadRefundedMinor
import dev.spendingtracker.server.income.dbValue
import dev.spendingtracker.server.category.dbValue as categoryDbValue
import dev.spendingtracker.server.income.loadDeductedMinor
import dev.spendingtracker.shared.api.BankRowSuggestion
import dev.spendingtracker.shared.api.Debt
import dev.spendingtracker.shared.api.DuplicateCandidate
import dev.spendingtracker.shared.api.CategoryKind
import dev.spendingtracker.shared.api.IncomeKind
import dev.spendingtracker.shared.api.RefundCandidate
import dev.spendingtracker.shared.api.RuleOutcome
import dev.spendingtracker.shared.api.SuggestedRecord
import dev.spendingtracker.shared.api.TransferCandidate
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.yearMonth
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
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
import kotlin.math.roundToLong
import kotlin.uuid.Uuid

/** Hints that don't say the money stayed in the household. */
private val NOT_MOVEMENT = setOf("Refund", "Money in", "Money in on a card: a refund?")

/** How far the two sides of a transfer may be apart, and how far their converted amounts may differ. */
private const val TRANSFER_DAYS = 4
private const val TRANSFER_PERCENT = 2

/** How far a hand-entered expense's date may be from the bank's (booking vs. purchase date). */
private const val DUPLICATE_DAYS = 3

/** How far back the expense a money-in row refunds may be, and how many candidates are offered. */
private const val REFUND_DAYS = 120
private const val REFUND_CANDIDATES = 5

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
    val accountRows = Accounts.select(Accounts.id, Accounts.ownerUserId, Accounts.kind, Accounts.iban, Accounts.interestTaxPercent)
        .where { Accounts.householdId eq householdId }.toList()
    val owners = accountRows.associate { it[Accounts.id] to it[Accounts.ownerUserId] }
    val cards = accountRows.filter { it[Accounts.kind] == "credit_card" }.map { it[Accounts.id] }.toSet()
    // What each savings account withholds from interest, in percent.
    val interestTax = accountRows.filter { it[Accounts.kind] == "savings" }
        .associate { it[Accounts.id] to (it[Accounts.interestTaxPercent] ?: DEFAULT_INTEREST_TAX_PERCENT) }
    // The account an IBAN belongs to: an account's own, else a feed's.
    val accountByIban = BankFeeds.select(BankFeeds.iban, BankFeeds.accountId).where { BankFeeds.householdId eq householdId }
        .mapNotNull { r -> r[BankFeeds.iban]?.let { it to r[BankFeeds.accountId] } }.toMap() +
        accountRows.mapNotNull { r -> r[Accounts.iban]?.let { it to r[Accounts.id] } }.toMap()
    val merchants = Merchants.selectAll().where { Merchants.householdId eq householdId }.toList()
    // The top-level income category interest is filed under: one with "interest" in its name.
    val interestCategory = Categories.select(Categories.id, Categories.name)
        .where {
            (Categories.householdId eq householdId) and Categories.parentId.isNull() and
                (Categories.kind eq CategoryKind.INCOME.categoryDbValue) and (Categories.archived eq false)
        }
        .firstOrNull { it[Categories.name].contains("interest", ignoreCase = true) }?.get(Categories.id)
    // The category of each payer's latest categorised earning, entered by hand or from the bank alike.
    val categoryBySource = Income.select(Income.sourceName, Income.categoryId)
        .where { (Income.householdId eq householdId) and Income.categoryId.isNotNull() }
        .orderBy(Income.earnedMonth to SortOrder.DESC, Income.date to SortOrder.DESC)
        .groupBy { it[Income.sourceName].trim().lowercase() }
        .mapValues { it.value.first()[Income.categoryId] }
    val merchantsByName = merchants.associateBy { it[Merchants.name].lowercase() }
    val merchantNames = merchants.associate { it[Merchants.id] to it[Merchants.name] }
    val memberNames = HouseholdMembers.join(Users, JoinType.INNER, HouseholdMembers.userId, Users.id)
        .select(HouseholdMembers.userId, Users.displayName, HouseholdMembers.legalName)
        .where { HouseholdMembers.householdId eq householdId }
        .map { r ->
            r[HouseholdMembers.userId] to listOfNotNull(
                r[Users.displayName],
                r[HouseholdMembers.legalName].takeIf { it.isNotBlank() },
            )
        }
    val owes = if (memberNames.size > 1) loadCurrentBalances(householdId) else emptyMap()

    // The latest decision per descriptor: what its expense or income line said.
    val pastExpenses = BankRows.join(Expenses, JoinType.INNER, BankRows.expenseId, Expenses.id)
        .select(BankRows.merchantKey, Expenses.merchantId, Expenses.categoryId, Expenses.paidByUserId, Expenses.date)
        .where { (BankRows.householdId eq householdId) and BankRows.expenseId.isNotNull() and BankRows.remember }
        .orderBy(Expenses.date to SortOrder.DESC)
        .groupBy { it[BankRows.merchantKey] }.mapValues { it.value.first() }
    val pastIncome = BankRows.join(Income, JoinType.INNER, BankRows.incomeId, Income.id)
        .select(
            BankRows.merchantKey, Income.sourceName, Income.categoryId, Income.recipientUserId, Income.date,
            Income.kind, Income.earnedMonth, Income.notes,
        )
        .where { (BankRows.householdId eq householdId) and BankRows.incomeId.isNotNull() and BankRows.remember }
        .orderBy(Income.date to SortOrder.DESC)
        .groupBy { it[BankRows.merchantKey] }.mapValues { it.value.first() }
    val pastContributions = BankRows.join(AssetContributions, JoinType.INNER, BankRows.contributionId, AssetContributions.id)
        .select(BankRows.merchantKey, AssetContributions.assetId, AssetContributions.date)
        .where { (BankRows.householdId eq householdId) and BankRows.contributionId.isNotNull() and BankRows.remember }
        .orderBy(AssetContributions.date to SortOrder.DESC)
        .groupBy { it[BankRows.merchantKey] }.mapValues { it.value.first() }

    val dates = rows.map { it[BankRows.date] }
    // The other sides of transfers: pending rows around these dates, loaded once for the whole list.
    val pool = rowsWithFeed().where {
        (BankRows.householdId eq householdId) and pendingRows() and
            (BankRows.date greaterEq dates.min().minus(DatePeriod(days = TRANSFER_DAYS))) and
            (BankRows.date lessEq dates.max().plus(DatePeriod(days = TRANSFER_DAYS)))
    }.toList()
    val fx = FxTable.load((rows + pool).map { it[BankRows.currency] }.filter { it != householdCurrency }.toSet() + householdCurrency)
    val evidence = TransferEvidence.load(householdId)
    val otherSides = otherSides(rows, pool, householdCurrency, fx, evidence, owners, owes)
    val (linkedExpenses, linkedIncome) = linkedRecords(householdId)
    // Records from before the household's bank data starts (the old app's ledger) have no bank rows on purpose, so no
    // bank row is a duplicate of one: income dated the 1st would otherwise catch next month's payments.
    val first = listOfNotNull(dates.min().minus(DatePeriod(days = INCOME_MONTH_DAYS)), startOfBankData(householdId, null)).max()
    val last = dates.max().plus(DatePeriod(days = INCOME_MONTH_DAYS))
    val expenses = Expenses.join(Merchants, JoinType.LEFT, Expenses.merchantId, Merchants.id)
        .select(Expenses.id, Expenses.date, Expenses.amountMinor, Expenses.accountId, Merchants.name)
        .where { (Expenses.householdId eq householdId) and (Expenses.date greaterEq first) and (Expenses.date lessEq last) }
        .filter { it[Expenses.id] !in linkedExpenses }
    val incomeLines = Income.selectAll()
        .where { (Income.householdId eq householdId) and (Income.date greaterEq first) and (Income.date lessEq last) }
        .filter { it[Income.id] !in linkedIncome }
    val deductedByEarning = loadDeductedMinor(
        householdId,
        incomeLines.filter { it[Income.kind] == IncomeKind.EARNING.dbValue }.map { it[Income.id] },
    )

    // Money in may refund an expense of the past REFUND_DAYS: positive expenses that aren't refunds themselves, and the
    // ones some bank row with the same descriptor was made into.
    val refundPool = if (rows.none { it[BankRows.amountMinor] > 0 }) emptyList() else {
        Expenses.join(Merchants, JoinType.LEFT, Expenses.merchantId, Merchants.id)
            .select(Expenses.id, Expenses.date, Expenses.amountMinor, Expenses.merchantId, Merchants.name)
            .where {
                (Expenses.householdId eq householdId) and Expenses.refundOf.isNull() and (Expenses.amountMinor greater 0L) and
                    (Expenses.date greaterEq dates.min().minus(DatePeriod(days = REFUND_DAYS))) and (Expenses.date lessEq dates.max())
            }.toList()
    }
    val refunded = loadRefundedMinor(householdId, refundPool.map { it[Expenses.id] })
    val expensesByKey = if (refundPool.isEmpty()) emptyMap() else {
        BankRows.select(BankRows.merchantKey, BankRows.expenseId)
            .where { (BankRows.householdId eq householdId) and BankRows.expenseId.isNotNull() and BankRows.remember }
            .groupBy({ it[BankRows.merchantKey] }, { it[BankRows.expenseId]!! })
            .mapValues { it.value.toSet() }
    }

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
        val interest = row[BankRows.hint] == INTEREST_HINT && native > 0
        val refund = !interest && (
            row[BankRows.code] == "CARD_REFUND" ||
                (!movement && (accountId in cards || (key in pastExpenses && key !in pastIncome)))
            )
        val rule = rules.firstMatch(row)
        val past = pastExpenses[key]
        val pastSource = pastIncome[key]
        // Without a rule, money out whose descriptor last became a deduction (an insurance advance filed by hand) is
        // one again: its source, whose, notes and how far its month was from the payment's carry over.
        val pastDeduction = pastSource?.takeIf { p ->
            rule == null && native < 0 && key.isNotEmpty() && p[Income.kind] == IncomeKind.DEDUCTION.dbValue &&
                (past == null || past[Expenses.date] <= p[Income.date])
        }
        // A rule can say the row is the other kind than the guess: money in as an expense (a refund), or out as income.
        val expenseOrIncome = when (rule?.outcome) {
            RuleOutcome.EXPENSE -> SuggestedRecord.EXPENSE
            RuleOutcome.INCOME, RuleOutcome.DEDUCTION -> SuggestedRecord.INCOME
            else -> if (pastDeduction == null && (native < 0 || refund)) SuggestedRecord.EXPENSE else SuggestedRecord.INCOME
        }
        val forDeduction = rule?.outcome == RuleOutcome.DEDUCTION || pastDeduction != null
        val text = row[BankRows.text]
        val owner = owners[accountId]

        val guessName = cleanDescriptor(text).ifEmpty { text }
        val known = merchantsByName[guessName.lowercase()]
        val candidates = otherSides[row[BankRows.id]].orEmpty().map { other ->
            TransferCandidate(
                other[BankRows.id].toString(), other[BankRows.version], other[BankFeeds.accountId].toString(),
                other[BankRows.date], other[BankRows.amountMinor], other[BankRows.currency], other[BankRows.text],
            )
        }
        val counterpartyAccountId = row[BankRows.counterpartyIban]?.let(accountByIban::get)?.takeIf { it != accountId }
        // With no other side yet, the account the money came from or went to: by IBAN, else by the card that paid.
        val ownAccount = (counterpartyAccountId ?: evidence.cardAccount(row)?.takeIf { it != accountId })?.takeIf { candidates.isEmpty() }
        val isMovement = row[BankRows.hint].removePrefix("Old app: ") in MOVEMENT_HINTS
        val isMemberHint = row[BankRows.hint].removePrefix("Old app: ") == MEMBER_NAME_HINT &&
            row[BankRows.code] != "CARD_PAYMENT"
        val settlementOther = if (owner == null || native == 0L) null else {
            if (candidates.isNotEmpty()) {
                owners[Uuid.parse(candidates.first().accountId)]?.takeIf { it != owner }
            } else {
                val byIban = counterpartyAccountId?.let { owners[it] }?.takeIf { it != owner }
                val byHint = if (byIban == null && isMemberHint) {
                    val textToCheck = row[BankRows.counterpartyName].ifBlank { text }
                    memberNames.filter { (uid, names) -> uid != owner && isHouseholdName(textToCheck, names) }
                        .singleOrNull()?.first
                } else {
                    null
                }
                byIban ?: byHint
            }
        }
        val guessedMerchant = past?.get(Expenses.merchantId)?.let(merchantNames::get) ?: known?.get(Merchants.name) ?: guessName
        val guessedCategory = (past?.get(Expenses.categoryId) ?: known?.get(Merchants.defaultCategoryId))
        // What a rule fills in: its merchant and category (else that merchant's default), and whose from the account unless it names a person.
        val forExpense = rule?.outcome == RuleOutcome.EXPENSE
        val forIncome = rule?.outcome == RuleOutcome.INCOME
        val ruleMerchant = if (forExpense && rule!!.merchant.isNotBlank()) rule.merchant else null
        val merchant = ruleMerchant ?: guessedMerchant
        val category = when {
            forDeduction -> null
            forIncome -> rule!!.categoryId ?: pastSource?.get(Income.categoryId)
            forExpense -> rule!!.categoryId
                ?: (if (ruleMerchant != null) merchantsByName[ruleMerchant.lowercase()]?.get(Merchants.defaultCategoryId) else guessedCategory)
            expenseOrIncome == SuggestedRecord.INCOME -> pastSource?.get(Income.categoryId)
            else -> guessedCategory
        }
        // A record entered by hand is the same one only if its merchant or source has a name in common with the row:
        // the same amount a few days apart isn't enough.
        val rowWords = words(listOf(text, row[BankRows.counterpartyName], row[BankRows.message]).joinToString(" "))
        val incomeSource = (if (forIncome || forDeduction) rule?.incomeSource?.ifBlank { null } else null)
            ?: pastSource?.get(Income.sourceName) ?: (if (interest) "Interest" else null) ?: row[BankRows.counterpartyName].ifEmpty { text }
        // Else an earning takes the category its payer's last one had, and interest the household's interest category.
        val incomeCategory = if (forDeduction || (rule != null && !forIncome) || expenseOrIncome != SuggestedRecord.INCOME) null else {
            categoryBySource[incomeSource.trim().lowercase()] ?: (if (interest) interestCategory else null)
        }
        // Interest arrives net: the bank kept [rate]% of the gross, so gross = net * 100 / (100 - rate).
        val withheldTax = if (!interest || rule != null || converted == null) null else {
            val rate = interestTax[accountId] ?: 0
            if (rate == 0) null else (converted * 100.0 / (100 - rate)).roundToLong() - converted
        }
        val duplicates = when (expenseOrIncome) {
            SuggestedRecord.EXPENSE -> {
                val amount = converted?.let { -it }
                expenses.filter { e ->
                    (e[Expenses.accountId] == null || e[Expenses.accountId] == accountId) &&
                        abs(date.daysUntil(e[Expenses.date])) <= DUPLICATE_DAYS &&
                        amount != null && closeEnough(e[Expenses.amountMinor], amount, currency != householdCurrency) &&
                        alike(e.getOrNull(Merchants.name).orEmpty(), merchant, rowWords)
                }.sortedBy { abs(date.daysUntil(it[Expenses.date])) }.take(3).map { e ->
                    DuplicateCandidate(SuggestedRecord.EXPENSE, e[Expenses.id].toString(), e[Expenses.date], e[Expenses.amountMinor], e.getOrNull(Merchants.name).orEmpty())
                }
            }
            else -> incomeLines.filter { i ->
                val gap = date.daysUntil(i[Income.date])
                // A deduction is money out booked as a positive line; an earning is what came in (net of linked deductions).
                val target = if (forDeduction) converted?.let { -it } else converted
                val gross = i[Income.amountMinor]
                val deducted = if (i[Income.kind] == IncomeKind.EARNING.dbValue) deductedByEarning[i[Income.id]] ?: 0L else 0L
                (i[Income.kind] == IncomeKind.DEDUCTION.dbValue) == forDeduction &&
                    (i[Income.accountId] == null || i[Income.accountId] == accountId) &&
                    (abs(gap) <= INCOME_DAYS || (i[Income.date].day == 1 && gap <= 0 && -gap <= INCOME_MONTH_DAYS)) &&
                    alike(i[Income.sourceName], incomeSource, rowWords) &&
                    target != null && (
                        closeEnough(gross, target, currency != householdCurrency) ||
                            (deducted != 0L && closeEnough(gross - deducted, target, currency != householdCurrency))
                        )
            }.sortedBy { abs(date.daysUntil(it[Income.date])) }.take(3).map { i ->
                val deducted = if (i[Income.kind] == IncomeKind.EARNING.dbValue) deductedByEarning[i[Income.id]] ?: 0L else 0L
                DuplicateCandidate(SuggestedRecord.INCOME, i[Income.id].toString(), i[Income.date], i[Income.amountMinor] - deducted, i[Income.sourceName])
            }
        }

        val refundCandidates = if (native <= 0 || converted == null || (rule != null && !forExpense)) emptyList() else {
            val merchantId = merchantsByName[merchant.lowercase()]?.get(Merchants.id)
            val sameKey = if (key.isEmpty()) emptySet() else expensesByKey[key].orEmpty()
            val oldest = date.minus(DatePeriod(days = REFUND_DAYS))
            refundPool.asSequence()
                .filter { e ->
                    e[Expenses.date] in oldest..date && e[Expenses.amountMinor] >= converted &&
                        ((merchantId != null && e[Expenses.merchantId] == merchantId) || e[Expenses.id] in sameKey)
                }
                .sortedWith(compareByDescending<ResultRow> { it[Expenses.date] }.thenBy { it[Expenses.id].toString() })
                .take(REFUND_CANDIDATES)
                .map { e ->
                    RefundCandidate(
                        e[Expenses.id].toString(), e[Expenses.date], e[Expenses.amountMinor], refunded[e[Expenses.id]] ?: 0L,
                        e.getOrNull(Merchants.name).orEmpty(),
                    )
                }.toList()
        }
        // The last row with this descriptor went into an asset, and nothing with it was decided otherwise since.
        val pastContribution = pastContributions[key]?.takeIf { c ->
            val since = c[AssetContributions.date]
            key.isNotEmpty() &&
                (past?.get(Expenses.date)?.let { it <= since } ?: true) &&
                (pastSource?.get(Income.date)?.let { it <= since } ?: true)
        }
        val record = when (rule?.outcome) {
            RuleOutcome.TRANSFER -> SuggestedRecord.TRANSFER
            RuleOutcome.SETTLEMENT -> SuggestedRecord.SETTLEMENT
            RuleOutcome.CONTRIBUTION -> SuggestedRecord.CONTRIBUTION
            RuleOutcome.EXPENSE, RuleOutcome.INCOME, RuleOutcome.DEDUCTION, RuleOutcome.SKIP -> expenseOrIncome
            else -> when {
                pastDeduction != null -> expenseOrIncome
                pastContribution != null -> SuggestedRecord.CONTRIBUTION
                owner != null && settlementOther != null -> SuggestedRecord.SETTLEMENT
                candidates.isNotEmpty() || ownAccount != null || isMovement -> SuggestedRecord.TRANSFER
                else -> expenseOrIncome
            }
        }
        val (settleFrom, settleTo) = when {
            rule?.outcome == RuleOutcome.SETTLEMENT ->
                rule.settleFromUserId?.toString() to rule.settleToUserId?.toString()
            record == SuggestedRecord.SETTLEMENT && owner != null && settlementOther != null ->
                if (native < 0) owner.toString() to settlementOther.toString()
                else settlementOther.toString() to owner.toString()
            else -> null to null
        }
        row[BankRows.id] to BankRowSuggestion(
            record = record,
            amountMinor = converted?.let {
                when {
                    record == SuggestedRecord.SETTLEMENT -> abs(it)
                    // Money out is put into the asset, money in taken out of it.
                    record == SuggestedRecord.CONTRIBUTION -> -it
                    expenseOrIncome == SuggestedRecord.EXPENSE || forDeduction -> -it
                    else -> it
                }
            },
            originalAmountMinor = if (currency == householdCurrency) null else abs(native),
            originalCurrency = if (currency == householdCurrency) null else currency,
            merchant = merchant,
            categoryId = (category ?: incomeCategory)?.toString(),
            paidByUserId = when {
                pastDeduction != null -> pastDeduction[Income.recipientUserId] ?: owner
                forExpense || forIncome || forDeduction -> rule!!.personUserId ?: owner
                past != null -> past[Expenses.paidByUserId]
                else -> owner
            }?.toString(),
            incomeSource = incomeSource,
            duplicates = duplicates,
            transferCandidates = candidates,
            transferAccountId = (if (rule?.outcome == RuleOutcome.TRANSFER) rule.otherAccountId?.takeIf { it != accountId } else null)?.toString()
                ?: ownAccount?.toString(),
            settleFromUserId = settleFrom,
            settleToUserId = settleTo,
            ruleId = rule?.id?.toString(),
            ruleName = rule?.name,
            skipReason = rule?.takeIf { it.outcome == RuleOutcome.SKIP }?.skipReason,
            incomeKind = if (forDeduction) IncomeKind.DEDUCTION else IncomeKind.EARNING,
            earnedMonth = when {
                pastDeduction != null -> {
                    val pastDate = pastDeduction[Income.date].yearMonth
                    val pastMonth = pastDeduction[Income.earnedMonth].yearMonth
                    val offset = (pastMonth.year - pastDate.year) * 12 + (pastMonth.month.ordinal - pastDate.month.ordinal)
                    date.yearMonth.plus(offset, DateTimeUnit.MONTH).toString()
                }
                forDeduction -> date.yearMonth.plus(rule!!.monthOffset, DateTimeUnit.MONTH).toString()
                else -> null
            },
            // An income line takes the notes of the last one of the same kind made from this descriptor (what the work was).
            notes = pastSource?.takeIf { p ->
                record == SuggestedRecord.INCOME && (p[Income.kind] == IncomeKind.DEDUCTION.dbValue) == forDeduction
            }?.get(Income.notes)?.ifBlank { null },
            refundCandidates = refundCandidates,
            withheldTaxMinor = withheldTax,
            assetId = when {
                rule?.outcome == RuleOutcome.CONTRIBUTION -> rule.assetId
                record == SuggestedRecord.CONTRIBUTION -> pastContribution?.get(AssetContributions.assetId)
                else -> null
            }?.toString(),
        )
    }
}

/**
 * The other sides of [rows]' transfers among [pool]: pending rows on another feed, opposite in sign, within
 * [TRANSFER_DAYS], the same money, and backed by [evidence]. Rows are paired one to one, the best-evidenced pairs
 * first and then the nearest in time, so ten identical top-ups pair with ten identical card payments rather than all
 * with the same one. A row lists its partner first, then the candidates nobody took.
 */
private fun otherSides(
    rows: List<ResultRow>,
    pool: List<ResultRow>,
    householdCurrency: String,
    fx: FxTable,
    evidence: TransferEvidence,
    owners: Map<Uuid, Uuid?>,
    owes: Map<Pair<String, String>, Long>,
): Map<Uuid, List<ResultRow>> {
    /**
     * How [row] ranks [other]: the stronger evidence first (an IBAN or a known card outweighs a day or two, as every
     * candidate is within [TRANSFER_DAYS] anyway), then the nearest, then a debt it would settle, then by id.
     */
    fun order(row: ResultRow) = compareBy<ResultRow>(
        { -evidence.strength(row, it) },
        { abs(row[BankRows.date].daysUntil(it[BankRows.date])) },
        { !candidateBalanceMatches(row, it, owners, owes) },
        { it[BankRows.id].toString() },
    )
    val candidates = rows.associate { row ->
        val date = row[BankRows.date]
        row[BankRows.id] to pool.filter { other ->
            other[BankRows.id] != row[BankRows.id] && other[BankRows.feedId] != row[BankRows.feedId] &&
                (other[BankRows.amountMinor] > 0) != (row[BankRows.amountMinor] > 0) &&
                abs(date.daysUntil(other[BankRows.date])) <= TRANSFER_DAYS &&
                sameMoney(row, other, householdCurrency, fx) && evidence.supports(row, other)
        }.sortedWith(order(row))
    }
    // Pair greedily, best pairs first by the same order seen from the money-out side (so both sides agree); a pool row
    // outside [rows] can still be taken once.
    val byId = (rows + pool).associateBy { it[BankRows.id] }
    val partner = HashMap<Uuid, Uuid>()
    val pairs = candidates.flatMap { (id, list) ->
        list.map { other ->
            val row = byId.getValue(id)
            if (row[BankRows.amountMinor] < 0) row to other else other to row
        }
    }.distinctBy { (out, inn) -> out[BankRows.id] to inn[BankRows.id] }
    pairs.sortedWith(
        compareBy<Pair<ResultRow, ResultRow>>(
            { (out, inn) -> -evidence.strength(out, inn) },
            { (out, inn) -> abs(out[BankRows.date].daysUntil(inn[BankRows.date])) },
            { (out, inn) -> !candidateBalanceMatches(out, inn, owners, owes) },
            { (out, _) -> out[BankRows.date] },
            { (out, inn) -> out[BankRows.id].toString() + inn[BankRows.id].toString() },
        ),
    ).forEach { (out, inn) ->
        val a = out[BankRows.id]
        val b = inn[BankRows.id]
        if (a !in partner && b !in partner) {
            partner[a] = b
            partner[b] = a
        }
    }
    // A row's own partner comes first, then what nobody else took: more than one means a person has to choose.
    return candidates.mapValues { (id, list) ->
        val mine = partner[id]
        (list.filter { it[BankRows.id] == mine } + list.filter { it[BankRows.id] !in partner }).take(3)
    }
}

/** Words that say nothing about who a payment was to or from. */
private val FILLER_WORDS = setOf(
    "SRO", "AS", "CZ", "CZE", "CZK", "EUR", "THE", "AND", "PRAHA", "PRAGUE", "BRNO", "IRL", "DUBLIN", "PLATBA",
    "KARTOU", "BEZKONTAKTNI", "VAM", "DEKUJE", "ZA", "NAKUP", "WWW", "COM", "PAY", "APPLE", "GOOGLE",
)

/** The telling words of [text], without diacritics: at least three letters, no digits, no filler. */
private fun words(text: String): Set<String> =
    normalizeText(text).split(' ').filter { it.length >= 3 && it.none(Char::isDigit) && it !in FILLER_WORDS }.toSet()

/** A record's [name] has a word in common with the bank row ([rowWords]) or is the merchant or source it suggests ([suggested]). */
private fun alike(name: String, suggested: String, rowWords: Set<String>): Boolean =
    name.isNotBlank() && (name.equals(suggested, ignoreCase = true) || words(name).any { it in rowWords })

/** Current net debts between members: `(fromUserId, toUserId)` -> positive amount owed. */
private fun loadCurrentBalances(householdId: Uuid): Map<Pair<String, String>, Long> =
    computeBalances(householdId).balances.associate { (it.fromUserId to it.toUserId) to it.amountMinor }

/** True when [other] is on another member's personal account and the current balance points the same way as this movement. */
private fun candidateBalanceMatches(
    row: ResultRow,
    other: ResultRow,
    owners: Map<Uuid, Uuid?>,
    owes: Map<Pair<String, String>, Long>,
): Boolean {
    val owner = owners[row[BankFeeds.accountId]] ?: return false
    val otherOwner = owners[other[BankFeeds.accountId]] ?: return false
    if (owner == otherOwner) return false
    val native = row[BankRows.amountMinor]
    val from = if (native < 0) owner else otherOwner
    val to = if (native < 0) otherOwner else owner
    return (owes[from.toString() to to.toString()] ?: 0L) > 0L
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
