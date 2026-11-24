package dev.spendingtracker.android

import dev.spendingtracker.android.util.currentMonth
import dev.spendingtracker.android.util.formatAmount
import dev.spendingtracker.android.util.monthRange
import dev.spendingtracker.android.util.parseMoney
import dev.spendingtracker.android.util.shiftMonth
import dev.spendingtracker.android.util.toInputValue
import java.util.Locale
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyUtilTest {
    @Test
    fun `parseMoney accepts the web's input shapes`() {
        assertEquals(12990L, parseMoney("129.90", "CZK"))
        assertEquals(12990L, parseMoney("129,9", "CZK"))
        assertEquals(12900L, parseMoney("129", "CZK"))
        assertEquals(-4550L, parseMoney("-45.5", "CZK"))
        assertEquals(129950L, parseMoney("1 299,50", "CZK"))
        assertEquals(1L, parseMoney("0.01", "CZK"))
    }

    @Test
    fun `parseMoney rejects junk and too many decimals`() {
        assertNull(parseMoney("abc", "CZK"))
        assertNull(parseMoney("1.234", "CZK"))
        assertNull(parseMoney("", "CZK"))
    }

    @Test
    fun `toInputValue round-trips`() {
        assertEquals("129.90", toInputValue(12990, "CZK"))
        assertEquals("-45.50", toInputValue(-4550, "CZK"))
    }

    @Test
    fun `formatAmount keeps the currency's precision`() {
        assertEquals("129.90", formatAmount(12990, "CZK", Locale.UK))
        assertEquals("130", formatAmount(130, "JPY", Locale.UK))
    }

    @Test
    fun `shiftMonth crosses year boundaries`() {
        assertEquals("2025-12", shiftMonth("2026-01", -1))
        assertEquals("2027-01", shiftMonth("2026-12", 1))
    }

    @Test
    fun `monthRange covers the whole month including leap February`() {
        assertEquals(LocalDate(2026, 10, 1) to LocalDate(2026, 10, 31), monthRange("2026-10"))
        assertEquals(LocalDate(2028, 2, 1) to LocalDate(2028, 2, 29), monthRange("2028-02"))
    }

    @Test
    fun `currentMonth is YYYY-MM`() {
        assertEquals("2026-11", currentMonth(LocalDate(2026, 11, 24)))
    }
}
