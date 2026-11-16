package dev.spendingtracker.server

import dev.spendingtracker.server.inbox.CzechAccount
import dev.spendingtracker.server.inbox.FeedShape
import dev.spendingtracker.server.inbox.HintContext
import dev.spendingtracker.server.inbox.cleanDescriptor
import dev.spendingtracker.server.inbox.hintFor
import dev.spendingtracker.server.inbox.isHouseholdName
import dev.spendingtracker.server.inbox.merchantKey
import dev.spendingtracker.server.inbox.parseAmount
import dev.spendingtracker.server.inbox.parseGoCardless
import dev.spendingtracker.server.inbox.readStatement
import dev.spendingtracker.shared.api.BankInstitution
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.uuid.Uuid
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BankTextTest {
    @Test
    fun czechAccountsBecomeIbans() {
        val account = CzechAccount.parse("19-2000145399/0800")!!
        assertEquals("CZ6508000000192000145399", account.iban)
        assertEquals(account, CzechAccount.fromIban(account.iban))
        assertEquals(null, CzechAccount.parse("2000145399"))
    }

    @Test
    fun descriptorsAndNames() {
        assertEquals("KAUFLAND CZ", merchantKey("Kaufland Cz  4310"))
        assertEquals("SUMUP HOLIC", merchantKey("SumUp **Holic M4QED8D8"))
        assertEquals("SumUp Holic", cleanDescriptor("SumUp **Holic M4QED8D8"))
        assertTrue(isHouseholdName("NOVAKOVA JANA", listOf("Jana Nováková")))
        assertFalse(isHouseholdName("JANA DVORAKOVA", listOf("Jana Nováková")))
        assertEquals("-1234.50".toBigDecimal(), parseAmount("-1 234,50 Kč"))
        assertEquals("-263.00".toBigDecimal(), parseAmount("-263.00"))
    }

    @Test
    fun readsCsobCardPaymentsFromTheMessage() {
        val raw = Json.parseToJsonElement(
            """{"internalTransactionId":"x1","bookingDate":"2026-09-15","transactionAmount":{"amount":"-40.00","currency":"CZK"},""" +
                """"remittanceInformationUnstructured":"Částka: 40 CZK 13.09.2026Místo: NYX*brothersczsroPrague "}""",
        ).jsonObject
        val row = parseGoCardless(raw, FeedShape(BankInstitution.CSOB, isCard = false))!!
        assertEquals(LocalDate(2026, 9, 13), row.date)
        assertEquals(LocalDate(2026, 9, 15), row.bookingDate)
        assertEquals("NYX*brothersczsroPrague", row.text)
        assertEquals(-4000, row.amountMinor)
    }

    @Test
    fun readsCsobAndRaiffeisenCsv() {
        val csob = "﻿Pohyby na účtu 123/0300.\n\n" +
            "číslo účtu;datum zaúčtování;částka;měna;zůstatek;číslo protiúčtu;kód banky protiúčtu;jméno protistrany;adresa protistrany;" +
            "konstantní symbol;variabilní symbol;specifický symbol;označení operace;název trvalého příkazu;vlastní poznámka;zpráva;kategorie;ID transakce\n" +
            "123/0300;15.09.2026;-1 500,00;CZK;0;2000145399;0800;Jane Doe;;;;;Odchozí platba;;;rent;;T-1\n"
        val statement = readStatement(csob, FeedShape(BankInstitution.CSOB, isCard = false))
        assertEquals("ČSOB CSV", statement.format)
        val row = statement.rows.single()
        assertEquals("T-1", row.externalId)
        assertEquals(-150_000, row.amountMinor)
        assertEquals("CZ1508000000002000145399".length, row.counterpartyIban!!.length)
        assertEquals("2000145399/0800", row.counterpartyAccount)
        val context = HintContext(setOf(row.counterpartyIban!!), listOf("Jane Doe"))
        assertEquals("Between your own accounts", hintFor(row, FeedShape(BankInstitution.CSOB, false), context))
        assertEquals(
            "To or from someone in the household",
            hintFor(row, FeedShape(BankInstitution.CSOB, false), HintContext(emptySet(), listOf("Jane Doe"))),
        )

        // Whose accounts it moved between: one member's, a member's and a joint one, two members'.
        val jane = Uuid.random()
        val john = Uuid.random()
        val iban = row.counterpartyIban!!
        val names = mapOf(jane to listOf("Jane Doe"), john to listOf("John Doe"))
        fun owned(other: Uuid?) = HintContext(setOf(iban), names.values.flatten(), mapOf(iban to other), names)
        val janes = FeedShape(BankInstitution.CSOB, false, ownerUserId = jane)
        assertEquals("Between your own accounts", hintFor(row, janes, owned(jane)))
        assertEquals("Between household accounts", hintFor(row, janes, owned(null)))
        assertEquals("Between members' accounts", hintFor(row, janes, owned(john)))
        assertEquals("Between your own accounts", hintFor(row, FeedShape(BankInstitution.CSOB, false), owned(null)))

        // An account in the owner's own name the app doesn't read, or in another member's.
        val unread = HintContext(emptySet(), names.values.flatten(), emptyMap(), names)
        assertEquals("To or from your own account", hintFor(row, janes, unread))
        assertEquals("To or from someone in the household", hintFor(row, FeedShape(BankInstitution.CSOB, false, ownerUserId = john), unread))
        assertEquals("To or from someone in the household", hintFor(row, FeedShape(BankInstitution.CSOB, false), unread))

        val rb = "﻿\"Datum provedení\";\"Datum zaúčtování\";\"Číslo účtu\";\"Název účtu\";\"Kategorie transakce\";\"Číslo protiúčtu\";" +
            "\"Název protiúčtu\";\"Typ transakce\";\"Zpráva\";\"Poznámka\";\"VS\";\"KS\";\"SS\";\"Zaúčtovaná částka\";\"Měna účtu\";" +
            "\"Původní částka\";\"Původní měna\";\"Poplatky\";\"Id transakce\";\"Vlastní poznámka\";\"Název obchodníka\"\n" +
            "\"19.09.2026\";\"19.09.2026 20:31\";\"1/5500\";\"Jane\";\"Platba\";\"\";\"\";\"Platba kartou\";\"Revolut**2770*\";\"\";\"\";\"\";\"\";" +
            "\"-2000\";\"CZK\";\"\";\"\";\"0\";\"9526\";\"\";\"Revolut\"\n"
        val rbRow = readStatement(rb, FeedShape(BankInstitution.RAIFFEISEN, isCard = false)).rows.single()
        assertEquals("9526", rbRow.externalId)
        assertEquals("Revolut", rbRow.text)
        assertEquals("Revolut top-up", hintFor(rbRow, FeedShape(BankInstitution.RAIFFEISEN, false), HintContext(emptySet(), emptyList())))
    }
}
