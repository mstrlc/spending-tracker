package dev.spendingtracker.server

import dev.spendingtracker.server.bank.BankAccountInfo
import dev.spendingtracker.server.bank.BankBalance
import dev.spendingtracker.server.bank.LinkState
import dev.spendingtracker.shared.api.Account
import dev.spendingtracker.shared.api.AccountInput
import dev.spendingtracker.shared.api.Asset
import dev.spendingtracker.shared.api.AssetHistory
import dev.spendingtracker.shared.api.AssetInput
import dev.spendingtracker.shared.api.AssetKind
import dev.spendingtracker.shared.api.BankConnectionStarted
import dev.spendingtracker.shared.api.ContributionInput
import dev.spendingtracker.shared.api.CreateConnectionFeeds
import dev.spendingtracker.shared.api.FeedFromAccount
import dev.spendingtracker.shared.api.StartBankConnection
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun tx(id: String, date: String, amount: String): JsonObject = buildJsonObject {
    put("transactionId", id)
    put("bookingDate", date)
    put("valueDate", date)
    putJsonObject("transactionAmount") {
        put("amount", amount)
        put("currency", "CZK")
    }
    put("remittanceInformationUnstructured", "Payment $id")
}

class NetWorthHistoryTest {
    @Test
    fun historyCarriesAccountBalancesWorkedBackThroughBankRows() {
        val bank = FakeBankData().apply {
            linkState = LinkState.LINKED
            accountIds = listOf("ext-current", "ext-empty")
            accounts["ext-current"] = BankAccountInfo("ext-current", null, null, "CZK", "Current", "Alice")
            accounts["ext-empty"] = BankAccountInfo("ext-empty", null, null, "CZK", "Old savings", "Alice")
            // The bank's only reading, on 20 March; rows from 1 February on.
            balanceByAccount["ext-current"] = BankBalance(125_000_00, "CZK", "interimBooked", LocalDate(2026, 3, 20))
            balanceByAccount["ext-empty"] = BankBalance(0, "CZK", "interimBooked", LocalDate(2026, 3, 20))
            transactionsByAccount["ext-current"] = listOf(
                tx("t1", "2026-02-10", "30000.00"),
                tx("t2", "2026-03-05", "-5000.00"),
                tx("t3", "2026-03-25", "-1000.00"),
            )
        }
        apiTest(bank = bank) {
            val client = newClient().also { it.signup() }
            val household = "/api/households/${client.createHousehold(currency = "CZK").id}"
            val current = client.post("$household/accounts") { setBody(AccountInput("Current")) }.body<Account>()
            // An empty account with a feed that starts late doesn't hold the history back.
            val empty = client.post("$household/accounts") { setBody(AccountInput("Old savings")) }.body<Account>()
            val fund = client.post("$household/assets") { setBody(AssetInput("Fund", kind = AssetKind.FUND)) }.body<Asset>()
            client.post("$household/assets/${fund.id}/contributions") { setBody(ContributionInput(LocalDate(2025, 12, 1), 50_000_00)) }

            val started = client.post("$household/bank-connections") { setBody(StartBankConnection("AIRBANK_CZ")) }.body<BankConnectionStarted>()
            client.post("$household/bank-connections/${started.connectionId}/finish")
            client.post("$household/bank-connections/${started.connectionId}/feeds") {
                setBody(
                    CreateConnectionFeeds(
                        listOf(
                            FeedFromAccount("ext-current", current.id, importFrom = LocalDate(2026, 2, 1)),
                            FeedFromAccount("ext-empty", empty.id, importFrom = LocalDate(2026, 3, 1)),
                        ),
                    ),
                )
            }
            client.post("$household/bank-connections/${started.connectionId}/sync")

            val points = client.get("$household/assets/history").body<AssetHistory>().points.associateBy { it.date }
            // Before the rows start, the balance can't be told.
            assertNull(points.getValue(LocalDate(2025, 12, 31)).accountsMinor)
            // The day before the first row: the reading less the rows booked since.
            assertEquals(100_000_00, points.getValue(LocalDate(2026, 1, 31)).accountsMinor)
            assertEquals(130_000_00, points.getValue(LocalDate(2026, 2, 28)).accountsMinor)
            // From the reading on, the bank's balance stands.
            assertEquals(125_000_00, points.getValue(LocalDate(2026, 3, 31)).accountsMinor)

            // One asset's history has no accounts.
            val fundPoints = client.get("$household/assets/history?assetId=${fund.id}").body<AssetHistory>().points
            assertNull(fundPoints.last().accountsMinor)
        }
    }
}
