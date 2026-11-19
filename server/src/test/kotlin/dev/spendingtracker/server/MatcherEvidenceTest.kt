package dev.spendingtracker.server

import dev.spendingtracker.server.inbox.CzechAccount
import dev.spendingtracker.shared.api.Account
import dev.spendingtracker.shared.api.AccountInput
import dev.spendingtracker.shared.api.BankFeed
import dev.spendingtracker.shared.api.BankFeedInput
import dev.spendingtracker.shared.api.BankInstitution
import dev.spendingtracker.shared.api.BankRow
import dev.spendingtracker.shared.api.ExpenseInput
import dev.spendingtracker.shared.api.HouseholdSettings
import dev.spendingtracker.shared.api.JointFunding
import dev.spendingtracker.shared.api.StatementResult
import dev.spendingtracker.shared.api.StatementUpload
import dev.spendingtracker.shared.api.SuggestedRecord
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Every statement here is made up: real ones hold household data.
private const val HEADER = "Type,Product,Started Date,Completed Date,Description,Amount,Fee,Currency,State,Balance"

private fun revolut(type: String, day: Int, description: String, amount: String) =
    "$type,Current,2026-09-%02d 08:00:00,2026-09-%02d 08:00:01,$description,$amount,0.00,CZK,COMPLETED,0.00".format(day, day)

/** One GoCardless row of money out to [iban]. */
private fun goCardlessOut(id: String, day: Int, amount: String, iban: String, text: String) = """
    { "transactions": { "booked": [ {
        "transactionId": "$id",
        "bookingDate": "2026-09-%02d",
        "transactionAmount": { "amount": "$amount", "currency": "CZK" },
        "creditorAccount": { "iban": "$iban" },
        "remittanceInformationUnstructured": "$text"
    } ] } }
""".trimIndent().format(day)

/** The matcher needs more than the same amount a few days apart to call two rows one transfer, or a row a duplicate. */
class MatcherEvidenceTest {
    private suspend fun HttpClient.account(base: String, name: String, iban: String? = null) =
        post("$base/accounts") { setBody(AccountInput(name, iban = iban)) }.body<Account>()

    private suspend fun HttpClient.feed(base: String, accountId: String, name: String) =
        post("$base/bank-feeds") { setBody(BankFeedInput(accountId, name, BankInstitution.REVOLUT, currency = "CZK")) }.body<BankFeed>()

    private suspend fun HttpClient.upload(base: String, feedId: String, text: String) {
        val response = post("$base/bank-feeds/$feedId/statements") { setBody(StatementUpload(text)) }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    private suspend fun HttpClient.csv(base: String, feedId: String, vararg lines: String) =
        upload(base, feedId, (listOf(HEADER) + lines).joinToString("\n"))

    private suspend fun HttpClient.pending(base: String) = get("$base/bank-rows").body<List<BankRow>>()

    @Test
    fun aPaymentToSomeoneElsesIbanIsNeverATransfer() = apiTest {
        val client = newClient().also { it.signup() }
        val base = "/api/households/${client.createHousehold().id}"
        val current = client.account(base, "Current", CzechAccount("", "1234567890", "0800").iban)
        val wallet = client.account(base, "Wallet")
        val currentFeed = client.feed(base, current.id, "Current")
        val walletFeed = client.feed(base, wallet.id, "Wallet")
        // 1 000 to the cleaner's account, and a 1 000 top-up of the wallet the next day: the same money, unrelated.
        client.upload(base, currentFeed.id, goCardlessOut("cleaner", 10, "-1000.00", CzechAccount("", "5555555555", "6363").iban, "Cleaning"))
        client.csv(base, walletFeed.id, revolut("Topup", 11, "Apple Pay Top-Up by *1234", "1000.00"))

        val rows = client.pending(base)
        val cleaner = rows.first { it.message == "Cleaning" || it.text == "Cleaning" }
        assertTrue(cleaner.suggestion!!.transferCandidates.isEmpty())
        assertEquals(SuggestedRecord.EXPENSE, cleaner.suggestion!!.record)
        assertTrue(rows.first { it.amountMinor > 0 }.suggestion!!.transferCandidates.isEmpty())
    }

    @Test
    fun identicalTopUpsPairOneToOne() = apiTest {
        val client = newClient().also { it.signup() }
        val base = "/api/households/${client.createHousehold().id}"
        val bankFeed = client.feed(base, client.account(base, "Bank").id, "Bank")
        val walletFeed = client.feed(base, client.account(base, "Wallet").id, "Wallet")
        // Card payments to Revolut and the top-ups they paid for, all 300: each pays for one top-up, nearest first.
        client.csv(base, bankFeed.id, revolut("Card Payment", 3, "Revolut**8460* Dublin", "-300.00"), revolut("Card Payment", 5, "Revolut**8460* Dublin", "-300.00"))
        client.csv(base, walletFeed.id, revolut("Topup", 2, "Apple Pay Top-Up by *3656", "300.00"), revolut("Topup", 5, "Apple Pay Top-Up by *3656", "300.00"))

        val rows = client.pending(base)
        val outs = rows.filter { it.amountMinor < 0 }.sortedBy { it.date }
        val ins = rows.filter { it.amountMinor > 0 }.sortedBy { it.date }
        assertTrue(rows.all { it.suggestion!!.record == SuggestedRecord.TRANSFER })
        // The 5th pairs with the 5th, which leaves the 3rd with the 2nd: no two rows share a first candidate.
        assertEquals(ins[1].id, outs[1].suggestion!!.transferCandidates.first().rowId)
        assertEquals(ins[0].id, outs[0].suggestion!!.transferCandidates.first().rowId)
        assertEquals(outs[0].id, ins[0].suggestion!!.transferCandidates.first().rowId)
        assertEquals(outs[1].id, ins[1].suggestion!!.transferCandidates.first().rowId)
    }

    @Test
    fun aHandEnteredRecordIsADuplicateOnlyWithANameInCommon() = apiTest {
        val client = newClient().also { it.signup() }
        val base = "/api/households/${client.createHousehold().id}"
        val account = client.account(base, "Current")
        val feed = client.feed(base, account.id, "Current")
        // Two hand-entered expenses of the same 250 on the same day: only the bakery one is this bank row.
        client.post("$base/expenses") { setBody(ExpenseInput(LocalDate(2026, 9, 6), 250_00, "Bakery Novak", accountId = account.id)) }
        client.post("$base/expenses") { setBody(ExpenseInput(LocalDate(2026, 9, 6), 250_00, "Haircut", accountId = account.id)) }
        client.csv(base, feed.id, revolut("Card Payment", 6, "PEKARNA NOVAK BRNO", "-250.00"), revolut("Card Payment", 6, "Florist", "-250.00"))

        val rows = client.pending(base)
        assertEquals(listOf("Bakery Novak"), rows.first { it.text.contains("NOVAK", ignoreCase = true) }.suggestion!!.duplicates.map { it.label })
        assertTrue(rows.first { it.text == "Florist" }.suggestion!!.duplicates.isEmpty())
    }

    @Test
    fun bankRowsStartWhereTheBalancesDo() = apiTest {
        val client = newClient().also { it.signup() }
        val base = "/api/households/${client.createHousehold().id}"
        client.put("$base/settings") { setBody(HouseholdSettings(false, LocalDate(2026, 9, 5), JointFunding.POT)) }
        val feed = client.feed(base, client.account(base, "Current").id, "Current")
        // The 2nd is before the balances start, so the old ledger covers it; the 5th and 9th come in.
        val result = client.post("$base/bank-feeds/${feed.id}/statements") {
            setBody(StatementUpload(listOf(HEADER, revolut("Card Payment", 2, "Old", "-10.00"), revolut("Card Payment", 5, "First", "-20.00"), revolut("Card Payment", 9, "Later", "-30.00")).joinToString("\n")))
        }.body<StatementResult>()
        assertEquals(2, result.added)
        assertEquals(1, result.beforeImportFrom)
        assertEquals(setOf("First", "Later"), client.pending(base).map { it.text }.toSet())
    }
}
