package dev.spendingtracker.server

import dev.spendingtracker.shared.api.Account
import dev.spendingtracker.shared.api.AccountInput
import dev.spendingtracker.shared.api.ApiError
import dev.spendingtracker.shared.api.BankFeed
import dev.spendingtracker.shared.api.BankFeedInput
import dev.spendingtracker.shared.api.BankInstitution
import dev.spendingtracker.shared.api.BankRow
import dev.spendingtracker.shared.api.StatementUpload
import dev.spendingtracker.shared.api.Category
import dev.spendingtracker.shared.api.CategoryInput
import dev.spendingtracker.shared.api.CreateInviteRequest
import dev.spendingtracker.shared.api.ExpenseInput
import dev.spendingtracker.shared.api.Income
import dev.spendingtracker.shared.api.IncomeInput
import dev.spendingtracker.shared.api.IncomeKind
import dev.spendingtracker.shared.api.Invite
import dev.spendingtracker.shared.api.NamedTotal
import dev.spendingtracker.shared.api.Overview
import dev.spendingtracker.shared.api.PayslipDeduction
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

class IncomeTest {
    private suspend fun HttpClient.add(base: String, input: IncomeInput) = post(base) { setBody(input) }.body<Income>()

    @Test
    fun createUpdateDeleteWithOptimisticConcurrency() = apiTest {
        val client = newClient()
        val me = client.signup()
        val base = "/api/households/${client.createHousehold().id}/income"

        val created = client.add(base, IncomeInput(LocalDate(2026, 10, 15), 6_500_000, " Acme ", recipientUserId = me.id))
        assertEquals("Acme", created.source)
        assertEquals(me.id, created.recipientUserId)
        assertEquals(1, created.version)

        val clawback = client.put("$base/${created.id}") {
            setBody(IncomeInput(LocalDate(2026, 10, 15), -20_000, "Acme", notes = "overpaid", version = 1))
        }.body<Income>()
        assertEquals(-20_000, clawback.amountMinor)
        assertEquals(null, clawback.recipientUserId)
        assertEquals(2, clawback.version)

        client.put("$base/${created.id}") { setBody(IncomeInput(LocalDate(2026, 10, 15), 1, version = 1)) }
            .assertError(HttpStatusCode.Conflict, "version_conflict")
        client.put("$base/${created.id}") { setBody(IncomeInput(LocalDate(2026, 10, 15), 1)) }
            .assertError(HttpStatusCode.BadRequest, "version_required")
        client.delete("$base/${created.id}?version=1").assertError(HttpStatusCode.Conflict, "version_conflict")
        assertEquals(HttpStatusCode.NoContent, client.delete("$base/${created.id}?version=2").status)
        client.delete("$base/${created.id}?version=2").assertError(HttpStatusCode.NotFound, "not_found")
    }

    @Test
    fun rejectsZeroAmountAndInvalidInput() = apiTest {
        val client = newClient().also { it.signup() }
        val base = "/api/households/${client.createHousehold().id}/income"

        client.post(base) { setBody(IncomeInput(LocalDate(2026, 10, 3), 0)) }
            .assertError(HttpStatusCode.BadRequest, "invalid_amount")
        client.post(base) { setBody(IncomeInput(LocalDate(2026, 10, 3), 100, recipientUserId = "nope")) }
            .assertError(HttpStatusCode.BadRequest, "invalid_id")
        client.get("$base?to=tomorrow").assertError(HttpStatusCode.BadRequest, "invalid_date")
    }

    @Test
    fun filtersByDateRangeAndAccount() = apiTest {
        val client = newClient().also { it.signup() }
        val household = "/api/households/${client.createHousehold().id}"
        val base = "$household/income"
        val bank = client.post("$household/accounts") { setBody(AccountInput("Bank")) }.body<Account>()
        val sept = client.add(base, IncomeInput(LocalDate(2026, 9, 30), 100, accountId = bank.id))
        val octFirst = client.add(base, IncomeInput(LocalDate(2026, 10, 1), 200, accountId = bank.id))
        val octLast = client.add(base, IncomeInput(LocalDate(2026, 10, 31), 300))

        val october = client.get("$base?from=2026-10-01&to=2026-10-31").body<List<Income>>()
        assertEquals(listOf(octLast, octFirst), october)
        assertEquals(setOf(sept, octFirst), client.get("$base?accountId=${bank.id}").body<List<Income>>().toSet())
    }

    @Test
    fun accountWithIncomeCannotBeDeleted() = apiTest {
        val client = newClient().also { it.signup() }
        val household = "/api/households/${client.createHousehold().id}"
        val bank = client.post("$household/accounts") { setBody(AccountInput("Bank")) }.body<Account>()
        client.add("$household/income", IncomeInput(LocalDate(2026, 10, 15), 100, accountId = bank.id))

        client.delete("$household/accounts/${bank.id}?version=1").assertError(HttpStatusCode.Conflict, "account_in_use")
    }

    @Test
    fun cannotReferenceOutsidersAndRemovedMembersBecomeShared() = apiTest {
        val alice = newClient().also { it.signup("alice@example.com") }
        val bob = newClient()
        val bobMe = bob.signup("bob@example.com", "Bob")
        val home = alice.createHousehold()
        val base = "/api/households/${home.id}/income"
        val bobAccount = bob.post("/api/households/${bob.createHousehold().id}/accounts") { setBody(AccountInput("Bob's")) }
            .body<Account>()

        alice.post(base) { setBody(IncomeInput(LocalDate(2026, 10, 1), 100, recipientUserId = bobMe.id)) }
            .assertError(HttpStatusCode.BadRequest, "invalid_member")
        alice.post(base) { setBody(IncomeInput(LocalDate(2026, 10, 1), 100, accountId = bobAccount.id)) }
            .assertError(HttpStatusCode.BadRequest, "invalid_account")
        assertTrue(alice.get(base).body<List<Income>>().isEmpty())

        val token = alice.post("/api/households/${home.id}/invites") { setBody(CreateInviteRequest("bob@example.com")) }
            .body<Invite>().token
        bob.post("/api/invites/$token/accept")
        val bobsSalary = alice.add(base, IncomeInput(LocalDate(2026, 10, 15), 100, "Globex", recipientUserId = bobMe.id))
        assertEquals(bobMe.id, bobsSalary.recipientUserId)

        alice.delete("/api/households/${home.id}/members/${bobMe.id}")
        assertEquals(null, alice.get(base).body<List<Income>>().single().recipientUserId)
    }

    @Test
    fun incomeCountsInTheMonthItWasEarned() = apiTest {
        val client = newClient().also { it.signup() }
        val household = "/api/households/${client.createHousehold().id}"
        val base = "$household/income"

        // October's salary, paid on 2 November.
        val salary = client.post(base) { setBody(IncomeInput(LocalDate(2026, 11, 2), 50_000_00, "Initech", earnedMonth = "2026-10")) }
            .body<Income>()
        assertEquals("2026-10", salary.earnedMonth)
        assertEquals("2026-11", client.post(base) { setBody(IncomeInput(LocalDate(2026, 11, 5), 1_000_00, "Gift")) }.body<Income>().earnedMonth)
        client.post(base) { setBody(IncomeInput(LocalDate(2026, 11, 5), 1_00, earnedMonth = "October")) }
            .assertError(HttpStatusCode.BadRequest, "invalid_month")

        assertEquals(listOf("Initech"), client.get("$base?from=2026-10-01&to=2026-10-31").body<List<Income>>().map { it.source })
        assertEquals(listOf("Gift"), client.get("$base?from=2026-11-01&to=2026-11-30").body<List<Income>>().map { it.source })
        val history = client.get("$household/overview?month=2026-11").body<Overview>().history.associate { it.month to it.incomeMinor }
        assertEquals(50_000_00L to 1_000_00L, history["2026-10"] to history["2026-11"])
    }

    @Test
    fun deductionsLowerNetIncomeWithoutBeingASource() = apiTest {
        val client = newClient().also { it.signup() }
        val household = "/api/households/${client.createHousehold().id}"
        val base = "$household/income"

        val salary = client.add(base, IncomeInput(LocalDate(2026, 10, 10), 60_000_00, "Initech"))
        assertEquals(IncomeKind.EARNING, salary.kind)
        val health = client.add(base, IncomeInput(LocalDate(2026, 10, 8), 3_143_00, "ZPMV", kind = IncomeKind.DEDUCTION))
        assertEquals(IncomeKind.DEDUCTION, health.kind)
        assertEquals(3_143_00, health.amountMinor)
        // An overpaid advance refunded is a negative deduction.
        client.add(base, IncomeInput(LocalDate(2026, 10, 20), -143_00, "ZPMV", kind = IncomeKind.DEDUCTION))

        val overview = client.get("$household/overview?month=2026-10").body<Overview>()
        assertEquals(60_000_00L - 3_000_00L, overview.history.single { it.month == "2026-10" }.incomeMinor)
        assertEquals(listOf(NamedTotal("Initech", 60_000_00)), overview.topSources)
        assertEquals(listOf(NamedTotal("Initech", 60_000_00)), overview.incomeSources)
        assertEquals(3_000_00L, overview.deductionsMinor)

        val asEarning = client.put("$base/${health.id}") {
            setBody(IncomeInput(health.date, health.amountMinor, "ZPMV", version = health.version))
        }.body<Income>()
        assertEquals(IncomeKind.EARNING, asEarning.kind)
    }

    @Test
    fun linkingDeductionsToEarningsAndUnlinking() = apiTest {
        val client = newClient().also { it.signup() }
        val household = "/api/households/${client.createHousehold().id}"
        val base = "$household/income"

        val salary = client.add(base, IncomeInput(LocalDate(2026, 10, 10), 60_000_00, "Initech"))
        val other = client.add(base, IncomeInput(LocalDate(2026, 10, 15), 10_000_00, "Bonus"))

        // Only a deduction can link to an earning, and the target must be an earning.
        client.post(base) {
            setBody(IncomeInput(LocalDate(2026, 10, 10), 1_000_00, "Bad", deductedFromId = salary.id))
        }.assertError(HttpStatusCode.BadRequest, "invalid_deducted_from")

        val tax = client.add(
            base,
            IncomeInput(LocalDate(2026, 10, 10), 8_500_00, "Tax", kind = IncomeKind.DEDUCTION, deductedFromId = salary.id),
        )
        assertEquals(salary.id, tax.deductedFromId)

        client.post(base) {
            setBody(IncomeInput(LocalDate(2026, 10, 10), 500_00, "Nested", kind = IncomeKind.DEDUCTION, deductedFromId = tax.id))
        }.assertError(HttpStatusCode.BadRequest, "invalid_deducted_from")

        // Turning a linked earning into a deduction is refused while deductions point at it.
        client.put("$base/${salary.id}") {
            setBody(IncomeInput(salary.date, salary.amountMinor, salary.source, kind = IncomeKind.DEDUCTION, version = salary.version))
        }.assertError(HttpStatusCode.Conflict, "income_has_deductions")

        val listed = client.get(base).body<List<Income>>().associateBy { it.id }
        assertEquals(8_500_00L, listed[salary.id]!!.deductedMinor)
        assertEquals(0L, listed[other.id]!!.deductedMinor)

        // Move deduction to another earning, then unlink it.
        val moved = client.put("$base/${tax.id}") {
            setBody(IncomeInput(tax.date, tax.amountMinor, tax.source, kind = IncomeKind.DEDUCTION, deductedFromId = other.id, version = tax.version))
        }.body<Income>()
        assertEquals(other.id, moved.deductedFromId)
        val afterMove = client.get(base).body<List<Income>>().associateBy { it.id }
        assertEquals(0L, afterMove[salary.id]!!.deductedMinor)
        assertEquals(8_500_00L, afterMove[other.id]!!.deductedMinor)

        // Deleting the earning deletes its deductions with it; unlinked ones stay.
        val zpmv = client.add(base, IncomeInput(LocalDate(2026, 10, 8), 3_143_00, "ZPMV", kind = IncomeKind.DEDUCTION))
        assertEquals(HttpStatusCode.NoContent, client.delete("$base/${other.id}?version=${other.version}").status)
        val left = client.get(base).body<List<Income>>().map { it.id }.toSet()
        assertEquals(false, tax.id in left)
        assertEquals(true, zpmv.id in left)
    }

    @Test
    fun movingAnEarningMovesItsPayslipDeductions() = apiTest {
        val client = newClient().also { it.signup() }
        val household = "/api/households/${client.createHousehold().id}"
        val base = "$household/income"
        val me = client.post("$household/members") { setBody(dev.spendingtracker.shared.api.MemberInput("Bob")) }
            .body<dev.spendingtracker.shared.api.Member>().userId

        // Paid on 2 November, entered as November, then corrected to October and to one member.
        val salary = client.add(
            base,
            IncomeInput(
                LocalDate(2026, 11, 2),
                60_000_00,
                "Initech",
                deductions = listOf(PayslipDeduction("Income tax", 8_500_00), PayslipDeduction("Health", 3_143_00)),
            ),
        )
        // A deduction linked by hand with a month of its own keeps it.
        val own = client.add(
            base,
            IncomeInput(LocalDate(2026, 12, 5), 1_000_00, "Annual", earnedMonth = "2026-12", kind = IncomeKind.DEDUCTION, deductedFromId = salary.id),
        )
        client.put("$base/${salary.id}") {
            setBody(IncomeInput(salary.date, salary.amountMinor, salary.source, recipientUserId = me, earnedMonth = "2026-10", version = salary.version))
        }.body<Income>()

        val all = client.get(base).body<List<Income>>()
        val payslip = all.filter { it.deductedFromId == salary.id && it.id != own.id }
        assertEquals(2, payslip.size)
        payslip.forEach {
            assertEquals("2026-10", it.earnedMonth)
            assertEquals(me, it.recipientUserId)
        }
        val ownAfter = all.single { it.id == own.id }
        assertEquals("2026-12", ownAfter.earnedMonth)
        assertEquals(me, ownAfter.recipientUserId)
    }

    @Test
    fun payslipCreatesEarningAndDeductionsInOneTransactionAndRollsBackOnError() = apiTest {
        val client = newClient()
        val me = client.signup()
        val household = "/api/households/${client.createHousehold().id}"
        val base = "$household/income"
        val bank = client.post("$household/accounts") { setBody(AccountInput("Bank")) }.body<Account>()

        // Bad second deduction rolls back the whole request and names the entry.
        val bad = client.post(base) {
            setBody(
                IncomeInput(
                    date = LocalDate(2026, 11, 2),
                    amountMinor = 60_000_00,
                    source = "Initech",
                    accountId = bank.id,
                    recipientUserId = me.id,
                    earnedMonth = "2026-10",
                    deductions = listOf(
                        PayslipDeduction("Income tax", 8_500_00),
                        PayslipDeduction("Health", 0),
                    ),
                ),
            )
        }
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        val err = bad.body<ApiError>()
        assertEquals("invalid_amount", err.code)
        assertEquals("deductions[1].amountMinor", err.field)
        assertTrue(client.get(base).body<List<Income>>().isEmpty())

        val salary = client.add(
            base,
            IncomeInput(
                date = LocalDate(2026, 11, 2),
                amountMinor = 60_000_00,
                source = "Initech",
                accountId = bank.id,
                recipientUserId = me.id,
                earnedMonth = "2026-10",
                deductions = listOf(
                    PayslipDeduction("Income tax", 8_500_00, "Advance"),
                    PayslipDeduction("Health", 3_143_00),
                ),
            ),
        )
        assertEquals(11_643_00L, salary.deductedMinor)

        val all = client.get(base).body<List<Income>>()
        val deds = all.filter { it.kind == IncomeKind.DEDUCTION }
        assertEquals(2, deds.size)
        for (d in deds) {
            assertEquals(salary.id, d.deductedFromId)
            assertEquals(salary.date, d.date)
            assertEquals("2026-10", d.earnedMonth)
            assertEquals(me.id, d.recipientUserId)
            assertEquals(null, d.accountId)
        }
    }

    @Test
    fun incomeCategoriesFilterWithSubcategoriesAndRejectWrongKinds() = apiTest {
        val client = newClient().also { it.signup() }
        val household = "/api/households/${client.createHousehold().id}"
        val categories = client.get("$household/categories").body<List<Category>>()
        val salary = categories.single { it.name == "Salary" }
        val freelance = categories.single { it.name == "Business" }
        val groceries = categories.single { it.name == "Groceries" }
        val bonus = client.post("$household/categories") {
            setBody(CategoryInput("Bonus", parentId = salary.id))
        }.body<Category>()

        val basePay = client.add("$household/income", IncomeInput(LocalDate(2026, 10, 5), 50_000_00, "Employer", categoryId = salary.id))
        val bonusPay = client.add("$household/income", IncomeInput(LocalDate(2026, 10, 10), 10_000_00, "Employer", categoryId = bonus.id))
        client.add("$household/income", IncomeInput(LocalDate(2026, 10, 12), 8_000_00, "Client", categoryId = freelance.id))

        assertEquals(salary.id, basePay.categoryId)
        val filtered = client.get("$household/income?categoryId=${salary.id}").body<List<Income>>()
        assertEquals(setOf(basePay.id, bonusPay.id), filtered.map { it.id }.toSet())

        client.post("$household/income") {
            setBody(IncomeInput(LocalDate(2026, 10, 1), 1000, "Oops", categoryId = groceries.id))
        }.assertError(HttpStatusCode.BadRequest, "invalid_category")
        client.post("$household/income") {
            setBody(IncomeInput(LocalDate(2026, 10, 1), 1000, "ZPMV", kind = IncomeKind.DEDUCTION, categoryId = salary.id))
        }.assertError(HttpStatusCode.BadRequest, "invalid_category")
        client.post("$household/expenses") {
            setBody(ExpenseInput(LocalDate(2026, 10, 1), 500, categoryId = salary.id))
        }.assertError(HttpStatusCode.BadRequest, "invalid_category")
    }

    @Test
    fun aBankRowFromAPayerIsSuggestedTheCategoryOfTheirLastEarning() = apiTest {
        val client = newClient().also { it.signup() }
        val household = "/api/households/${client.createHousehold().id}"
        val account = client.post("$household/accounts") { setBody(AccountInput("Current")) }.body<Account>()
        val feed = client.post("$household/bank-feeds") {
            setBody(BankFeedInput(account.id, "Current", BankInstitution.CSOB, currency = "CZK"))
        }.body<BankFeed>()
        val business = client.get("$household/categories").body<List<Category>>().single { it.name == "Business" }
        // Entered by hand, with no bank row behind it: only the payer links it to the row below. Made up.
        client.post("$household/income") {
            setBody(IncomeInput(LocalDate(2026, 9, 1), 50_000_00, source = "Acme Ltd", categoryId = business.id))
        }
        val statement = """
            { "transactions": { "booked": [
                { "transactionId": "acme-oct", "bookingDate": "2026-10-15",
                  "transactionAmount": { "amount": "48000.00", "currency": "CZK" },
                  "debtorName": "Acme Ltd", "remittanceInformationUnstructured": "Invoice 2026-10" },
                { "transactionId": "someone", "bookingDate": "2026-10-16",
                  "transactionAmount": { "amount": "100.00", "currency": "CZK" },
                  "debtorName": "Jane Doe", "remittanceInformationUnstructured": "Thanks" }
            ] } }
        """.trimIndent()
        client.post("$household/bank-feeds/${feed.id}/statements") { setBody(StatementUpload(statement, "current.json")) }

        val rows = client.get("$household/bank-rows").body<List<BankRow>>()
        val acme = rows.single { it.amountMinor == 48_000_00L }.suggestion!!
        assertEquals("Acme Ltd", acme.incomeSource)
        assertEquals(business.id, acme.categoryId)
        assertEquals(null, rows.single { it.amountMinor == 100_00L }.suggestion!!.categoryId)
    }
}
