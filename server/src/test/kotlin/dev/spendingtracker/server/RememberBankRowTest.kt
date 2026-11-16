package dev.spendingtracker.server

import dev.spendingtracker.shared.api.Account
import dev.spendingtracker.shared.api.AccountInput
import dev.spendingtracker.shared.api.BankFeed
import dev.spendingtracker.shared.api.BankFeedInput
import dev.spendingtracker.shared.api.BankInstitution
import dev.spendingtracker.shared.api.BankRow
import dev.spendingtracker.shared.api.BankRowRef
import dev.spendingtracker.shared.api.ExpenseInput
import dev.spendingtracker.shared.api.RememberBankRows
import dev.spendingtracker.shared.api.StatementUpload
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Every statement here is made up: real ones hold household data.
private const val HEADER = "Type,Product,Started Date,Completed Date,Description,Amount,Fee,Currency,State,Balance"

private fun revolut(month: Int, description: String, amount: String) =
    "Transfer,Current,2026-%02d-05 08:00:00,2026-%02d-05 08:00:01,$description,$amount,0.00,CZK,COMPLETED,0.00".format(month, month)

class RememberBankRowTest {
    private suspend fun HttpClient.upload(base: String, feedId: String, line: String) {
        val response = post("$base/bank-feeds/$feedId/statements") { setBody(StatementUpload(listOf(HEADER, line).joinToString("\n"))) }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    private suspend fun HttpClient.pending(base: String) = get("$base/bank-rows").body<List<BankRow>>()

    @Test
    fun aOneOffDoesntTeachWhatThePayeeIs() = apiTest {
        val client = newClient().also { it.signup() }
        val base = "/api/households/${client.createHousehold().id}"
        val account = client.post("$base/accounts") { setBody(AccountInput("Current")) }.body<Account>()
        val feed = client.post("$base/bank-feeds") {
            setBody(BankFeedInput(account.id, "Current", BankInstitution.REVOLUT, currency = "CZK"))
        }.body<BankFeed>()

        // A friend paid for dinner; paying him back is filed as the restaurant.
        client.upload(base, feed.id, revolut(10, "Vladimir Friend", "-418.00"))
        val dinner = client.pending(base).single()
        assertTrue(dinner.remember)
        client.post("$base/expenses") {
            setBody(ExpenseInput(dinner.date, 418_00, merchant = "Bratrs", accountId = account.id, bankRows = listOf(BankRowRef(dinner.id, dinner.version))))
        }

        // Remembered, the next payment to him is suggested as the restaurant too.
        client.upload(base, feed.id, revolut(11, "Vladimir Friend", "-250.00"))
        assertEquals("Bratrs", client.pending(base).single().suggestion!!.merchant)

        // Not remembered, it isn't: the suggestion goes back to what the bank says.
        val filed = client.get("$base/bank-rows?status=all").body<List<BankRow>>().first { it.id == dinner.id }
        val changed = client.post("$base/bank-rows/remember") {
            setBody(RememberBankRows(listOf(BankRowRef(filed.id, filed.version)), remember = false))
        }.body<List<BankRow>>().single()
        assertFalse(changed.remember)
        assertEquals(filed.version + 1, changed.version)
        assertEquals("Vladimir Friend", client.pending(base).single().suggestion!!.merchant)

        client.post("$base/bank-rows/remember") {
            setBody(RememberBankRows(listOf(BankRowRef(filed.id, filed.version)), remember = true))
        }.assertError(HttpStatusCode.Conflict, "version_conflict")
    }
}
