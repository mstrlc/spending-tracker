package dev.spendingtracker.server

import dev.spendingtracker.server.market.CzkRate
import dev.spendingtracker.shared.api.Account
import dev.spendingtracker.shared.api.AccountInput
import dev.spendingtracker.shared.api.ApplyRulesResult
import dev.spendingtracker.shared.api.BankFeed
import dev.spendingtracker.shared.api.BankFeedInput
import dev.spendingtracker.shared.api.BankInstitution
import dev.spendingtracker.shared.api.BankRow
import dev.spendingtracker.shared.api.BankRowOutcome
import dev.spendingtracker.shared.api.BankRowRef
import dev.spendingtracker.shared.api.Category
import dev.spendingtracker.shared.api.CategoryInput
import dev.spendingtracker.shared.api.CreateInviteRequest
import dev.spendingtracker.shared.api.Expense
import dev.spendingtracker.shared.api.ExpenseInput
import dev.spendingtracker.shared.api.HouseholdImport
import dev.spendingtracker.shared.api.HouseholdRole
import dev.spendingtracker.shared.api.HouseholdSettings
import dev.spendingtracker.shared.api.ImportAccount
import dev.spendingtracker.shared.api.ImportBankFeed
import dev.spendingtracker.shared.api.ImportBankRow
import dev.spendingtracker.shared.api.InboxRule
import dev.spendingtracker.shared.api.InboxRuleInput
import dev.spendingtracker.shared.api.Income
import dev.spendingtracker.shared.api.IncomeInput
import dev.spendingtracker.shared.api.IncomeKind
import dev.spendingtracker.shared.api.Invite
import dev.spendingtracker.shared.api.JointFunding
import dev.spendingtracker.shared.api.Member
import dev.spendingtracker.shared.api.RuleDirection
import dev.spendingtracker.shared.api.RuleOutcome
import dev.spendingtracker.shared.api.StatementUpload
import dev.spendingtracker.shared.api.SuggestedRecord
import dev.spendingtracker.shared.api.Transfer
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
private const val REVOLUT_HEADER = "Type,Product,Started Date,Completed Date,Description,Amount,Fee,Currency,State,Balance"

private fun line(type: String, day: Int, description: String, amount: String, currency: String = "CZK") =
    "$type,Current,2026-09-%02d 08:00:00,2026-09-%02d 08:00:01,$description,$amount,0.00,$currency,COMPLETED,0.00".format(day, day)

private fun card(day: Int, description: String, amount: String, currency: String = "CZK") = line("Card Payment", day, description, amount, currency)

class RulesTest {
    private suspend fun HttpClient.account(base: String, name: String, owner: String? = null) =
        post("$base/accounts") { setBody(AccountInput(name, ownerUserId = owner)) }.body<Account>()

    private suspend fun HttpClient.feed(base: String, accountId: String, name: String, currency: String = "CZK") =
        post("$base/bank-feeds") { setBody(BankFeedInput(accountId, name, BankInstitution.REVOLUT, currency = currency)) }.body<BankFeed>()

    private suspend fun HttpClient.upload(base: String, feedId: String, vararg lines: String) {
        val response = post("$base/bank-feeds/$feedId/statements") { setBody(StatementUpload((listOf(REVOLUT_HEADER) + lines).joinToString("\n"))) }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    private suspend fun HttpClient.rule(base: String, input: InboxRuleInput): InboxRule {
        val response = post("$base/inbox-rules") { setBody(input) }
        assertEquals(HttpStatusCode.Created, response.status)
        return response.body()
    }

    private suspend fun HttpClient.rules(base: String) = get("$base/inbox-rules").body<List<InboxRule>>()
    private suspend fun HttpClient.pending(base: String) = get("$base/bank-rows").body<List<BankRow>>()
    private suspend fun HttpClient.allRows(base: String) = get("$base/bank-rows?status=all").body<List<BankRow>>()
    private suspend fun HttpClient.expenses(base: String) = get("$base/expenses").body<List<Expense>>()
    private suspend fun HttpClient.transfers(base: String) = get("$base/transfers").body<List<Transfer>>()
    private suspend fun HttpClient.apply(base: String) = post("$base/bank-rows/apply-rules").body<ApplyRulesResult>()

    private fun BankRow.ref() = BankRowRef(id, version)

    @Test
    fun managesRulesWithVersionsAndChecks() = apiTest {
        val client = newClient().also { it.signup() }
        val base = "/api/households/${client.createHousehold().id}"
        val category = client.post("$base/categories") { setBody(CategoryInput("Rule test category")) }.body<Category>()

        val created = client.rule(
            base,
            InboxRuleInput(
                name = " Bakery ", textContains = " Pekárna  Čaj ", outcome = RuleOutcome.EXPENSE, merchant = " Corner Bakery ",
                categoryId = category.id, direction = RuleDirection.OUT, priority = 3,
            ),
        )
        // The text is kept the way rows are compared: upper case, no diacritics, letters and digits.
        assertEquals("PEKARNA CAJ", created.textContains)
        assertEquals("Bakery", created.name)
        assertEquals("Corner Bakery", created.merchant)
        assertEquals(RuleDirection.OUT, created.direction)
        assertEquals(1, created.version)
        assertEquals(0, created.filedCount)

        val updated = client.put("$base/inbox-rules/${created.id}") {
            setBody(InboxRuleInput(name = "Bakery", merchantKey = "pekarna", outcome = RuleOutcome.SKIP, skipReason = "Not ours", auto = true, version = 1))
        }.body<InboxRule>()
        assertEquals(2, updated.version)
        assertEquals("PEKARNA", updated.merchantKey)
        assertNull(updated.textContains)
        // What the new outcome doesn't use is cleared.
        assertEquals("", updated.merchant)
        assertNull(updated.categoryId)
        assertTrue(updated.auto)
        assertEquals(listOf(updated), client.rules(base))

        client.put("$base/inbox-rules/${created.id}") {
            setBody(InboxRuleInput(name = "x", textContains = "x", outcome = RuleOutcome.EXPENSE, version = 1))
        }.assertError(HttpStatusCode.Conflict, "version_conflict")
        client.put("$base/inbox-rules/${created.id}") {
            setBody(InboxRuleInput(name = "x", textContains = "x", outcome = RuleOutcome.EXPENSE))
        }.assertError(HttpStatusCode.BadRequest, "version_required")

        fun bad(code: String, input: InboxRuleInput) = suspend { client.post("$base/inbox-rules") { setBody(input) }.assertError(HttpStatusCode.BadRequest, code) }
        bad("rule_needs_match", InboxRuleInput(name = "x", direction = RuleDirection.IN, outcome = RuleOutcome.EXPENSE))()
        bad("rule_needs_match", InboxRuleInput(name = "x", textContains = "  ", outcome = RuleOutcome.EXPENSE))()
        bad("invalid_text", InboxRuleInput(name = "x", textContains = "***", outcome = RuleOutcome.EXPENSE))()
        bad("required", InboxRuleInput(name = " ", textContains = "x", outcome = RuleOutcome.EXPENSE))()
        bad("required", InboxRuleInput(name = "x", textContains = "x", outcome = RuleOutcome.SKIP))()
        bad("required", InboxRuleInput(name = "x", textContains = "x", outcome = RuleOutcome.TRANSFER))()
        bad("invalid_iban", InboxRuleInput(name = "x", counterpartyIban = "CZ00 1234", outcome = RuleOutcome.EXPENSE))()
        bad("invalid_priority", InboxRuleInput(name = "x", textContains = "x", outcome = RuleOutcome.EXPENSE, priority = 5_000))()
        bad("invalid_category", InboxRuleInput(name = "x", textContains = "x", outcome = RuleOutcome.EXPENSE, categoryId = "00000000-0000-0000-0000-000000000001"))()
        bad("invalid_feed", InboxRuleInput(name = "x", textContains = "x", outcome = RuleOutcome.EXPENSE, feedId = "00000000-0000-0000-0000-000000000001"))()
        bad("invalid_account", InboxRuleInput(name = "x", textContains = "x", outcome = RuleOutcome.EXPENSE, accountId = "00000000-0000-0000-0000-000000000001"))()
        bad("invalid_account", InboxRuleInput(name = "x", textContains = "x", outcome = RuleOutcome.TRANSFER, otherAccountId = "00000000-0000-0000-0000-000000000001"))()
        bad("invalid_member", InboxRuleInput(name = "x", textContains = "x", outcome = RuleOutcome.EXPENSE, personUserId = "00000000-0000-0000-0000-000000000001"))()

        // An IBAN is compacted and checked; a Czech account number becomes its IBAN.
        val byIban = client.rule(base, InboxRuleInput(name = "Landlord", counterpartyIban = "cz65 0800 0000 1920 0014 5399", outcome = RuleOutcome.EXPENSE))
        assertEquals("CZ6508000000192000145399", byIban.counterpartyIban)
        assertEquals("CZ6508000000192000145399", client.rule(base, InboxRuleInput(name = "Landlord 2", counterpartyIban = "19-2000145399/0800", outcome = RuleOutcome.EXPENSE)).counterpartyIban)

        client.delete("$base/inbox-rules/${created.id}?version=1").assertError(HttpStatusCode.Conflict, "version_conflict")
        client.delete("$base/inbox-rules/${created.id}").assertError(HttpStatusCode.BadRequest, "version_required")
        assertEquals(HttpStatusCode.NoContent, client.delete("$base/inbox-rules/${created.id}?version=2").status)
        client.delete("$base/inbox-rules/${created.id}?version=2").assertError(HttpStatusCode.NotFound, "not_found")
        assertEquals(2, client.rules(base).size)

        // Another household's rules are out of reach.
        val other = newClient().also { it.signup("bob@example.com", "Bob") }
        other.get("$base/inbox-rules").assertError(HttpStatusCode.NotFound, "not_found")
    }

    @Test
    fun theMostSpecificRuleWinsAndThenTheOldest() = apiTest {
        val client = newClient().also { it.signup() }
        val base = "/api/households/${client.createHousehold().id}"
        val account = client.account(base, "Revolut")
        val feed = client.feed(base, account.id, "Revolut · CZK")
        client.upload(base, feed.id, card(3, "Bakery Prague", "-120.00"), card(4, "Gym", "-500.00"))

        suspend fun winner(text: String) = client.pending(base).first { it.text == text }.suggestion!!.ruleName

        client.rule(base, InboxRuleInput(name = "Old", textContains = "bakery", outcome = RuleOutcome.EXPENSE, merchant = "Old"))
        client.rule(base, InboxRuleInput(name = "Young", textContains = "bakery", outcome = RuleOutcome.EXPENSE, merchant = "Young"))
        assertEquals("Old", winner("Bakery Prague"))
        assertNull(client.pending(base).first { it.text == "Gym" }.suggestion!!.ruleId)

        // More conditions beat fewer; a condition that doesn't hold rules the rule out.
        client.rule(base, InboxRuleInput(name = "Specific", textContains = "bakery", direction = RuleDirection.OUT, outcome = RuleOutcome.EXPENSE))
        assertEquals("Specific", winner("Bakery Prague"))
        client.rule(base, InboxRuleInput(name = "Wrong way", textContains = "bakery", direction = RuleDirection.IN, outcome = RuleOutcome.EXPENSE, priority = 9))
        assertEquals("Specific", winner("Bakery Prague"))
        // Narrowed to this account, with a priority: the priority comes first.
        client.rule(base, InboxRuleInput(name = "Urgent", textContains = "bakery", accountId = account.id, outcome = RuleOutcome.EXPENSE, priority = 1))
        assertEquals("Urgent", winner("Bakery Prague"))
        // Matching is by text, counterparty and message together, without regard to case or diacritics, and by merchant key.
        client.rule(base, InboxRuleInput(name = "By key", merchantKey = "gym", outcome = RuleOutcome.EXPENSE))
        assertEquals("By key", winner("Gym"))
        client.rule(base, InboxRuleInput(name = "Prefix", textContains = "GYM", outcome = RuleOutcome.EXPENSE, priority = 2))
        assertEquals("Prefix", winner("Gym"))
    }

    @Test
    fun aRuleWithoutAutoShapesTheSuggestionOnly() = apiTest {
        val client = newClient()
        val me = client.signup()
        val base = "/api/households/${client.createHousehold().id}"
        val account = client.account(base, "Revolut", owner = me.id)
        val savings = client.account(base, "Savings")
        val feed = client.feed(base, account.id, "Revolut · CZK")
        val category = client.post("$base/categories") { setBody(CategoryInput("Rule test category")) }.body<Category>()
        val expense = client.rule(
            base,
            InboxRuleInput(name = "Bakery", textContains = "bakery", outcome = RuleOutcome.EXPENSE, merchant = "Corner Bakery", categoryId = category.id),
        )
        val skip = client.rule(base, InboxRuleInput(name = "Gym card", textContains = "gym", outcome = RuleOutcome.SKIP, skipReason = "Paid elsewhere"))
        val move = client.rule(base, InboxRuleInput(name = "Pocket", textContains = "pocket", outcome = RuleOutcome.TRANSFER, otherAccountId = savings.id))
        client.upload(base, feed.id, card(3, "Bakery Prague", "-120.00"), card(4, "Gym", "-500.00"), card(5, "Pocket money", "-50.00"), card(6, "Pizza Place", "-90.00"))

        val rows = client.pending(base)
        assertEquals(4, rows.size)
        val bakery = rows.first { it.text == "Bakery Prague" }.suggestion!!
        assertEquals(expense.id, bakery.ruleId)
        assertEquals("Bakery", bakery.ruleName)
        assertEquals(SuggestedRecord.EXPENSE, bakery.record)
        assertEquals("Corner Bakery", bakery.merchant)
        assertEquals(category.id, bakery.categoryId)
        // Whose follows the account when the rule names nobody.
        assertEquals(me.id, bakery.paidByUserId)
        assertEquals(12_000, bakery.amountMinor)

        val gym = rows.first { it.text == "Gym" }.suggestion!!
        assertEquals(skip.id, gym.ruleId)
        assertEquals("Paid elsewhere", gym.skipReason)

        val pocket = rows.first { it.text == "Pocket money" }.suggestion!!
        assertEquals(move.id, pocket.ruleId)
        assertEquals(SuggestedRecord.TRANSFER, pocket.record)
        assertEquals(savings.id, pocket.transferAccountId)

        val pizza = rows.first { it.text == "Pizza Place" }.suggestion!!
        assertNull(pizza.ruleId)
        assertNull(pizza.skipReason)

        // Nothing was filed, even when asked to apply the rules.
        assertEquals(ApplyRulesResult(4, 0, 0, 0, 0), client.apply(base))
        assertEquals(4, client.pending(base).size)
        assertEquals(emptyList(), client.expenses(base))
    }

    @Test
    fun autoRulesFileNewRowsAsTheFormWould() = apiTest {
        val client = newClient()
        val me = client.signup()
        val base = "/api/households/${client.createHousehold().id}"
        val account = client.account(base, "Revolut", owner = me.id)
        val feed = client.feed(base, account.id, "Revolut · CZK")
        val category = client.post("$base/categories") { setBody(CategoryInput("Rule test category")) }.body<Category>()
        val rule = client.rule(
            base,
            InboxRuleInput(name = "Bakery", textContains = "bakery", outcome = RuleOutcome.EXPENSE, merchant = "Corner Bakery", categoryId = category.id, auto = true),
        )
        client.upload(base, feed.id, card(3, "Bakery Prague", "-120.00"), card(4, "Pizza Place", "-200.00"))

        assertEquals(listOf("Pizza Place"), client.pending(base).map { it.text })
        val filed = client.allRows(base).first { it.text == "Bakery Prague" }
        assertEquals(BankRowOutcome.EXPENSE, filed.outcome)
        assertTrue(filed.decidedAuto)
        assertEquals(rule.id, filed.ruleId)
        assertNull(filed.decidedByUserId)
        val expense = client.expenses(base).single()
        assertEquals(filed.expenseId, expense.id)
        assertEquals("Corner Bakery", expense.merchant)
        assertEquals(12_000, expense.amountMinor)
        assertEquals(LocalDate(2026, 9, 3), expense.date)
        assertEquals(category.id, expense.categoryId)
        assertEquals(account.id, expense.accountId)
        assertEquals(me.id, expense.paidByUserId)
        assertEquals(1, client.rules(base).single().filedCount)

        // A later rule applies on demand to what is already waiting, once.
        client.rule(base, InboxRuleInput(name = "Pizza", textContains = "pizza", outcome = RuleOutcome.EXPENSE, auto = true, personUserId = me.id))
        assertEquals(ApplyRulesResult(1, 1, 0, 0, 0), client.apply(base))
        assertEquals(emptyList(), client.pending(base))
        // Without a category, the merchant's default (here none) and the descriptor's merchant fill in.
        assertEquals(setOf("Corner Bakery", "Pizza Place"), client.expenses(base).map { it.merchant }.toSet())
        assertEquals(ApplyRulesResult(0, 0, 0, 0, 0), client.apply(base))
    }

    @Test
    fun aRuleTakesTheMerchantsDefaultCategory() = apiTest {
        val client = newClient().also { it.signup() }
        val base = "/api/households/${client.createHousehold().id}"
        val account = client.account(base, "Revolut")
        val feed = client.feed(base, account.id, "Revolut · CZK")
        val category = client.post("$base/categories") { setBody(CategoryInput("Rule test category")) }.body<Category>()
        client.post("$base/expenses") { setBody(ExpenseInput(LocalDate(2026, 8, 1), 10_000, "Corner Bakery", category.id)) }
        client.rule(base, InboxRuleInput(name = "Bakery", textContains = "bakery", outcome = RuleOutcome.EXPENSE, merchant = "corner bakery", auto = true))
        client.upload(base, feed.id, card(3, "Bakery Prague", "-120.00"))
        val expense = client.expenses(base).first { it.date == LocalDate(2026, 9, 3) }
        assertEquals(category.id, expense.categoryId)
        assertEquals(1, client.get("$base/merchants").body<List<dev.spendingtracker.shared.api.Merchant>>().size)
    }

    @Test
    fun autoRulesFileForeignRowsWithTheirOriginalAmount() = apiTest(
        FakeMarketData(rates = listOf(CzkRate("EUR", LocalDate(2026, 9, 4), "25.000".toBigDecimal()))),
    ) {
        val client = newClient().also { it.signup() }
        val base = "/api/households/${client.createHousehold().id}"
        val account = client.account(base, "Revolut")
        val feed = client.feed(base, account.id, "Revolut · EUR", "EUR")
        client.rule(base, InboxRuleInput(name = "Cafe", textContains = "cafe", outcome = RuleOutcome.EXPENSE, auto = true))
        client.upload(base, feed.id, card(6, "Cafe", "-2.50", "EUR"))
        val expense = client.expenses(base).single()
        assertEquals(62_50, expense.amountMinor)
        assertEquals(250, expense.originalAmountMinor)
        assertEquals("EUR", expense.originalCurrency)
    }

    @Test
    fun anAutoRuleLinksToAHandEnteredDuplicateInsteadOfCreating() = apiTest {
        val client = newClient().also { it.signup() }
        val base = "/api/households/${client.createHousehold().id}"
        val account = client.account(base, "Revolut")
        val feed = client.feed(base, account.id, "Revolut · CZK")
        val typed = client.post("$base/expenses") {
            setBody(ExpenseInput(LocalDate(2026, 9, 4), 120_00, "Bakery", accountId = account.id))
        }.body<Expense>()
        val rule = client.rule(base, InboxRuleInput(name = "Bakery", textContains = "bakery", outcome = RuleOutcome.EXPENSE, auto = true))
        // Two bank rows look like the one typed expense: the first takes it, the second is a purchase of its own.
        client.upload(base, feed.id, card(3, "Bakery Prague", "-120.00"), card(4, "Bakery Brno", "-120.00"))

        val rows = client.allRows(base)
        assertTrue(rows.all { it.outcome == BankRowOutcome.EXPENSE && it.decidedAuto && it.ruleId == rule.id })
        assertEquals(2, client.expenses(base).size)
        assertEquals(1, rows.count { it.expenseId == typed.id })

        // Undoing puts rows back; the typed expense stays, the one made from a row goes.
        client.post("$base/bank-rows/undo") { setBody(UndoBankRows(rows.map { it.ref() })) }
        assertEquals(listOf(typed.id), client.expenses(base).map { it.id })
        val back = client.pending(base)
        assertEquals(2, back.size)
        assertTrue(back.all { !it.decidedAuto && it.ruleId == null && it.decidedByUserId == null })
        assertEquals(0, client.rules(base).single().filedCount)
    }

    @Test
    fun undoingAutoFiledRowsDeletesWhatTheyMadeAndDeletingARuleKeepsThem() = apiTest {
        val client = newClient().also { it.signup() }
        val base = "/api/households/${client.createHousehold().id}"
        val account = client.account(base, "Revolut")
        val feed = client.feed(base, account.id, "Revolut · CZK")
        val rule = client.rule(base, InboxRuleInput(name = "Bakery", textContains = "bakery", outcome = RuleOutcome.EXPENSE, auto = true))
        client.upload(base, feed.id, card(3, "Bakery Prague", "-120.00"), card(5, "Bakery Brno", "-80.00"))
        assertEquals(2, client.expenses(base).size)

        // Bulk undo of one row: its expense goes, the other stays.
        val rows = client.allRows(base)
        client.post("$base/bank-rows/undo") { setBody(UndoBankRows(listOf(rows.first { it.text == "Bakery Prague" }.ref()))) }
        assertEquals(listOf("Bakery Brno"), client.allRows(base).filter { it.outcome == BankRowOutcome.EXPENSE }.map { it.text })
        assertEquals(1, client.expenses(base).size)

        // Deleting the rule leaves the row filed, still marked automatic, without the rule.
        assertEquals(1, client.rules(base).single().filedCount)
        assertEquals(HttpStatusCode.NoContent, client.delete("$base/inbox-rules/${rule.id}?version=${rule.version}").status)
        val kept = client.allRows(base).first { it.text == "Bakery Brno" }
        assertEquals(BankRowOutcome.EXPENSE, kept.outcome)
        assertTrue(kept.decidedAuto)
        assertNull(kept.ruleId)
        assertEquals(1, client.expenses(base).size)
    }

    @Test
    fun autoRulesFileIncomeAndSkips() = apiTest {
        val client = newClient()
        val me = client.signup()
        val base = "/api/households/${client.createHousehold().id}"
        val account = client.account(base, "Revolut")
        val feed = client.feed(base, account.id, "Revolut · CZK")
        client.rule(base, InboxRuleInput(name = "Employer", textContains = "employer", outcome = RuleOutcome.INCOME, incomeSource = "Acme Ltd", personUserId = me.id, auto = true))
        client.rule(base, InboxRuleInput(name = "Top-ups", textContains = "apple pay", outcome = RuleOutcome.SKIP, skipReason = "Top-up", auto = true))
        client.upload(base, feed.id, line("Transfer", 5, "From Employer", "30000.00"), line("Topup", 6, "Apple Pay top-up by *1234", "500.00"))

        assertEquals(emptyList(), client.pending(base))
        val income = client.get("$base/income").body<List<Income>>().single()
        assertEquals("Acme Ltd", income.source)
        assertEquals(30_000_00, income.amountMinor)
        assertEquals(me.id, income.recipientUserId)
        assertEquals(account.id, income.accountId)
        val rows = client.allRows(base)
        assertEquals(income.id, rows.first { it.text == "Employer" }.incomeId)
        val skipped = rows.first { it.outcome == BankRowOutcome.SKIPPED }
        assertEquals("Top-up", skipped.skipReason)
        assertTrue(skipped.decidedAuto)
        assertNull(skipped.decidedByUserId)

        // Undoing the income row deletes the line.
        client.post("$base/bank-rows/undo") { setBody(UndoBankRows(rows.map { it.ref() })) }
        assertEquals(emptyList(), client.get("$base/income").body<List<Income>>())
        assertEquals(2, client.pending(base).size)
    }

    @Test
    fun aTransferRulePairsWithTheOnlyCandidateOrFilesAlone() = apiTest {
        val client = newClient().also { it.signup() }
        val base = "/api/households/${client.createHousehold().id}"
        val current = client.account(base, "Current")
        val savings = client.account(base, "Savings")
        val currentFeed = client.feed(base, current.id, "Current")
        val savingsFeed = client.feed(base, savings.id, "Savings")
        val rule = client.rule(base, InboxRuleInput(name = "To savings", textContains = "savings", outcome = RuleOutcome.TRANSFER, otherAccountId = savings.id, auto = true))

        // The other side is waiting, and the only candidate: both rows become one transfer.
        client.upload(base, savingsFeed.id, line("Transfer", 2, "From Current", "1000.00"))
        client.upload(base, currentFeed.id, line("Transfer", 1, "To Savings", "-1000.00"))
        val paired = client.transfers(base).single()
        assertEquals(2, paired.rowIds.size)
        assertEquals(current.id, paired.fromAccountId)
        assertEquals(savings.id, paired.toAccountId)
        assertEquals(emptyList(), client.pending(base))
        assertTrue(client.allRows(base).all { it.transferId == paired.id && it.decidedAuto && it.ruleId == rule.id })

        // Undoing one row keeps the transfer, undoing the other deletes it.
        val rows = client.allRows(base)
        client.post("$base/bank-rows/undo") { setBody(UndoBankRows(listOf(rows[0].ref()))) }
        assertEquals(1, client.transfers(base).size)
        client.post("$base/bank-rows/undo") { setBody(UndoBankRows(listOf(client.allRows(base).first { it.id == rows[1].id }.ref()))) }
        assertEquals(emptyList(), client.transfers(base))

        // Two candidates are ambiguous: the row goes alone, against the rule's account.
        client.upload(base, savingsFeed.id, line("Transfer", 8, "From Current", "700.00"), line("Transfer", 9, "From Current Too", "700.00"))
        client.upload(base, currentFeed.id, line("Transfer", 8, "To Savings", "-700.00"))
        val alone = client.transfers(base).single()
        assertEquals(1, alone.rowIds.size)
        assertEquals(current.id, alone.fromAccountId)
        assertEquals(savings.id, alone.toAccountId)
        assertEquals(setOf("Current", "Current Too"), client.pending(base).filter { it.amountMinor > 0 }.map { it.text }.toSet())
    }

    @Test
    fun theMatcherFilesOnlyWhatItIsSureOfAndOnlyWhenAllowed() = apiTest {
        val alice = newClient()
        alice.signup()
        val household = alice.createHousehold()
        val base = "/api/households/${household.id}"
        val current = alice.account(base, "Current")
        val savings = alice.account(base, "Savings")
        val currentFeed = alice.feed(base, current.id, "Current")
        val savingsFeed = alice.feed(base, savings.id, "Savings")
        alice.post("$base/expenses") { setBody(ExpenseInput(LocalDate(2026, 9, 20), 150_00, "Kiosk", accountId = current.id)) }
        alice.post("$base/expenses") { setBody(ExpenseInput(LocalDate(2026, 9, 20), 99_00, "Florist", accountId = current.id)) }
        alice.post("$base/expenses") { setBody(ExpenseInput(LocalDate(2026, 9, 25), 80_00, "Bakery", accountId = current.id)) }

        alice.upload(
            base, currentFeed.id,
            line("Transfer", 1, "To Savings", "-1000.00"), // pairs: 2 days
            line("Transfer", 1, "To Savings B", "-2000.00"), // 4 days away: no
            line("Transfer", 10, "To Savings C", "-3000.00"), // two outgoing for one incoming: no
            line("Transfer", 11, "To Savings D", "-3000.00"),
            card(21, "Kiosk", "-150.00"), // one day off the typed one: yes
            card(23, "Florist", "-99.00"), // three days off: no
            card(25, "Bakery", "-80.00"), // two bank rows for one typed expense: no
            card(25, "Bakery Two", "-80.00"),
        )
        alice.upload(
            base, savingsFeed.id,
            line("Transfer", 3, "From Current", "1000.00"),
            line("Transfer", 5, "From Current B", "2000.00"),
            line("Transfer", 10, "From Current C", "3000.00"),
        )
        // Off by default: nothing is filed, on a statement or on demand.
        assertEquals(11, alice.pending(base).size)
        assertEquals(ApplyRulesResult(11, 0, 0, 0, 0), alice.apply(base))
        assertEquals(11, alice.pending(base).size)

        // Only owners change the setting.
        val bob = newClient().also { it.signup("bob@example.com", "Bob") }
        val invite = alice.post("/api/households/${household.id}/invites") { setBody(CreateInviteRequest("bob@example.com", HouseholdRole.MEMBER)) }.body<Invite>()
        bob.post("/api/invites/${invite.token}/accept")
        bob.put("$base/settings") { setBody(HouseholdSettings(true, null, JointFunding.POT)) }.assertError(HttpStatusCode.Forbidden, "owner_only")
        assertEquals(HouseholdSettings(false, null, JointFunding.POT), bob.get("$base/settings").body<HouseholdSettings>())
        assertEquals(HouseholdSettings(true, null, JointFunding.POT), alice.put("$base/settings") { setBody(HouseholdSettings(true, null, JointFunding.POT)) }.body<HouseholdSettings>())

        val result = alice.apply(base)
        assertEquals(ApplyRulesResult(11, 0, 0, pairedByMatcher = 2, duplicatesByMatcher = 1), result)
        val rows = alice.allRows(base)
        val auto = rows.filter { it.decidedAuto }
        assertEquals(setOf("Savings", "Current", "Kiosk"), auto.map { it.text }.toSet())
        assertTrue(auto.all { it.ruleId == null && it.decidedByUserId == null })
        assertEquals(3, alice.expenses(base).size)
        val transfer = alice.transfers(base).single()
        assertEquals(2, transfer.rowIds.size)
        assertEquals(current.id, transfer.fromAccountId)
        assertEquals(8, alice.pending(base).size)

        // A statement with the other side of a pending row pairs the two, once the setting is on.
        alice.upload(base, currentFeed.id, line("Transfer", 15, "To Savings E", "-500.00"))
        alice.upload(base, savingsFeed.id, line("Transfer", 16, "From Current E", "500.00"))
        assertEquals(2, alice.transfers(base).size)
        assertEquals(8, alice.pending(base).size)

        // Undoing the matcher's work: the pair's transfer goes, the linked typed expense stays.
        alice.post("$base/bank-rows/undo") { setBody(UndoBankRows(alice.allRows(base).filter { it.decidedAuto }.map { it.ref() })) }
        assertEquals(emptyList(), alice.transfers(base))
        assertEquals(3, alice.expenses(base).size)
        assertEquals(13, alice.pending(base).size)

        // Between two members' own accounts it may be a settlement: left for a person.
        val members = alice.get("$base/members").body<List<Member>>()
        val alices = alice.account(base, "Alice's", owner = members.first { it.displayName == "Alice" }.userId)
        val bobs = alice.account(base, "Bob's", owner = members.first { it.displayName == "Bob" }.userId)
        alice.upload(base, alice.feed(base, alices.id, "Alice's").id, line("Transfer", 20, "To Bob", "-700.00"))
        alice.upload(base, alice.feed(base, bobs.id, "Bob's").id, line("Transfer", 20, "From Alice", "700.00"))
        assertEquals(emptyList(), alice.transfers(base))
        assertEquals(15, alice.pending(base).size)
    }

    @Test
    fun anImportFilesWhatRulesCoverToo() = apiTest {
        val client = newClient().also { it.signup() }
        val base = "/api/households/${client.createHousehold().id}"
        client.rule(base, InboxRuleInput(name = "Kiosk", textContains = "kiosk", outcome = RuleOutcome.SKIP, skipReason = "Small stuff", auto = true))
        fun raw(id: String, amount: String, name: String): JsonObject = Json.parseToJsonElement(
            """{"transactionId":"$id","bookingDate":"2026-09-10","transactionAmount":{"amount":"$amount","currency":"CZK"},"creditorName":"$name"}""",
        ).jsonObject
        val file = HouseholdImport(
            currency = "CZK",
            accounts = listOf(ImportAccount("Current")),
            bankFeeds = listOf(ImportBankFeed("Current · CZK", "Current", "CZK", BankInstitution.KB)),
            bankRows = listOf(
                ImportBankRow("Current · CZK", raw("t1", "-70", "Kiosk")),
                ImportBankRow("Current · CZK", raw("t2", "-90", "Bakery")),
            ),
        )
        assertEquals(HttpStatusCode.OK, client.post("$base/import") { setBody(file) }.status)
        assertEquals(listOf("Bakery"), client.pending(base).map { it.text })
        val kiosk = client.allRows(base).first { it.text == "Kiosk" }
        assertEquals(BankRowOutcome.SKIPPED, kiosk.outcome)
        assertTrue(kiosk.decidedAuto)
    }

    @Test
    fun moneyInOffersTheExpensesItMayRefund() = apiTest {
        val client = newClient().also { it.signup() }
        val base = "/api/households/${client.createHousehold().id}"
        val account = client.account(base, "Revolut")
        val feed = client.feed(base, account.id, "Revolut · CZK")
        suspend fun typed(date: LocalDate, amount: Long, merchant: String) =
            client.post("$base/expenses") { setBody(ExpenseInput(date, amount, merchant)) }.body<Expense>()
        val tooOld = typed(LocalDate(2026, 5, 1), 400_00, "Corner Bakery")
        val older = typed(LocalDate(2026, 8, 20), 150_00, "Corner Bakery")
        val newer = typed(LocalDate(2026, 9, 1), 200_00, "corner bakery")
        val tooSmall = typed(LocalDate(2026, 9, 2), 40_00, "Corner Bakery")
        val elsewhere = typed(LocalDate(2026, 9, 1), 500_00, "Pizza Place")
        val laterThanTheRow = typed(LocalDate(2026, 9, 20), 300_00, "Corner Bakery")
        // A refund already made doesn't make an original a candidate again, but shows what it has been refunded.
        client.post("$base/expenses") { setBody(ExpenseInput(LocalDate(2026, 9, 5), -50_00, "Corner Bakery", refundOfId = newer.id)) }

        client.upload(base, feed.id, line("Card Refund", 10, "Corner Bakery", "100.00"), card(11, "Corner Bakery", "-100.00"))
        val rows = client.pending(base)
        val refund = rows.first { it.text == "Corner Bakery" && it.amountMinor > 0 }.suggestion!!
        assertEquals(SuggestedRecord.EXPENSE, refund.record)
        assertEquals(-100_00, refund.amountMinor)
        // Newest first within 120 days before the row, at least as large as the refund, and not refunds themselves.
        assertEquals(listOf(newer.id, older.id), refund.refundCandidates.map { it.id })
        assertEquals(listOf(50_00L, 0L), refund.refundCandidates.map { it.refundedMinor })
        assertEquals("Corner Bakery", refund.refundCandidates.first().label)
        assertEquals(setOf(tooOld.id, tooSmall.id, elsewhere.id, laterThanTheRow.id).intersect(refund.refundCandidates.map { it.id }.toSet()), emptySet())
        // Money out refunds nothing.
        assertEquals(emptyList(), rows.first { it.amountMinor < 0 }.suggestion!!.refundCandidates)
    }

    @Test
    fun aRowAtTheSameMerchantKeyAsAnEarlierRowOffersItsExpense() = apiTest {
        val client = newClient().also { it.signup() }
        val base = "/api/households/${client.createHousehold().id}"
        val account = client.account(base, "Revolut")
        val feed = client.feed(base, account.id, "Revolut · CZK")
        // The purchase was made from a bank row under a merchant name that differs from the descriptor.
        client.upload(base, feed.id, card(2, "Shoe Store Brno", "-900.00"))
        val purchase = client.pending(base).single()
        val made = client.post("$base/expenses") {
            setBody(ExpenseInput(purchase.date, 900_00, "Boots", bankRows = listOf(purchase.ref())))
        }.body<Expense>()
        client.upload(base, feed.id, line("Card Refund", 9, "Shoe Store Brno", "300.00"))
        val suggestion = client.pending(base).single().suggestion!!
        assertEquals(listOf(made.id), suggestion.refundCandidates.map { it.id })
    }

    @Test
    fun aDeductionRuleSuggestsAPrefilledDeduction() = apiTest {
        val client = newClient()
        val me = client.signup()
        val base = "/api/households/${client.createHousehold().id}"
        val account = client.account(base, "Revolut", owner = me.id)
        val feed = client.feed(base, account.id, "Revolut · CZK")
        val rule = client.rule(
            base,
            InboxRuleInput(name = "Health insurance", textContains = "zpmv", outcome = RuleOutcome.DEDUCTION, incomeSource = "ZPMV", personUserId = me.id),
        )
        assertEquals(-1, rule.monthOffset)
        assertEquals("ZPMV", rule.incomeSource)
        client.rule(base, InboxRuleInput(name = "Social", textContains = "ossz", outcome = RuleOutcome.DEDUCTION, incomeSource = "OSSZ", monthOffset = 0))
        client.post("$base/inbox-rules") {
            setBody(InboxRuleInput(name = "x", textContains = "x", outcome = RuleOutcome.DEDUCTION, monthOffset = 13))
        }.assertError(HttpStatusCode.BadRequest, "invalid_month_offset")
        // The offset only means something to a deduction.
        assertEquals(-1, client.rule(base, InboxRuleInput(name = "Y", textContains = "y", outcome = RuleOutcome.EXPENSE, monthOffset = 5)).monthOffset)

        client.upload(base, feed.id, line("Transfer", 12, "ZPMV advance", "-2500.00"), line("Transfer", 13, "OSSZ advance", "-3200.00"))
        val rows = client.pending(base)
        val zpmv = rows.first { it.text == "ZPMV advance" }.suggestion!!
        assertEquals(rule.id, zpmv.ruleId)
        assertEquals(SuggestedRecord.INCOME, zpmv.record)
        assertEquals(IncomeKind.DEDUCTION, zpmv.incomeKind)
        // Money out is a positive deduction, for the month before the row's.
        assertEquals(250_000, zpmv.amountMinor)
        assertEquals("2026-08", zpmv.earnedMonth)
        assertEquals("ZPMV", zpmv.incomeSource)
        assertEquals(me.id, zpmv.paidByUserId)
        assertEquals("2026-09", rows.first { it.text == "OSSZ advance" }.suggestion!!.earnedMonth)

        // Nothing is filed without auto; the form's save is an ordinary deduction made from the row.
        assertEquals(emptyList(), client.get("$base/income").body<List<Income>>())
        val row = rows.first { it.text == "ZPMV advance" }
        client.post("$base/income") {
            setBody(
                IncomeInput(
                    row.date, zpmv.amountMinor!!, zpmv.incomeSource, account.id, zpmv.paidByUserId,
                    earnedMonth = zpmv.earnedMonth, kind = zpmv.incomeKind, bankRows = listOf(row.ref()),
                ),
            )
        }
        assertEquals(BankRowOutcome.INCOME, client.allRows(base).first { it.id == row.id }.outcome)
    }

    @Test
    fun anAutoDeductionRuleFilesADeductionOrLinksTheOneEnteredByHand() = apiTest {
        val client = newClient()
        val me = client.signup()
        val base = "/api/households/${client.createHousehold().id}"
        val account = client.account(base, "Revolut")
        val feed = client.feed(base, account.id, "Revolut · CZK")
        client.rule(
            base,
            InboxRuleInput(name = "Health insurance", textContains = "zpmv", outcome = RuleOutcome.DEDUCTION, incomeSource = "ZPMV", personUserId = me.id, auto = true),
        )
        client.rule(base, InboxRuleInput(name = "Social", textContains = "ossz", outcome = RuleOutcome.DEDUCTION, incomeSource = "OSSZ", monthOffset = 0, auto = true))
        // An earning of the same amount around the same day is not the deduction's twin; a hand-entered deduction is.
        client.post("$base/income") { setBody(IncomeInput(LocalDate(2026, 10, 13), 3_200_00, "Client", account.id)) }
        val typed = client.post("$base/income") {
            setBody(IncomeInput(LocalDate(2026, 9, 14), 2_500_00, "ZPMV", account.id, earnedMonth = "2026-08", kind = IncomeKind.DEDUCTION))
        }.body<Income>()

        client.upload(
            base, feed.id,
            line("Transfer", 12, "ZPMV advance", "-2500.00"),
            line("Transfer", 1, "ZPMV advance", "-2500.00"),
            line("Transfer", 13, "OSSZ advance", "-3200.00"),
        )
        assertEquals(emptyList(), client.pending(base))

        val income = client.get("$base/income").body<List<Income>>()
        val deductions = income.filter { it.kind == IncomeKind.DEDUCTION }
        // Three rows: one linked to the typed deduction, two made.
        assertEquals(3, deductions.size)
        val rows = client.allRows(base)
        assertEquals(typed.id, rows.first { it.date == LocalDate(2026, 9, 12) }.incomeId)
        assertTrue(rows.all { it.decidedAuto && it.ruleId != null })

        val september = deductions.first { it.id == rows.first { r -> r.date == LocalDate(2026, 9, 13) }.incomeId }
        assertEquals("OSSZ", september.source)
        assertEquals(3_200_00, september.amountMinor)
        assertEquals(LocalDate(2026, 9, 13), september.date)
        // Offset 0: the row's own month. The account is the feed's.
        assertEquals("2026-09", september.earnedMonth)
        assertEquals(account.id, september.accountId)

        val early = deductions.first { it.id == rows.first { r -> r.date == LocalDate(2026, 9, 1) }.incomeId }
        assertEquals("ZPMV", early.source)
        assertEquals(me.id, early.recipientUserId)
        assertEquals("2026-08", early.earnedMonth)
        assertEquals(2_500_00, early.amountMinor)

        // Undoing a row that made a deduction deletes it; the hand-entered one stays.
        client.post("$base/bank-rows/undo") { setBody(UndoBankRows(rows.map { it.ref() })) }
        assertEquals(listOf(IncomeKind.EARNING, IncomeKind.DEDUCTION), client.get("$base/income").body<List<Income>>().map { it.kind }.sorted())
    }

    @Test
    fun aSettlementRuleSuggestsAndAutoFilesASettlement() = apiTest {
        val client = newClient()
        val alice = client.signup("alice@example.com", "Alice")
        val base = "/api/households/${client.createHousehold().id}"
        val bob = client.post("$base/members") { setBody(dev.spendingtracker.shared.api.MemberInput("Bob")) }.body<dev.spendingtracker.shared.api.Member>()
        val account = client.account(base, "Alice")
        val bobAccount = client.account(base, "Bob")
        val feed = client.feed(base, account.id, "Alice · CZK")
        val bobFeed = client.feed(base, bobAccount.id, "Bob · CZK")

        // Validation: requires both members and different members.
        client.post("$base/inbox-rules") {
            setBody(InboxRuleInput(name = "Bad", textContains = "repay", outcome = RuleOutcome.SETTLEMENT))
        }.assertError(HttpStatusCode.BadRequest, "required")
        client.post("$base/inbox-rules") {
            setBody(InboxRuleInput(name = "Same", textContains = "repay", outcome = RuleOutcome.SETTLEMENT, settleFromUserId = alice.id, settleToUserId = alice.id))
        }.assertError(HttpStatusCode.BadRequest, "same_member")

        // Non-auto rule shapes the suggestion.
        val rule = client.rule(
            base,
            InboxRuleInput(
                name = "Monthly repay",
                textContains = "repay",
                outcome = RuleOutcome.SETTLEMENT,
                settleFromUserId = alice.id,
                settleToUserId = bob.userId,
            ),
        )
        assertEquals(alice.id, rule.settleFromUserId)
        assertEquals(bob.userId, rule.settleToUserId)

        client.upload(base, feed.id, line("Transfer", 10, "Repay Bob", "-1500.00"))
        val pendingRow = client.pending(base).single()
        val s = pendingRow.suggestion!!
        assertEquals(rule.id, s.ruleId)
        assertEquals(SuggestedRecord.SETTLEMENT, s.record)
        assertEquals(alice.id, s.settleFromUserId)
        assertEquals(bob.userId, s.settleToUserId)
        assertEquals(150_000L, s.amountMinor)
        assertEquals(emptyList(), client.get("$base/settlements").body<List<dev.spendingtracker.shared.api.Settlement>>())

        // Turning auto on and applying rules pairs the matching row with the other side into one settlement.
        client.put("$base/inbox-rules/${rule.id}") {
            setBody(
                InboxRuleInput(
                    name = "Monthly repay",
                    textContains = "repay",
                    outcome = RuleOutcome.SETTLEMENT,
                    settleFromUserId = alice.id,
                    settleToUserId = bob.userId,
                    auto = true,
                    version = rule.version,
                ),
            )
        }
        client.upload(base, bobFeed.id, line("Transfer", 10, "From Alice", "1500.00"))
        client.post("$base/bank-rows/apply-rules")
        assertEquals(emptyList(), client.pending(base))
        val settlements = client.get("$base/settlements").body<List<dev.spendingtracker.shared.api.Settlement>>()
        assertEquals(1, settlements.size)
        val settled = settlements.single()
        assertEquals(alice.id, settled.fromUserId)
        assertEquals(bob.userId, settled.toUserId)
        assertEquals(150_000L, settled.amountMinor)

        val rows = client.allRows(base)
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.outcome == BankRowOutcome.SETTLEMENT && it.settlementId == settled.id && it.decidedAuto })

        // Undoing both rows deletes the settlement.
        client.post("$base/bank-rows/undo") { setBody(UndoBankRows(rows.map { it.ref() })) }
        assertEquals(emptyList(), client.get("$base/settlements").body<List<dev.spendingtracker.shared.api.Settlement>>())
    }

    @Test
    fun incomeRulesSetCategoryAndRejectWrongCategoryKind() = apiTest {
        val client = newClient().also { it.signup() }
        val base = "/api/households/${client.createHousehold().id}"
        val categories = client.get("$base/categories").body<List<dev.spendingtracker.shared.api.Category>>()
        val salary = categories.single { it.name == "Salary" }
        val groceries = categories.single { it.name == "Groceries" }
        val account = client.account(base, "Checking")
        val feed = client.feed(base, account.id, "Checking · CZK")

        client.post("$base/inbox-rules") {
            setBody(InboxRuleInput(name = "Bad income", textContains = "acme", outcome = RuleOutcome.INCOME, categoryId = groceries.id))
        }.assertError(HttpStatusCode.BadRequest, "invalid_category")
        client.post("$base/inbox-rules") {
            setBody(InboxRuleInput(name = "Bad expense", textContains = "shop", outcome = RuleOutcome.EXPENSE, categoryId = salary.id))
        }.assertError(HttpStatusCode.BadRequest, "invalid_category")

        val rule = client.rule(
            base,
            InboxRuleInput(name = "Acme salary", textContains = "acme", outcome = RuleOutcome.INCOME, incomeSource = "Acme", categoryId = salary.id, auto = true),
        )
        assertEquals(salary.id, rule.categoryId)

        client.upload(base, feed.id, line("Transfer", 10, "Acme payroll", "45000.00"))
        assertEquals(emptyList(), client.pending(base))
        val income = client.get("$base/income").body<List<Income>>().single()
        assertEquals(salary.id, income.categoryId)
        assertEquals("Acme", income.source)
    }
}
