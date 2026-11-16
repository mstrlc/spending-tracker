package dev.spendingtracker.server.order

import dev.spendingtracker.shared.api.OrderInput
import dev.spendingtracker.shared.api.OrderItemInput
import dev.spendingtracker.shared.api.OrderItemKind
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * Alza.cz's "Připraveno v AlzaBoxu" (ready for pickup) emails. Their body lists each line as three lines of text: the
 * name, "<quantity> x" and the line's total ("1 234,56 Kč", negative for discounts). The amount is under "Částka k
 * úhradě": what's left to pay (rounded to whole crowns, as the box charges it), or "Uhrazeno" when it was paid on
 * ordering, when the order's total is what its lines add up to. The order date is the email's.
 */
object AlzaParser : OrderParser {
    override val shop = "alza"

    private val number = Regex("""Obj\. č\. (\d{6,})""")
    private val quantity = Regex("""^(\d+) x$""")
    private val price = Regex("""^(-?[\d\s\u00A0\u202F]+(?:,\d{1,2})?)[\s\u00A0\u202F]*Kč$""")

    override fun parse(email: OrderEmail): OrderInput? {
        val lines = email.lines
        // A pasted email has no sender: Alza's footer names the company.
        val fromAlza = email.from.lowercase().endsWith("@alza.cz") ||
            (email.from.isEmpty() && lines.any { it.startsWith("Alza.cz a.s.") })
        if (!fromAlza) return null
        val orderNumber = number.find(email.subject)?.groupValues?.get(1)
            ?: lines.indexOf("Vaše objednávka").takeIf { it >= 0 }?.let { lines.getOrNull(it + 1) }?.takeIf { it.all(Char::isDigit) }
            ?: return null
        val parsedLines = buildList {
            for (i in 1 until lines.size - 1) {
                val qty = quantity.matchEntire(lines[i]) ?: continue
                val amount = parsePrice(lines[i + 1]) ?: continue
                val name = lines[i - 1]
                add(OrderItemInput(name, amount, qty.groupValues[1].toDouble(), kindOf(name, amount)))
            }
        }
        val items = withoutFreeShipping(parsedLines)
        if (items.none { it.kind == OrderItemKind.ITEM }) return null
        val due = lines.indexOf("Částka k úhradě").takeIf { it >= 0 }?.let { header ->
            // The headers stand in a row ("Kód pro vyzvednutí", "Částka k úhradě", "K vyzvednutí do"), their values below.
            lines.drop(header + 1).dropWhile { it == "K vyzvednutí do" }.getOrNull(1)
        }
        val total = due?.let(::parsePrice) ?: items.sumOf { it.amountMinor }
        val on = (email.sentAt ?: Clock.System.now()).toLocalDateTime(TimeZone.of("Europe/Prague")).date
        return OrderInput(
            shop = shop,
            number = orderNumber,
            orderedOn = on,
            totalMinor = total,
            currency = "CZK",
            merchant = "Alza",
            items = items,
        )
    }

    /**
     * [lines] without shipping that a shipping discount cancels (AlzaPlus+ makes every delivery free, so it's on nearly
     * every order and says nothing): each "Sleva na dopravné…" line goes with a "Doprava…" line of the same amount.
     * Shipping that was charged, or only partly discounted, stays.
     */
    private fun withoutFreeShipping(lines: List<OrderItemInput>): List<OrderItemInput> {
        val dropped = mutableSetOf<Int>()
        lines.forEachIndexed { i, discount ->
            if (!discount.name.startsWith("Sleva na dopravné")) return@forEachIndexed
            val shipping = lines.indices.firstOrNull { j ->
                j !in dropped && lines[j].kind == OrderItemKind.SHIPPING && lines[j].amountMinor == -discount.amountMinor
            } ?: return@forEachIndexed
            dropped += setOf(i, shipping)
        }
        return lines.filterIndexed { i, _ -> i !in dropped }
    }

    /** "1 234,56 Kč" (any spaces) as minor units, or null. */
    private fun parsePrice(text: String): Long? {
        val match = price.matchEntire(text.trim()) ?: return null
        val digits = match.groupValues[1].filterNot(Char::isWhitespace)
        val negative = digits.startsWith("-")
        val (whole, fraction) = digits.removePrefix("-").split(',').let { it[0] to it.getOrElse(1) { "" } }
        if (whole.isEmpty()) return null
        val minor = whole.toLong() * 100 + fraction.padEnd(2, '0').toLong()
        return if (negative) -minor else minor
    }

    private fun kindOf(name: String, amountMinor: Long): OrderItemKind = when {
        name.startsWith("Doprava") -> OrderItemKind.SHIPPING
        amountMinor < 0 || name.startsWith("Sleva") -> OrderItemKind.DISCOUNT
        name.startsWith("Platba") || name.startsWith("Poplatek") || name.startsWith("Dobírka") -> OrderItemKind.FEE
        else -> OrderItemKind.ITEM
    }
}
