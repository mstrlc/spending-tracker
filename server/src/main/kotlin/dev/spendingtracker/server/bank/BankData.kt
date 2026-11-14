package dev.spendingtracker.server.bank

import io.ktor.server.application.Application
import io.ktor.util.AttributeKey
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

/** A bank GoCardless can connect to. */
data class BankListing(
    val id: String,
    val name: String,
    val logo: String?,
    /** Days of history the bank gives. */
    val historyDays: Int,
    /** The longest a consent can last, in days. */
    val accessDays: Int,
)

/** A requisition just made: [link] is the bank's page for the person to log in and agree. */
data class BankLink(val requisitionId: String, val agreementId: String, val link: String, val institutionName: String)

/** GoCardless's requisition status codes, grouped by what they mean for us. */
enum class LinkState { PENDING, LINKED, EXPIRED, REVOKED, FAILED }

data class RequisitionInfo(val state: LinkState, val agreementId: String?, val accountIds: List<String>)

/** What the person agreed to: when, and for how long. [accepted] is null until they have. */
data class AgreementInfo(val accepted: Instant?, val accessValidForDays: Int)

data class BankAccountInfo(
    val id: String,
    val iban: String?,
    val maskedPan: String?,
    val currency: String,
    val name: String,
    val ownerName: String,
)

data class BankBalance(val amountMinor: Long, val currency: String, val type: String, val date: LocalDate?)

/** The bank's API failed with [status]; [consentLapsed] says the consent itself is the problem (expired, withdrawn, suspended). */
class BankDataException(val status: Int, message: String, val consentLapsed: Boolean = false) : RuntimeException(message)

/**
 * Access to banks' data through an aggregator (GoCardless Bank Account Data today). Everything the app needs from a
 * bank is here, so tests use a fake and another aggregator can be added later. Transactions are the bank's own JSON
 * (the Berlin Group shape), read by the same parser as an uploaded statement.
 */
interface BankData {
    suspend fun institutions(country: String): List<BankListing>

    /** Makes an agreement for as much history and access as the bank allows and a requisition that sends the person back to [redirect] with `?ref=[reference]`. */
    suspend fun startLink(institutionId: String, redirect: String, reference: String): BankLink

    suspend fun requisition(requisitionId: String): RequisitionInfo

    suspend fun agreement(agreementId: String): AgreementInfo

    /** An account's details (IBAN, currency, name); the bank limits how often they may be read. */
    suspend fun account(accountId: String): BankAccountInfo

    /** The booked transactions from [from] on (everything the bank gives when null). */
    suspend fun transactions(accountId: String, from: LocalDate?): List<JsonObject>

    /** The account's balance, the booked one where the bank gives several; null when it gives none. */
    suspend fun balance(accountId: String): BankBalance?

    /** Withdraws the consent. */
    suspend fun deleteRequisition(requisitionId: String)
}

/** What a request needs to connect banks: the data source, and the web app's address the bank sends people back to. */
class BankSetup(val data: BankData, val appUrl: String)

private val BankSetupKey = AttributeKey<BankSetup>("BankSetup")

/** Null when the server has no bank keys: bank connections are then reported as not configured. */
var Application.bankSetup: BankSetup?
    get() = attributes.getOrNull(BankSetupKey)
    set(value) {
        if (value == null) attributes.remove(BankSetupKey) else attributes.put(BankSetupKey, value)
    }
