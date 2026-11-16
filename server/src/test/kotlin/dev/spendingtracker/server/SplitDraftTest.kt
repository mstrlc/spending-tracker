package dev.spendingtracker.server

import dev.spendingtracker.shared.api.Account
import dev.spendingtracker.shared.api.AccountInput
import dev.spendingtracker.shared.api.BankFeed
import dev.spendingtracker.shared.api.BankFeedInput
import dev.spendingtracker.shared.api.BankInstitution
import dev.spendingtracker.shared.api.BankRow
import dev.spendingtracker.shared.api.BankRowOutcome
import dev.spendingtracker.shared.api.BankRowRef
import dev.spendingtracker.shared.api.Category
import dev.spendingtracker.shared.api.CategoryInput
import dev.spendingtracker.shared.api.CategoryKind
import dev.spendingtracker.shared.api.DraftBankRow
import dev.spendingtracker.shared.api.ExpenseInput
import dev.spendingtracker.shared.api.ExpenseLineInput
import dev.spendingtracker.shared.api.SplitDraft
import dev.spendingtracker.shared.api.StatementUpload
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Every statement here is made up: real ones hold household data.
private const val HEADER = "Type,Product,Started Date,Completed Date,Description,Amount,Fee,Currency,State,Balance"

class SplitDraftTest {
    private suspend fun HttpClient.pending(base: String) = get("$base/bank-rows").body<List<BankRow>>()

    @Test
    fun aDraftKeepsTheRowInTheInboxUntilFiled() = apiTest {
        val client = newClient()
        val me = client.signup()
        val base = "/api/households/${client.createHousehold().id}"
        val account = client.post("$base/accounts") { setBody(AccountInput("Current")) }.body<Account>()
        val feed = client.post("$base/bank-feeds") {
            setBody(BankFeedInput(account.id, "Current", BankInstitution.REVOLUT, currency = "CZK"))
        }.body<BankFeed>()
        val tech = client.post("$base/categories") { setBody(CategoryInput("Electronics")) }.body<Category>()
        val home = client.post("$base/categories") { setBody(CategoryInput("Home")) }.body<Category>()
        client.post("$base/bank-feeds/${feed.id}/statements") {
            setBody(StatementUpload("$HEADER\nCard Payment,Current,2026-11-05 08:00:00,2026-11-05 08:00:01,Alza.cz,-1500.00,0.00,CZK,COMPLETED,0.00"))
        }
        val row = client.pending(base).single()

        val draft = SplitDraft(
            lines = listOf(
                ExpenseLineInput(tech.id, 1_200_00, " Headphones ", me.id),
                ExpenseLineInput(home.id, 300_00, "Light bulbs", null),
            ),
            merchant = " Alza ",
            source = "Alza order 123",
        )
        val drafted = client.post("$base/bank-rows/draft") { setBody(DraftBankRow(BankRowRef(row.id, row.version), draft)) }.body<BankRow>()
        assertEquals(BankRowOutcome.PENDING, drafted.outcome)
        assertEquals(row.version + 1, drafted.version)
        assertEquals("Alza", drafted.draft!!.merchant)
        assertEquals("Headphones", drafted.draft!!.lines[0].notes)
        assertEquals(drafted.draft, client.pending(base).single().draft)

        // Lines that don't add up to the row, an unknown category or a stale version are refused.
        val ref = BankRowRef(row.id, drafted.version)
        client.post("$base/bank-rows/draft") {
            setBody(DraftBankRow(ref, draft.copy(lines = draft.lines.take(1))))
        }.assertError(HttpStatusCode.BadRequest, "draft_total")
        client.post("$base/bank-rows/draft") {
            setBody(DraftBankRow(ref, draft.copy(lines = draft.lines.map { it.copy(amountMinor = -it.amountMinor) })))
        }.assertError(HttpStatusCode.BadRequest, "invalid_line")
        client.post("$base/bank-rows/draft") {
            setBody(DraftBankRow(ref, draft.copy(lines = listOf(draft.lines[0].copy(categoryId = account.id), draft.lines[1]))))
        }.assertError(HttpStatusCode.BadRequest, "invalid_category")
        client.post("$base/bank-rows/draft") {
            setBody(DraftBankRow(BankRowRef(row.id, row.version), draft))
        }.assertError(HttpStatusCode.Conflict, "version_conflict")

        // Filing the row keeps the draft; a decided row's draft can't change.
        client.post("$base/expenses") {
            setBody(
                ExpenseInput(
                    row.date, 1_500_00, merchant = "Alza", accountId = account.id,
                    lines = draft.lines, bankRows = listOf(ref),
                ),
            )
        }
        val filed = client.get("$base/bank-rows?status=all").body<List<BankRow>>().single()
        assertEquals(BankRowOutcome.EXPENSE, filed.outcome)
        assertEquals("Alza order 123", filed.draft!!.source)
        client.post("$base/bank-rows/draft") {
            setBody(DraftBankRow(BankRowRef(filed.id, filed.version), null))
        }.assertError(HttpStatusCode.Conflict, "already_decided")

        // Put back, it can be cleared.
        val back = client.post("$base/bank-rows/undo") {
            setBody(dev.spendingtracker.shared.api.UndoBankRows(listOf(BankRowRef(filed.id, filed.version))))
        }.body<List<BankRow>>().single()
        val cleared = client.post("$base/bank-rows/draft") { setBody(DraftBankRow(BankRowRef(back.id, back.version), null)) }.body<BankRow>()
        assertNull(cleared.draft)
    }

    @Test
    fun aDraftCannotUseAnIncomeCategoryNorTurnItsCategoryIntoOne() = apiTest {
        val client = newClient().also { it.signup() }
        val base = "/api/households/${client.createHousehold().id}"
        val account = client.post("$base/accounts") { setBody(AccountInput("Current")) }.body<Account>()
        val feed = client.post("$base/bank-feeds") {
            setBody(BankFeedInput(account.id, "Current", BankInstitution.REVOLUT, currency = "CZK"))
        }.body<BankFeed>()
        val tech = client.post("$base/categories") { setBody(CategoryInput("Electronics")) }.body<Category>()
        val bonus = client.post("$base/categories") { setBody(CategoryInput("Bonus", kind = CategoryKind.INCOME)) }.body<Category>()
        client.post("$base/bank-feeds/${feed.id}/statements") {
            setBody(StatementUpload("$HEADER\nCard Payment,Current,2026-11-05 08:00:00,2026-11-05 08:00:01,Alza.cz,-1500.00,0.00,CZK,COMPLETED,0.00"))
        }
        val row = client.pending(base).single()
        val ref = BankRowRef(row.id, row.version)

        client.post("$base/bank-rows/draft") {
            setBody(DraftBankRow(ref, SplitDraft(lines = listOf(ExpenseLineInput(bonus.id, 1_500_00)))))
        }.assertError(HttpStatusCode.BadRequest, "invalid_category")

        client.post("$base/bank-rows/draft") {
            setBody(DraftBankRow(ref, SplitDraft(lines = listOf(ExpenseLineInput(tech.id, 1_500_00)))))
        }.also { assertEquals(HttpStatusCode.OK, it.status) }
        client.put("$base/categories/${tech.id}") {
            setBody(CategoryInput("Electronics", kind = CategoryKind.INCOME, version = tech.version))
        }.assertError(HttpStatusCode.Conflict, "category_in_use")
    }
}
