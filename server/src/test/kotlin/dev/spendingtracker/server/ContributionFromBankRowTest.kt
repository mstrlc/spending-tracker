package dev.spendingtracker.server

import dev.spendingtracker.server.market.CzkRate
import dev.spendingtracker.shared.api.Account
import dev.spendingtracker.shared.api.AccountInput
import dev.spendingtracker.shared.api.ApplyRulesResult
import dev.spendingtracker.shared.api.Asset
import dev.spendingtracker.shared.api.AssetInput
import dev.spendingtracker.shared.api.BankFeed
import dev.spendingtracker.shared.api.BankFeedInput
import dev.spendingtracker.shared.api.BankInstitution
import dev.spendingtracker.shared.api.BankRow
import dev.spendingtracker.shared.api.BankRowOutcome
import dev.spendingtracker.shared.api.BankRowRef
import dev.spendingtracker.shared.api.ContributeBankRows
import dev.spendingtracker.shared.api.Contribution
import dev.spendingtracker.shared.api.InboxRule
import dev.spendingtracker.shared.api.InboxRuleInput
import dev.spendingtracker.shared.api.RuleOutcome
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Every statement here is made up: real ones hold household data.
private const val HEADER = "Type,Product,Started Date,Completed Date,Description,Amount,Fee,Currency,State,Balance"

private fun revolut(day: Int, description: String, amount: String, currency: String = "CZK", month: Int = 9) =
    "Transfer,Current,2026-%02d-%02d 08:00:00,2026-%02d-%02d 08:00:01,$description,$amount,0.00,$currency,COMPLETED,0.00"
        .format(month, day, month, day)

class ContributionFromBankRowTest {
    private suspend fun HttpClient.account(base: String, name: String) =
        post("$base/accounts") { setBody(AccountInput(name)) }.body<Account>()

    private suspend fun HttpClient.feed(base: String, accountId: String, currency: String = "CZK") =
        post("$base/bank-feeds") { setBody(BankFeedInput(accountId, "Feed $currency", BankInstitution.REVOLUT, currency = currency)) }.body<BankFeed>()

    private suspend fun HttpClient.upload(base: String, feedId: String, vararg lines: String) {
        val response = post("$base/bank-feeds/$feedId/statements") { setBody(StatementUpload((listOf(HEADER) + lines).joinToString("\n"))) }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    private suspend fun HttpClient.pending(base: String) = get("$base/bank-rows").body<List<BankRow>>()
    private suspend fun HttpClient.all(base: String) = get("$base/bank-rows?status=all").body<List<BankRow>>()
    private suspend fun HttpClient.contributions(base: String, assetId: String) =
        get("$base/assets/$assetId/contributions").body<List<Contribution>>()

    private suspend fun HttpClient.contribute(base: String, assetId: String, vararg refs: BankRowRef, notes: String = "") =
        post("$base/bank-rows/contribute") { setBody(ContributeBankRows(refs.toList(), assetId, notes)) }

    private fun BankRow.ref() = BankRowRef(id, version)

    @Test
    fun aRowBecomesAContributionAndGoesBackWithIt() = apiTest {
        val client = newClient().also { it.signup() }
        val base = "/api/households/${client.createHousehold().id}"
        val current = client.account(base, "Current")
        val feed = client.feed(base, current.id)
        val fund = client.post("$base/assets") { setBody(AssetInput("Fund")) }.body<Asset>()
        client.upload(base, feed.id, revolut(17, "Fund deposit", "-5000.00"), revolut(20, "Fund sale", "1200.00"), revolut(21, "Other", "-10.00"))
        val rows = client.pending(base)
        val deposit = rows.first { it.text == "Fund deposit" }
        val sale = rows.first { it.text == "Fund sale" }
        val other = rows.first { it.text == "Other" }

        client.contribute(base, fund.id, deposit.ref(), other.ref()).assertError(HttpStatusCode.BadRequest, "one_row")
        client.contribute(base, "00000000-0000-0000-0000-000000000000", deposit.ref()).assertError(HttpStatusCode.BadRequest, "invalid_asset")
        client.contribute(base, "nope", deposit.ref()).assertError(HttpStatusCode.BadRequest, "invalid_id")

        // Money out is money put in, from the row's account, on the row's day.
        val filed = client.contribute(base, fund.id, deposit.ref(), notes = "Monthly").body<List<BankRow>>().single()
        assertEquals(BankRowOutcome.CONTRIBUTION, filed.outcome)
        assertEquals("Fund", filed.outcomeLabel)
        // Money in is money taken out.
        client.contribute(base, fund.id, sale.ref())
        val made = client.contributions(base, fund.id).sortedBy { it.date }
        assertEquals(listOf(5_000_00L, -1_200_00L), made.map { it.amountMinor })
        assertEquals(listOf(LocalDate(2026, 9, 17), LocalDate(2026, 9, 20)), made.map { it.date })
        assertEquals(listOf(current.id, current.id), made.map { it.accountId })
        assertEquals("Monthly", made.first().notes)
        assertEquals(filed.contributionId, made.first().id)
        assertEquals(listOf("Other"), client.pending(base).map { it.text })

        client.contribute(base, fund.id, filed.ref()).assertError(HttpStatusCode.Conflict, "already_decided")

        // Putting the row back deletes the contribution made from it.
        client.post("$base/bank-rows/undo") { setBody(UndoBankRows(listOf(filed.ref()))) }
        assertEquals(listOf(-1_200_00L), client.contributions(base, fund.id).map { it.amountMinor })
        assertTrue(client.pending(base).any { it.id == deposit.id })

        // Deleting the contribution puts its row back.
        val left = client.contributions(base, fund.id).single()
        assertEquals(HttpStatusCode.NoContent, client.delete("$base/assets/${fund.id}/contributions/${left.id}?version=${left.version}").status)
        val back = client.all(base).first { it.id == sale.id }
        assertEquals(BankRowOutcome.PENDING, back.outcome)
        assertNull(back.contributionId)
    }

    @Test
    fun theNextRowLikeItIsSuggestedForTheSameAsset() = apiTest {
        val client = newClient().also { it.signup() }
        val base = "/api/households/${client.createHousehold().id}"
        val feed = client.feed(base, client.account(base, "Current").id)
        val fund = client.post("$base/assets") { setBody(AssetInput("Fund")) }.body<Asset>()
        client.upload(base, feed.id, revolut(17, "Fund deposit", "-5000.00"))
        client.contribute(base, fund.id, client.pending(base).single().ref())

        client.upload(base, feed.id, revolut(17, "Fund deposit", "-5000.00", month = 10))
        val next = client.pending(base).single().suggestion!!
        assertEquals(SuggestedRecord.CONTRIBUTION, next.record)
        assertEquals(fund.id, next.assetId)
        assertEquals(5_000_00L, next.amountMinor)
    }

    @Test
    fun aRuleSuggestsOrFilesTheContribution() = apiTest {
        val client = newClient().also { it.signup() }
        val base = "/api/households/${client.createHousehold().id}"
        val feed = client.feed(base, client.account(base, "Current").id)
        val fund = client.post("$base/assets") { setBody(AssetInput("Fund")) }.body<Asset>()

        client.post("$base/inbox-rules") { setBody(InboxRuleInput("Fund", textContains = "fund", outcome = RuleOutcome.CONTRIBUTION)) }
            .assertError(HttpStatusCode.BadRequest, "required")
        client.post("$base/inbox-rules") {
            setBody(InboxRuleInput("Fund", textContains = "fund", outcome = RuleOutcome.CONTRIBUTION, assetId = "00000000-0000-0000-0000-000000000000"))
        }.assertError(HttpStatusCode.BadRequest, "invalid_asset")
        val rule = client.post("$base/inbox-rules") {
            setBody(InboxRuleInput("Fund", textContains = "fund", outcome = RuleOutcome.CONTRIBUTION, assetId = fund.id))
        }.body<InboxRule>()
        assertEquals(fund.id, rule.assetId)

        client.upload(base, feed.id, revolut(17, "Fund deposit", "-5000.00"))
        val suggestion = client.pending(base).single().suggestion!!
        assertEquals(SuggestedRecord.CONTRIBUTION, suggestion.record)
        assertEquals(fund.id, suggestion.assetId)
        assertEquals(rule.id, suggestion.ruleId)

        // With auto, the rule files it.
        val auto = client.put("$base/inbox-rules/${rule.id}") {
            setBody(InboxRuleInput("Fund", textContains = "fund", outcome = RuleOutcome.CONTRIBUTION, assetId = fund.id, auto = true, version = rule.version))
        }.body<InboxRule>()
        assertTrue(auto.auto)
        val result = client.post("$base/bank-rows/apply-rules").body<ApplyRulesResult>()
        assertEquals(1, result.filedByRules)
        val filed = client.all(base).single()
        assertEquals(BankRowOutcome.CONTRIBUTION, filed.outcome)
        assertTrue(filed.decidedAuto)
        assertEquals(rule.id, filed.ruleId)
        assertEquals(listOf(5_000_00L), client.contributions(base, fund.id).map { it.amountMinor })

        // Deleting the asset deletes its rule too.
        val asset = client.get("$base/assets").body<List<Asset>>().first { it.id == fund.id }
        assertEquals(HttpStatusCode.NoContent, client.delete("$base/assets/${fund.id}?version=${asset.version}").status)
        assertEquals(emptyList(), client.get("$base/inbox-rules").body<List<InboxRule>>())
    }

    @Test
    fun aForeignRowKeepsItsOwnAmount() = apiTest(
        FakeMarketData(rates = listOf(CzkRate("EUR", LocalDate(2026, 9, 1), "25.000".toBigDecimal()))),
    ) {
        val client = newClient().also { it.signup() }
        val base = "/api/households/${client.createHousehold().id}"
        val feed = client.feed(base, client.account(base, "Euro").id, "EUR")
        val fund = client.post("$base/assets") { setBody(AssetInput("Fund")) }.body<Asset>()
        client.upload(base, feed.id, revolut(3, "Fund deposit", "-40.00", "EUR"))
        client.contribute(base, fund.id, client.pending(base).single().ref())
        val made = client.contributions(base, fund.id).single()
        assertEquals(1_000_00L, made.amountMinor)
        assertEquals(40_00L, made.originalAmountMinor)
        assertEquals("EUR", made.originalCurrency)
    }
}
