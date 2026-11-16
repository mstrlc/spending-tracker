package dev.spendingtracker.server

import dev.spendingtracker.shared.api.Account
import dev.spendingtracker.shared.api.AccountInput
import dev.spendingtracker.shared.api.AccountKind
import dev.spendingtracker.shared.api.BankFeed
import dev.spendingtracker.shared.api.BankFeedInput
import dev.spendingtracker.shared.api.BankInstitution
import dev.spendingtracker.shared.api.BankRow
import dev.spendingtracker.shared.api.Category
import dev.spendingtracker.shared.api.CategoryKind
import dev.spendingtracker.shared.api.StatementUpload
import dev.spendingtracker.shared.api.SuggestedRecord
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Savings accounts: their own kind, a tax rate on interest, and interest suggested as income with the tax worked back. */
class SavingsTest {
    // Made up, shaped like ČSOB's: interest booked at the month's end with no text and no counterparty.
    private val statement = """
        { "transactions": { "booked": [
            { "transactionId": "interest-oct", "bookingDate": "2026-10-31",
              "transactionAmount": { "amount": "656.49", "currency": "CZK" } },
            { "transactionId": "deposit", "bookingDate": "2026-10-20",
              "transactionAmount": { "amount": "2000.00", "currency": "CZK" },
              "debtorName": "Jane Doe", "remittanceInformationUnstructured": "Savings" }
        ] } }
    """.trimIndent()

    @Test
    fun aSavingsAccountWithholdsFifteenPercentUnlessToldOtherwise() = apiTest {
        val client = newClient().also { it.signup() }
        val base = "/api/households/${client.createHousehold().id}"
        val savings = client.post("$base/accounts") { setBody(AccountInput("Savings", kind = AccountKind.SAVINGS)) }.body<Account>()
        assertEquals(AccountKind.SAVINGS, savings.kind)
        assertEquals(15, savings.interestTaxPercent)

        val untaxed = client.put("$base/accounts/${savings.id}") {
            setBody(AccountInput("Savings", kind = AccountKind.SAVINGS, interestTaxPercent = 0, version = savings.version))
        }.body<Account>()
        assertEquals(0, untaxed.interestTaxPercent)
        // A current account has no interest tax, whatever is sent.
        val current = client.post("$base/accounts") { setBody(AccountInput("Current", interestTaxPercent = 15)) }.body<Account>()
        assertNull(current.interestTaxPercent)
        client.post("$base/accounts") { setBody(AccountInput("Bad", kind = AccountKind.SAVINGS, interestTaxPercent = 100)) }
            .assertError(HttpStatusCode.BadRequest, "invalid_interest_tax")
    }

    @Test
    fun interestIsSuggestedAsIncomeWithTheTaxWorkedBack() = apiTest {
        val client = newClient().also { it.signup() }
        val base = "/api/households/${client.createHousehold().id}"
        val savings = client.post("$base/accounts") { setBody(AccountInput("Savings", kind = AccountKind.SAVINGS)) }.body<Account>()
        val feed = client.post("$base/bank-feeds") { setBody(BankFeedInput(savings.id, "Savings", BankInstitution.CSOB, currency = "CZK")) }.body<BankFeed>()
        assertEquals(HttpStatusCode.OK, client.post("$base/bank-feeds/${feed.id}/statements") { setBody(StatementUpload(statement, "savings.json")) }.status)

        val rows = client.get("$base/bank-rows").body<List<BankRow>>()
        val interest = rows.first { it.amountMinor == 656_49L }
        assertEquals("Interest", interest.hint)
        val s = interest.suggestion!!
        assertEquals(SuggestedRecord.INCOME, s.record)
        assertEquals("Interest", s.incomeSource)
        assertEquals(656_49L, s.amountMinor)
        // 656.49 is 85% of 772.34: 115.85 was withheld.
        assertEquals(115_85L, s.withheldTaxMinor)
        // Filed under the household's interest income category.
        val categories = client.get("$base/categories").body<List<Category>>()
        assertEquals(categories.single { it.name == "Bank bonuses & interest" && it.kind == CategoryKind.INCOME }.id, s.categoryId)

        // Money paid in by someone isn't interest.
        val deposit = rows.first { it.amountMinor == 2000_00L }
        assertEquals("", deposit.hint)
        assertNull(deposit.suggestion!!.withheldTaxMinor)
        assertNull(deposit.suggestion!!.categoryId)
    }
}
