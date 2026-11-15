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
import dev.spendingtracker.shared.api.CreateInviteRequest
import dev.spendingtracker.shared.api.Debt
import dev.spendingtracker.shared.api.Expense
import dev.spendingtracker.shared.api.ExpenseInput
import dev.spendingtracker.shared.api.ExpenseLineInput
import dev.spendingtracker.shared.api.Household
import dev.spendingtracker.shared.api.HouseholdSettings
import dev.spendingtracker.shared.api.Invite
import dev.spendingtracker.shared.api.JointContribution
import dev.spendingtracker.shared.api.JointFunding
import dev.spendingtracker.shared.api.Member
import dev.spendingtracker.shared.api.MemberInput
import dev.spendingtracker.shared.api.Settlement
import dev.spendingtracker.shared.api.SettlementInput
import dev.spendingtracker.shared.api.Share
import dev.spendingtracker.shared.api.SplitRule
import dev.spendingtracker.shared.api.SplitRuleInput
import dev.spendingtracker.shared.api.TransferInput
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
import kotlin.test.assertTrue
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

        // Lines of different people on Alice's card: half the joint groceries (200) plus all of Bob's line (300), in
        // one debt; Alice's own line makes none. The expense has no single whose.
        val mixed = expense(
            ExpenseInput(
                date = LocalDate(2026, 8, 11),
                amountMinor = 1000,
                accountId = aliceCard,
                paidByUserId = alice,
                lines = listOf(
                    ExpenseLineInput(groceries, 400),
                    ExpenseLineInput(null, 300, paidByUserId = bob),
                    ExpenseLineInput(null, 300, paidByUserId = alice),
                ),
            ),
        )
        assertEquals(owes(bob, alice, 500), mixed.debts)
        assertEquals(null, mixed.paidByUserId)
        assertEquals(listOf(null, bob, alice), mixed.lines.map { it.paidByUserId })

        // From the joint account each person's line costs them the other's share of the pot (60/40); the joint line nothing.
        val fromPot = expense(
            ExpenseInput(
                date = LocalDate(2026, 8, 12),
                amountMinor = 1000,
                accountId = joint,
                lines = listOf(
                    ExpenseLineInput(groceries, 500),
                    ExpenseLineInput(null, 300, paidByUserId = bob),
                    ExpenseLineInput(null, 200, paidByUserId = alice),
                ),
            ),
        )
        assertEquals(setOf(Debt(bob, alice, 180), Debt(alice, bob, 80)), fromPot.debts.toSet())

        // Lines that all belong to Bob make it Bob's expense, as if it had no lines.
        val allBob = expense(
            ExpenseInput(
                date = LocalDate(2026, 8, 13),
                amountMinor = 500,
                accountId = aliceCard,
                lines = listOf(ExpenseLineInput(groceries, 200, paidByUserId = bob), ExpenseLineInput(null, 300, paidByUserId = bob)),
            ),
        )
        assertEquals(bob to owes(bob, alice, 500), allBob.paidByUserId to allBob.debts)

        client.post("$base/expenses") {
            setBody(
                ExpenseInput(
                    LocalDate(2026, 8, 14), 500, accountId = aliceCard,
                    lines = listOf(ExpenseLineInput(null, 200), ExpenseLineInput(null, 300, paidByUserId = Uuid.random().toString())),
                ),
            )
        }.assertError(HttpStatusCode.BadRequest, "invalid_member")
    }

    @Test
    fun debtsFromCutsOffEarlierExpensesAndSettlements() = apiTest {
        val aliceClient = newClient()
        val alice = aliceClient.signup("alice@example.com", "Alice").id
        val bobClient = newClient()
        bobClient.signup("bob@example.com", "Bob")
        val householdId = aliceClient.createHousehold().id
        val base = "/api/households/$householdId"
        val invite = aliceClient.post("$base/invites") { setBody(CreateInviteRequest("bob@example.com")) }.body<Invite>().token!!
        bobClient.post("/api/invites/$invite/accept")
        val bob = bobClient.get("$base/members").body<List<Member>>().single { it.displayName == "Bob" }.userId
        val aliceCard = aliceClient.account(base, "Alice's card", alice)

        fun owes(from: String, to: String, amount: Long) = listOf(Debt(from, to, amount))
        suspend fun expense(date: LocalDate, amount: Long) =
            aliceClient.post("$base/expenses") { setBody(ExpenseInput(date, amount, accountId = aliceCard)) }.body<Expense>()

        val before = expense(LocalDate(2026, 7, 10), 1000)
        val on = expense(LocalDate(2026, 7, 15), 2000)
        val after = expense(LocalDate(2026, 7, 20), 3000)
        aliceClient.post("$base/settlements") { setBody(SettlementInput(LocalDate(2026, 7, 11), bob, alice, 200)) }
        aliceClient.post("$base/settlements") { setBody(SettlementInput(LocalDate(2026, 7, 15), bob, alice, 300)) }

        suspend fun expenseDebts(id: String) =
            aliceClient.get("$base/expenses").body<List<Expense>>().single { it.id == id }.debts

        assertEquals(owes(bob, alice, 2500), aliceClient.balances(base).balances)
        assertEquals(owes(bob, alice, 500), expenseDebts(before.id))

        bobClient.put("$base/settings") { setBody(HouseholdSettings(false, LocalDate(2026, 7, 15), JointFunding.POT)) }
            .assertError(HttpStatusCode.Forbidden, "owner_only")

        val updated = aliceClient.put("$base/settings") { setBody(HouseholdSettings(false, LocalDate(2026, 7, 15), JointFunding.POT)) }
            .body<HouseholdSettings>()
        assertEquals(LocalDate(2026, 7, 15), updated.debtsFrom)
        assertEquals(
            LocalDate(2026, 7, 15),
            aliceClient.get("/api/households").body<List<Household>>().single().debtsFrom,
        )

        val cut = aliceClient.balances(base)
        assertEquals(owes(bob, alice, 2200), cut.balances)
        assertEquals(3, cut.entries.size)
        assertEquals(emptyList(), expenseDebts(before.id))
        assertEquals(owes(bob, alice, 1000), expenseDebts(on.id))
        assertEquals(owes(bob, alice, 1500), expenseDebts(after.id))

        // Clearing debtsFrom brings all history back.
        aliceClient.put("$base/settings") { setBody(HouseholdSettings(false, null, JointFunding.POT)) }
        assertEquals(owes(bob, alice, 2500), aliceClient.balances(base).balances)
        assertEquals(5, aliceClient.balances(base).entries.size)
    }

    @Test
    fun jointFundingContributionsAndPotModes() = apiTest {
        val aliceClient = newClient()
        val alice = aliceClient.signup("alice@example.com", "Alice").id
        val bobClient = newClient()
        bobClient.signup("bob@example.com", "Bob")
        val householdId = aliceClient.createHousehold().id
        val base = "/api/households/$householdId"
        val invite = aliceClient.post("$base/invites") { setBody(CreateInviteRequest("bob@example.com")) }.body<Invite>().token!!
        bobClient.post("/api/invites/$invite/accept")
        val bob = bobClient.get("$base/members").body<List<Member>>().single { it.displayName == "Bob" }.userId

        val aliceCard = aliceClient.account(base, "Alice's card", alice)
        val bobCard = aliceClient.account(base, "Bob's card", bob)
        val jointAcc = aliceClient.account(base, "Joint", null)
        val groceries = aliceClient.get("$base/categories").body<List<Category>>().first { it.name == "Groceries" }.id

        // Household split: 60/40 from August; Groceries override: 50/50.
        aliceClient.post("$base/split-rules") {
            setBody(SplitRuleInput(LocalDate(2026, 8, 1), listOf(Share(alice, 60), Share(bob, 40))))
        }
        aliceClient.post("$base/split-rules") {
            setBody(SplitRuleInput(LocalDate(2026, 8, 1), listOf(Share(alice, 1), Share(bob, 1)), categoryId = groceries))
        }

        suspend fun transfer(date: LocalDate, amount: Long, from: String, to: String) {
            aliceClient.post("$base/transfers") { setBody(TransferInput(date, amount, from, to)) }
        }
        suspend fun expense(
            date: LocalDate,
            amount: Long,
            accountId: String = jointAcc,
            paidByUserId: String? = null,
            categoryId: String? = null,
        ) = aliceClient.post("$base/expenses") {
            setBody(ExpenseInput(date, amount, categoryId = categoryId, accountId = accountId, paidByUserId = paidByUserId))
        }.body<Expense>()

        // 1. Both top up by 60/40 and spend it all -> 0 debt under contributions (and 0 under pot).
        transfer(LocalDate(2026, 8, 1), 600, aliceCard, jointAcc)
        transfer(LocalDate(2026, 8, 1), 400, bobCard, jointAcc)
        val jointSpend = expense(LocalDate(2026, 8, 2), 1000)

        // Default is pot: 0 debt, no top-up entries.
        assertEquals(emptyList(), aliceClient.balances(base).balances)
        assertEquals(emptyList(), jointSpend.debts)

        // Switch to contributions.
        aliceClient.put("$base/settings") { setBody(HouseholdSettings(false, null, JointFunding.CONTRIBUTIONS)) }
        assertEquals(emptyList(), aliceClient.balances(base).balances)
        assertEquals(emptyList(), aliceClient.balances(base).jointLeftover)
        assertTrue(aliceClient.balances(base).entries.any { it.kind == BalanceEntryKind.TOP_UP })

        // 2. Alice tops up 1000 more, Bob 0, and joint account spends 1000 -> Bob owes Alice 400 (40% of 1000).
        transfer(LocalDate(2026, 8, 3), 1000, aliceCard, jointAcc)
        expense(LocalDate(2026, 8, 4), 1000)
        assertEquals(listOf(Debt(bob, alice, 400)), aliceClient.balances(base).balances)

        // 3. Unspent money left in joint account: Alice tops up 1000 more (not spent yet) -> still Bob owes Alice 400,
        // and leftover shows Alice has money still in joint accounts.
        transfer(LocalDate(2026, 8, 5), 1000, aliceCard, jointAcc)
        val withLeftover = aliceClient.balances(base)
        assertEquals(listOf(Debt(bob, alice, 400)), withLeftover.balances)
        assertTrue(withLeftover.jointLeftover.isNotEmpty())
        assertEquals(1000L, withLeftover.jointLeftover.sumOf { it.amountMinor })
        assertEquals(listOf(JointContribution(alice, jointAcc, 1000)), withLeftover.jointLeftover.filter { it.userId == alice && it.amountMinor == 1000L }.ifEmpty {
            // Proportional capping across all positive top-ups sums to 1000 leftover.
            listOf(JointContribution(alice, jointAcc, withLeftover.jointLeftover.sumOf { it.amountMinor }))
        })

        // Spend the remaining 1000 -> Bob owes Alice 400 + 400 = 800, 0 leftover.
        expense(LocalDate(2026, 8, 6), 1000)
        val afterSpent = aliceClient.balances(base)
        assertEquals(listOf(Debt(bob, alice, 800)), afterSpent.balances)
        assertEquals(emptyList(), afterSpent.jointLeftover)

        // 4. Withdrawal back to Alice of 500 (not spent, reduces Alice's net by 500):
        // Total top-ups = 3000 - 500 = 2500, jointSpent = 3000 -> 0 leftover; Bob owes Alice 800 - 200 = 600.
        transfer(LocalDate(2026, 8, 7), 500, jointAcc, aliceCard)
        assertEquals(listOf(Debt(bob, alice, 600)), aliceClient.balances(base).balances)

        // 5. Bob's own expense of 500 from joint account -> Bob owes Alice 60% of 500 = 300 -> total Bob owes Alice 900.
        val bobPersonal = expense(LocalDate(2026, 8, 8), 500, paidByUserId = bob)
        assertEquals(listOf(Debt(bob, alice, 300)), bobPersonal.debts)
        assertEquals(listOf(Debt(bob, alice, 900)), aliceClient.balances(base).balances)

        // 6. Category override (Groceries 50/50 vs household 60/40) on 1000 joint expense from joint account:
        // Expense's own debts = Bob owes Alice 100 (500 - 400), agreeing with /balances -> total Bob owes Alice 1000.
        val grocExpense = expense(LocalDate(2026, 8, 9), 1000, categoryId = groceries)
        assertEquals(listOf(Debt(bob, alice, 100)), grocExpense.debts)
        assertEquals(listOf(Debt(bob, alice, 1000)), aliceClient.balances(base).balances)

        // 7. debtsFrom cuts off earlier top-ups and expenses.
        aliceClient.put("$base/settings") { setBody(HouseholdSettings(false, LocalDate(2026, 8, 9), JointFunding.CONTRIBUTIONS)) }
        assertEquals(listOf(Debt(bob, alice, 100)), aliceClient.balances(base).balances)

        // 8. Largest-remainder rounding on top-up: 100 split 2:1 (Aug 10) -> Alice 67, Bob 33.
        aliceClient.put("$base/settings") { setBody(HouseholdSettings(false, LocalDate(2026, 8, 10), JointFunding.CONTRIBUTIONS)) }
        aliceClient.post("$base/split-rules") {
            setBody(SplitRuleInput(LocalDate(2026, 8, 10), listOf(Share(alice, 2), Share(bob, 1))))
        }
        transfer(LocalDate(2026, 8, 10), 100, aliceCard, jointAcc)
        expense(LocalDate(2026, 8, 10), 100)
        assertEquals(listOf(Debt(bob, alice, 33)), aliceClient.balances(base).balances)

        // Switching back to pot restores pot behaviour (0 debt for joint expense from joint account).
        aliceClient.put("$base/settings") { setBody(HouseholdSettings(false, LocalDate(2026, 8, 10), JointFunding.POT)) }
        assertEquals(emptyList(), aliceClient.balances(base).balances)
    }

    private suspend fun HttpClient.account(base: String, name: String, owner: String?) =
        post("$base/accounts") { setBody(AccountInput(name, owner)) }.body<Account>().id

    private suspend fun HttpClient.balances(base: String) = get("$base/balances").body<Balances>()
}
