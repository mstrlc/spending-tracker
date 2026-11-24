package dev.spendingtracker.shared.api

import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** How often one feed may ask the bank for its data in a day: GoCardless's own limit is about this per account. */
const val BANK_SYNCS_PER_DAY = 4

/** A bank the household can connect to (GoCardless's list for a country). */
@Serializable
data class ConnectableBank(
    val id: String,
    val name: String,
    val logo: String?,
    /** How many days back the bank gives transactions. */
    val historyDays: Int,
    /** How long a consent lasts at most, in days; the person has to reconnect then. */
    val accessDays: Int,
)

/** Body for starting a connection: the bank to connect to. */
@Serializable
data class StartBankConnection(val institutionId: String)

/** [link] is the bank's own page where the person logs in and agrees; it sends them back to the app afterwards. */
@Serializable
data class BankConnectionStarted(val connectionId: String, val link: String)

@Serializable
enum class BankConnectionStatus {
    /** Sent to the bank and not back yet. */
    @SerialName("pending") PENDING,
    @SerialName("active") ACTIVE,
    /** The consent ran out: connect the bank again. */
    @SerialName("expired") EXPIRED,
    /** The bank or the person withdrew the consent. */
    @SerialName("revoked") REVOKED,
    /** The bank refused, or the link never finished. */
    @SerialName("failed") FAILED,
}

/**
 * One of the bank's accounts under a connection. [feedId] is the feed reading it, once there is one. Until then,
 * [suggestedFeedId] is an existing feed it most likely continues (same IBAN and currency, not read live by another
 * connection): attaching to it keeps the account's history in one feed after reconnecting or after statements.
 */
@Serializable
data class BankConnectionAccount(
    val externalId: String,
    val iban: String?,
    /** A card's masked number, where the bank gives no IBAN. */
    val maskedPan: String?,
    val currency: String,
    val name: String,
    val ownerName: String,
    val feedId: String?,
    val suggestedFeedId: String? = null,
)

/** A consent given at a bank. [consentExpiresAt] is an ISO timestamp, known once the connection is finished. */
@Serializable
data class BankConnection(
    val id: String,
    val institutionId: String,
    val institutionName: String,
    val status: BankConnectionStatus,
    val consentExpiresAt: String?,
    val lastError: String,
    val version: Int,
    val accounts: List<BankConnectionAccount>,
)

/**
 * The household's bank connections; [configured] is false when the server has no GoCardless keys. [nextSyncAt] is
 * when the daily sync next runs (an ISO timestamp), null when the server doesn't run one.
 */
@Serializable
data class BankConnections(
    val configured: Boolean,
    val connections: List<BankConnection>,
    val nextSyncAt: String? = null,
)

/**
 * One bank account to read. With [feedId] it attaches to that existing feed (an uploaded one, or one whose connection
 * lapsed), which then syncs from a week before its newest row; [accountId] can be left out, and [name] and [importFrom]
 * change the feed only when given. Without it a new feed is made, read into the ledger account [accountId];
 * [name] defaults to the ledger account's name.
 */
@Serializable
data class FeedFromAccount(
    val externalId: String,
    val accountId: String? = null,
    val name: String? = null,
    val importFrom: LocalDate? = null,
    val feedId: String? = null,
)

@Serializable
data class CreateConnectionFeeds(val feeds: List<FeedFromAccount>)

/** The latest balance a sync read for a feed: [amountMinor] in [currency] with the bank's sign. [readAt] is an ISO timestamp. */
@Serializable
data class FeedBalance(
    val amountMinor: Long,
    val currency: String,
    val readAt: String,
    val balanceDate: LocalDate?,
    val type: String,
)

/** What a sync did for one feed. [status] is "synced", "skipped" (not asked: limit reached, no live connection) or "error". */
@Serializable
data class FeedSyncResult(
    val feedId: String,
    val feedName: String,
    val status: String,
    val added: Int,
    val existing: Int,
    /** Rows the rules filed on their own. */
    val filed: Int,
    val message: String,
)

@Serializable
data class SyncResult(val feeds: List<FeedSyncResult>)

/**
 * The latest time the app asked the bank for a feed, whether it worked or not. [at] is an ISO timestamp; [trigger] is
 * "manual" (someone pressed sync) or "scheduled" (the daily run); [status] is "synced" or "error", with the bank's
 * reason in [error]. [added] counts the new rows it brought.
 */
@Serializable
data class FeedSyncAttempt(
    val at: String,
    val trigger: String,
    val status: String,
    val added: Int,
    val error: String,
)
