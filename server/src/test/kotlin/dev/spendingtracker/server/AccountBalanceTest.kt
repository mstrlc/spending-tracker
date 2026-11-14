package dev.spendingtracker.server

import dev.spendingtracker.shared.api.Account
import dev.spendingtracker.shared.api.AccountBalance
import dev.spendingtracker.shared.api.AccountBalanceInput
import dev.spendingtracker.shared.api.AccountInput
import dev.spendingtracker.shared.api.AccountKind
import dev.spendingtracker.shared.api.AccountValue
import dev.spendingtracker.shared.api.AccountValueSource
import dev.spendingtracker.shared.api.Asset
import dev.spendingtracker.shared.api.AssetInput
import dev.spendingtracker.shared.api.AssetSummary
import dev.spendingtracker.shared.api.ContributionInput
import dev.spendingtracker.shared.api.ExpenseInput
import dev.spendingtracker.shared.api.IncomeInput
import dev.spendingtracker.shared.api.Overview
import dev.spendingtracker.shared.api.PayslipDeduction
import dev.spendingtracker.shared.api.TransferInput
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class AccountBalanceTest {
    @Test
    fun computesAnUnreadAccountFromItsBalanceCheckAndTheLedger() = apiTest {
        val client = newClient().also { it.signup() }
        val household = "/api/households/${client.createHousehold(currency = "CZK").id}"
        val current = client.post("$household/accounts") { setBody(AccountInput("Current")) }.body<Account>()
        val savings = client.post("$household/accounts") { setBody(AccountInput("Savings", kind = AccountKind.SAVINGS)) }.body<Account>()
        val fund = client.post("$household/assets") { setBody(AssetInput("Fund")) }.body<Asset>()

        val check = client.post("$household/accounts/${savings.id}/balances") {
            setBody(AccountBalanceInput(LocalDate(2026, 10, 10), 100_000, "From the bank's app"))
        }.also { assertEquals(HttpStatusCode.Created, it.status) }.body<AccountBalance>()
        client.post("$household/accounts/${savings.id}/balances") { setBody(AccountBalanceInput(LocalDate(2026, 10, 10), 1)) }
            .assertError(HttpStatusCode.Conflict, "balance_exists")

        // Before the check: taken out on the 5th, so it held 5,000 more until then.
        client.post("$household/transfers") { setBody(TransferInput(LocalDate(2026, 10, 5), 5_000, savings.id, current.id)) }
        // After it: a transfer in, interest net of its tax, a purchase and its partial refund, and money into the fund.
        client.post("$household/transfers") { setBody(TransferInput(LocalDate(2026, 10, 15), 20_000, current.id, savings.id)) }
        client.post("$household/income") {
            setBody(IncomeInput(LocalDate(2026, 10, 31), 1_000, "Interest", accountId = savings.id, deductions = listOf(PayslipDeduction("Tax on interest", 150))))
        }
        client.post("$household/expenses") { setBody(ExpenseInput(LocalDate(2026, 10, 20), 300, "Bank fee", accountId = savings.id)) }
        client.post("$household/expenses") { setBody(ExpenseInput(LocalDate(2026, 10, 21), -100, "Bank fee", accountId = savings.id)) }
        client.post("$household/assets/${fund.id}/contributions") {
            setBody(ContributionInput(LocalDate(2026, 10, 25), 2_000, accountId = savings.id))
        }
        val october = 100_000L + 20_000 + 850 - 200 - 2_000

        val worth = client.get("$household/overview?month=2026-10&today=2026-10-31").body<Overview>().netWorth
        assertEquals(october, worth.accountsMinor)
        assertEquals(2_000, worth.assetsMinor)
        assertEquals(october + 2_000, worth.netMinor)
        assertEquals(october, worth.liquidMinor)
        // Worked out, not read from the bank.
        assertEquals(0, worth.accountsRead)
        val september = client.get("$household/overview?month=2026-09&today=2026-10-31").body<Overview>().netWorth
        assertEquals(105_000, september.accountsMinor)

        val values = client.get("$household/assets/accounts").body<List<AccountValue>>().associateBy { it.accountId }
        assertEquals(AccountValue(savings.id, october, AccountValueSource.COMPUTED, LocalDate(2026, 10, 10)), values[savings.id])
        assertEquals(AccountValue(current.id, null, AccountValueSource.NONE, null), values[current.id])
        val summary = client.get("$household/assets/summary").body<AssetSummary>()
        assertEquals(october, summary.accountsMinor)
        assertEquals(october + 2_000, summary.netMinor)

        assertEquals(listOf(check), client.get("$household/accounts/${savings.id}/balances").body<List<AccountBalance>>())
        client.delete("$household/accounts/${savings.id}/balances/${check.id}?version=1").also { assertEquals(HttpStatusCode.NoContent, it.status) }
        val after = client.get("$household/assets/accounts").body<List<AccountValue>>().single { it.accountId == savings.id }
        assertEquals(AccountValueSource.NONE, after.source)
        assertEquals(0, client.get("$household/assets/summary").body<AssetSummary>().accountsMinor)
    }
}
