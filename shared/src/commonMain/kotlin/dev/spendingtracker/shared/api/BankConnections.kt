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

/** One of the bank's accounts under a connection. [feedId] is the feed made from it, once there is one. */
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

/** The household's bank connections; [configured] is false when the server has no GoCardless keys. */
@Serializable
data class BankConnections(
    val configured: Boolean,
    val connections: List<BankConnection>,
)

/** One bank account to read into the ledger account [accountId]. [name] defaults to the ledger account's name. */
@Serializable
data class FeedFromAccount(
    val externalId: String,
    val accountId: String,
    val name: String? = null,
    val importFrom: LocalDate? = null,
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
