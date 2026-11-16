package dev.spendingtracker.server

import dev.spendingtracker.server.order.AlzaParser
import dev.spendingtracker.server.order.readOrderEmail
import dev.spendingtracker.shared.api.OrderItemKind
import kotlinx.datetime.LocalDate
import java.util.Base64
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A made-up email in the layout of Alza's "ready in AlzaBox" ones: the real ones hold household data. */
private fun alzaEmail(due: String, items: List<Triple<String, Int, String>>, from: String = "sluzebnicek@alza.cz"): String {
    val rows = items.joinToString("") { (name, qty, price) ->
        "<tr><td>$name</td><td>$qty x</td><td>$price</td></tr>"
    }
    val html = """
        <html><body>
        <h1>Přijďte si pro nákup do AlzaBoxu</h1>
        <p>Vaše objednávka</p><p>1234567890</p><p>právě dorazila do</p><p>AlzaBoxu - Někde</p>
        <table><tr><td>Kód pro vyzvednutí</td><td>Částka k úhradě</td><td>K vyzvednutí do</td></tr>
        <tr><td>123 456</td><td>$due</td><td>Středy 16.9. 23:59</td></tr></table>
        <table>$rows</table>
        <p>Potřebujete poradit?</p>
        <p>Alza.cz a.s., se sídlem Někde 1, Praha</p>
        </body></html>
    """.trimIndent()
    val subject = "=?utf-8?B?" + Base64.getEncoder().encodeToString("Připraveno v AlzaBoxu / Obj. č. 1234567890".toByteArray()) + "?="
    return listOf(
        "From: \"Alza.cz\" <$from>",
        "Subject: $subject",
        "Date: 14 Sep 2026 22:30:00 +0000",
        "MIME-Version: 1.0",
        "Content-Type: text/html; charset=utf-8",
        "Content-Transfer-Encoding: base64",
        "",
        Base64.getMimeEncoder().encodeToString(html.toByteArray()),
    ).joinToString("\r\n")
}

class AlzaParserTest {
    @Test
    fun readsAnOrderLeftToPay() {
        val order = AlzaParser.parse(
            readOrderEmail(
                alzaEmail(
                    "1 148 Kč",
                    listOf(
                        Triple("Peanut butter 1kg", 1, "164,08 Kč"),
                        Triple("Headphones", 2, "1 000,00 Kč"),
                        Triple("Doprava - AlzaBox", 1, "69,00 Kč"),
                        Triple("Sleva na dopravné - AlzaPlus+", 1, "-69,00 Kč"),
                        Triple("Sleva 10% k položce XY_1", 1, "-16,41 Kč"),
                    ),
                ),
            ),
        )!!
        assertEquals("1234567890", order.number)
        assertEquals("alza", order.shop)
        assertEquals("Alza", order.merchant)
        // Sent late in the evening UTC: already the next day in Prague.
        assertEquals(LocalDate(2026, 9, 15), order.orderedOn)
        assertEquals(1_148_00, order.totalMinor)
        // Shipping its AlzaPlus+ discount cancels is left out; the item's own discount stays.
        assertEquals(listOf(16_408L, 100_000L, -1_641L), order.items.map { it.amountMinor })
        assertEquals(listOf(1.0, 2.0, 1.0), order.items.map { it.quantity })
        assertEquals(listOf(OrderItemKind.ITEM, OrderItemKind.ITEM, OrderItemKind.DISCOUNT), order.items.map { it.kind })
    }

    @Test
    fun shippingChargedOrOnlyPartlyDiscountedStays() {
        val order = AlzaParser.parse(
            readOrderEmail(
                alzaEmail(
                    "229 Kč",
                    listOf(
                        Triple("Cable", 1, "199,90 Kč"),
                        Triple("Doprava - AlzaBox", 1, "69,00 Kč"),
                        Triple("Sleva na dopravné - AlzaPlus+", 1, "-40,00 Kč"),
                    ),
                ),
            ),
        )!!
        assertEquals(listOf(OrderItemKind.ITEM, OrderItemKind.SHIPPING, OrderItemKind.DISCOUNT), order.items.map { it.kind })
    }

    @Test
    fun aPaidOrderTotalsItsLines() {
        val order = AlzaParser.parse(
            readOrderEmail(alzaEmail("Uhrazeno", listOf(Triple("Cable", 1, "199,90 Kč"), Triple("Doprava - AlzaBox", 1, "0,00 Kč")))),
        )!!
        assertEquals(199_90, order.totalMinor)
    }

    @Test
    fun otherSendersAndPastedTextWithoutTheFooterAreNotAlzas() {
        val items = listOf(Triple("Cable", 1, "199,90 Kč"))
        assertNull(AlzaParser.parse(readOrderEmail(alzaEmail("200 Kč", items, from = "shop@example.com"))))
        assertNull(AlzaParser.parse(readOrderEmail("Vaše objednávka\n1234567890\nCable\n1 x\n199,90 Kč")))
        // Pasted with Alza's footer, it reads.
        val pasted = AlzaParser.parse(readOrderEmail("Vaše objednávka\n1234567890\nCable\n1 x\n199,90 Kč\nAlza.cz a.s., Praha"))!!
        assertEquals("1234567890" to 199_90L, pasted.number to pasted.totalMinor)
    }

    /** The real emails on this machine (none elsewhere): each reads, and its lines add up to its total within 1 Kč. */
    @Test
    fun readsEveryRealEmail() {
        val files = orderEmailFixtures("alza")
        println("Alza fixtures: ${files.size}")
        for (file in files) {
            val order = AlzaParser.parse(readOrderEmail(file.readText()))
            assertTrue(order != null, "${file.name}: not read")
            assertTrue(file.name.contains(order.number), "${file.name}: read order ${order.number}")
            assertTrue(order.items.any { it.kind == OrderItemKind.ITEM }, "${file.name}: no items")
            val off = abs(order.items.sumOf { it.amountMinor } - order.totalMinor)
            assertTrue(off < 100, "${file.name}: lines are $off off the total")
        }
    }
}
