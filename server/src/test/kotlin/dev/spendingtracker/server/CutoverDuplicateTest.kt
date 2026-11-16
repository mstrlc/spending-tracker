package dev.spendingtracker.server

import dev.spendingtracker.shared.api.Account
import dev.spendingtracker.shared.api.AccountInput
import dev.spendingtracker.shared.api.BankFeed
import dev.spendingtracker.shared.api.BankFeedInput
import dev.spendingtracker.shared.api.BankInstitution
import dev.spendingtracker.shared.api.BankRow
import dev.spendingtracker.shared.api.HouseholdSettings
import dev.spendingtracker.shared.api.IncomeInput
import dev.spendingtracker.shared.api.JointFunding
import dev.spendingtracker.shared.api.StatementUpload
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

// Every statement here is made up: real ones hold household data.
private const val HEADER = "Type,Product,Started Date,Completed Date,Description,Amount,Fee,Currency,State,Balance"

class CutoverDuplicateTest {
    @Test
    fun aRecordFromBeforeTheCutoverIsNoBankRowsDuplicate() = apiTest {
        val client = newClient().also { it.signup() }
        val base = "/api/households/${client.createHousehold().id}"
        val account = client.post("$base/accounts") { setBody(AccountInput("Current")) }.body<Account>()
        val feed = client.post("$base/bank-feeds") {
            setBody(BankFeedInput(account.id, "Current", BankInstitution.REVOLUT, currency = "CZK"))
        }.body<BankFeed>()
        // The old app put monthly income on the 1st; this one was September's.
        client.post("$base/income") { setBody(IncomeInput(LocalDate(2026, 9, 1), 1_000_00, source = "Jane Doe")) }
        client.post("$base/bank-feeds/${feed.id}/statements") {
            setBody(StatementUpload("$HEADER\nTransfer,Current,2026-10-05 08:00:00,2026-10-05 08:00:01,Jane Doe,1000.00,0.00,CZK,COMPLETED,0.00"))
        }
        suspend fun duplicates() = client.get("$base/bank-rows").body<List<BankRow>>().single().suggestion!!.duplicates

        // Without a cutover, income dated the 1st may still arrive weeks later.
        assertEquals(1, duplicates().size)

        // From the cutover on, the bank is the record; nothing before it has (or needs) a bank row.
        client.put("$base/settings") { setBody(HouseholdSettings(false, LocalDate(2026, 10, 1), JointFunding.POT)) }
        assertEquals(emptyList(), duplicates())
    }
}
