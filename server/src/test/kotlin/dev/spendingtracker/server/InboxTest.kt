package dev.spendingtracker.server

import dev.spendingtracker.server.market.CzkRate
import dev.spendingtracker.shared.api.Account
import dev.spendingtracker.shared.api.AccountInput
import dev.spendingtracker.shared.api.AccountKind
import dev.spendingtracker.shared.api.ActivityEntity
import dev.spendingtracker.shared.api.ActivityPage
import dev.spendingtracker.shared.api.BankFeed
import dev.spendingtracker.shared.api.BankFeedInput
import dev.spendingtracker.shared.api.BankInstitution
import dev.spendingtracker.shared.api.BankRow
import dev.spendingtracker.shared.api.BankRowOutcome
import dev.spendingtracker.shared.api.BankRowRef
import dev.spendingtracker.shared.api.Expense
import dev.spendingtracker.shared.api.ExpenseInput
import dev.spendingtracker.shared.api.HouseholdImport
import dev.spendingtracker.shared.api.ImportAccount
import dev.spendingtracker.shared.api.ImportBankFeed
import dev.spendingtracker.shared.api.ImportBankRow
import dev.spendingtracker.shared.api.ImportExpense
import dev.spendingtracker.shared.api.ImportResult
import dev.spendingtracker.shared.api.Income
import dev.spendingtracker.shared.api.IncomeInput
import dev.spendingtracker.shared.api.LinkBankRows
import dev.spendingtracker.shared.api.Member
import dev.spendingtracker.shared.api.MemberInput
import dev.spendingtracker.shared.api.SkipBankRows
import dev.spendingtracker.shared.api.StatementResult
import dev.spendingtracker.shared.api.StatementUpload
import dev.spendingtracker.shared.api.SuggestedRecord
import dev.spendingtracker.shared.api.UndoBankRows
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Every statement here is made up: real ones hold household data.
private val REVOLUT_CSV = """
    Type,Product,Started Date,Completed Date,Description,Amount,Fee,Currency,State,Balance
    Card Payment,Current,2026-09-04 17:20:16,2026-09-05 03:04:37,Pizza Place,-349.00,0.00,CZK,COMPLETED,1000.00
    Topup,Current,2026-09-05 09:59:16,2026-09-05 09:59:17,Apple Pay top-up by *1234,500.00,0.00,CZK,COMPLETED,1500.00
    Card Payment,Current,2026-09-06 10:00:00,,Corner Shop,-99.00,0.00,CZK,PENDING,1401.00
    Transfer,Current,2026-09-07 08:00:00,2026-09-07 08:00:01,To Jane Doe,-1200.00,0.00,CZK,COMPLETED,201.00
    Card Payment,Current,2026-09-08 12:00:00,2026-09-09 01:00:00,Pizza Place,-412.50,0.00,CZK,COMPLETED,-211.50
""".trimIndent()

private val RB_CARD_CSV = "﻿" + """
    "Číslo kreditní karty";"Držitel karty";"Datum transakce";"Datum zúčtování";"Typ transakce";"Původní částka";"Původní měna";"Zaúčtovaná částka";"Měna zaúčtování";"Převodní kurs";"Popis/Místo transakce";"Vlastní poznámka";"Název Obchodníka";"Město"
    "123456XXXXXX0000";"Jane Doe";"9. 9. 2026";"11. 9. 2026";"Platba u obchodníka";"";"";"-400";"CZK";"";"SHOP 01";"";"Shop";"Brno"
    "123456XXXXXX0000";"Jane Doe";"10. 9. 2026";"10. 9. 2026";"Splátka klienta";"";"";"5 000,00";"CZK";"";"";"";"";""
""".trimIndent()

class InboxTest {
    private suspend fun HttpClient.account(base: String, name: String, kind: AccountKind = AccountKind.BANK, owner: String? = null) =
        post("$base/accounts") { setBody(AccountInput(name, ownerUserId = owner, kind = kind)) }.body<Account>()

    private suspend fun HttpClient.feed(base: String, input: BankFeedInput) =
        post("$base/bank-feeds") { setBody(input) }.body<BankFeed>()

    private suspend fun HttpClient.upload(base: String, feedId: String, content: String) =
        post("$base/bank-feeds/$feedId/statements") { setBody(StatementUpload(content)) }

    private suspend fun HttpClient.pending(base: String) = get("$base/bank-rows").body<List<BankRow>>()

    private fun BankRow.ref() = BankRowRef(id, version)

    @Test
    fun uploadsStatementsOnceAndReadsThem() = apiTest {
        val client = newClient()
        val me = client.signup()
        val base = "/api/households/${client.createHousehold().id}"
        val revolut = client.account(base, "Revolut", owner = me.id)
        val feed = client.feed(base, BankFeedInput(revolut.id, "Revolut · CZK", BankInstitution.REVOLUT, currency = "czk"))
        assertEquals("CZK", feed.currency)

        val first = client.upload(base, feed.id, REVOLUT_CSV).body<StatementResult>()
        assertEquals(StatementResult("Revolut CSV", added = 4, existing = 0, beforeImportFrom = 0, notBooked = 1), first)
        // The same statement again adds nothing.
        val again = client.upload(base, feed.id, REVOLUT_CSV).body<StatementResult>()
        assertEquals(0, again.added)
        assertEquals(4, again.existing)

        val rows = client.pending(base)
        assertEquals(listOf("Pizza Place", "Jane Doe", "Apple Pay top-up by *1234", "Pizza Place"), rows.map { it.text })
        val pizza = rows.first()
        assertEquals(LocalDate(2026, 9, 8), pizza.date)
        assertEquals(-41_250, pizza.amountMinor)
        assertEquals(revolut.id, pizza.accountId)
        assertEquals("CARD_PAYMENT", pizza.code)
        assertEquals("PIZZA PLACE", pizza.merchantKey)
        assertNull(pizza.ruleId)
        assertEquals("Top-up", rows[2].hint)
        val suggestion = pizza.suggestion!!
        assertEquals(SuggestedRecord.EXPENSE, suggestion.record)
        assertEquals(41_250, suggestion.amountMinor)
        assertEquals("Pizza Place", suggestion.merchant)
        assertEquals(me.id, suggestion.paidByUserId)
        // A top-up moves money between the household's own accounts.
        assertEquals(SuggestedRecord.TRANSFER, rows[2].suggestion!!.record)

        // Money back from a card payment is a refund: a negative expense, not income.
        client.upload(base, feed.id, "Type,Product,Started Date,Completed Date,Description,Amount,Fee,Currency,State,Balance\n" +
            "Card Refund,Current,2026-09-09 12:00:00,2026-09-09 13:00:00,Pizza Place,20.00,0.00,CZK,COMPLETED,0")
        val refund = client.pending(base).first { it.code == "CARD_REFUND" }.suggestion!!
        assertEquals(SuggestedRecord.EXPENSE, refund.record)
        assertEquals(-20_00, refund.amountMinor)

        val listed = client.get("$base/bank-feeds").body<List<BankFeed>>().single()
        assertEquals(5, listed.rowCount)
        assertEquals(5, listed.pendingCount)
        assertEquals(LocalDate(2026, 9, 9), listed.lastDate)

        client.upload(base, feed.id, "date,amount\n2026-01-01,5").assertError(HttpStatusCode.BadRequest, "unreadable_statement")
        val euro = client.feed(base, BankFeedInput(revolut.id, "Revolut · EUR", BankInstitution.REVOLUT, currency = "EUR"))
        client.upload(base, euro.id, REVOLUT_CSV).assertError(HttpStatusCode.BadRequest, "currency_mismatch")
        client.post("$base/bank-feeds") { setBody(BankFeedInput(revolut.id, "revolut · czk", currency = "CZK")) }
            .assertError(HttpStatusCode.Conflict, "name_taken")
    }

    @Test
    fun readsCardStatementsAndSkipsHistoryBeforeImportFrom() = apiTest {
        val client = newClient().also { it.signup() }
        val base = "/api/households/${client.createHousehold().id}"
        val card = client.account(base, "Card", AccountKind.CREDIT_CARD)
        val feed = client.feed(base, BankFeedInput(card.id, "Card", BankInstitution.RAIFFEISEN, currency = "CZK", importFrom = LocalDate(2026, 9, 11)))

        val result = client.upload(base, feed.id, RB_CARD_CSV).body<StatementResult>()
        assertEquals("Raiffeisenbank credit card CSV", result.format)
        assertEquals(1, result.added)
        assertEquals(1, result.beforeImportFrom)
        val row = client.pending(base).single()
        assertEquals("Shop", row.text)
        assertEquals(LocalDate(2026, 9, 9), row.date)
        assertEquals(LocalDate(2026, 9, 11), row.bookingDate)
        assertEquals(-40_000, row.amountMinor)

        // Without the import-from date the repayment comes in too, recognised as one.
        val open = client.feed(base, BankFeedInput(card.id, "Card 2", BankInstitution.RAIFFEISEN, currency = "CZK"))
        client.upload(base, open.id, RB_CARD_CSV)
        val repayment = client.get("$base/bank-rows?feedId=${open.id}").body<List<BankRow>>().first { it.amountMinor > 0 }
        assertEquals("Card repayment", repayment.hint)
        assertEquals(SuggestedRecord.TRANSFER, repayment.suggestion!!.record)
    }

    @Test
    fun decidesRowsAndUndoesThem() = apiTest {
        val client = newClient().also { it.signup() }
        val base = "/api/households/${client.createHousehold().id}"
        val account = client.account(base, "Revolut")
        val feed = client.feed(base, BankFeedInput(account.id, "Revolut · CZK", BankInstitution.REVOLUT, currency = "CZK"))
        client.upload(base, feed.id, REVOLUT_CSV)
        // Typed in by hand two days after the bank's date, rounded.
        val typed = client.post("$base/expenses") {
            setBody(ExpenseInput(LocalDate(2026, 9, 6), 349_00, "Pizza", accountId = account.id))
        }.body<Expense>()

        val rows = client.pending(base)
        val (pizza2, transfer, topup, pizza1) = rows
        assertEquals(listOf(typed.id), pizza1.suggestion!!.duplicates.map { it.id })
        assertTrue(pizza2.suggestion!!.duplicates.isEmpty())

        // The first pizza is the expense already typed in.
        val linked = client.post("$base/bank-rows/link") { setBody(LinkBankRows(listOf(pizza1.ref()), expenseId = typed.id)) }.body<List<BankRow>>()
        assertEquals(BankRowOutcome.EXPENSE, linked.single().outcome)
        assertEquals(typed.id, linked.single().expenseId)
        // Deciding it again, or from an old version, fails.
        client.post("$base/bank-rows/link") { setBody(LinkBankRows(listOf(pizza1.ref()), expenseId = typed.id)) }
            .assertError(HttpStatusCode.Conflict, "version_conflict")
        client.post("$base/bank-rows/link") { setBody(LinkBankRows(listOf(linked.single().ref()), expenseId = typed.id)) }
            .assertError(HttpStatusCode.Conflict, "already_decided")

        // The second becomes a new expense made from the row; the transfer and the top-up together become one.
        val made = client.post("$base/expenses") {
            setBody(ExpenseInput(pizza2.date, 412_50, "Pizza", accountId = account.id, bankRows = listOf(pizza2.ref())))
        }.body<Expense>()
        val skipped = client.post("$base/bank-rows/skip") { setBody(SkipBankRows(listOf(topup.ref()), "Top-up")) }.body<List<BankRow>>()
        assertEquals("Top-up", skipped.single().skipReason)
        client.post("$base/bank-rows/skip") { setBody(SkipBankRows(listOf(transfer.ref()), " ")) }
            .assertError(HttpStatusCode.BadRequest, "required")
        val income = client.post("$base/income") {
            setBody(IncomeInput(transfer.date, 1, "test", bankRows = listOf(transfer.ref())))
        }.body<Income>()

        assertEquals(emptyList(), client.pending(base))
        // Once decided, a pizza with the same descriptor suggests what the last one became.
        val decided = client.get("$base/bank-rows?status=decided").body<List<BankRow>>()
        assertEquals(4, decided.size)
        assertTrue(decided.all { it.suggestion == null && it.decidedByUserId != null })

        // Undoing a row made into a record deletes the record; a hand-typed one stays.
        val undone = client.post("$base/bank-rows/undo") {
            setBody(UndoBankRows(decided.map { it.ref() }))
        }.body<List<BankRow>>()
        assertTrue(undone.all { it.outcome == BankRowOutcome.PENDING })
        client.get("$base/expenses").body<List<Expense>>().let { expenses ->
            assertEquals(listOf(typed.id), expenses.map { it.id })
        }
        assertTrue(client.get("$base/income").body<List<Income>>().none { it.id == income.id })
        assertTrue(made.id.isNotEmpty())

        // A suggestion learns from the decision before it.
        client.post("$base/expenses") {
            setBody(ExpenseInput(pizza2.date, 412_50, "Pizza Napoli", accountId = account.id, bankRows = listOf(undone.first { it.id == pizza2.id }.ref())))
        }
        assertEquals("Pizza Napoli", client.pending(base).first { it.text == "Pizza Place" }.suggestion!!.merchant)

        // Deleting the expense puts its row back.
        val expense = client.get("$base/expenses").body<List<Expense>>().first { it.merchant == "Pizza Napoli" }
        client.delete("$base/expenses/${expense.id}?version=${expense.version}")
        assertEquals(4, client.pending(base).size)

        // A feed with decided rows can't be deleted; one without can.
        client.post("$base/bank-rows/link") { setBody(LinkBankRows(listOf(client.pending(base).last().ref()), expenseId = typed.id)) }
        client.delete("$base/bank-feeds/${feed.id}?version=${feed.version}").assertError(HttpStatusCode.Conflict, "feed_in_use")
    }

    @Test
    fun convertsForeignRowsAtTheDaysFixing() = apiTest(
        FakeMarketData(rates = listOf(CzkRate("EUR", LocalDate(2026, 9, 4), "25.000".toBigDecimal()))),
    ) {
        val client = newClient().also { it.signup() }
        val base = "/api/households/${client.createHousehold().id}"
        val account = client.account(base, "Revolut")
        val feed = client.feed(base, BankFeedInput(account.id, "Revolut · EUR", BankInstitution.REVOLUT, currency = "EUR"))
        client.upload(base, feed.id, "Type,Product,Started Date,Completed Date,Description,Amount,Fee,Currency,State,Balance\n" +
            "Card Payment,Current,2026-09-06 17:20:16,2026-09-07 03:04:37,Cafe,-2.50,0.00,EUR,COMPLETED,10.00")
        val suggestion = client.pending(base).single().suggestion!!
        assertEquals(62_50, suggestion.amountMinor)
        assertEquals(250, suggestion.originalAmountMinor)
        assertEquals("EUR", suggestion.originalCurrency)
    }

    @Test
    fun importsBankRowsWithTheirDecisions() = apiTest {
        val client = newClient().also { it.signup() }
        val base = "/api/households/${client.createHousehold().id}"
        fun raw(id: String, amount: String, name: String, iban: String? = null): JsonObject = Json.parseToJsonElement(
            """{"transactionId":"$id","bookingDate":"2026-09-10","transactionAmount":{"amount":"$amount","currency":"CZK"},""" +
                """"creditorName":"$name"${iban?.let { ""","creditorAccount":{"bban":"$it"}""" } ?: ""}}""",
        ).jsonObject
        val file = HouseholdImport(
            currency = "CZK",
            accounts = listOf(ImportAccount("Current", iban = "CZ65 0800 0000 1920 0014 5399"), ImportAccount("Savings")),
            expenses = listOf(ImportExpense("old:1", LocalDate(2026, 9, 10), 250_00, "Bakery", account = "Current")),
            bankFeeds = listOf(ImportBankFeed("Current · CZK", "Current", "CZK", BankInstitution.KB)),
            bankRows = listOf(
                ImportBankRow("Current · CZK", raw("t1", "-250.00", "Bakery"), expense = "old:1"),
                ImportBankRow("current · czk", raw("t2", "-100.00", "Someone"), skipped = "Not ours"),
                ImportBankRow("Current · CZK", raw("t3", "-5000", "Me", "19-2000145399/0800"), hint = "Old app: Between your own accounts"),
                ImportBankRow("Current · CZK", raw("t4", "-70", "Kiosk")),
            ),
        )
        val result = client.post("$base/import") { setBody(file) }.body<ImportResult>()
        assertEquals(1, result.created.bankFeeds)
        assertEquals(4, result.created.bankRows)
        val pending = client.pending(base)
        assertEquals(2, pending.size)
        // The old app's hint wins over the reader's own.
        assertEquals("Old app: Between your own accounts", pending.first { it.text == "Me" }.hint)
        assertEquals("19-2000145399/0800", pending.first { it.text == "Me" }.counterpartyAccount)
        assertEquals("CZ6508000000192000145399", pending.first { it.text == "Me" }.counterpartyIban)
        assertEquals("", pending.first { it.text == "Kiosk" }.hint)
        val decided = client.get("$base/bank-rows?status=decided").body<List<BankRow>>()
        assertEquals(setOf(BankRowOutcome.EXPENSE, BankRowOutcome.SKIPPED), decided.map { it.outcome }.toSet())
        assertNull(decided.first { it.outcome == BankRowOutcome.SKIPPED }.expenseId)

        // Again: nothing new, and decisions taken since stay.
        client.post("$base/bank-rows/undo") { setBody(UndoBankRows(listOf(decided.first { it.outcome == BankRowOutcome.SKIPPED }.ref()))) }
        val again = client.post("$base/import") { setBody(file) }.body<ImportResult>()
        assertEquals(0, again.created.bankRows)
        assertEquals(4, again.existing.bankRows)
        assertEquals(3, client.pending(base).size)

        client.post("$base/import") {
            setBody(file.copy(bankRows = listOf(ImportBankRow("Nope", raw("t9", "-1", "X")))))
        }.assertError(HttpStatusCode.BadRequest, "unknown_feed")
    }

    @Test
    fun revolutFeesAndCsvThenGoCardlessDedup() = apiTest {
        val client = newClient()
        val alice = client.signup()
        val household = client.createHousehold()
        val base = "/api/households/${household.id}"
        val account = client.post("$base/accounts") {
            setBody(AccountInput("Revolut", kind = AccountKind.BANK))
        }.body<Account>()
        val feed = client.post("$base/bank-feeds") {
            setBody(BankFeedInput(account.id, "Revolut CZK", BankInstitution.REVOLUT, currency = "CZK"))
        }.body<BankFeed>()

        // Revolut CSV with a non-zero fee on the first row and 0.00 on the second
        val csv = """
            Type,Product,Started Date,Completed Date,Description,Amount,Fee,Currency,State,Balance
            CARD_PAYMENT,Current,2026-09-10 10:00:00,2026-09-10 12:00:00,Albert,-250.00,15.00,CZK,COMPLETED,1000.00
            CARD_PAYMENT,Current,2026-09-11 10:00:00,2026-09-11 12:00:00,Bakery,-80.00,0.00,CZK,COMPLETED,920.00
        """.trimIndent()
        val res1 = client.post("$base/bank-feeds/${feed.id}/statements") {
            setBody(StatementUpload(csv, "rev.csv"))
        }.body<StatementResult>()
        assertEquals(3, res1.added)

        val pending = client.pending(base)
        val feeRow = pending.single { it.code == "FEE" }
        assertEquals(-1500L, feeRow.amountMinor)
        assertEquals("Revolut fee: Albert", feeRow.text)

        // Later GoCardless JSON with the same Albert and Bakery transactions (different externalId)
        val gcJson = """
            {
              "transactions": {
                "booked": [
                  {
                    "transactionId": "gc-albert-1",
                    "bookingDate": "2026-09-10",
                    "valueDate": "2026-09-10",
                    "transactionAmount": { "amount": "-250.00", "currency": "CZK" },
                    "remittanceInformationUnstructured": "Albert"
                  },
                  {
                    "transactionId": "gc-bakery-1",
                    "bookingDate": "2026-09-11",
                    "valueDate": "2026-09-11",
                    "transactionAmount": { "amount": "-80.00", "currency": "CZK" },
                    "remittanceInformationUnstructured": "Bakery"
                  }
                ]
              }
            }
        """.trimIndent()
        val res2 = client.post("$base/bank-feeds/${feed.id}/statements") {
            setBody(StatementUpload(gcJson, "gc.json"))
        }.body<StatementResult>()
        assertEquals(0, res2.added)
        assertEquals(2, res2.existing)
    }

    @Test
    fun memberLegalNameHintDecidedOutcomeLabelAndActivity() = apiTest {
        val client = newClient()
        val alice = client.signup()
        val household = client.createHousehold()
        val base = "/api/households/${household.id}"

        // Set Alice's legal name for bank transfer hints
        val updatedMember = client.put("$base/members/${alice.id}") {
            setBody(MemberInput("Alice", legalName = "Nováková Alice"))
        }.body<Member>()
        assertEquals("Nováková Alice", updatedMember.legalName)

        val account = client.post("$base/accounts") {
            setBody(AccountInput("Current", kind = AccountKind.BANK))
        }.body<Account>()
        val feed = client.post("$base/bank-feeds") {
            setBody(BankFeedInput(account.id, "Current CZK", BankInstitution.OTHER, currency = "CZK"))
        }.body<BankFeed>()

        val gcJson = """
            {
              "transactions": {
                "booked": [
                  {
                    "transactionId": "t-legal",
                    "bookingDate": "2026-09-12",
                    "transactionAmount": { "amount": "-500.00", "currency": "CZK" },
                    "debtorName": "NOVAKOVA ALICE",
                    "creditorName": "NOVAKOVA ALICE",
                    "remittanceInformationUnstructured": "Transfer"
                  },
                  {
                    "transactionId": "t-exp",
                    "bookingDate": "2026-09-13",
                    "transactionAmount": { "amount": "-120.00", "currency": "CZK" },
                    "remittanceInformationUnstructured": "Cafe"
                  }
                ]
              }
            }
        """.trimIndent()
        client.post("$base/bank-feeds/${feed.id}/statements") { setBody(StatementUpload(gcJson, "gc.json")) }

        val pending = client.pending(base)
        assertEquals("To or from someone in the household", pending.first { it.text == "NOVAKOVA ALICE" }.hint)

        // File the cafe row as an expense -> decided row carries outcomeLabel = "Cafe"
        val expRow = pending.first { it.text == "Cafe" }
        client.post("$base/expenses") {
            setBody(
                ExpenseInput(
                    date = expRow.date,
                    amountMinor = 12000,
                    merchant = "Cafe",
                    accountId = account.id,
                    bankRows = listOf(expRow.ref()),
                ),
            )
        }.also { assertEquals(HttpStatusCode.Created, it.status) }

        val decided = client.get("$base/bank-rows?status=decided").body<List<BankRow>>()
        assertEquals("Cafe", decided.single().outcomeLabel)

        // Bank rows appear in Activity
        val activity = client.get("$base/activity?entity=bank_row").body<ActivityPage>()
        assertTrue(activity.entries.any { it.entity == ActivityEntity.BANK_ROW && it.label == "Cafe" })
    }
}
