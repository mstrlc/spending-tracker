package dev.spendingtracker.server

import dev.spendingtracker.server.bank.AgreementInfo
import dev.spendingtracker.server.bank.BankAccountInfo
import dev.spendingtracker.server.bank.BankBalance
import dev.spendingtracker.server.bank.BankData
import dev.spendingtracker.server.bank.BankDataException
import dev.spendingtracker.server.bank.BankLink
import dev.spendingtracker.server.bank.BankListing
import dev.spendingtracker.server.bank.LinkState
import dev.spendingtracker.server.bank.RequisitionInfo
import dev.spendingtracker.shared.api.Account
import dev.spendingtracker.shared.api.AccountInput
import dev.spendingtracker.shared.api.AccountKind
import dev.spendingtracker.shared.api.BankConnection
import dev.spendingtracker.shared.api.BankConnectionStarted
import dev.spendingtracker.shared.api.BankConnectionStatus
import dev.spendingtracker.shared.api.BankConnections
import dev.spendingtracker.shared.api.BankFeed
import dev.spendingtracker.shared.api.BankRow
import dev.spendingtracker.shared.api.ConnectableBank
import dev.spendingtracker.shared.api.CreateConnectionFeeds
import dev.spendingtracker.shared.api.FeedFromAccount
import dev.spendingtracker.shared.api.InboxRule
import dev.spendingtracker.shared.api.InboxRuleInput
import dev.spendingtracker.shared.api.RuleOutcome
import dev.spendingtracker.shared.api.StartBankConnection
import dev.spendingtracker.shared.api.SyncResult
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

private class FakeBankData : BankData {
    var listings = listOf(BankListing("AIRBANK_CZ", "Air Bank", null, 730, 90))
    var linkState = LinkState.PENDING
    var accountIds = listOf("ext-acc-1")
    var accounts = mutableMapOf(
        "ext-acc-1" to BankAccountInfo("ext-acc-1", "CZ6508000000192000145399", null, "CZK", "Běžný účet", "Alice"),
    )
    var transactionsByAccount = mutableMapOf<String, List<JsonObject>>()
    var balanceByAccount = mutableMapOf<String, BankBalance?>()
    var accountCalls = 0
    var deletedRequisitions = mutableListOf<String>()
    var recordedFrom = mutableListOf<LocalDate?>()
    var failWith: BankDataException? = null

    override suspend fun institutions(country: String) = listings

    override suspend fun startLink(institutionId: String, redirect: String, reference: String): BankLink {
        val name = listings.first { it.id == institutionId }.name
        return BankLink("req-1", "agr-1", "https://bank.example/auth?ref=$reference", name)
    }

    override suspend fun requisition(requisitionId: String) = RequisitionInfo(linkState, "agr-1", accountIds)

    override suspend fun agreement(agreementId: String) =
        AgreementInfo(accepted = Clock.System.now() - 1.days, accessValidForDays = 90)

    override suspend fun account(accountId: String): BankAccountInfo {
        accountCalls++
        return accounts.getValue(accountId)
    }

    override suspend fun transactions(accountId: String, from: LocalDate?): List<JsonObject> {
        failWith?.let { throw it }
        recordedFrom += from
        return transactionsByAccount[accountId].orEmpty()
    }

    override suspend fun balance(accountId: String): BankBalance? = balanceByAccount[accountId]

    override suspend fun deleteRequisition(requisitionId: String) {
        deletedRequisitions += requisitionId
    }
}

private fun gcTx(id: String, date: String, amount: String, currency: String = "CZK", text: String = "Rohlik.cz"): JsonObject =
    buildJsonObject {
        put("transactionId", id)
        put("bookingDate", date)
        put("valueDate", date)
        putJsonObject("transactionAmount") {
            put("amount", amount)
            put("currency", currency)
        }
        put("remittanceInformationUnstructured", text)
    }

class BankConnectionTest {
    @Test
    fun unconfiguredServerReportsNotConfigured() = apiTest(bank = null) {
        val client = newClient()
        client.signup()
        val household = client.createHousehold()

        val list = client.get("/api/households/${household.id}/bank-connections")
        assertEquals(HttpStatusCode.OK, list.status)
        val body = list.body<BankConnections>()
        assertFalse(body.configured)
        assertTrue(body.connections.isEmpty())

        client.post("/api/households/${household.id}/bank-connections") {
            setBody(StartBankConnection("AIRBANK_CZ"))
        }.assertError(HttpStatusCode.ServiceUnavailable, "bank_not_configured")
    }

    @Test
    fun fullConnectionAndSyncLifecycle() {
        val bank = FakeBankData()
        apiTest(bank = bank) {
            val client = newClient()
            client.signup()
            val household = client.createHousehold()
            val account = client.post("/api/households/${household.id}/accounts") {
                setBody(AccountInput("Air Bank", kind = AccountKind.BANK))
            }.body<Account>()

            // Institutions
            val institutions = client.get("/api/households/${household.id}/bank-institutions?country=CZ")
                .body<List<ConnectableBank>>()
            assertEquals(listOf("AIRBANK_CZ"), institutions.map { it.id })

            // Start connection
            val started = client.post("/api/households/${household.id}/bank-connections") {
                setBody(StartBankConnection("AIRBANK_CZ"))
            }
            assertEquals(HttpStatusCode.Created, started.status)
            val startBody = started.body<BankConnectionStarted>()
            assertTrue(startBody.link.contains(startBody.connectionId))

            // Finish while pending
            val pending = client.post("/api/households/${household.id}/bank-connections/${startBody.connectionId}/finish")
                .body<BankConnection>()
            assertEquals(BankConnectionStatus.PENDING, pending.status)

            // Finish once linked
            bank.linkState = LinkState.LINKED
            val linked = client.post("/api/households/${household.id}/bank-connections/${startBody.connectionId}/finish")
                .body<BankConnection>()
            assertEquals(BankConnectionStatus.ACTIVE, linked.status)
            assertNotNull(linked.consentExpiresAt)
            assertEquals(1, linked.accounts.size)
            assertEquals(1, bank.accountCalls)

            // Repeat finish doesn't re-fetch already-stored account details
            client.post("/api/households/${household.id}/bank-connections/${startBody.connectionId}/finish")
            assertEquals(1, bank.accountCalls)

            // Create feed from bank account
            val feeds = client.post("/api/households/${household.id}/bank-connections/${startBody.connectionId}/feeds") {
                setBody(
                    CreateConnectionFeeds(
                        listOf(
                            FeedFromAccount(
                                externalId = "ext-acc-1",
                                accountId = account.id,
                                name = "Air Bank CZK",
                                importFrom = LocalDate(2026, 1, 1),
                            ),
                        ),
                    ),
                )
            }
            assertEquals(HttpStatusCode.Created, feeds.status)
            val feed = feeds.body<List<BankFeed>>().single()
            assertEquals("gocardless", feed.source)
            assertEquals(startBody.connectionId, feed.connectionId)

            // Create an auto-skip rule so sync auto-files one row
            client.post("/api/households/${household.id}/inbox-rules") {
                setBody(
                    InboxRuleInput(
                        name = "Skip test",
                        textContains = "skipme",
                        outcome = RuleOutcome.SKIP,
                        skipReason = "Noise",
                        auto = true,
                    ),
                )
            }.also { assertEquals(HttpStatusCode.Created, it.status); it.body<InboxRule>() }

            // First sync: adds 2 rows, auto-files 1, reads balance
            bank.transactionsByAccount["ext-acc-1"] = listOf(
                gcTx("tx-1", "2026-03-10", "-250.00", text = "Rohlik.cz"),
                gcTx("tx-2", "2026-03-11", "-10.00", text = "skipme fee"),
            )
            bank.balanceByAccount["ext-acc-1"] = BankBalance(123450, "CZK", "closingBooked", LocalDate(2026, 3, 11))

            val sync1 = client.post("/api/households/${household.id}/bank-feeds/${feed.id}/sync")
            assertEquals(HttpStatusCode.OK, sync1.status)
            val sync1Body = sync1.body<SyncResult>().feeds.single()
            assertEquals("synced", sync1Body.status)
            assertEquals(2, sync1Body.added)
            assertEquals(1, sync1Body.filed)
            assertEquals(LocalDate(2026, 1, 1), bank.recordedFrom.single())

            // Feed now carries balance & syncsToday = 1
            val updatedFeed = client.get("/api/households/${household.id}/bank-feeds").body<List<BankFeed>>().single()
            assertEquals(1, updatedFeed.syncsToday)
            assertEquals(123450L, updatedFeed.balance?.amountMinor)

            // Second sync asks from 2026-03-11 - 7 days = 2026-03-04 and dedupes existing rows
            val sync2 = client.post("/api/households/${household.id}/bank-feeds/${feed.id}/sync").body<SyncResult>().feeds.single()
            assertEquals(0, sync2.added)
            assertEquals(2, sync2.existing)
            assertEquals(LocalDate(2026, 3, 4), bank.recordedFrom.last())

            // Syncs 3 and 4 succeed, 5th is rate-limited
            assertEquals("synced", client.post("/api/households/${household.id}/bank-feeds/${feed.id}/sync").body<SyncResult>().feeds.single().status)
            assertEquals("synced", client.post("/api/households/${household.id}/bank-feeds/${feed.id}/sync").body<SyncResult>().feeds.single().status)
            val sync5 = client.post("/api/households/${household.id}/bank-feeds/${feed.id}/sync").body<SyncResult>().feeds.single()
            assertEquals("skipped", sync5.status)

            // Delete connection leaves feed & rows intact
            val del = client.delete("/api/households/${household.id}/bank-connections/${startBody.connectionId}?version=${linked.version}")
            assertEquals(HttpStatusCode.NoContent, del.status)
            assertEquals(listOf("req-1"), bank.deletedRequisitions)

            val feedAfterDelete = client.get("/api/households/${household.id}/bank-feeds").body<List<BankFeed>>().single()
            assertNull(feedAfterDelete.connectionId)
            val pendingRows = client.get("/api/households/${household.id}/bank-rows?status=pending").body<List<BankRow>>()
            val decidedRows = client.get("/api/households/${household.id}/bank-rows?status=decided").body<List<BankRow>>()
            assertEquals(1, pendingRows.size)
            assertEquals(1, decidedRows.size)
        }
    }

    @Test
    fun consentLapsedMarksConnectionRevoked() {
        val bank = FakeBankData().apply { linkState = LinkState.LINKED }
        apiTest(bank = bank) {
            val client = newClient()
            client.signup()
            val household = client.createHousehold()
            val account = client.post("/api/households/${household.id}/accounts") {
                setBody(AccountInput("Air Bank", kind = AccountKind.BANK))
            }.body<Account>()

            val started = client.post("/api/households/${household.id}/bank-connections") {
                setBody(StartBankConnection("AIRBANK_CZ"))
            }.body<BankConnectionStarted>()
            client.post("/api/households/${household.id}/bank-connections/${started.connectionId}/finish")
            val feed = client.post("/api/households/${household.id}/bank-connections/${started.connectionId}/feeds") {
                setBody(CreateConnectionFeeds(listOf(FeedFromAccount("ext-acc-1", account.id, "Air Bank"))))
            }.body<List<BankFeed>>().single()

            bank.failWith = BankDataException(401, "Access expired", consentLapsed = true)
            val sync = client.post("/api/households/${household.id}/bank-feeds/${feed.id}/sync").body<SyncResult>().feeds.single()
            assertEquals("error", sync.status)

            val conn = client.get("/api/households/${household.id}/bank-connections").body<BankConnections>().connections.single()
            assertEquals(BankConnectionStatus.REVOKED, conn.status)
        }
    }
}
