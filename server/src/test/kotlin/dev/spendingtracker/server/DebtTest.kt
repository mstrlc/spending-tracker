package dev.spendingtracker.server

import dev.spendingtracker.server.debt.allocate
import dev.spendingtracker.shared.api.Account
import dev.spendingtracker.shared.api.AccountInput
import dev.spendingtracker.shared.api.ActivityEntity
import dev.spendingtracker.shared.api.ActivityPage
import dev.spendingtracker.shared.api.BalanceEntryKind
import dev.spendingtracker.shared.api.Balances
import dev.spendingtracker.shared.api.Category
import dev.spendingtracker.shared.api.CategoryInput
import dev.spendingtracker.shared.api.Debt
import dev.spendingtracker.shared.api.Expense
import dev.spendingtracker.shared.api.ExpenseInput
import dev.spendingtracker.shared.api.ExpenseLineInput
import dev.spendingtracker.shared.api.Member
import dev.spendingtracker.shared.api.MemberInput
import dev.spendingtracker.shared.api.Settlement
import dev.spendingtracker.shared.api.SettlementInput
import dev.spendingtracker.shared.api.Share
import dev.spendingtracker.shared.api.SplitRule
import dev.spendingtracker.shared.api.SplitRuleInput
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class DebtTest {
    @Test
    fun allocationAddsUpExactly() {
        val a = Uuid.parse("00000000-0000-0000-0000-00000000000a")
        val b = Uuid.parse("00000000-0000-0000-0000-00000000000b")
        val c = Uuid.parse("00000000-0000-0000-0000-00000000000c")
        assertEquals(mapOf(a to 34L, b to 33L, c to 33L), allocate(100, mapOf(a to 1, b to 1, c to 1)))
        assertEquals(mapOf(a to -600L, b to -400L), allocate(-1000, mapOf(a to 60, b to 40)))
        assertEquals(mapOf(a to 333L, b to 666L), allocate(999, mapOf(a to 1, b to 2)))
    }

    @Test
    fun mismatchesBetweenPayerAndOwnerBecomeDebts() = apiTest {
        val client = newClient()
        val alice = client.signup().id
        val base = "/api/households/${client.createHousehold().id}"
        val bob = client.post("$base/members") { setBody(MemberInput("Bob")) }.body<Member>().userId
        val aliceCard = client.account(base, "Alice's card", alice)
        val bobCard = client.account(base, "Bob's card", bob)
        val joint = client.account(base, "Joint", null)
        val groceries = client.post("$base/categories") { setBody(CategoryInput("Potraviny")) }.body<Category>().id

        suspend fun expense(input: ExpenseInput) = client.post("$base/expenses") { setBody(input) }.body<Expense>()
        fun owes(from: String, to: String, amount: Long) = listOf(Debt(from, to, amount))

        // Before any rule, joint spending is shared equally.
        assertEquals(owes(bob, alice, 500), expense(ExpenseInput(LocalDate(2025, 12, 1), 1000, accountId = aliceCard)).debts)

        client.post("$base/split-rules") {
            setBody(SplitRuleInput(LocalDate(2026, 1, 1), listOf(Share(alice, 60), Share(bob, 40))))
        }.body<SplitRule>()
        val groceryRule = client.post("$base/split-rules") {
            setBody(SplitRuleInput(LocalDate(2026, 6, 1), listOf(Share(alice, 1), Share(bob, 1), Share(bob, 0)), groceries))
        }.assertError(HttpStatusCode.BadRequest, "duplicate_member").let {
            client.post("$base/split-rules") {
                setBody(SplitRuleInput(LocalDate(2026, 6, 1), listOf(Share(alice, 1), Share(bob, 1)), groceries))
            }.body<SplitRule>()
        }
        client.post("$base/split-rules") { setBody(SplitRuleInput(LocalDate(2026, 6, 1), listOf(Share(alice, 1)), groceries)) }
            .assertError(HttpStatusCode.Conflict, "rule_exists")
        client.post("$base/split-rules") { setBody(SplitRuleInput(LocalDate(2026, 6, 1), listOf(Share(alice, 0)))) }
            .assertError(HttpStatusCode.BadRequest, "empty_split")
        client.post("$base/split-rules") { setBody(SplitRuleInput(LocalDate(2026, 6, 1), listOf(Share(Uuid.random().toString(), 1)))) }
            .assertError(HttpStatusCode.BadRequest, "invalid_member")

        // A joint expense on Alice's card: Bob owes his share, by the household split or the category's from its date.
        assertEquals(owes(bob, alice, 400), expense(ExpenseInput(LocalDate(2026, 3, 1), 1000, accountId = aliceCard)).debts)
        assertEquals(owes(bob, alice, 400), expense(ExpenseInput(LocalDate(2026, 5, 31), 1000, categoryId = groceries, accountId = aliceCard)).debts)
        assertEquals(owes(bob, alice, 500), expense(ExpenseInput(LocalDate(2026, 7, 1), 1000, categoryId = groceries, accountId = aliceCard)).debts)
        // Bob's own spending from the joint account: he owes Alice her part of the pot, whatever the category.
        assertEquals(owes(bob, alice, 180), expense(ExpenseInput(LocalDate(2026, 7, 2), 300, categoryId = groceries, accountId = joint, paidByUserId = bob)).debts)
        // Bob's own spending on Alice's card: all of it.
        assertEquals(owes(bob, alice, 100), expense(ExpenseInput(LocalDate(2026, 7, 3), 100, accountId = aliceCard, paidByUserId = bob)).debts)
        // A joint expense with its own split, on Bob's card.
        val own = expense(ExpenseInput(LocalDate(2026, 7, 4), 999, accountId = bobCard, split = listOf(Share(alice, 1), Share(bob, 2))))
        assertEquals(owes(alice, bob, 333) to listOf(Share(alice, 1), Share(bob, 2)).sortedBy { it.userId }, own.debts to own.split)
        // Switched off, no account, or matching: nothing.
        assertEquals(emptyList(), expense(ExpenseInput(LocalDate(2026, 7, 5), 1000, accountId = aliceCard, createsDebt = false)).debts)
        assertEquals(emptyList(), expense(ExpenseInput(LocalDate(2026, 7, 5), 1000)).debts)
        assertEquals(emptyList(), expense(ExpenseInput(LocalDate(2026, 7, 5), 1000, accountId = joint)).debts)
        assertEquals(emptyList(), expense(ExpenseInput(LocalDate(2026, 7, 5), 1000, accountId = aliceCard, paidByUserId = alice)).debts)
        // A refund on Alice's card takes Bob's share back off.
        assertEquals(owes(bob, alice, -40), expense(ExpenseInput(LocalDate(2026, 7, 6), -100, accountId = aliceCard)).debts)

        // 500 + 400 + 400 + 500 + 180 + 100 - 40 owed to Alice, 333 owed to Bob.
        assertEquals(owes(bob, alice, 1707), client.balances(base).balances)

        val settlement = client.post("$base/settlements") {
            setBody(SettlementInput(LocalDate(2026, 7, 31), bob, alice, 707, "Revolut"))
        }.body<Settlement>()
        client.post("$base/settlements") { setBody(SettlementInput(LocalDate(2026, 7, 31), bob, bob, 1)) }
            .assertError(HttpStatusCode.BadRequest, "same_member")
        client.post("$base/settlements") { setBody(SettlementInput(LocalDate(2026, 7, 31), bob, alice, 0)) }
            .assertError(HttpStatusCode.BadRequest, "invalid_amount")
        val july = client.get("$base/balances?from=2026-07-01&to=2026-07-31").body<Balances>()
        assertEquals(owes(bob, alice, 1000), july.balances)
        assertEquals(BalanceEntryKind.SETTLEMENT to owes(alice, bob, 707), july.entries.first().let { it.kind to it.debts })
        assertEquals(6, july.entries.size)

        // Taking the expense's own split away puts it back on the household's 60/40: Alice owes 599 instead of 333.
        val shared = client.put("$base/expenses/${own.id}") {
            setBody(ExpenseInput(own.date, own.amountMinor, accountId = bobCard, version = own.version))
        }.body<Expense>()
        assertEquals(owes(alice, bob, 599) to null, shared.debts to shared.split)
        assertEquals(owes(bob, alice, 734), client.balances(base).balances)

        // Moving the grocery rule later sends the July groceries back to 60/40.
        client.put("$base/split-rules/${groceryRule.id}") {
            setBody(SplitRuleInput(LocalDate(2026, 8, 1), groceryRule.shares, groceries, version = groceryRule.version))
        }.body<SplitRule>()
        assertEquals(owes(bob, alice, 634), client.balances(base).balances)
        assertEquals(2, client.get("$base/split-rules").body<List<SplitRule>>().size)

        client.delete("$base/settlements/${settlement.id}?version=${settlement.version}")
        assertEquals(owes(bob, alice, 1341), client.balances(base).balances)

        val logged = client.get("$base/activity").body<ActivityPage>().entries
        assertEquals("Bob → Alice", logged.first { it.entity == ActivityEntity.SETTLEMENT }.label)
        assertEquals(setOf("Alice", "Bob"), logged.filter { it.entity == ActivityEntity.SPLIT_SHARE }.map { it.label }.toSet())

        // A receipt split across Groceries (50/50 from August) and another category (60/40 household split):
        // Bob owes half of the 400 groceries (200) plus 40 % of the 600 rest (240) = 440.
        val splitReceipt = expense(
            ExpenseInput(
                date = LocalDate(2026, 8, 10),
                amountMinor = 1000,
                accountId = aliceCard,
                lines = listOf(ExpenseLineInput(groceries, 400), ExpenseLineInput(null, 600)),
            ),
        )
        assertEquals(owes(bob, alice, 440), splitReceipt.debts)
    }

    private suspend fun HttpClient.account(base: String, name: String, owner: String?) =
        post("$base/accounts") { setBody(AccountInput(name, owner)) }.body<Account>().id

    private suspend fun HttpClient.balances(base: String) = get("$base/balances").body<Balances>()
}
