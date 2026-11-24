package dev.spendingtracker.server.bank

import dev.spendingtracker.server.apiJson
import dev.spendingtracker.server.auth.userId
import dev.spendingtracker.server.common.ApiException
import dev.spendingtracker.server.common.badRequest
import dev.spendingtracker.server.common.checkVersioned
import dev.spendingtracker.server.common.conflict
import dev.spendingtracker.server.common.notFound
import dev.spendingtracker.server.common.parseId
import dev.spendingtracker.server.common.pathId
import dev.spendingtracker.server.common.versionConflict
import dev.spendingtracker.server.common.versionParam
import dev.spendingtracker.server.db.Accounts
import dev.spendingtracker.server.db.BankConnections
import dev.spendingtracker.server.db.BankFeeds
import dev.spendingtracker.server.db.dbQuery
import dev.spendingtracker.server.db.isUniqueViolation
import dev.spendingtracker.server.household.household
import dev.spendingtracker.server.household.requireOwner
import dev.spendingtracker.server.inbox.dbValue
import dev.spendingtracker.server.inbox.loadFeeds
import dev.spendingtracker.shared.api.ApiError
import dev.spendingtracker.shared.api.BankConnection
import dev.spendingtracker.shared.api.BankConnectionAccount
import dev.spendingtracker.shared.api.BankConnectionStarted
import dev.spendingtracker.shared.api.BankConnectionStatus
import dev.spendingtracker.shared.api.BankConnections as BankConnectionsResponse
import dev.spendingtracker.shared.api.BankFeed
import dev.spendingtracker.shared.api.ConnectableBank
import dev.spendingtracker.shared.api.CreateConnectionFeeds
import dev.spendingtracker.shared.api.FeedFromAccount
import dev.spendingtracker.shared.api.StartBankConnection
import dev.spendingtracker.shared.api.SyncResult
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid
import dev.spendingtracker.shared.api.BankInstitution
import dev.spendingtracker.server.market.market

private val log = LoggerFactory.getLogger("BankRoutes")

/** The bank's accounts as kept in `bank_connections.accounts`. */
@Serializable
private data class StoredAccount(
    val id: String,
    val iban: String? = null,
    val maskedPan: String? = null,
    val currency: String = "",
    val name: String = "",
    val ownerName: String = "",
)

private fun ApplicationCall.setup(): BankSetup =
    application.bankSetup ?: throw ApiException(HttpStatusCode.ServiceUnavailable, "bank_not_configured", "Bank connections aren't set up on this server")

/** Runs a call to the bank, turning its failures into a 502 the person can read. */
private suspend fun <T> bank(block: suspend () -> T): T =
    try {
        block()
    } catch (e: BankDataException) {
        log.warn("Bank call failed: {}", e.message)
        throw ApiException(HttpStatusCode.BadGateway, "bank_error", e.message ?: "The bank didn't answer")
    }

/** Must be inside a HouseholdAccess scope. Owners connect and disconnect banks; any member can sync. */
fun Route.bankRoutes() {
    route("/bank-institutions") {
        get({
            operationId = "listBankInstitutions"
            description = "The banks that can be connected in a country (CZ by default)."
            request { queryParameter<String>("country") { description = "Two-letter country code" } }
            response {
                code(HttpStatusCode.OK) { body<List<ConnectableBank>>() }
                code(HttpStatusCode.ServiceUnavailable) { body<ApiError>() }
                code(HttpStatusCode.BadGateway) { body<ApiError>() }
            }
        }) {
            val setup = call.setup()
            val country = (call.request.queryParameters["country"] ?: "CZ").trim().uppercase()
            if (!Regex("^[A-Z]{2}$").matches(country)) throw badRequest("invalid_country", "Use a 2-letter country code", "country")
            val banks = bank { setup.data.institutions(country) }
            call.respond(banks.map { ConnectableBank(it.id, it.name, it.logo, it.historyDays, it.accessDays) })
        }
    }

    route("/bank-connections") {
        get({
            operationId = "listBankConnections"
            description = "The household's bank connections with consent expiry. `configured` is false when the server has no GoCardless keys."
            response { code(HttpStatusCode.OK) { body<BankConnectionsResponse>() } }
        }) {
            val householdId = call.household.id
            val configured = call.application.bankSetup != null
            val nextSync = call.application.bankSyncHour?.let { hour -> Clock.System.now() + nextPragueRunDelay(hour, Clock.System.now()) }
            call.respond(BankConnectionsResponse(configured, dbQuery { loadConnections(householdId) }, nextSync?.toString()))
        }

        post("/sync", {
            operationId = "syncAllBankConnections"
            description = "Reads the new rows and balances of every feed of every active connection, as the daily sync does. A feed is read at most 4 times a day."
            response {
                code(HttpStatusCode.OK) { body<SyncResult>() }
                code(HttpStatusCode.ServiceUnavailable) { body<ApiError>() }
            }
        }) {
            val setup = call.setup()
            val household = call.household
            val results = BankSync(setup.data, call.application.market).syncHousehold(household.id, household.currency, call.userId)
            call.respond(SyncResult(results))
        }

        post({
            operationId = "startBankConnection"
            description = "Owners only. Asks the aggregator for the bank's consent page: send the person to `link`; the bank sends them back to the app's /bank-connected page, which finishes the connection."
            request { body<StartBankConnection>() }
            response {
                code(HttpStatusCode.Created) { body<BankConnectionStarted>() }
                code(HttpStatusCode.BadRequest) { body<ApiError>() }
                code(HttpStatusCode.Forbidden) { body<ApiError>() }
                code(HttpStatusCode.ServiceUnavailable) { body<ApiError>() }
                code(HttpStatusCode.BadGateway) { body<ApiError>() }
            }
        }) {
            call.requireOwner()
            val setup = call.setup()
            val householdId = call.household.id
            val input = call.receive<StartBankConnection>()
            val institutionId = input.institutionId.trim()
            if (institutionId.isEmpty() || institutionId.length > 100) throw badRequest("invalid_institution", "Choose a bank", "institutionId")
            val id = Uuid.random()
            val link = bank { setup.data.startLink(institutionId, "${setup.appUrl.trimEnd('/')}/h/$householdId/bank-connected", id.toString()) }
            dbQuery {
                BankConnections.insert {
                    it[BankConnections.id] = id
                    it[BankConnections.householdId] = householdId
                    it[BankConnections.institutionId] = institutionId
                    it[institutionName] = link.institutionName
                    it[requisitionId] = link.requisitionId
                    it[agreementId] = link.agreementId
                    it[status] = "pending"
                    it[accounts] = "[]"
                    it[lastError] = ""
                    it[createdBy] = call.userId
                }
            }
            call.respond(HttpStatusCode.Created, BankConnectionStarted(id.toString(), link.link))
        }

        post("/{connectionId}/finish", {
            operationId = "finishBankConnection"
            description = "Owners only. Asks the aggregator how the person's visit to the bank went. When linked, reads the bank's accounts and the consent's expiry; " +
                "a connection still waiting at the bank stays pending. Safe to repeat."
            request { pathParameter<String>("connectionId") }
            response {
                code(HttpStatusCode.OK) { body<BankConnection>() }
                code(HttpStatusCode.Forbidden) { body<ApiError>() }
                code(HttpStatusCode.NotFound) { body<ApiError>() }
                code(HttpStatusCode.ServiceUnavailable) { body<ApiError>() }
                code(HttpStatusCode.BadGateway) { body<ApiError>() }
            }
        }) {
            call.requireOwner()
            val setup = call.setup()
            val householdId = call.household.id
            val id = call.pathId("connectionId", "Bank connection")
            val row = dbQuery { connectionRow(householdId, id) } ?: throw notFound("Bank connection")
            if (row[BankConnections.status] == "pending" || row[BankConnections.status] == "active") {
                val requisition = bank { setup.data.requisition(row[BankConnections.requisitionId]) }
                when (requisition.state) {
                    LinkState.LINKED -> {
                        val agreementId = requisition.agreementId ?: row[BankConnections.agreementId]
                        val agreement = agreementId?.let { bank { setup.data.agreement(it) } }
                        val known = storedAccounts(row[BankConnections.accounts]).associateBy { it.id }
                        // Details have a limit of their own: only the accounts not read before. When one fails, those
                        // read so far are kept, so trying again doesn't spend their calls a second time.
                        val accounts = mutableListOf<StoredAccount>()
                        for (accountId in requisition.accountIds) {
                            accounts += known[accountId] ?: try {
                                bank { setup.data.account(accountId) }.let {
                                    StoredAccount(it.id, it.iban, it.maskedPan, it.currency, it.name, it.ownerName)
                                }
                            } catch (e: ApiException) {
                                val partial = known.values.filter { it.id !in accounts.map(StoredAccount::id) } + accounts
                                dbQuery {
                                    BankConnections.update({ BankConnections.id eq id }) {
                                        it[BankConnections.accounts] = apiJson.encodeToString(partial)
                                    }
                                }
                                throw e
                            }
                        }
                        val accepted = agreement?.accepted
                        val expires = if (agreement != null && accepted != null) accepted + agreement.accessValidForDays.days else null
                        dbQuery {
                            BankConnections.update({ BankConnections.id eq id }) {
                                it[status] = "active"
                                it[BankConnections.accounts] = apiJson.encodeToString(accounts)
                                it[BankConnections.agreementId] = agreementId
                                it[consentExpiresAt] = expires
                                it[lastError] = ""
                                it[updatedAt] = Clock.System.now()
                            }
                        }
                    }
                    LinkState.PENDING -> {}
                    LinkState.EXPIRED -> dbQuery { markConnection(id, BankConnectionStatus.EXPIRED, "The consent has expired. Connect the bank again.") }
                    LinkState.REVOKED -> dbQuery { markConnection(id, BankConnectionStatus.REVOKED, "The consent was withdrawn. Connect the bank again.") }
                    LinkState.FAILED -> dbQuery { markConnection(id, BankConnectionStatus.FAILED, "The bank didn't accept the connection. Try again.") }
                }
            }
            call.respond(dbQuery { loadConnections(householdId, id).single() })
        }

        post("/{connectionId}/feeds", {
            operationId = "createConnectionFeeds"
            description = "Owners only. Makes a feed (source gocardless) of each chosen bank account, read into the chosen ledger account, " +
                "or with `feedId` attaches the bank account to an existing feed (statements uploaded before, or a feed whose connection lapsed)."
            request {
                pathParameter<String>("connectionId")
                body<CreateConnectionFeeds>()
            }
            response {
                code(HttpStatusCode.Created) { body<List<BankFeed>>() }
                code(HttpStatusCode.BadRequest) { body<ApiError>() }
                code(HttpStatusCode.Forbidden) { body<ApiError>() }
                code(HttpStatusCode.NotFound) { body<ApiError>() }
                code(HttpStatusCode.Conflict) { body<ApiError>() }
            }
        }) {
            call.requireOwner()
            val householdId = call.household.id
            val householdCurrency = call.household.currency
            val id = call.pathId("connectionId", "Bank connection")
            val input = call.receive<CreateConnectionFeeds>()
            if (input.feeds.isEmpty()) throw badRequest("required", "Choose at least one account", "feeds")
            val feeds = try {
                dbQuery {
                    val connection = connectionRow(householdId, id) ?: throw notFound("Bank connection")
                    if (connection[BankConnections.status] != "active") {
                        throw conflict("connection_not_active", "The bank isn't connected (any more). Connect it first.")
                    }
                    val stored = storedAccounts(connection[BankConnections.accounts]).associateBy { it.id }
                    val institution = institutionOf(connection[BankConnections.institutionName])
                    val created = input.feeds.mapIndexed { index, wanted ->
                        val path = "feeds[$index]"
                        val account = stored[wanted.externalId] ?: throw badRequest("invalid_bank_account", "Unknown bank account", "$path.externalId")
                        val existingId = parseId(wanted.feedId, "$path.feedId")
                        if (existingId != null) {
                            return@mapIndexed attachFeed(householdId, id, existingId, account, wanted, institution, path)
                        }
                        val ledgerId = parseId(wanted.accountId, "$path.accountId") ?: throw badRequest("required", "Choose an account", "$path.accountId")
                        val ledger = Accounts.selectAll().where { (Accounts.id eq ledgerId) and (Accounts.householdId eq householdId) }.singleOrNull()
                            ?: throw badRequest("invalid_account", "Unknown account", "$path.accountId")
                        // A bank that gave no details leaves the currency unknown: the household's is the likeliest.
                        val currency = account.currency.ifEmpty { householdCurrency }.uppercase()
                        if (BankFeeds.selectAll().where { (BankFeeds.householdId eq householdId) and (BankFeeds.externalId eq account.id) }.any()) {
                            throw conflict("account_already_connected", "This bank account already has a feed", "$path.externalId")
                        }
                        val typed = wanted.name?.trim()?.takeIf { it.isNotEmpty() }?.take(100)
                        if (typed != null && feedNameTaken(householdId, typed)) {
                            throw conflict("name_taken", "A feed named \"$typed\" already exists", "$path.name")
                        }
                        // Unnamed: the ledger account's name (with the currency when it isn't the household's), numbered
                        // when a bank gives several accounts in one currency that read into the same ledger account.
                        val name = typed ?: uniqueFeedName(
                            householdId,
                            ledger[Accounts.name].let { if (currency != householdCurrency) "$it · $currency" else it },
                        )
                        val feedId = Uuid.random()
                        BankFeeds.insert {
                            it[BankFeeds.id] = feedId
                            it[BankFeeds.householdId] = householdId
                            it[accountId] = ledgerId
                            it[BankFeeds.name] = name
                            it[BankFeeds.institution] = institution.dbValue
                            it[feedSource] = "gocardless"
                            it[externalId] = account.id
                            it[iban] = account.iban
                            it[BankFeeds.currency] = currency
                            it[importFrom] = wanted.importFrom
                            it[connectionId] = id
                            it[createdBy] = call.userId
                        }
                        feedId
                    }
                    loadFeeds(householdId).filter { Uuid.parse(it.id) in created }
                }
            } catch (e: Exception) {
                if (e.isUniqueViolation()) throw conflict("name_taken", "Two of these feeds have the same name", "feeds")
                throw e
            }
            call.respond(HttpStatusCode.Created, feeds)
        }

        post("/{connectionId}/sync", {
            operationId = "syncBankConnection"
            description = "Reads the new rows and balances of every feed of the connection, as the daily sync does. A feed is read at most 4 times a day."
            request { pathParameter<String>("connectionId") }
            response {
                code(HttpStatusCode.OK) { body<SyncResult>() }
                code(HttpStatusCode.NotFound) { body<ApiError>() }
                code(HttpStatusCode.ServiceUnavailable) { body<ApiError>() }
            }
        }) {
            val setup = call.setup()
            val household = call.household
            val id = call.pathId("connectionId", "Bank connection")
            if (dbQuery { connectionRow(household.id, id) } == null) throw notFound("Bank connection")
            val results = BankSync(setup.data, call.application.market).syncConnection(household.id, household.currency, id, call.userId)
            call.respond(SyncResult(results))
        }

        delete("/{connectionId}", {
            operationId = "deleteBankConnection"
            description = "Owners only. Withdraws the consent at the bank (as far as it can) and forgets the connection. Its feeds and rows stay, but stop syncing."
            request {
                pathParameter<String>("connectionId")
                queryParameter<Int>("version") { required = true }
            }
            response {
                code(HttpStatusCode.NoContent) {}
                code(HttpStatusCode.Forbidden) { body<ApiError>() }
                code(HttpStatusCode.NotFound) { body<ApiError>() }
                code(HttpStatusCode.Conflict) { body<ApiError>() }
            }
        }) {
            call.requireOwner()
            val householdId = call.household.id
            val id = call.pathId("connectionId", "Bank connection")
            val version = call.versionParam()
            val row = dbQuery { connectionRow(householdId, id) } ?: throw notFound("Bank connection")
            if (row[BankConnections.version] != version) throw versionConflict()
            // Best effort: the consent may be gone already, or the keys not set.
            call.application.bankSetup?.let { setup ->
                try {
                    setup.data.deleteRequisition(row[BankConnections.requisitionId])
                } catch (e: Exception) {
                    log.info("Couldn't withdraw requisition of connection {}: {}", id, e.message)
                }
            }
            dbQuery {
                val affected = BankConnections.deleteWhere { (BankConnections.id eq id) and (BankConnections.householdId eq householdId) and (BankConnections.version eq version) }
                checkVersioned(affected, { connectionRow(householdId, id) != null }, "Bank connection")
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }

    route("/bank-feeds") {
        post("/{feedId}/sync", {
            operationId = "syncBankFeed"
            description = "Reads the feed's new rows and balance now. A feed is read at most 4 times a day."
            request { pathParameter<String>("feedId") }
            response {
                code(HttpStatusCode.OK) { body<SyncResult>() }
                code(HttpStatusCode.NotFound) { body<ApiError>() }
                code(HttpStatusCode.ServiceUnavailable) { body<ApiError>() }
            }
        }) {
            val setup = call.setup()
            val household = call.household
            val id = call.pathId("feedId", "Bank feed")
            if (dbQuery { BankFeeds.selectAll().where { (BankFeeds.id eq id) and (BankFeeds.householdId eq household.id) }.empty() }) throw notFound("Bank feed")
            val result = BankSync(setup.data, call.application.market).syncFeed(household.id, household.currency, id, call.userId)
            call.respond(SyncResult(listOf(result)))
        }
    }
}

private fun connectionRow(householdId: Uuid, id: Uuid) =
    BankConnections.selectAll().where { (BankConnections.id eq id) and (BankConnections.householdId eq householdId) }.singleOrNull()

private fun storedAccounts(json: String): List<StoredAccount> = apiJson.decodeFromString(json)

/** The household's connections (or just [id]), newest first, with the feeds made from their accounts. Call inside a transaction. */
private fun loadConnections(householdId: Uuid, id: Uuid? = null): List<BankConnection> {
    val feeds = BankFeeds.selectAll().where { BankFeeds.householdId eq householdId }.toList()
    val feedByAccount = feeds.filter { it[BankFeeds.externalId] != null }.associate { it[BankFeeds.externalId]!! to it[BankFeeds.id].toString() }
    val activeConnections = BankConnections.select(BankConnections.id)
        .where { (BankConnections.householdId eq householdId) and (BankConnections.status eq "active") }
        .map { it[BankConnections.id] }.toSet()
    // A feed can take a new bank account unless a live connection already reads it.
    val attachable = feeds.filter { feed -> feed[BankFeeds.connectionId].let { it == null || it !in activeConnections } }
    var query = BankConnections.selectAll().where { BankConnections.householdId eq householdId }
    if (id != null) query = BankConnections.selectAll().where { (BankConnections.householdId eq householdId) and (BankConnections.id eq id) }
    return query.orderBy(BankConnections.createdAt to SortOrder.DESC).map { row ->
        BankConnection(
            id = row[BankConnections.id].toString(),
            institutionId = row[BankConnections.institutionId],
            institutionName = row[BankConnections.institutionName],
            status = BankConnectionStatus.valueOf(row[BankConnections.status].uppercase()),
            consentExpiresAt = row[BankConnections.consentExpiresAt]?.toString(),
            lastError = row[BankConnections.lastError],
            version = row[BankConnections.version],
            accounts = storedAccounts(row[BankConnections.accounts]).map { account ->
                val feedId = feedByAccount[account.id]
                BankConnectionAccount(
                    account.id, account.iban, account.maskedPan, account.currency, account.name, account.ownerName, feedId,
                    suggestedFeedId = if (feedId != null) null else suggestFeed(attachable, account)?.toString(),
                )
            },
        )
    }
}

private fun normalizeIban(iban: String?) = iban?.replace(Regex("\\s+"), "")?.uppercase()?.takeIf { it.isNotEmpty() }

/** The feed [account] most likely continues: the only attachable one with its IBAN (and currency, when known). */
private fun suggestFeed(attachable: List<ResultRow>, account: StoredAccount): Uuid? {
    val iban = normalizeIban(account.iban) ?: return null
    val currency = account.currency.uppercase()
    return attachable.singleOrNull {
        normalizeIban(it[BankFeeds.iban]) == iban && (currency.isEmpty() || it[BankFeeds.currency] == currency)
    }?.get(BankFeeds.id)
}

/**
 * Points the existing feed [feedId] at the bank account [account] of [connectionId]. Its rows stay, so the first
 * sync asks from a week before the newest one, and rows already brought in from statements are recognised as such.
 * Call inside a transaction.
 */
private fun attachFeed(
    householdId: Uuid,
    connectionId: Uuid,
    feedId: Uuid,
    account: StoredAccount,
    wanted: FeedFromAccount,
    institution: BankInstitution,
    path: String,
): Uuid {
    val feed = BankFeeds.selectAll().where { (BankFeeds.id eq feedId) and (BankFeeds.householdId eq householdId) }.forUpdate().singleOrNull()
        ?: throw badRequest("invalid_feed", "Unknown bank feed", "$path.feedId")
    val current = feed[BankFeeds.connectionId]
    if (current != null && current != connectionId) {
        val status = BankConnections.select(BankConnections.status).where { BankConnections.id eq current }.singleOrNull()?.get(BankConnections.status)
        if (status == "active") throw conflict("feed_connected", "This feed is already read through another live connection", "$path.feedId")
    }
    parseId(wanted.accountId, "$path.accountId")?.let {
        if (it != feed[BankFeeds.accountId]) throw badRequest("account_mismatch", "An attached feed keeps its own ledger account", "$path.accountId")
    }
    val currency = account.currency.uppercase()
    if (currency.isNotEmpty() && currency != feed[BankFeeds.currency]) {
        throw badRequest("currency_mismatch", "The bank account is in $currency, the feed in ${feed[BankFeeds.currency]}", "$path.feedId")
    }
    if (BankFeeds.selectAll().where { (BankFeeds.householdId eq householdId) and (BankFeeds.externalId eq account.id) and (BankFeeds.id neq feedId) }.any()) {
        throw conflict("account_already_connected", "This bank account already has a feed", "$path.externalId")
    }
    val name = wanted.name?.trim()?.takeIf { it.isNotEmpty() }?.take(100)
    BankFeeds.update({ BankFeeds.id eq feedId }) {
        it[feedSource] = "gocardless"
        it[externalId] = account.id
        it[BankFeeds.connectionId] = connectionId
        if (feed[BankFeeds.iban] == null && account.iban != null) it[iban] = account.iban
        if (feed[BankFeeds.institution] == BankInstitution.OTHER.dbValue) it[BankFeeds.institution] = institution.dbValue
        if (name != null) it[BankFeeds.name] = name
        if (wanted.importFrom != null) it[importFrom] = wanted.importFrom
        it[version] = feed[BankFeeds.version] + 1
        it[updatedAt] = Clock.System.now()
    }
    return feedId
}

/** The reader a bank's rows need, guessed from the bank's name; anything else is read generically. */
internal fun institutionOf(name: String): BankInstitution {
    val n = name.lowercase()
    return when {
        "revolut" in n -> BankInstitution.REVOLUT
        "čsob" in n || "csob" in n -> BankInstitution.CSOB
        "komerční" in n || "komercni" in n -> BankInstitution.KB
        "raiffeisen" in n -> BankInstitution.RAIFFEISEN
        else -> BankInstitution.OTHER
    }
}

private fun feedNameTaken(householdId: Uuid, name: String): Boolean =
    BankFeeds.select(BankFeeds.id).where { (BankFeeds.householdId eq householdId) and (BankFeeds.name.lowerCase() eq name.lowercase()) }.any()

/** [base], or "[base] 2", "[base] 3", … : the first no other feed of the household has (names are unique ignoring case). */
private fun uniqueFeedName(householdId: Uuid, base: String): String {
    val stem = base.take(95)
    return generateSequence(1) { it + 1 }
        .map { n -> if (n == 1) base.take(100) else "$stem $n" }
        .first { !feedNameTaken(householdId, it) }
}
