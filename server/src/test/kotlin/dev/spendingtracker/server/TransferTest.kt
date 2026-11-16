package dev.spendingtracker.server

import dev.spendingtracker.server.market.CzkRate
import dev.spendingtracker.shared.api.Account
import dev.spendingtracker.shared.api.AccountInput
import dev.spendingtracker.shared.api.Balances
import dev.spendingtracker.shared.api.BankFeed
import dev.spendingtracker.shared.api.BankFeedInput
import dev.spendingtracker.shared.api.BankInstitution
import dev.spendingtracker.shared.api.BankRow
import dev.spendingtracker.shared.api.BankRowOutcome
import dev.spendingtracker.shared.api.BankRowRef
import dev.spendingtracker.shared.api.HouseholdImport
import dev.spendingtracker.shared.api.ImportAccount
import dev.spendingtracker.shared.api.ImportBankFeed
import dev.spendingtracker.shared.api.ImportBankRow
import dev.spendingtracker.shared.api.JointContribution
import dev.spendingtracker.shared.api.Member
import dev.spendingtracker.shared.api.MemberInput
import dev.spendingtracker.shared.api.Settlement
import dev.spendingtracker.shared.api.SettleBankRows
import dev.spendingtracker.shared.api.StatementUpload
import dev.spendingtracker.shared.api.SuggestedRecord
import dev.spendingtracker.shared.api.Transfer
import dev.spendingtracker.shared.api.TransferBankRows
import dev.spendingtracker.shared.api.TransferInput
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
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Every statement here is made up: real ones hold household data.
private const val HEADER = "Type,Product,Started Date,Completed Date,Description,Amount,Fee,Currency,State,Balance"

private fun revolut(type: String, day: Int, description: String, amount: String, currency: String = "CZK") =
    "$type,Current,2026-09-%02d 08:00:00,2026-09-%02d 08:00:01,$description,$amount,0.00,$currency,COMPLETED,0.00".format(day, day)

class TransferTest {
    private suspend fun HttpClient.account(base: String, name: String, owner: String? = null) =
        post("$base/accounts") { setBody(AccountInput(name, ownerUserId = owner)) }.body<Account>()

    private suspend fun HttpClient.feed(base: String, accountId: String, name: String, currency: String = "CZK") =
        post("$base/bank-feeds") { setBody(BankFeedInput(accountId, name, BankInstitution.REVOLUT, currency = currency)) }.body<BankFeed>()

    private suspend fun HttpClient.upload(base: String, feedId: String, vararg lines: String) {
        val response = post("$base/bank-feeds/$feedId/statements") { setBody(StatementUpload((listOf(HEADER) + lines).joinToString("\n"))) }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    private suspend fun HttpClient.pending(base: String) = get("$base/bank-rows").body<List<BankRow>>()
    private suspend fun HttpClient.row(base: String, id: String) = get("$base/bank-rows?status=all").body<List<BankRow>>().first { it.id == id }
    private suspend fun HttpClient.transfers(base: String) = get("$base/transfers").body<List<Transfer>>()

    private fun BankRow.ref() = BankRowRef(id, version)

    @Test
    fun pairsTwoRowsIntoOneTransfer() = apiTest {
        val client = newClient().also { it.signup() }
        val base = "/api/households/${client.createHousehold().id}"
        val current = client.account(base, "Current")
        val savings = client.account(base, "Savings")
        val currentFeed = client.feed(base, current.id, "Current")
        val savingsFeed = client.feed(base, savings.id, "Savings")
        client.upload(base, currentFeed.id, revolut("Transfer", 1, "To Savings", "-1000.00"), revolut("Card Payment", 1, "Bakery", "-1000.00"))
        client.upload(base, savingsFeed.id, revolut("Transfer", 2, "From Current", "1000.00"), revolut("Transfer", 9, "From Elsewhere", "1000.00"))

        val rows = client.pending(base)
        val out = rows.first { it.text == "Savings" }
        val inn = rows.first { it.text == "Current" }
        // The bakery payment is the same amount, but money in the same direction isn't a candidate; neither is a row 8 days away.
        assertEquals(listOf(inn.id), out.suggestion!!.transferCandidates.map { it.rowId })
        assertEquals(listOf(out.id, rows.first { it.text == "Bakery" }.id).toSet(), rows.filter { it.amountMinor == -100_000L }.map { it.id }.toSet())
        val candidate = out.suggestion!!.transferCandidates.single()
        assertEquals(inn.version, candidate.version)
        assertEquals(savings.id, candidate.accountId)
        assertEquals(LocalDate(2026, 9, 2), candidate.date)
        assertEquals(100_000L, candidate.amountMinor)
        assertEquals("CZK", candidate.currency)
        assertEquals(SuggestedRecord.TRANSFER, out.suggestion!!.record)
        assertEquals(SuggestedRecord.TRANSFER, inn.suggestion!!.record)
        // Both of the outgoing 1 000 rows want the incoming one nearest in time; the far one isn't offered.
        assertEquals(listOf(inn.id), rows.first { it.text == "Bakery" }.suggestion!!.transferCandidates.map { it.rowId })
        assertEquals(listOf(out.id, rows.first { it.text == "Bakery" }.id).sorted(), inn.suggestion!!.transferCandidates.map { it.rowId }.sorted())
        assertEquals(SuggestedRecord.INCOME, rows.first { it.text == "Elsewhere" }.suggestion!!.record)

        val filed = client.post("$base/bank-rows/transfer") { setBody(TransferBankRows(listOf(out.ref(), inn.ref()), notes = " top-up ")) }
            .body<List<BankRow>>()
        assertEquals(listOf(BankRowOutcome.TRANSFER, BankRowOutcome.TRANSFER), filed.map { it.outcome })
        val transfer = client.transfers(base).single()
        assertEquals(current.id, transfer.fromAccountId)
        assertEquals(savings.id, transfer.toAccountId)
        assertEquals(100_000L, transfer.amountMinor)
        assertEquals(LocalDate(2026, 9, 1), transfer.date)
        assertEquals("top-up", transfer.notes)
        assertEquals(setOf(out.id, inn.id), transfer.rowIds.toSet())
        assertEquals(transfer.id, filed.first().transferId)
        assertNull(filed.first().expenseId)
        assertTrue(filed.all { it.decidedByUserId != null && !it.decidedAuto })
        // The feed has decided rows now.
        client.delete("$base/bank-feeds/${currentFeed.id}?version=${currentFeed.version}").assertError(HttpStatusCode.Conflict, "feed_in_use")

        // Undoing one row keeps the transfer; undoing the other deletes it.
        client.post("$base/bank-rows/undo") { setBody(UndoBankRows(listOf(client.row(base, out.id).ref()))) }
        assertEquals(1, client.transfers(base).size)
        client.post("$base/bank-rows/undo") { setBody(UndoBankRows(listOf(client.row(base, inn.id).ref()))) }
        assertEquals(emptyList(), client.transfers(base))

        // Deleting the transfer puts its rows back.
        client.post("$base/bank-rows/transfer") { setBody(TransferBankRows(listOf(client.row(base, out.id).ref(), client.row(base, inn.id).ref()))) }
        val again = client.transfers(base).single()
        client.delete("$base/transfers/${again.id}?version=${again.version + 1}").assertError(HttpStatusCode.Conflict, "version_conflict")
        client.delete("$base/transfers/${again.id}?version=${again.version}").also { assertEquals(HttpStatusCode.NoContent, it.status) }
        assertEquals(emptyList(), client.transfers(base))
        val back = client.pending(base).filter { it.id == out.id || it.id == inn.id }
        assertEquals(2, back.size)
        assertTrue(back.all { it.outcome == BankRowOutcome.PENDING && it.transferId == null && it.decidedByUserId == null })
    }

    @Test
    fun pairsAcrossCurrenciesWithinTwoPercent() = apiTest(
        FakeMarketData(rates = listOf(CzkRate("EUR", LocalDate(2026, 9, 1), "25.000".toBigDecimal()))),
    ) {
        val client = newClient().also { it.signup() }
        val base = "/api/households/${client.createHousehold().id}"
        val czk = client.feed(base, client.account(base, "Current").id, "Current")
        val eurAccount = client.account(base, "Euro")
        val eur = client.feed(base, eurAccount.id, "Euro", "EUR")
        client.upload(base, czk.id, revolut("Transfer", 3, "To Euro", "-1000.00"))
        // 40.60 EUR is 1 015 CZK: 1.5% off. 42.00 EUR is 1 050: 5% off.
        client.upload(base, eur.id, revolut("Transfer", 3, "Close", "40.60", "EUR"), revolut("Transfer", 3, "Far", "42.00", "EUR"))

        val rows = client.pending(base)
        val out = rows.first { it.text == "Euro" }
        assertEquals(listOf("Close"), out.suggestion!!.transferCandidates.map { c -> rows.first { it.id == c.rowId }.text })
        assertEquals("EUR", out.suggestion!!.transferCandidates.single().currency)
        assertEquals(SuggestedRecord.INCOME, rows.first { it.text == "Far" }.suggestion!!.record)
        assertEquals(SuggestedRecord.TRANSFER, rows.first { it.text == "Close" }.suggestion!!.record)

        val close = rows.first { it.text == "Close" }
        client.post("$base/bank-rows/transfer") { setBody(TransferBankRows(listOf(out.ref(), close.ref()))) }
        val transfer = client.transfers(base).single()
        assertEquals(100_000L, transfer.amountMinor)
        assertEquals(eurAccount.id, transfer.toAccountId)

        // An incoming euro row alone is converted at the fixing; a day before any fixing has no rate.
        val far = client.pending(base).first { it.text == "Far" }
        client.post("$base/bank-rows/transfer") { setBody(TransferBankRows(listOf(far.ref()), otherAccountId = eurAccount.id)) }
            .assertError(HttpStatusCode.BadRequest, "same_account")
        client.upload(base, eur.id, revolut("Transfer", 4, "Old", "10.00", "EUR").replace("2026-09-04", "2025-01-04"))
        val old = client.pending(base).first { it.text == "Old" }
        client.post("$base/bank-rows/transfer") { setBody(TransferBankRows(listOf(old.ref()), otherAccountId = client.account(base, "Savings").id)) }
            .assertError(HttpStatusCode.BadRequest, "no_rate")
    }

    @Test
    fun filesOneRowWithAnotherAccountAndRejectsBadInput() = apiTest {
        val client = newClient().also { it.signup() }
        val base = "/api/households/${client.createHousehold().id}"
        val current = client.account(base, "Current")
        val savings = client.account(base, "Savings")
        val a = client.feed(base, current.id, "Current")
        val b = client.feed(base, client.account(base, "Other").id, "Other")
        client.upload(base, a.id, revolut("Transfer", 1, "Save one", "-500.00"), revolut("Transfer", 2, "Save two", "-700.00"), revolut("Transfer", 3, "Back", "300.00"))
        client.upload(base, b.id, revolut("Transfer", 1, "Elsewhere", "-100.00"))
        val rows = client.pending(base)
        val one = rows.first { it.text == "Save one" }
        val two = rows.first { it.text == "Save two" }
        val back = rows.first { it.text == "Back" }
        val elsewhere = rows.first { it.text == "Elsewhere" }

        suspend fun transfer(vararg refs: BankRowRef, other: String? = null) =
            client.post("$base/bank-rows/transfer") { setBody(TransferBankRows(refs.toList(), other)) }

        transfer(one.ref(), two.ref()).assertError(HttpStatusCode.BadRequest, "not_a_pair")
        transfer(back.ref(), back.ref()).assertError(HttpStatusCode.BadRequest, "duplicate_rows")
        transfer(one.ref()).assertError(HttpStatusCode.BadRequest, "other_account_required")
        transfer(one.ref(), other = "nope").assertError(HttpStatusCode.BadRequest, "invalid_id")
        transfer(one.ref(), other = "00000000-0000-0000-0000-000000000000").assertError(HttpStatusCode.BadRequest, "invalid_account")
        transfer(one.ref(), back.ref(), other = savings.id).assertError(HttpStatusCode.BadRequest, "other_account_given")
        transfer(one.ref(), other = current.id).assertError(HttpStatusCode.BadRequest, "same_account")
        // The same feed: the only incoming row is in feed a, like "Save one".
        transfer(one.ref(), back.ref()).assertError(HttpStatusCode.BadRequest, "same_feed")
        transfer(BankRowRef(one.id, one.version + 1), other = savings.id).assertError(HttpStatusCode.Conflict, "version_conflict")
        assertEquals(emptyList(), client.transfers(base))

        // Money out with the other account given: from this row's account to the other.
        val out = client.post("$base/bank-rows/transfer") { setBody(TransferBankRows(listOf(one.ref()), savings.id)) }.body<List<BankRow>>()
        assertEquals(BankRowOutcome.TRANSFER, out.single().outcome)
        // Money in: from the other account to this row's.
        client.post("$base/bank-rows/transfer") { setBody(TransferBankRows(listOf(back.ref()), savings.id)) }
        val listed = client.transfers(base)
        assertEquals(listOf(current.id to savings.id, savings.id to current.id), listed.sortedBy { it.date }.map { it.fromAccountId to it.toAccountId })
        assertEquals(listOf(50_000L, 30_000L), listed.sortedBy { it.date }.map { it.amountMinor })

        // Already decided.
        transfer(out.single().let { BankRowRef(it.id, it.version) }, other = savings.id).assertError(HttpStatusCode.Conflict, "already_decided")
        assertTrue(elsewhere.id.isNotEmpty())

        // Date filters.
        assertEquals(1, client.get("$base/transfers?from=2026-09-02").body<List<Transfer>>().size)
        assertEquals(1, client.get("$base/transfers?to=2026-09-01").body<List<Transfer>>().size)
    }

    @Test
    fun suggestsTheAccountOfACounterpartyIban() = apiTest {
        val client = newClient().also { it.signup() }
        val base = "/api/households/${client.createHousehold().id}"
        fun raw(id: String, bban: String) = Json.parseToJsonElement(
            """{"transactionId":"$id","bookingDate":"2026-09-10","transactionAmount":{"amount":"-5000","currency":"CZK"},""" +
                """"creditorName":"Me","creditorAccount":{"bban":"$bban"}}""",
        ).jsonObject
        val file = HouseholdImport(
            currency = "CZK",
            accounts = listOf(ImportAccount("Current"), ImportAccount("Savings", iban = "CZ65 0800 0000 1920 0014 5399")),
            bankFeeds = listOf(ImportBankFeed("Current · CZK", "Current", "CZK", BankInstitution.KB)),
            bankRows = listOf(
                ImportBankRow("Current · CZK", raw("t1", "19-2000145399/0800")),
                ImportBankRow("Current · CZK", raw("t2", "123-456/0100")),
            ),
        )
        client.post("$base/import") { setBody(file) }
        val savings = client.get("$base/accounts").body<List<Account>>().first { it.name == "Savings" }
        val rows = client.pending(base)
        val known = rows.first { it.counterpartyAccount == "19-2000145399/0800" }.suggestion!!
        assertEquals(savings.id, known.transferAccountId)
        assertEquals(SuggestedRecord.TRANSFER, known.record)
        assertTrue(known.transferCandidates.isEmpty())
        val stranger = rows.first { it.counterpartyAccount == "123-456/0100" }.suggestion!!
        assertNull(stranger.transferAccountId)
        assertEquals(SuggestedRecord.EXPENSE, stranger.record)
    }

    @Test
    fun settlesAPaymentBetweenMembers() = apiTest {
        val client = newClient()
        val alice = client.signup().id
        val base = "/api/households/${client.createHousehold().id}"
        val bob = client.post("$base/members") { setBody(MemberInput("Bob")) }.body<Member>().userId
        val aliceFeed = client.feed(base, client.account(base, "Alice", alice).id, "Alice")
        val bobFeed = client.feed(base, client.account(base, "Bob", bob).id, "Bob")
        client.upload(base, aliceFeed.id, revolut("Transfer", 5, "To Bob", "-500.00"))
        client.upload(base, bobFeed.id, revolut("Transfer", 6, "From Alice", "500.00"))
        val rows = client.pending(base)
        val out = rows.first { it.amountMinor < 0 }
        val inn = rows.first { it.amountMinor > 0 }

        suspend fun settle(refs: List<BankRowRef>, from: String = alice, to: String = bob) =
            client.post("$base/bank-rows/settle") { setBody(SettleBankRows(refs, from, to, notes = "dinner")) }
        settle(listOf(out.ref()), from = alice, to = alice).assertError(HttpStatusCode.BadRequest, "same_member")
        settle(listOf(out.ref()), to = "00000000-0000-0000-0000-000000000000").assertError(HttpStatusCode.BadRequest, "invalid_member")
        settle(listOf(out.ref(), BankRowRef(inn.id, inn.version + 1))).assertError(HttpStatusCode.Conflict, "version_conflict")

        val filed = settle(listOf(out.ref(), inn.ref())).body<List<BankRow>>()
        assertEquals(listOf(BankRowOutcome.SETTLEMENT, BankRowOutcome.SETTLEMENT), filed.map { it.outcome })
        assertTrue(filed.all { it.settlementId != null && it.transferId == null })
        val settlement = client.get("$base/settlements").body<List<Settlement>>().single()
        assertEquals(filed.first().settlementId, settlement.id)
        assertEquals(50_000L, settlement.amountMinor)
        assertEquals(LocalDate(2026, 9, 5), settlement.date)
        assertEquals(alice, settlement.fromUserId)
        assertEquals("dinner", settlement.notes)
        assertEquals(1, client.get("$base/balances").body<Balances>().entries.size)
        assertEquals(1, client.get("$base/balances").body<Balances>().balances.size)
        // No transfer is made for a settlement.
        assertEquals(emptyList(), client.transfers(base))

        // Undoing both rows deletes the settlement, and so the balance.
        client.post("$base/bank-rows/undo") { setBody(UndoBankRows(filed.map { it.ref() })) }
        assertEquals(emptyList(), client.get("$base/settlements").body<List<Settlement>>())
        assertEquals(0, client.get("$base/balances").body<Balances>().balances.size)
        assertEquals(2, client.pending(base).size)

        // One row is enough, and deleting the settlement puts it back.
        settle(listOf(client.pending(base).first { it.amountMinor < 0 }.ref())).body<List<BankRow>>()
        val again = client.get("$base/settlements").body<List<Settlement>>().single()
        client.delete("$base/settlements/${again.id}?version=${again.version}")
        assertEquals(2, client.pending(base).size)
    }

    @Test
    fun countsWhatMembersPutIntoJointAccounts() = apiTest {
        val client = newClient()
        val alice = client.signup().id
        val base = "/api/households/${client.createHousehold().id}"
        val bob = client.post("$base/members") { setBody(MemberInput("Bob")) }.body<Member>().userId
        val aliceAccount = client.account(base, "Alice", alice)
        val bobAccount = client.account(base, "Bob", bob)
        val joint = client.account(base, "Joint")
        val joint2 = client.account(base, "Joint 2")
        val aliceFeed = client.feed(base, aliceAccount.id, "Alice")
        val bobFeed = client.feed(base, bobAccount.id, "Bob")
        val jointFeed = client.feed(base, joint.id, "Joint")
        client.upload(
            base, aliceFeed.id,
            revolut("Transfer", 1, "Top up joint", "-3000.00"),
            revolut("Transfer", 2, "Top up joint again", "-1000.00"),
            revolut("Transfer", 3, "To Bob", "-200.00"),
        )
        client.upload(base, bobFeed.id, revolut("Transfer", 3, "From Alice", "200.00"), revolut("Transfer", 4, "Top up joint", "-500.00"))
        client.upload(base, jointFeed.id, revolut("Transfer", 1, "From Alice", "3000.00"), revolut("Transfer", 5, "Cash to Bob", "-800.00"))
        val rows = client.pending(base)
        fun row(text: String, amount: Long) = rows.first { it.text == text && it.amountMinor == amount }

        // Alice -> joint (paired): +3000 for Alice. Alice -> joint 2 (one row): +1000. Bob -> joint (one row): +500.
        client.post("$base/bank-rows/transfer") { setBody(TransferBankRows(listOf(row("Top up joint", -300_000).ref(), row("Alice", 300_000).ref()))) }
        client.post("$base/bank-rows/transfer") { setBody(TransferBankRows(listOf(row("Top up joint again", -100_000).ref()), joint2.id)) }
        client.post("$base/bank-rows/transfer") { setBody(TransferBankRows(listOf(row("Top up joint", -50_000).ref()), joint.id)) }
        // Joint -> Bob: -800 for Bob. Alice -> Bob: personal to personal, doesn't count.
        client.post("$base/bank-rows/transfer") { setBody(TransferBankRows(listOf(row("Cash to Bob", -80_000).ref()), bobAccount.id)) }
        client.post("$base/bank-rows/transfer") { setBody(TransferBankRows(listOf(row("Bob", -20_000).ref(), row("Alice", 20_000).ref()))) }

        val balances = client.get("$base/balances").body<Balances>()
        assertEquals(
            setOf(
                JointContribution(alice, joint.id, 300_000),
                JointContribution(alice, joint2.id, 100_000),
                JointContribution(bob, joint.id, 50_000 - 80_000),
            ),
            balances.jointContributions.toSet(),
        )
        assertEquals(3, balances.jointContributions.size)
        // Not a debt.
        assertEquals(emptyList(), balances.balances)
        // The range applies to the transfers' dates.
        val september2 = client.get("$base/balances?from=2026-09-02&to=2026-09-04").body<Balances>()
        assertEquals(
            setOf(JointContribution(alice, joint2.id, 100_000), JointContribution(bob, joint.id, 50_000)),
            september2.jointContributions.toSet(),
        )
        assertEquals(emptyList(), client.get("$base/balances?from=2027-01-01").body<Balances>().jointContributions)
    }

    @Test
    fun manualTransferCreateEditFilterDelete() = apiTest {
        val client = newClient().also { it.signup() }
        val home = client.createHousehold("Home")
        val base = "/api/households/${home.id}"
        val otherHome = client.createHousehold("Other")
        val otherAccount = client.account("/api/households/${otherHome.id}", "Stranger")

        val alice = client.get("$base/members").body<List<Member>>().single().userId
        val personal = client.account(base, "Personal", alice)
        val cash = client.account(base, "Cash", alice)
        val joint = client.account(base, "Joint")

        // Validation errors.
        client.post("$base/transfers") {
            setBody(TransferInput(LocalDate(2026, 9, 10), 0, personal.id, cash.id))
        }.assertError(HttpStatusCode.BadRequest, "invalid_amount")
        client.post("$base/transfers") {
            setBody(TransferInput(LocalDate(2026, 9, 10), 10_000, personal.id, personal.id))
        }.assertError(HttpStatusCode.BadRequest, "same_account")
        client.post("$base/transfers") {
            setBody(TransferInput(LocalDate(2026, 9, 10), 10_000, personal.id, otherAccount.id))
        }.assertError(HttpStatusCode.BadRequest, "invalid_account")

        // Create two hand-entered transfers; one tops up the joint account.
        val created = client.post("$base/transfers") {
            setBody(TransferInput(LocalDate(2026, 9, 10), 50_000, personal.id, joint.id, "  monthly top-up "))
        }.let {
            assertEquals(HttpStatusCode.Created, it.status)
            it.body<Transfer>()
        }
        assertEquals(1, created.version)
        assertEquals("monthly top-up", created.notes)
        assertEquals(emptyList(), created.rowIds)
        assertEquals(emptyList(), created.bankAccountIds)

        val second = client.post("$base/transfers") {
            setBody(TransferInput(LocalDate(2026, 9, 12), 20_000, personal.id, cash.id, "ATM"))
        }.body<Transfer>()

        // Filter by accountId.
        assertEquals(listOf(second.id), client.get("$base/transfers?accountId=${cash.id}").body<List<Transfer>>().map { it.id })
        assertEquals(listOf(created.id), client.get("$base/transfers?accountId=${joint.id}").body<List<Transfer>>().map { it.id })

        // Joint top-up counts in Balances.
        val balances = client.get("$base/balances").body<Balances>()
        assertEquals(listOf(JointContribution(alice, joint.id, 50_000)), balances.jointContributions)

        // Edit requires version and rejects stale version.
        client.put("$base/transfers/${created.id}") {
            setBody(TransferInput(LocalDate(2026, 9, 11), 60_000, personal.id, joint.id, "updated"))
        }.assertError(HttpStatusCode.BadRequest, "version_required")
        client.put("$base/transfers/${created.id}") {
            setBody(TransferInput(LocalDate(2026, 9, 11), 60_000, personal.id, joint.id, "updated", version = 99))
        }.assertError(HttpStatusCode.Conflict, "version_conflict")

        val updated = client.put("$base/transfers/${created.id}") {
            setBody(TransferInput(LocalDate(2026, 9, 11), 60_000, personal.id, joint.id, "updated", version = 1))
        }.body<Transfer>()
        assertEquals(2, updated.version)
        assertEquals(LocalDate(2026, 9, 11), updated.date)
        assertEquals(60_000L, updated.amountMinor)
        assertEquals("updated", updated.notes)

        // Delete works for a transfer without bank rows.
        val del = client.delete("$base/transfers/${created.id}?version=${updated.version}")
        assertEquals(HttpStatusCode.NoContent, del.status)
        assertEquals(listOf(second.id), client.transfers(base).map { it.id })
    }

    @Test
    fun bankRowTransferEditingIsRestricted() = apiTest {
        val client = newClient().also { it.signup() }
        val base = "/api/households/${client.createHousehold().id}"
        val current = client.account(base, "Current")
        val savings = client.account(base, "Savings")
        val cash = client.account(base, "Cash")
        val currentFeed = client.feed(base, current.id, "Current")
        val savingsFeed = client.feed(base, savings.id, "Savings")

        client.upload(base, currentFeed.id, revolut("Transfer", 1, "To Savings", "-1000.00"), revolut("Transfer", 2, "ATM", "-500.00"))
        client.upload(base, savingsFeed.id, revolut("Transfer", 1, "From Current", "1000.00"))
        val rows = client.pending(base)
        val pairOut = rows.first { it.text == "Savings" }
        val pairIn = rows.first { it.text == "Current" }
        val atm = rows.first { it.text == "ATM" }

        client.post("$base/bank-rows/transfer") { setBody(TransferBankRows(listOf(pairOut.ref(), pairIn.ref()), notes = "pair")) }
        client.post("$base/bank-rows/transfer") { setBody(TransferBankRows(listOf(atm.ref()), otherAccountId = cash.id, notes = "atm")) }

        val transfers = client.transfers(base)
        val paired = transfers.first { it.rowIds.size == 2 }
        val oneRow = transfers.first { it.rowIds.size == 1 }
        assertEquals(listOf(current.id, savings.id).toSet(), paired.bankAccountIds.toSet())
        assertEquals(listOf(current.id), oneRow.bankAccountIds)

        // Two-row transfer: only notes can change.
        client.put("$base/transfers/${paired.id}") {
            setBody(TransferInput(paired.date, paired.amountMinor + 1, paired.fromAccountId, paired.toAccountId, "x", paired.version))
        }.assertError(HttpStatusCode.Conflict, "transfer_from_bank_rows")
        client.put("$base/transfers/${paired.id}") {
            setBody(TransferInput(paired.date, paired.amountMinor, paired.fromAccountId, cash.id, "x", paired.version))
        }.assertError(HttpStatusCode.Conflict, "transfer_from_bank_rows")
        val pairedEdited = client.put("$base/transfers/${paired.id}") {
            setBody(TransferInput(paired.date, paired.amountMinor, paired.fromAccountId, paired.toAccountId, "edited", paired.version))
        }.body<Transfer>()
        assertEquals("edited", pairedEdited.notes)

        // One-row outgoing transfer: date, amount, and fromAccountId are fixed; notes and toAccountId can change.
        client.put("$base/transfers/${oneRow.id}") {
            setBody(TransferInput(LocalDate(2026, 9, 3), oneRow.amountMinor, oneRow.fromAccountId, oneRow.toAccountId, "x", oneRow.version))
        }.assertError(HttpStatusCode.Conflict, "transfer_from_bank_rows")
        client.put("$base/transfers/${oneRow.id}") {
            setBody(TransferInput(oneRow.date, oneRow.amountMinor, savings.id, cash.id, "x", oneRow.version))
        }.assertError(HttpStatusCode.Conflict, "transfer_from_bank_rows")
        val oneRowEdited = client.put("$base/transfers/${oneRow.id}") {
            setBody(TransferInput(oneRow.date, oneRow.amountMinor, oneRow.fromAccountId, savings.id, "moved", oneRow.version))
        }.body<Transfer>()
        assertEquals(savings.id, oneRowEdited.toAccountId)
        assertEquals("moved", oneRowEdited.notes)
    }
}
