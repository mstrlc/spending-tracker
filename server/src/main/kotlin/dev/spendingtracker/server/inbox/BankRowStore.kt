package dev.spendingtracker.server.inbox

import dev.spendingtracker.server.common.badRequest
import dev.spendingtracker.server.common.conflict
import dev.spendingtracker.server.common.notFound
import dev.spendingtracker.server.common.parseId
import dev.spendingtracker.server.common.versionConflict
import dev.spendingtracker.server.db.Accounts
import dev.spendingtracker.server.db.BankFeeds
import dev.spendingtracker.server.db.BankRows
import dev.spendingtracker.server.db.Expenses
import dev.spendingtracker.server.db.HouseholdMembers
import dev.spendingtracker.server.db.Income
import dev.spendingtracker.server.db.Settlements
import dev.spendingtracker.server.db.Transfers
import dev.spendingtracker.server.db.Users
import dev.spendingtracker.shared.api.AccountKind
import dev.spendingtracker.shared.api.BankRow
import dev.spendingtracker.shared.api.BankRowOutcome
import dev.spendingtracker.shared.api.BankRowRef
import dev.spendingtracker.shared.api.BankRowSuggestion
import kotlinx.datetime.LocalDate
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** What the household knows for hints: its accounts' and feeds' IBANs, and its members' names. Call inside a transaction. */
fun loadHintContext(householdId: Uuid): HintContext {
    val ibans = Accounts.select(Accounts.iban).where { Accounts.householdId eq householdId }.mapNotNull { it[Accounts.iban] } +
        BankFeeds.select(BankFeeds.iban).where { BankFeeds.householdId eq householdId }.mapNotNull { it[BankFeeds.iban] }
    val names = HouseholdMembers.join(Users, JoinType.INNER, HouseholdMembers.userId, Users.id)
        .select(Users.displayName, HouseholdMembers.legalName).where { HouseholdMembers.householdId eq householdId }
        .flatMap { listOfNotNull(it[Users.displayName], it[HouseholdMembers.legalName].takeIf { n -> n.isNotBlank() }) }
    return HintContext(ibans.toSet(), names)
}

/** How a feed's rows are read: its bank, and whether its account is a credit card. Call inside a transaction. */
fun feedShape(institution: String, accountId: Uuid): FeedShape {
    val kind = Accounts.select(Accounts.kind).where { Accounts.id eq accountId }.single()[Accounts.kind]
    return FeedShape(bankInstitution(institution), kind == AccountKind.CREDIT_CARD.name.lowercase())
}

/** [addedIds]: the external ids of the rows added. */
data class StoredCounts(val added: Int, val existing: Int, val beforeImportFrom: Int, val addedIds: Set<String>)

/**
 * Adds [rows] to a feed, leaving out those it has already (same external id) and those booked before [importFrom].
 * [hints] can give a row a hint of its own (the old app's decision); otherwise the reader's applies. Call inside a transaction.
 */
private data class ExistingRowKey(val bookingDate: LocalDate, val amountMinor: Long, val currency: String)
private class ExistingRowRef(val externalId: String, val counterpartyIban: String?, val normalizedText: String)

fun storeRows(
    householdId: Uuid,
    feedId: Uuid,
    shape: FeedShape,
    importFrom: LocalDate?,
    rows: List<ParsedRow>,
    context: HintContext,
    hints: Map<String, String> = emptyMap(),
): StoredCounts {
    val existing = BankRows
        .select(BankRows.externalId, BankRows.bookingDate, BankRows.amountMinor, BankRows.currency, BankRows.counterpartyIban, BankRows.text)
        .where { BankRows.feedId eq feedId }
        .toList()
    val known = existing.map { it[BankRows.externalId] }.toMutableSet()
    val matchedExternalIds = rows.map { it.externalId }.filter { it in known }.toSet()
    val pool = existing
        .filter { it[BankRows.externalId] !in matchedExternalIds }
        .groupBy { ExistingRowKey(it[BankRows.bookingDate], it[BankRows.amountMinor], it[BankRows.currency]) }
        .mapValues { (_, list) ->
            list.map {
                ExistingRowRef(it[BankRows.externalId], it[BankRows.counterpartyIban], normalizeText(it[BankRows.text]))
            }.toMutableList()
        }

    val before = rows.count { importFrom != null && it.bookingDate < importFrom }
    val fresh = rows.filter { row ->
        if (importFrom != null && row.bookingDate < importFrom) return@filter false
        if (!known.add(row.externalId)) return@filter false
        // Also recognize the same transaction imported earlier from a different format (e.g. CSV vs GoCardless).
        val candidates = pool[ExistingRowKey(row.bookingDate, row.amountMinor, row.currency)]
        if (candidates != null) {
            val normText = normalizeText(row.text)
            val idx = candidates.indexOfFirst { cand ->
                (row.counterpartyIban != null && row.counterpartyIban == cand.counterpartyIban) ||
                    (normText.isNotEmpty() && normText == cand.normalizedText)
            }
            if (idx >= 0) {
                candidates.removeAt(idx)
                return@filter false
            }
        }
        true
    }
    BankRows.batchInsert(fresh, shouldReturnGeneratedValues = false) { row ->
        this[BankRows.id] = Uuid.random()
        this[BankRows.householdId] = householdId
        this[BankRows.feedId] = feedId
        this[BankRows.externalId] = row.externalId
        this[BankRows.bookingDate] = row.bookingDate
        this[BankRows.valueDate] = row.valueDate
        this[BankRows.date] = row.date
        this[BankRows.amountMinor] = row.amountMinor
        this[BankRows.currency] = row.currency
        this[BankRows.counterpartyName] = row.counterpartyName
        this[BankRows.counterpartyIban] = row.counterpartyIban
        this[BankRows.counterpartyAccount] = row.counterpartyAccount
        this[BankRows.text] = row.text
        this[BankRows.message] = row.message
        this[BankRows.merchantKey] = merchantKey(row.text).ifEmpty { row.counterpartyIban.orEmpty() }
        this[BankRows.code] = row.code
        this[BankRows.hint] = hints[row.externalId] ?: hintFor(row, shape, context)
        this[BankRows.raw] = row.raw.toString()
    }
    return StoredCounts(fresh.size, rows.size - fresh.size - before, before, fresh.map { it.externalId }.toSet())
}

/** Rows nothing was decided about: the inbox. */
fun pendingRows(): Op<Boolean> = BankRows.expenseId.isNull() and BankRows.incomeId.isNull() and
    BankRows.transferId.isNull() and BankRows.settlementId.isNull() and BankRows.skipReason.isNull()

fun ResultRow.isPending() = outcome() == BankRowOutcome.PENDING

fun ResultRow.outcome() = when {
    this[BankRows.expenseId] != null -> BankRowOutcome.EXPENSE
    this[BankRows.incomeId] != null -> BankRowOutcome.INCOME
    this[BankRows.transferId] != null -> BankRowOutcome.TRANSFER
    this[BankRows.settlementId] != null -> BankRowOutcome.SETTLEMENT
    this[BankRows.skipReason] != null -> BankRowOutcome.SKIPPED
    else -> BankRowOutcome.PENDING
}

/** A row of BankRows joined with BankFeeds. */
fun ResultRow.toBankRow(suggestion: BankRowSuggestion? = null, outcomeLabel: String? = null) = BankRow(
    id = this[BankRows.id].toString(),
    feedId = this[BankRows.feedId].toString(),
    accountId = this[BankFeeds.accountId].toString(),
    date = this[BankRows.date],
    bookingDate = this[BankRows.bookingDate],
    amountMinor = this[BankRows.amountMinor],
    currency = this[BankRows.currency],
    counterpartyName = this[BankRows.counterpartyName],
    counterpartyIban = this[BankRows.counterpartyIban],
    counterpartyAccount = this[BankRows.counterpartyAccount],
    text = this[BankRows.text],
    message = this[BankRows.message],
    code = this[BankRows.code],
    hint = this[BankRows.hint],
    merchantKey = this[BankRows.merchantKey],
    outcome = outcome(),
    expenseId = this[BankRows.expenseId]?.toString(),
    incomeId = this[BankRows.incomeId]?.toString(),
    transferId = this[BankRows.transferId]?.toString(),
    settlementId = this[BankRows.settlementId]?.toString(),
    skipReason = this[BankRows.skipReason],
    decidedByUserId = this[BankRows.decidedBy]?.toString(),
    decidedAuto = this[BankRows.decidedAuto],
    ruleId = this[BankRows.ruleId]?.toString(),
    decidedAt = this[BankRows.decidedAt]?.toString(),
    version = this[BankRows.version],
    suggestion = suggestion,
    outcomeLabel = outcomeLabel,
)

fun rowsWithFeed() = BankRows.join(BankFeeds, JoinType.INNER, BankRows.feedId, BankFeeds.id)
    .select(BankRows.columns + BankFeeds.accountId)

/** Loads [refs] for a decision, checking each exists in the household and is at the version sent. Call inside a transaction. */
fun lockRows(householdId: Uuid, refs: List<BankRowRef>, field: String = "rows"): List<ResultRow> {
    if (refs.isEmpty()) throw badRequest("no_rows", "Pick at least one bank row", field)
    val ids = refs.map { parseId(it.id, field)!! }
    if (ids.toSet().size != ids.size) throw badRequest("duplicate_rows", "A bank row is listed twice", field)
    val rows = rowsWithFeed().where { (BankRows.householdId eq householdId) and (BankRows.id inList ids) }
        .forUpdate().associateBy { it[BankRows.id] }
    return refs.zip(ids).map { (ref, id) ->
        val row = rows[id] ?: throw notFound("Bank row")
        if (row[BankRows.version] != ref.version) throw versionConflict()
        row
    }
}

/**
 * Files [refs] under an expense, income line, transfer or settlement (one of the four ids), or with a [skipReason]. [created]: the
 * record was made from these rows just now, so undoing them all deletes it. [auto]: a rule ([ruleId]) or the matcher decided,
 * not [userId]. Call inside a transaction.
 */
fun fileBankRows(
    householdId: Uuid,
    refs: List<BankRowRef>,
    userId: Uuid,
    expenseId: Uuid? = null,
    incomeId: Uuid? = null,
    transferId: Uuid? = null,
    settlementId: Uuid? = null,
    created: Boolean = false,
    skipReason: String? = null,
    auto: Boolean = false,
    ruleId: Uuid? = null,
) {
    if (refs.isEmpty()) return
    val rows = lockRows(householdId, refs, "bankRows")
    if (rows.any { it.outcome() != BankRowOutcome.PENDING }) {
        throw conflict("already_decided", "A bank row was already decided. Reload the inbox.", "bankRows")
    }
    val now = Clock.System.now()
    BankRows.update({ BankRows.id inList rows.map { it[BankRows.id] } }) {
        it[BankRows.expenseId] = expenseId
        it[BankRows.incomeId] = incomeId
        it[BankRows.transferId] = transferId
        it[BankRows.settlementId] = settlementId
        it[BankRows.skipReason] = skipReason
        it[createdRecord] = created
        it[decidedBy] = if (auto) null else userId
        it[decidedAuto] = auto
        it[BankRows.ruleId] = ruleId
        it[decidedAt] = now
        it[version] = BankRows.version + 1
        it[updatedAt] = now
    }
}

/** Puts decided rows back in the inbox, deleting records made from them once none of their rows is left. Call inside a transaction. */
fun undoBankRows(householdId: Uuid, refs: List<BankRowRef>) {
    val rows = lockRows(householdId, refs)
    if (rows.any { it.outcome() == BankRowOutcome.PENDING }) {
        throw conflict("not_decided", "A bank row is already in the inbox. Reload the inbox.", "rows")
    }
    val ids = rows.map { it[BankRows.id] }.toSet()
    val now = Clock.System.now()
    BankRows.update({ BankRows.id inList ids }) {
        it[expenseId] = null
        it[incomeId] = null
        it[transferId] = null
        it[settlementId] = null
        it[skipReason] = null
        it[createdRecord] = false
        it[decidedBy] = null
        it[decidedAuto] = false
        it[BankRows.ruleId] = null
        it[decidedAt] = null
        it[version] = BankRows.version + 1
        it[updatedAt] = now
    }
    // Records made from these rows go once none of their rows is left.
    deleteOrphans(rows, BankRows.expenseId) { gone -> Expenses.deleteWhere { (Expenses.householdId eq householdId) and (Expenses.id inList gone) } }
    deleteOrphans(rows, BankRows.incomeId) { gone -> Income.deleteWhere { (Income.householdId eq householdId) and (Income.id inList gone) } }
    deleteOrphans(rows, BankRows.transferId) { gone -> Transfers.deleteWhere { (Transfers.householdId eq householdId) and (Transfers.id inList gone) } }
    deleteOrphans(rows, BankRows.settlementId) { gone -> Settlements.deleteWhere { (Settlements.householdId eq householdId) and (Settlements.id inList gone) } }
}

/** Deletes (with [delete]) the records [rows] were filed under by [column] that were made from them and that no other row is filed under. */
private fun deleteOrphans(rows: List<ResultRow>, column: Column<Uuid?>, delete: (Set<Uuid>) -> Unit) {
    val created = rows.filter { it[BankRows.createdRecord] }.mapNotNull { it[column] }.toSet()
    if (created.isEmpty()) return
    val stillUsed = BankRows.select(column).where { column inList created }.mapNotNull { it[column] }.toSet()
    (created - stillUsed).takeIf { it.isNotEmpty() }?.let(delete)
}

/** The expenses and income lines some bank row is filed under. Call inside a transaction. */
fun linkedRecords(householdId: Uuid): Pair<Set<Uuid>, Set<Uuid>> {
    val rows = BankRows.select(BankRows.expenseId, BankRows.incomeId)
        .where { (BankRows.householdId eq householdId) and (BankRows.expenseId.isNotNull() or BankRows.incomeId.isNotNull()) }
        .toList()
    return rows.mapNotNull { it[BankRows.expenseId] }.toSet() to rows.mapNotNull { it[BankRows.incomeId] }.toSet()
}
