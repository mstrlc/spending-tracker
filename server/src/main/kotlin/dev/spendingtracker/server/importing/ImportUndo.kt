package dev.spendingtracker.server.importing

import dev.spendingtracker.server.common.conflict
import dev.spendingtracker.server.common.notFound
import dev.spendingtracker.server.db.Accounts
import dev.spendingtracker.server.db.AssetContributions
import dev.spendingtracker.server.db.AssetFees
import dev.spendingtracker.server.db.AssetValuations
import dev.spendingtracker.server.db.Assets
import dev.spendingtracker.server.db.BankFeeds
import dev.spendingtracker.server.db.BankRows
import dev.spendingtracker.server.db.Categories
import dev.spendingtracker.server.db.ExpenseLines
import dev.spendingtracker.server.db.Expenses
import dev.spendingtracker.server.db.HouseholdInvites
import dev.spendingtracker.server.db.HouseholdMembers
import dev.spendingtracker.server.db.Imports
import dev.spendingtracker.server.db.InboxRules
import dev.spendingtracker.server.db.Income
import dev.spendingtracker.server.db.Merchants
import dev.spendingtracker.server.db.Settlements
import dev.spendingtracker.server.db.SplitRules
import dev.spendingtracker.server.db.SplitShares
import dev.spendingtracker.server.db.Transfers
import dev.spendingtracker.server.db.Trips
import dev.spendingtracker.server.db.Users
import dev.spendingtracker.shared.api.ImportCounts
import dev.spendingtracker.shared.api.ImportSummary
import dev.spendingtracker.shared.api.ImportUndoResult
import dev.spendingtracker.shared.api.KeptImportItem
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.uuid.Uuid

internal class Tally {
    var members = 0
    var accounts = 0
    var categories = 0
    var trips = 0
    var merchants = 0
    var expenses = 0
    var income = 0
    var assets = 0
    var contributions = 0
    var bankFeeds = 0
    var bankRows = 0

    fun counts() = ImportCounts(members, accounts, categories, trips, merchants, expenses, income, assets, contributions, bankFeeds, bankRows)
}

/** Lists the household's imports, newest first. Call inside a transaction. */
internal fun listImports(householdId: Uuid): List<ImportSummary> {
    val rows = Imports.selectAll()
        .where { Imports.householdId eq householdId }
        .orderBy(Imports.createdAt to SortOrder.DESC)
        .toList()
    val userIds = rows.flatMap { listOfNotNull(it[Imports.createdBy], it[Imports.undoneBy]) }.distinct()
    val names = if (userIds.isEmpty()) emptyMap() else {
        Users.select(Users.id, Users.displayName).where { Users.id inList userIds }.associate { it[Users.id] to it[Users.displayName] }
    }
    return rows.map { row ->
        ImportSummary(
            id = row[Imports.id].toString(),
            createdAt = row[Imports.createdAt].toString(),
            createdByName = row[Imports.createdBy]?.let(names::get),
            counts = Json.decodeFromString(ImportCounts.serializer(), row[Imports.counts]),
            undoneAt = row[Imports.undoneAt]?.toString(),
            undoneByName = row[Imports.undoneBy]?.let(names::get),
        )
    }
}

/**
 * Undoes an import as a whole inside the current transaction: deletes leaf records first, then unused containers,
 * keeping anything still referenced by other records or any member who now has a login.
 */
internal fun undoImport(householdId: Uuid, importId: Uuid, userId: Uuid, dryRun: Boolean): ImportUndoResult {
    val importRow = Imports.selectAll()
        .where { (Imports.id eq importId) and (Imports.householdId eq householdId) }
        .singleOrNull() ?: throw notFound("Import")
    if (importRow[Imports.undoneAt] != null) {
        throw conflict("import_already_undone", "This import has already been undone.")
    }

    val deleted = Tally()
    val edited = Tally()
    val kept = Tally()
    val keptItems = mutableListOf<KeptImportItem>()

    fun anyExists(query: org.jetbrains.exposed.v1.jdbc.Query): Boolean = !query.limit(1).empty()

    // 1. Leaf records first.
    val importedRows = BankRows.select(
        BankRows.version, BankRows.createdRecord, BankRows.decidedAuto,
        BankRows.expenseId, BankRows.incomeId, BankRows.transferId, BankRows.settlementId,
    ).where { (BankRows.householdId eq householdId) and (BankRows.importId eq importId) }.toList()
    val bankRowVersions = importedRows.map { it[BankRows.version] }
    // Records the inbox rules made from the import's rows when it stored them (or a later automatic pass) aren't
    // stamped with the import, but they're the import's doing: they go with their rows, unless another row still
    // points at them. Records a person made from these rows by hand stay.
    val autoMade = importedRows.filter { it[BankRows.createdRecord] && it[BankRows.decidedAuto] }
    if (bankRowVersions.isNotEmpty()) {
        deleted.bankRows = bankRowVersions.size
        edited.bankRows = bankRowVersions.count { it > 1 }
        BankRows.deleteWhere { (BankRows.householdId eq householdId) and (BankRows.importId eq importId) }
    }

    val contributionVersions = AssetContributions.select(AssetContributions.version)
        .where { (AssetContributions.householdId eq householdId) and (AssetContributions.importId eq importId) }
        .map { it[AssetContributions.version] }
    if (contributionVersions.isNotEmpty()) {
        deleted.contributions = contributionVersions.size
        edited.contributions = contributionVersions.count { it > 1 }
        AssetContributions.deleteWhere { (AssetContributions.householdId eq householdId) and (AssetContributions.importId eq importId) }
    }

    val incomeRows = Income.select(Income.id, Income.version)
        .where { (Income.householdId eq householdId) and (Income.importId eq importId) }
        .toList()
    val incomeIds = incomeRows.map { it[Income.id] }
    deleted.income = incomeRows.size
    edited.income = incomeRows.count { it[Income.version] > 1 }

    val expenseRows = Expenses.select(Expenses.id, Expenses.version)
        .where { (Expenses.householdId eq householdId) and (Expenses.importId eq importId) }
        .toList()
    val expenseIds = expenseRows.map { it[Expenses.id] }
    deleted.expenses = expenseRows.size
    edited.expenses = expenseRows.count { it[Expenses.version] > 1 }

    // Count external bank rows and refunds that will lose their link when these expenses/income are deleted.
    val unlinkedBankRows = if (expenseIds.isEmpty() && incomeIds.isEmpty()) 0 else {
        val linkFilter: Op<Boolean> = when {
            expenseIds.isNotEmpty() && incomeIds.isNotEmpty() -> (BankRows.expenseId inList expenseIds) or (BankRows.incomeId inList incomeIds)
            expenseIds.isNotEmpty() -> BankRows.expenseId inList expenseIds
            else -> BankRows.incomeId inList incomeIds
        }
        BankRows.select(BankRows.id)
            .where { (BankRows.householdId eq householdId) and (BankRows.importId.isNull() or (BankRows.importId neq importId)) and linkFilter }
            .count().toInt()
    }
    val unlinkedRefunds = if (expenseIds.isEmpty()) 0 else {
        Expenses.select(Expenses.id)
            .where { (Expenses.householdId eq householdId) and (Expenses.importId.isNull() or (Expenses.importId neq importId)) and (Expenses.refundOf inList expenseIds) }
            .count().toInt()
    }

    // The import's rows are gone now, so a record no remaining row points at was made only from them.
    fun orphans(column: org.jetbrains.exposed.v1.core.Column<Uuid?>): Set<Uuid> {
        val made = autoMade.mapNotNull { it[column] }.toSet()
        if (made.isEmpty()) return emptySet()
        val stillUsed = BankRows.select(column).where { (BankRows.householdId eq householdId) and (column inList made) }
            .mapNotNull { it[column] }.toSet()
        return made - stillUsed
    }
    val orphanExpenses = orphans(BankRows.expenseId)
    val orphanIncome = orphans(BankRows.incomeId)
    val allDeletedIncomeIds = (incomeIds + orphanIncome).distinct()

    if (allDeletedIncomeIds.isNotEmpty()) {
        // V25 cascades deletes on deducted_from; unlink non-imported deductions first so they survive as ordinary deductions.
        val keptDeductions = Income.select(Income.id, Income.sourceName)
            .where {
                (Income.householdId eq householdId) and
                    (Income.deductedFrom inList allDeletedIncomeIds) and
                    (Income.id notInList allDeletedIncomeIds)
            }
            .toList()
        if (keptDeductions.isNotEmpty()) {
            val keptDeductionIds = keptDeductions.map { it[Income.id] }
            Income.update({ (Income.householdId eq householdId) and (Income.id inList keptDeductionIds) }) {
                it[deductedFrom] = null
            }
            kept.income += keptDeductions.size
            keptDeductions.forEach { row ->
                keptItems += KeptImportItem("income", row[Income.sourceName].ifBlank { "Deduction" }, "Was linked to an imported earning")
            }
        }
    }

    if (incomeIds.isNotEmpty()) {
        Income.deleteWhere { (Income.householdId eq householdId) and (Income.importId eq importId) }
    }
    if (expenseIds.isNotEmpty()) {
        Expenses.deleteWhere { (Expenses.householdId eq householdId) and (Expenses.importId eq importId) }
    }

    if (orphanExpenses.isNotEmpty()) {
        deleted.expenses += Expenses.deleteWhere { (Expenses.householdId eq householdId) and (Expenses.id inList orphanExpenses) }
    }
    if (orphanIncome.isNotEmpty()) {
        deleted.income += Income.deleteWhere { (Income.householdId eq householdId) and (Income.id inList orphanIncome) }
    }
    orphans(BankRows.transferId).takeIf { it.isNotEmpty() }?.let { gone ->
        Transfers.deleteWhere { (Transfers.householdId eq householdId) and (Transfers.id inList gone) }
    }
    orphans(BankRows.settlementId).takeIf { it.isNotEmpty() }?.let { gone ->
        Settlements.deleteWhere { (Settlements.householdId eq householdId) and (Settlements.id inList gone) }
    }

    // 2. Containers, in dependency order.
    BankFeeds.select(BankFeeds.id, BankFeeds.name, BankFeeds.feedSource, BankFeeds.connectionId, BankFeeds.version)
        .where { (BankFeeds.householdId eq householdId) and (BankFeeds.importId eq importId) }
        .toList()
        .forEach { feed ->
            val id = feed[BankFeeds.id]
            val reason = when {
                feed[BankFeeds.connectionId] != null || feed[BankFeeds.feedSource] == "gocardless" -> "Connected to GoCardless"
                anyExists(BankRows.select(BankRows.id).where { (BankRows.householdId eq householdId) and (BankRows.feedId eq id) }) -> "Has bank rows"
                anyExists(InboxRules.select(InboxRules.id).where { (InboxRules.householdId eq householdId) and (InboxRules.feedId eq id) }) -> "Used by an inbox rule"
                else -> null
            }
            if (reason != null) {
                kept.bankFeeds++
                keptItems += KeptImportItem("bankFeed", feed[BankFeeds.name], reason)
            } else {
                BankFeeds.deleteWhere { (BankFeeds.householdId eq householdId) and (BankFeeds.id eq id) }
                deleted.bankFeeds++
                if (feed[BankFeeds.version] > 1) edited.bankFeeds++
            }
        }

    Assets.select(Assets.id, Assets.name, Assets.version)
        .where { (Assets.householdId eq householdId) and (Assets.importId eq importId) }
        .toList()
        .forEach { asset ->
            val id = asset[Assets.id]
            val reason = when {
                anyExists(AssetContributions.select(AssetContributions.id).where { (AssetContributions.householdId eq householdId) and (AssetContributions.assetId eq id) }) -> "Still has contributions"
                anyExists(AssetValuations.select(AssetValuations.id).where { (AssetValuations.householdId eq householdId) and (AssetValuations.assetId eq id) }) -> "Has valuations"
                anyExists(AssetFees.select(AssetFees.id).where { (AssetFees.householdId eq householdId) and (AssetFees.assetId eq id) }) -> "Has fees"
                else -> null
            }
            if (reason != null) {
                kept.assets++
                keptItems += KeptImportItem("asset", asset[Assets.name], reason)
            } else {
                Assets.deleteWhere { (Assets.householdId eq householdId) and (Assets.id eq id) }
                deleted.assets++
                if (asset[Assets.version] > 1) edited.assets++
            }
        }

    Merchants.select(Merchants.id, Merchants.name, Merchants.version)
        .where { (Merchants.householdId eq householdId) and (Merchants.importId eq importId) }
        .toList()
        .forEach { merchant ->
            val id = merchant[Merchants.id]
            val inUse = anyExists(Expenses.select(Expenses.id).where { (Expenses.householdId eq householdId) and (Expenses.merchantId eq id) })
            if (inUse) {
                kept.merchants++
                keptItems += KeptImportItem("merchant", merchant[Merchants.name], "Used by other expenses")
            } else {
                Merchants.deleteWhere { (Merchants.householdId eq householdId) and (Merchants.id eq id) }
                deleted.merchants++
                if (merchant[Merchants.version] > 1) edited.merchants++
            }
        }

    Trips.select(Trips.id, Trips.name, Trips.version)
        .where { (Trips.householdId eq householdId) and (Trips.importId eq importId) }
        .toList()
        .forEach { trip ->
            val id = trip[Trips.id]
            val inUse = anyExists(Expenses.select(Expenses.id).where { (Expenses.householdId eq householdId) and (Expenses.tripId eq id) })
            if (inUse) {
                kept.trips++
                keptItems += KeptImportItem("trip", trip[Trips.name], "Used by other expenses")
            } else {
                Trips.deleteWhere { (Trips.householdId eq householdId) and (Trips.id eq id) }
                deleted.trips++
                if (trip[Trips.version] > 1) edited.trips++
            }
        }

    // Categories: children before parents so an unused parent whose child was also unused can go too.
    Categories.select(Categories.id, Categories.name, Categories.parentId, Categories.version)
        .where { (Categories.householdId eq householdId) and (Categories.importId eq importId) }
        .toList()
        .sortedBy { it[Categories.parentId] == null }
        .forEach { cat ->
            val id = cat[Categories.id]
            val reason = when {
                anyExists(Categories.select(Categories.id).where { (Categories.householdId eq householdId) and (Categories.parentId eq id) }) -> "Has subcategories"
                anyExists(Expenses.select(Expenses.id).where { (Expenses.householdId eq householdId) and (Expenses.categoryId eq id) }) -> "Used by other expenses"
                anyExists(ExpenseLines.select(ExpenseLines.id).where { (ExpenseLines.householdId eq householdId) and (ExpenseLines.categoryId eq id) }) -> "Used by other expenses"
                anyExists(Merchants.select(Merchants.id).where { (Merchants.householdId eq householdId) and (Merchants.defaultCategoryId eq id) }) -> "Default category of a merchant"
                anyExists(SplitRules.select(SplitRules.id).where { (SplitRules.householdId eq householdId) and (SplitRules.categoryId eq id) }) -> "Used by a split rule"
                anyExists(InboxRules.select(InboxRules.id).where { (InboxRules.householdId eq householdId) and (InboxRules.categoryId eq id) }) -> "Used by an inbox rule"
                else -> null
            }
            if (reason != null) {
                kept.categories++
                keptItems += KeptImportItem("category", cat[Categories.name], reason)
            } else {
                Categories.deleteWhere { (Categories.householdId eq householdId) and (Categories.id eq id) }
                deleted.categories++
                if (cat[Categories.version] > 1) edited.categories++
            }
        }

    Accounts.select(Accounts.id, Accounts.name, Accounts.version)
        .where { (Accounts.householdId eq householdId) and (Accounts.importId eq importId) }
        .toList()
        .forEach { account ->
            val id = account[Accounts.id]
            val reason = when {
                anyExists(Expenses.select(Expenses.id).where { (Expenses.householdId eq householdId) and (Expenses.accountId eq id) }) -> "Used by other expenses"
                anyExists(Income.select(Income.id).where { (Income.householdId eq householdId) and (Income.accountId eq id) }) -> "Used by other income"
                anyExists(AssetContributions.select(AssetContributions.id).where { (AssetContributions.householdId eq householdId) and (AssetContributions.accountId eq id) }) -> "Used by asset contributions"
                anyExists(BankFeeds.select(BankFeeds.id).where { (BankFeeds.householdId eq householdId) and (BankFeeds.accountId eq id) }) -> "Has a bank feed"
                anyExists(Assets.select(Assets.id).where { (Assets.householdId eq householdId) and (Assets.accountId eq id) }) -> "Tracked by an asset"
                anyExists(Transfers.select(Transfers.id).where { (Transfers.householdId eq householdId) and ((Transfers.fromAccountId eq id) or (Transfers.toAccountId eq id)) }) -> "Used by transfers"
                anyExists(InboxRules.select(InboxRules.id).where { (InboxRules.householdId eq householdId) and ((InboxRules.accountId eq id) or (InboxRules.otherAccountId eq id)) }) -> "Used by an inbox rule"
                else -> null
            }
            if (reason != null) {
                kept.accounts++
                keptItems += KeptImportItem("account", account[Accounts.name], reason)
            } else {
                Accounts.deleteWhere { (Accounts.householdId eq householdId) and (Accounts.id eq id) }
                deleted.accounts++
                if (account[Accounts.version] > 1) edited.accounts++
            }
        }

    HouseholdMembers.join(Users, JoinType.INNER, HouseholdMembers.userId, Users.id)
        .select(HouseholdMembers.userId, Users.displayName, Users.email)
        .where { (HouseholdMembers.householdId eq householdId) and (HouseholdMembers.importId eq importId) }
        .toList()
        .forEach { member ->
            val uid = member[HouseholdMembers.userId]
            val reason = when {
                member[Users.email] != null -> "Has a login"
                anyExists(Accounts.select(Accounts.id).where { (Accounts.householdId eq householdId) and (Accounts.ownerUserId eq uid) }) -> "Owns an account"
                anyExists(Expenses.select(Expenses.id).where { (Expenses.householdId eq householdId) and (Expenses.paidByUserId eq uid) }) -> "Used on expenses"
                anyExists(Income.select(Income.id).where { (Income.householdId eq householdId) and (Income.recipientUserId eq uid) }) -> "Used on income"
                anyExists(Assets.select(Assets.id).where { (Assets.householdId eq householdId) and (Assets.ownerUserId eq uid) }) -> "Owns an asset"
                anyExists(Settlements.select(Settlements.id).where { (Settlements.householdId eq householdId) and ((Settlements.fromUserId eq uid) or (Settlements.toUserId eq uid)) }) -> "Has settlements"
                anyExists(SplitShares.select(SplitShares.id).where { (SplitShares.householdId eq householdId) and (SplitShares.userId eq uid) }) -> "In a split rule"
                anyExists(InboxRules.select(InboxRules.id).where { (InboxRules.householdId eq householdId) and (InboxRules.personUserId eq uid) }) -> "Used by an inbox rule"
                anyExists(HouseholdInvites.select(HouseholdInvites.id).where { (HouseholdInvites.householdId eq householdId) and (HouseholdInvites.claimUserId eq uid) and HouseholdInvites.acceptedAt.isNull() }) -> "Has a pending invite"
                else -> null
            }
            if (reason != null) {
                kept.members++
                keptItems += KeptImportItem("member", member[Users.displayName], reason)
            } else {
                HouseholdMembers.deleteWhere { (HouseholdMembers.householdId eq householdId) and (HouseholdMembers.userId eq uid) }
                Users.deleteWhere { (Users.id eq uid) and Users.email.isNull() }
                deleted.members++
            }
        }

    // 3. Mark the import undone.
    Imports.update({ (Imports.id eq importId) and (Imports.householdId eq householdId) }) {
        it[undoneAt] = Clock.System.now()
        it[undoneBy] = userId
    }

    return ImportUndoResult(
        dryRun = dryRun,
        deleted = deleted.counts(),
        edited = edited.counts(),
        kept = kept.counts(),
        keptItems = keptItems,
        unlinkedBankRows = unlinkedBankRows,
        unlinkedRefunds = unlinkedRefunds,
    )
}
