package dev.spendingtracker.server

import dev.spendingtracker.shared.api.Account
import dev.spendingtracker.shared.api.AccountInput
import dev.spendingtracker.shared.api.BankFeed
import dev.spendingtracker.shared.api.BankFeedInput
import dev.spendingtracker.shared.api.BankInstitution
import dev.spendingtracker.shared.api.BankRow
import dev.spendingtracker.shared.api.StatementUpload
import dev.spendingtracker.shared.api.SuggestedRecord
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals

// Every statement here is made up: real ones hold household data.
private const val HEADER = "Type,Product,Started Date,Completed Date,Description,Amount,Fee,Currency,State,Balance"

class AccountCardTest {
    @Test
    fun aTopUpsCardNamesTheAccountItCameFrom() = apiTest {
        val client = newClient().also { it.signup() }
        val base = "/api/households/${client.createHousehold().id}"
        val revolut = client.post("$base/accounts") { setBody(AccountInput("Revolut")) }.body<Account>()
        val bank = client.post("$base/accounts") { setBody(AccountInput("Bank", cards = listOf("*3656", " 7998 ", "3656"))) }.body<Account>()
        assertEquals(listOf("3656", "7998"), bank.cards)

        // A card is four digits, and only one account's.
        client.post("$base/accounts") { setBody(AccountInput("Other", cards = listOf("123"))) }.assertError(HttpStatusCode.BadRequest, "invalid_card")
        client.post("$base/accounts") { setBody(AccountInput("Other", cards = listOf("7998"))) }.assertError(HttpStatusCode.Conflict, "card_taken")
        val kept = client.put("$base/accounts/${bank.id}") {
            setBody(AccountInput("Bank", cards = listOf("7998"), version = bank.version))
        }.body<Account>()
        assertEquals(listOf("7998"), kept.cards)

        val feed = client.post("$base/bank-feeds") {
            setBody(BankFeedInput(revolut.id, "Revolut", BankInstitution.REVOLUT, currency = "CZK"))
        }.body<BankFeed>()
        client.post("$base/bank-feeds/${feed.id}/statements") {
            setBody(
                StatementUpload(
                    listOf(
                        HEADER,
                        "Topup,Current,2026-11-15 08:00:00,2026-11-15 08:00:01,Apple Pay Top-Up by *7998,300.00,0.00,CZK,COMPLETED,0.00",
                        "Topup,Current,2026-11-15 09:00:00,2026-11-15 09:00:01,Apple Pay Top-Up by *1111,200.00,0.00,CZK,COMPLETED,0.00",
                    ).joinToString("\n"),
                ),
            )
        }
        val rows = client.get("$base/bank-rows").body<List<BankRow>>()
        // Before the bank books its side, the card says where the money came from; an unknown card says nothing.
        val known = rows.single { it.text.endsWith("7998") }.suggestion!!
        assertEquals(SuggestedRecord.TRANSFER, known.record)
        assertEquals(bank.id, known.transferAccountId)
        assertEquals(null, rows.single { it.text.endsWith("1111") }.suggestion!!.transferAccountId)
    }
}
