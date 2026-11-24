package dev.spendingtracker.server.bank

import dev.spendingtracker.server.db.BankBalances
import dev.spendingtracker.server.db.BankConnections
import dev.spendingtracker.server.db.BankFeeds
import dev.spendingtracker.server.db.BankRows
import dev.spendingtracker.server.db.BankSyncs
import dev.spendingtracker.server.db.HouseholdMembers
import dev.spendingtracker.server.db.Households
import dev.spendingtracker.server.db.dbQuery
import dev.spendingtracker.server.inbox.addedRows
import dev.spendingtracker.server.inbox.applyRules
import dev.spendingtracker.server.inbox.feedShape
import dev.spendingtracker.server.inbox.loadHintContext
import dev.spendingtracker.server.inbox.parseGoCardless
import dev.spendingtracker.server.inbox.startOfBankData
import dev.spendingtracker.server.inbox.storeRows
import dev.spendingtracker.server.market.Market
import dev.spendingtracker.shared.api.BANK_SYNCS_PER_DAY
import dev.spendingtracker.shared.api.BankConnectionStatus
import dev.spendingtracker.shared.api.FeedSyncResult
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val log = LoggerFactory.getLogger("BankSync")

/** Banks' days run on Prague time: the daily limit counts from its midnight. */
val PRAGUE: TimeZone = TimeZone.of("Europe/Prague")

/** Midnight at the start of [now]'s day in Prague. */
fun startOfPragueDay(now: Instant): Instant = now.toLocalDateTime(PRAGUE).date.atStartOfDayIn(PRAGUE)

/** A sync asks for rows this many days before the newest one it has: overlap is harmless, external ids dedupe. */
const val SYNC_OVERLAP_DAYS = 7

private const val SYNC_TRIGGER_MANUAL = "manual"
private const val SYNC_TRIGGER_SCHEDULED = "scheduled"

/**
 * Reads live feeds from the bank: new booked rows go through [storeRows] and then the inbox rules, exactly as an
 * uploaded statement does, and the balance is read after. A feed is asked for at most [BANK_SYNCS_PER_DAY] times a day,
 * and a connection whose consent has lapsed stops syncing.
 */
class BankSync(private val data: BankData, private val market: Market, private val clock: Clock = Clock.System) {
    private fun dayStart(): Instant = startOfPragueDay(clock.now())

    /** A feed's sync, and whether the bank said the connection's consent is gone (the other feeds then aren't asked). */
    private class Outcome(val result: FeedSyncResult, val consentLapsed: Boolean = false)

    private class Plan(
        val feedId: Uuid,
        val feedName: String,
        val accountId: String,
        val shape: dev.spendingtracker.server.inbox.FeedShape,
        val currency: String,
        val importFrom: LocalDate?,
        val connectionId: Uuid,
        val from: LocalDate?,
    )

    /**
     * Syncs one feed. [userId] is who the rules act as: whoever pressed the button, or for the daily run the person who
     * made the connection.
     */
    suspend fun syncFeed(householdId: Uuid, householdCurrency: String, feedId: Uuid, userId: Uuid, scheduled: Boolean = false): FeedSyncResult =
        sync(householdId, householdCurrency, feedId, userId, scheduled).result

    private suspend fun sync(householdId: Uuid, householdCurrency: String, feedId: Uuid, userId: Uuid, scheduled: Boolean): Outcome {
        var name = ""
        fun result(status: String, message: String, added: Int = 0, existing: Int = 0, filed: Int = 0) =
            Outcome(FeedSyncResult(feedId.toString(), name, status, added, existing, filed, message))

        val syncId = Uuid.random()
        val prepared = dbQuery {
            // Locked, so two syncs of one feed started together count each other against the daily limit.
            val feed = BankFeeds.selectAll().where { (BankFeeds.id eq feedId) and (BankFeeds.householdId eq householdId) }.forUpdate().singleOrNull()
                ?: return@dbQuery null to "Bank feed not found"
            name = feed[BankFeeds.name]
            val connectionId = feed[BankFeeds.connectionId]
            if (feed[BankFeeds.feedSource] != "gocardless" || connectionId == null) {
                return@dbQuery null to "This feed isn't connected to a bank. Upload statements to it instead."
            }
            val connection = BankConnections.selectAll().where { BankConnections.id eq connectionId }.single()
            val expires = connection[BankConnections.consentExpiresAt]
            val status = connection[BankConnections.status]
            if (status == "active" && expires != null && expires <= clock.now()) {
                markConnection(connectionId, BankConnectionStatus.EXPIRED, "The consent has expired. Connect the bank again.")
                return@dbQuery null to "The consent has expired. Connect the bank again."
            }
            if (status != "active") {
                return@dbQuery null to when (status) {
                    "pending" -> "The bank hasn't been connected yet."
                    else -> connection[BankConnections.lastError].ifEmpty { "The consent is no longer valid. Connect the bank again." }
                }
            }
            val today = BankSyncs.id.count()
            val syncsToday = BankSyncs.select(today).where { (BankSyncs.feedId eq feedId) and (BankSyncs.startedAt greaterEq dayStart()) }
                .single()[today]
            if (syncsToday >= BANK_SYNCS_PER_DAY) {
                return@dbQuery null to "Already read $BANK_SYNCS_PER_DAY times today, which is the bank's limit. Try again tomorrow."
            }
            val importFrom = startOfBankData(householdId, feed[BankFeeds.importFrom])
            val newestBooked = BankRows.bookingDate.max()
            val newest = BankRows.select(newestBooked).where { BankRows.feedId eq feedId }.single()[newestBooked]
            // From a week before the newest row we have, never before the feed's import-from date or the household's start.
            val from = when {
                newest == null -> importFrom
                else -> (newest - DatePeriod(days = SYNC_OVERLAP_DAYS)).let { if (importFrom != null && it < importFrom) importFrom else it }
            }
            BankSyncs.insert {
                it[id] = syncId
                it[BankSyncs.householdId] = householdId
                it[BankSyncs.feedId] = feedId
                it[trigger] = if (scheduled) SYNC_TRIGGER_SCHEDULED else SYNC_TRIGGER_MANUAL
                it[added] = 0
                it[error] = ""
            }
            Plan(
                feedId, name, feed[BankFeeds.externalId]!!, feedShape(feed[BankFeeds.institution], feed[BankFeeds.accountId]),
                feed[BankFeeds.currency], importFrom, connectionId, from,
            ) to ""
        }
        val plan = prepared.first ?: return result("skipped", prepared.second)

        suspend fun fail(message: String): Outcome {
            dbQuery {
                BankSyncs.update({ BankSyncs.id eq syncId }) { it[error] = message.take(500) }
                BankConnections.update({ (BankConnections.id eq plan.connectionId) and (BankConnections.status eq "active") }) {
                    it[lastError] = message.take(500)
                }
            }
            return result("error", message)
        }

        val raw = try {
            data.transactions(plan.accountId, plan.from)
        } catch (e: BankDataException) {
            if (e.consentLapsed) {
                val message = "The bank no longer allows access (${e.message}). Connect the bank again."
                dbQuery {
                    val expires = BankConnections.select(BankConnections.consentExpiresAt)
                        .where { BankConnections.id eq plan.connectionId }.single()[BankConnections.consentExpiresAt]
                    val status = if (expires != null && expires <= clock.now()) BankConnectionStatus.EXPIRED else BankConnectionStatus.REVOKED
                    markConnection(plan.connectionId, status, message)
                    BankSyncs.update({ BankSyncs.id eq syncId }) { it[error] = message.take(500) }
                }
                return Outcome(result("error", message).result, consentLapsed = true)
            }
            log.warn("Bank sync of feed {} failed: {}", feedId, e.message)
            return fail(e.message ?: "The bank didn't answer")
        } catch (e: Exception) {
            log.warn("Bank sync of feed {} failed", feedId, e)
            return fail("The bank didn't answer")
        }

        val parsed = raw.mapNotNull { parseGoCardless(it, plan.shape) }
        val usable = parsed.filter { it.currency == plan.currency }
        val notes = buildList {
            if (parsed.size < raw.size) add("${raw.size - parsed.size} rows had no id, date or amount and were left out")
            if (usable.size < parsed.size) add("${parsed.size - usable.size} rows in another currency were left out")
        }
        val stored = dbQuery {
            val counts = storeRows(householdId, feedId, plan.shape, plan.importFrom, usable, loadHintContext(householdId))
            BankFeeds.update({ BankFeeds.id eq feedId }) { it[lastSyncedAt] = clock.now() }
            BankSyncs.update({ BankSyncs.id eq syncId }) { it[added] = counts.added }
            BankConnections.update({ BankConnections.id eq plan.connectionId }) { it[lastError] = "" }
            counts to addedRows(feedId, counts.addedIds).map { it[BankRows.id] to it[BankRows.date] }
        }
        val (counts, addedIds) = stored

        // The rules file what they can of the new rows, foreign amounts converted at the ČNB fixings.
        var filed = 0
        if (addedIds.isNotEmpty()) {
            if (plan.currency != householdCurrency) market.refreshFx(addedIds.map { it.second }.distinct())
            filed = dbQuery {
                val applied = applyRules(householdId, householdCurrency, userId, addedIds.map { it.first }.toSet())
                applied.filedByRules + applied.linkedByRules + applied.pairedByMatcher + applied.duplicatesByMatcher
            }
        }

        val balanceNote = readBalance(householdId, plan)
        val message = (listOf(describe(counts.added, counts.existing, filed)) + notes + listOfNotNull(balanceNote)).joinToString(". ") + "."
        return result("synced", message, counts.added, counts.existing, filed)
    }

    /** Reads and stores the feed's balance; a failure costs nothing but the reading, and is returned as a note. */
    private suspend fun readBalance(householdId: Uuid, plan: Plan): String? {
        val balance = try {
            data.balance(plan.accountId)
        } catch (e: Exception) {
            log.warn("Reading the balance of feed {} failed: {}", plan.feedId, e.message)
            return "The balance couldn't be read"
        } ?: return "The bank gave no balance"
        dbQuery {
            BankBalances.insert {
                it[id] = Uuid.random()
                it[BankBalances.householdId] = householdId
                it[feedId] = plan.feedId
                it[readAt] = clock.now()
                it[balanceDate] = balance.date
                it[amountMinor] = balance.amountMinor
                it[currency] = balance.currency
                it[balanceType] = balance.type
            }
        }
        return null
    }

    /** Syncs every feed of a connection, one after another. */
    suspend fun syncConnection(householdId: Uuid, householdCurrency: String, connectionId: Uuid, userId: Uuid, scheduled: Boolean = false): List<FeedSyncResult> {
        val feedIds = dbQuery {
            BankFeeds.select(BankFeeds.id).where { (BankFeeds.householdId eq householdId) and (BankFeeds.connectionId eq connectionId) }.map { it[BankFeeds.id] }
        }
        val results = mutableListOf<FeedSyncResult>()
        for (feedId in feedIds) {
            val outcome = sync(householdId, householdCurrency, feedId, userId, scheduled)
            results += outcome.result
            // Once one account says the consent is gone the rest will too: don't spend their calls.
            if (outcome.consentLapsed) break
        }
        return results
    }

    /** Syncs every feed of the household's active connections, one connection after another. */
    suspend fun syncHousehold(householdId: Uuid, householdCurrency: String, userId: Uuid): List<FeedSyncResult> {
        val connectionIds = dbQuery {
            BankConnections.select(BankConnections.id)
                .where { (BankConnections.householdId eq householdId) and (BankConnections.status eq "active") }
                .orderBy(BankConnections.institutionName)
                .map { it[BankConnections.id] }
        }
        return connectionIds.flatMap { syncConnection(householdId, householdCurrency, it, userId) }
    }

    /** Syncs every active connection across all households (the daily scheduled run). */
    suspend fun syncAll(): List<FeedSyncResult> {
        data class Target(val householdId: Uuid, val currency: String, val connectionId: Uuid, val userId: Uuid)
        val targets = dbQuery {
            val currencies = Households.select(Households.id, Households.currency)
                .associate { it[Households.id] to it[Households.currency] }
            val fallbackUsers = HouseholdMembers.select(HouseholdMembers.householdId, HouseholdMembers.userId, HouseholdMembers.role)
                .orderBy(HouseholdMembers.role)
                .groupBy { it[HouseholdMembers.householdId] }
                .mapValues { (_, rows) ->
                    rows.firstOrNull { it[HouseholdMembers.role] == "owner" }?.get(HouseholdMembers.userId)
                        ?: rows.first()[HouseholdMembers.userId]
                }
            BankConnections.select(BankConnections.id, BankConnections.householdId, BankConnections.createdBy)
                .where { BankConnections.status eq "active" }
                .mapNotNull { row ->
                    val householdId = row[BankConnections.householdId]
                    val currency = currencies[householdId] ?: return@mapNotNull null
                    val userId = row[BankConnections.createdBy] ?: fallbackUsers[householdId] ?: return@mapNotNull null
                    Target(householdId, currency, row[BankConnections.id], userId)
                }
        }
        val results = mutableListOf<FeedSyncResult>()
        for (target in targets) {
            try {
                results += syncConnection(target.householdId, target.currency, target.connectionId, target.userId, scheduled = true)
            } catch (e: Exception) {
                log.warn("Scheduled sync of connection {} failed", target.connectionId, e)
            }
        }
        return results
    }

    private fun describe(added: Int, existing: Int, filed: Int): String {
        val parts = mutableListOf("$added new ${if (added == 1) "row" else "rows"}")
        if (existing > 0) parts += "$existing already here"
        if (filed > 0) parts += "$filed filed by rules"
        return parts.joinToString(", ")
    }
}

/** Delay from [now] until the next occurrence of [hour]:00 in Prague time (at least 1 minute in the future). */
fun nextPragueRunDelay(hour: Int, now: Instant): Duration {
    val local = now.toLocalDateTime(PRAGUE)
    val todayTarget = LocalDateTime(local.date.year, local.date.month, local.date.day, hour, 0).toInstant(PRAGUE)
    val target = if (todayTarget > now + 1.minutes) {
        todayTarget
    } else {
        val tomorrow = local.date + DatePeriod(days = 1)
        LocalDateTime(tomorrow.year, tomorrow.month, tomorrow.day, hour, 0).toInstant(PRAGUE)
    }
    return target - now
}

/** Sets a connection's status (and why); call inside a transaction. */
fun markConnection(connectionId: Uuid, status: BankConnectionStatus, error: String) {
    BankConnections.update({ BankConnections.id eq connectionId }) {
        it[BankConnections.status] = status.name.lowercase()
        it[lastError] = error.take(500)
        it[updatedAt] = Clock.System.now()
    }
}
