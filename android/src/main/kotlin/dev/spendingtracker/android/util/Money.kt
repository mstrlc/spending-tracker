package dev.spendingtracker.android.util

import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import kotlin.math.pow
import kotlin.time.Clock as KotlinClock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/** A calendar month as "YYYY-MM". */
typealias Month = String

fun currentMonth(today: LocalDate = KotlinClock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date): Month =
    "%04d-%02d".format(today.year, today.monthNumber)

fun shiftMonth(month: Month, delta: Int): Month {
    val (year, m) = month.split("-").map(String::toInt)
    val shifted = LocalDate(year, m, 1).plus(delta, DateTimeUnit.MONTH)
    return "%04d-%02d".format(shifted.year, shifted.monthNumber)
}

/** Inclusive first and last day of the month. */
fun monthRange(month: Month): Pair<LocalDate, LocalDate> {
    val (year, m) = month.split("-").map(String::toInt)
    val first = LocalDate(year, m, 1)
    val last = first.plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY)
    return first to last
}

fun parseMonth(month: Month): LocalDate = LocalDate(month.substring(0, 4).toInt(), month.substring(5, 7).toInt(), 1)

fun formatMonth(month: Month, locale: Locale = Locale.getDefault()): String {
    val first = parseMonth(month)
    // Build the java.util.Date at noon UTC so no timezone can shift it to the previous month.
    val cal = java.util.GregorianCalendar(java.util.TimeZone.getTimeZone("UTC"))
    cal.clear()
    cal.set(first.year, first.monthNumber - 1, first.dayOfMonth, 12, 0)
    return java.text.SimpleDateFormat("LLLL yyyy", locale).format(cal.time).replaceFirstChar { it.uppercase() }
}

/** "Thu 29 Oct" style label for a date, in the user's locale. */
fun formatDay(date: LocalDate, locale: Locale = Locale.getDefault()): String {
    val cal = java.util.GregorianCalendar(java.util.TimeZone.getTimeZone("UTC"))
    cal.clear()
    cal.set(date.year, date.monthNumber - 1, date.dayOfMonth, 12, 0)
    return java.text.SimpleDateFormat("EEE d MMM", locale).format(cal.time).replaceFirstChar { it.uppercase() }
}

/** Number of minor-unit digits for a currency (2 for EUR, 0 for JPY). */
fun fractionDigits(currency: String): Int = Currency.getInstance(currency).defaultFractionDigits

/** Amount in minor units as a currency string, like the web's formatMoney (Intl style: currency). */
fun formatMoney(amountMinor: Long, currency: String, locale: Locale = Locale.getDefault()): String {
    val digits = fractionDigits(currency)
    val nf = NumberFormat.getCurrencyInstance(locale)
    nf.currency = Currency.getInstance(currency)
    nf.maximumFractionDigits = digits
    nf.minimumFractionDigits = digits
    return nf.format(amountMinor / 10.0.pow(digits))
}

/** Minor units as a plain number in the currency's precision (for tables headed by the currency). */
fun formatAmount(amountMinor: Long, currency: String, locale: Locale = Locale.getDefault()): String {
    val digits = fractionDigits(currency)
    val nf = NumberFormat.getNumberInstance(locale)
    nf.minimumFractionDigits = digits
    nf.maximumFractionDigits = digits
    return nf.format(amountMinor / 10.0.pow(digits))
}

/**
 * Parses user input like "129.90", "129,9", "-45" or "1 299,50" into minor units. Returns null when the input
 * isn't a number or has more decimals than the currency allows (same rules as the web's parseMoney).
 */
fun parseMoney(input: String, currency: String): Long? {
    val digits = fractionDigits(currency)
    val compact = input.replace("[\\s ']".toRegex(), "")
    val match = "^(-?)(\\d+)(?:[.,](\\d*))?$".toRegex().find(compact) ?: return null
    val (sign, whole, fraction) = match.destructured
    if (fraction.length > digits) return null
    val minor = whole.toLong() * 10.0.pow(digits).toLong() + fraction.padEnd(digits, '0').toLong()
    if (minor > Long.MAX_VALUE / 2) return null
    return if (sign == "-") -minor else minor
}

/** Minor units back to an editable string, e.g. 12990 CZK → "129.90". */
fun toInputValue(amountMinor: Long, currency: String): String {
    val digits = fractionDigits(currency)
    val sign = if (amountMinor < 0) "-" else ""
    val abs = kotlin.math.abs(amountMinor)
    if (digits == 0) return "$sign$abs"
    val whole = abs / 10.0.pow(digits).toLong()
    val fraction = (abs % 10.0.pow(digits).toLong()).toString().padStart(digits, '0')
    return "$sign$whole.$fraction"
}
