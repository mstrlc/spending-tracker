package dev.spendingtracker.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MoneyTest {
    @Test
    fun addsAndSubtractsSameCurrency() {
        assertEquals(Money(350, "CZK"), Money(200, "CZK") + Money(150, "CZK"))
        assertEquals(Money(50, "CZK"), Money(200, "CZK") - Money(150, "CZK"))
    }

    @Test
    fun rejectsMixedCurrencies() {
        assertFailsWith<IllegalArgumentException> { Money(100, "CZK") + Money(100, "EUR") }
    }

    @Test
    fun rejectsInvalidCurrencyCode() {
        assertFailsWith<IllegalArgumentException> { Money(100, "czk") }
    }
}
