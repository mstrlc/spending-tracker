package dev.spendingtracker.server.inbox

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer
import java.util.Currency

/** Collapses runs of whitespace and trims. */
internal fun squash(text: String) = text.replace(Regex("\\s+"), " ").trim()

/** Uppercase, no diacritics, letters and digits only: how names and descriptors are compared. */
fun normalizeText(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "")
        .uppercase()
        .replace(Regex("[^A-Z0-9]+"), " ")
        .trim()

/**
 * A descriptor without what changes from one purchase to the next (store numbers, terminal ids, card suffixes):
 * "Kaufland Cz  4310" → "KAUFLAND CZ", "SumUp **Holic M4QED8D8" → "SUMUP HOLIC". Past decisions are matched on it.
 */
fun merchantKey(text: String): String = normalizeText(text).split(' ').filter { it.isNotEmpty() && it.none(Char::isDigit) }.joinToString(" ")

/** A readable first guess at the merchant: "SumUp **Holic M4QED8D8" → "SumUp Holic". */
fun cleanDescriptor(text: String): String =
    squash(
        text.split(Regex("\\s{2,}")).first()
            .split(' ').filter { it.none(Char::isDigit) }.joinToString(" ")
            .replace(Regex("\\*+"), " "),
    )

/** Every word of one of [names] appears in [text], in any order and ignoring diacritics: "NOVAKOVA JANA" matches "Jana Nováková". */
fun isHouseholdName(text: String, names: Collection<String>): Boolean {
    if (text.isBlank()) return false
    val words = normalizeText(text).split(' ').toSet()
    return names.any { name ->
        val parts = normalizeText(name).split(' ').filter { it.length > 1 }
        parts.isNotEmpty() && parts.all { it in words }
    }
}

/** A decimal amount as minor units of [currency]: "-129.9" CZK → -12990. */
fun toMinor(amount: BigDecimal, currency: String): Long {
    val digits = runCatching { Currency.getInstance(currency).defaultFractionDigits }.getOrDefault(2).coerceAtLeast(0)
    return amount.movePointRight(digits).setScale(0, RoundingMode.HALF_UP).longValueExact()
}

/** "-1 234,50", "1 234,50 Kč", "-263.00" → BigDecimal; null for anything else. */
fun parseAmount(text: String): BigDecimal? {
    var cleaned = text.replace(Regex("[\\s\\u00A0\\u202F]|Kč|CZK", RegexOption.IGNORE_CASE), "")
    if (cleaned.contains(',')) cleaned = cleaned.replace(".", "").replace(',', '.')
    return cleaned.toBigDecimalOrNull()
}

/** A Czech domestic account number: [prefix-]number/bank. */
data class CzechAccount(val prefix: String, val number: String, val bank: String) {
    override fun toString() = "${if (prefix.isNotEmpty()) "$prefix-" else ""}$number/$bank"

    val iban: String
        get() {
            val bban = bank + prefix.padStart(6, '0') + number.padStart(10, '0')
            val check = 98 - mod97("${bban}CZ00")
            return "CZ${check.toString().padStart(2, '0')}$bban"
        }

    companion object {
        private val PATTERN = Regex("^(?:(\\d{1,6})-)?(\\d{2,10})/(\\d{4})$")

        fun parse(text: String): CzechAccount? {
            val m = PATTERN.matchEntire(text.filterNot(Char::isWhitespace)) ?: return null
            val prefix = m.groupValues[1].trimStart('0')
            val number = m.groupValues[2].trimStart('0').padStart(2, '0')
            return CzechAccount(prefix, number, m.groupValues[3])
        }

        /** The account in a Czech IBAN (CZkk BBBB PPPPPP NNNNNNNNNN), for display. */
        fun fromIban(iban: String): CzechAccount? {
            if (!iban.startsWith("CZ") || iban.length != 24) return null
            return CzechAccount(iban.substring(8, 14).trimStart('0'), iban.substring(14).trimStart('0').padStart(2, '0'), iban.substring(4, 8))
        }
    }
}

private fun mod97(text: String): Int {
    var rest = 0
    for (c in text) {
        val value = if (c.isDigit()) c.digitToInt() else c - 'A' + 10
        rest = (if (value >= 10) rest * 100 + value else rest * 10 + value) % 97
    }
    return rest
}

/** A compact IBAN, or the IBAN of a Czech "number/bank"; null when [text] is neither. */
fun ibanOf(text: String?): String? {
    val compact = text?.filterNot(Char::isWhitespace)?.uppercase()?.takeIf { it.isNotEmpty() } ?: return null
    if (Regex("^[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}$").matches(compact)) return compact
    return CzechAccount.parse(compact)?.iban
}
