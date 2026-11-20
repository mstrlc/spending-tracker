package dev.spendingtracker.server.bank

import dev.spendingtracker.server.inbox.toMinor
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.accept
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private const val BASE_URL = "https://bankaccountdata.gocardless.com/api/v2"

/**
 * GoCardless Bank Account Data (formerly Nordigen), https://developer.gocardless.com/bank-account-data: PSD2 read
 * access to banks. A bank is connected by a requisition made with an end-user agreement (how much history, how long
 * access lasts); the person logs in at the bank, picks accounts, and the requisition turns "LN" (linked) with those
 * accounts' ids. Access lapses after the agreement's days and the bank's accounts then answer 401.
 *
 * The API wants an access token (24 hours) from the secret id and key, and a refresh token (30 days) to renew it;
 * both are kept in memory. Transactions, details and balances are each limited to about 4 calls per account per day,
 * which the sync respects.
 */
class GoCardlessBankData(
    private val secretId: String,
    private val secretKey: String,
    private val client: HttpClient = defaultClient(),
    private val baseUrl: String = BASE_URL,
    private val clock: Clock = Clock.System,
) : BankData {
    private val json = Json { ignoreUnknownKeys = true }
    private val tokenLock = Mutex()
    private var access: String? = null
    private var accessUntil = Instant.DISTANT_PAST
    private var refresh: String? = null
    private var refreshUntil = Instant.DISTANT_PAST

    /** A token valid for a while yet; renewed through the refresh token while that lasts, else made from the secrets. */
    private suspend fun accessToken(): String = tokenLock.withLock {
        val now = clock.now()
        access?.takeIf { now < accessUntil }?.let { return@withLock it }
        val refreshToken = refresh
        val body = if (refreshToken != null && now < refreshUntil) {
            val renewed = post("/token/refresh/", buildJsonObject { put("refresh", refreshToken) }, token = null)
            renewed.takeIf { it.status.isSuccess() }?.let { json.parseToJsonElement(it.bodyAsText()).jsonObject }
        } else {
            null
        } ?: run {
            val created = post("/token/new/", buildJsonObject { put("secret_id", secretId); put("secret_key", secretKey) }, token = null)
            val parsed = parse(created.bodyAsText())
            if (!created.status.isSuccess()) throw BankDataException(created.status.value, "GoCardless sign-in failed: ${reason(parsed, created.status.value)}")
            parsed
        }
        val token = body.str("access") ?: throw BankDataException(500, "GoCardless sign-in gave no token")
        // Renew a few minutes early.
        accessUntil = now + ((body.int("access_expires") ?: 86_400) - 300).seconds
        access = token
        body.str("refresh")?.let {
            refresh = it
            refreshUntil = now + ((body.int("refresh_expires") ?: 2_592_000) - 300).seconds
        }
        token
    }

    private suspend fun post(path: String, body: JsonElement, token: String?): HttpResponse = network {
        client.post("$baseUrl$path") {
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.Json)
            if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
            setBody(body.toString())
        }
    }

    /** Forgets [token] if it's still the one in use, so the next call signs in again. */
    private suspend fun dropToken(token: String) = tokenLock.withLock {
        if (access == token) {
            access = null
            accessUntil = Instant.DISTANT_PAST
        }
    }

    /**
     * Calls the API and returns its JSON. A 401 is first taken to be about our token (revoked, or the keys changed): it
     * is dropped and the call made once more with a new one. Account-level calls that still answer 401, or 403 or 409,
     * mean the consent is gone. A network failure or timeout is a [BankDataException] with status 504.
     */
    private suspend fun call(
        path: String,
        method: String = "GET",
        body: JsonElement? = null,
        query: Map<String, String> = emptyMap(),
        accountCall: Boolean = false,
    ): JsonElement {
        var response = send(path, method, body, query)
        if (response.second.status.value == 401) {
            dropToken(response.first)
            response = send(path, method, body, query)
        }
        val parsed = parse(network { response.second.bodyAsText() })
        if (!response.second.status.isSuccess()) {
            val status = response.second.status.value
            // GoCardless explains itself in summary and detail ("EUA … has expired", "Rate limit exceeded").
            throw BankDataException(status, reason(parsed, status), consentLapsed = accountCall && status in setOf(401, 403, 409))
        }
        return parsed
    }

    /** One request with the current token; returns the token used and the response. */
    private suspend fun send(path: String, method: String, body: JsonElement?, query: Map<String, String>): Pair<String, HttpResponse> {
        val token = accessToken()
        return token to network {
            when (method) {
                "POST" -> post(path, body ?: JsonObject(emptyMap()), token)
                "DELETE" -> client.delete("$baseUrl$path") {
                    accept(ContentType.Application.Json)
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
                else -> client.get("$baseUrl$path") {
                    accept(ContentType.Application.Json)
                    header(HttpHeaders.Authorization, "Bearer $token")
                    query.forEach { (k, v) -> parameter(k, v) }
                }
            }
        }
    }

    private fun parse(text: String): JsonObject =
        runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())

    private fun reason(body: JsonObject, status: Int) =
        listOfNotNull(body.str("summary"), body.str("detail")).joinToString(": ").ifEmpty { "HTTP $status" }

    override suspend fun institutions(country: String): List<BankListing> {
        val list = call("/institutions/", query = mapOf("country" to country.lowercase())) as? JsonArray ?: return emptyList()
        return list.map { it.jsonObject }.mapNotNull { i ->
            BankListing(
                id = i.str("id") ?: return@mapNotNull null,
                name = i.str("name") ?: return@mapNotNull null,
                logo = i.str("logo"),
                historyDays = i.int("transaction_total_days") ?: 90,
                accessDays = i.int("max_access_valid_for_days") ?: 90,
            )
        }.sortedBy { it.name.lowercase() }
    }

    override suspend fun startLink(institutionId: String, redirect: String, reference: String): BankLink {
        val institution = call("/institutions/$institutionId/").jsonObject
        val agreement = call(
            "/agreements/enduser/",
            "POST",
            buildJsonObject {
                put("institution_id", institutionId)
                put("max_historical_days", minOf(730, institution.int("transaction_total_days") ?: 90))
                put("access_valid_for_days", minOf(180, institution.int("max_access_valid_for_days") ?: 90))
                put("access_scope", buildJsonArray { listOf("balances", "details", "transactions").forEach { add(JsonPrimitive(it)) } })
            },
        ).jsonObject
        val agreementId = agreement.str("id") ?: throw BankDataException(500, "GoCardless gave no agreement id")
        val requisition = call(
            "/requisitions/",
            "POST",
            buildJsonObject {
                put("institution_id", institutionId)
                put("agreement", agreementId)
                put("redirect", redirect)
                put("reference", reference)
                put("user_language", "CS")
            },
        ).jsonObject
        return BankLink(
            requisitionId = requisition.str("id") ?: throw BankDataException(500, "GoCardless gave no requisition id"),
            agreementId = agreementId,
            link = requisition.str("link") ?: throw BankDataException(500, "GoCardless gave no link"),
            institutionName = institution.str("name") ?: institutionId,
        )
    }

    override suspend fun requisition(requisitionId: String): RequisitionInfo {
        val r = call("/requisitions/$requisitionId/").jsonObject
        val state = when (r.str("status")) {
            "LN" -> LinkState.LINKED
            "EX" -> LinkState.EXPIRED
            "SU" -> LinkState.REVOKED
            "RJ", "ER", "ID" -> LinkState.FAILED
            // CR created, GC giving consent, UA undergoing authentication, SA selecting accounts, GA granting access.
            else -> LinkState.PENDING
        }
        return RequisitionInfo(
            state,
            r.str("agreement"),
            (r["accounts"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
        )
    }

    override suspend fun agreement(agreementId: String): AgreementInfo {
        val a = call("/agreements/enduser/$agreementId/").jsonObject
        return AgreementInfo(a.str("accepted")?.let { runCatching { Instant.parse(it) }.getOrNull() }, a.int("access_valid_for_days") ?: 90)
    }

    override suspend fun account(accountId: String): BankAccountInfo {
        val meta = call("/accounts/$accountId/", accountCall = true).jsonObject
        // Details have a daily limit of their own and some banks give none: the meta alone will do.
        val details = runCatching { call("/accounts/$accountId/details/", accountCall = true).jsonObject["account"]?.jsonObject }.getOrNull()
        // A card's "iban" is its masked number (Raiffeisenbank's credit card).
        val metaIban = meta.str("iban")
        val looksLikeIban = metaIban != null && Regex("^[A-Z]{2}\\d{2}[A-Z0-9]+$").matches(metaIban)
        return BankAccountInfo(
            id = accountId,
            iban = details?.str("iban") ?: metaIban?.takeIf { looksLikeIban },
            maskedPan = details?.str("maskedPan") ?: metaIban?.takeIf { !looksLikeIban },
            currency = details?.str("currency").orEmpty(),
            name = (details?.str("name") ?: details?.str("product")).orEmpty().trim(),
            ownerName = (meta.str("owner_name") ?: details?.str("ownerName")).orEmpty().trim(),
        )
    }

    override suspend fun transactions(accountId: String, from: LocalDate?): List<JsonObject> {
        val response = call(
            "/accounts/$accountId/transactions/",
            query = if (from != null) mapOf("date_from" to from.toString()) else emptyMap(),
            accountCall = true,
        ).jsonObject
        // Pending ones change or vanish, so they're left for a later sync.
        return (response["transactions"]?.jsonObject?.get("booked") as? JsonArray)?.map { it.jsonObject }.orEmpty()
    }

    override suspend fun balance(accountId: String): BankBalance? {
        val balances = ((call("/accounts/$accountId/balances/", accountCall = true).jsonObject["balances"]) as? JsonArray)
            ?.map { it.jsonObject }.orEmpty()
        val best = balances.minByOrNull { BALANCE_PREFERENCE.indexOf(it.str("balanceType")).let { i -> if (i < 0) BALANCE_PREFERENCE.size else i } }
            ?: return null
        val amount = best["balanceAmount"]?.jsonObject ?: return null
        val value = amount.str("amount")?.toBigDecimalOrNull() ?: return null
        val currency = amount.str("currency") ?: return null
        return BankBalance(
            amountMinor = toMinor(value, currency),
            currency = currency,
            type = best.str("balanceType").orEmpty(),
            date = best.str("referenceDate")?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() },
        )
    }

    override suspend fun deleteRequisition(requisitionId: String) {
        call("/requisitions/$requisitionId/", "DELETE")
    }
}

/** Requests give up after a minute (a bank can be slow to answer), connecting after 15 seconds. */
private fun defaultClient() = HttpClient(Java) {
    install(HttpTimeout) {
        connectTimeoutMillis = 15_000
        requestTimeoutMillis = 60_000
        socketTimeoutMillis = 60_000
    }
}

/** Runs a request, turning a network failure or timeout into a [BankDataException] the callers already handle. */
private suspend fun <T> network(block: suspend () -> T): T =
    try {
        block()
    } catch (e: HttpRequestTimeoutException) {
        throw BankDataException(504, "GoCardless didn't answer in time")
    } catch (e: IOException) {
        throw BankDataException(504, "Couldn't reach GoCardless: ${e.message ?: e::class.simpleName}")
    }

/** Booked balances first (an "available" balance adds a card's credit line or an overdraft), the most current of each kind first. */
private val BALANCE_PREFERENCE = listOf("interimBooked", "closingBooked", "expected", "interimAvailable", "closingAvailable")

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
