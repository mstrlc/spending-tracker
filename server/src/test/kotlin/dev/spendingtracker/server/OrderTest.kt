package dev.spendingtracker.server

import dev.spendingtracker.server.order.OrderEmail
import dev.spendingtracker.server.order.OrderParser
import dev.spendingtracker.server.order.readOrderEmail
import dev.spendingtracker.server.order.scaleTo
import dev.spendingtracker.shared.api.Account
import dev.spendingtracker.shared.api.AccountInput
import dev.spendingtracker.shared.api.BankFeed
import dev.spendingtracker.shared.api.BankFeedInput
import dev.spendingtracker.shared.api.BankInstitution
import dev.spendingtracker.shared.api.BankRow
import dev.spendingtracker.shared.api.BankRowRef
import dev.spendingtracker.shared.api.Category
import dev.spendingtracker.shared.api.CategoryInput
import dev.spendingtracker.shared.api.DraftBankRow
import dev.spendingtracker.shared.api.ExpenseInput
import dev.spendingtracker.shared.api.ExpenseLineInput
import dev.spendingtracker.shared.api.MatchOrder
import dev.spendingtracker.shared.api.Order
import dev.spendingtracker.shared.api.OrderEmailInput
import dev.spendingtracker.shared.api.OrderInput
import dev.spendingtracker.shared.api.OrderItemAssignment
import dev.spendingtracker.shared.api.OrderItemInput
import dev.spendingtracker.shared.api.OrderItemKind
import dev.spendingtracker.shared.api.OrderItemsInput
import dev.spendingtracker.shared.api.SplitDraft
import dev.spendingtracker.shared.api.StatementUpload
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDate
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Every order and statement here is made up: real ones hold household data.
private const val HEADER = "Type,Product,Started Date,Completed Date,Description,Amount,Fee,Currency,State,Balance"

private fun statementRow(date: String, text: String, amount: String) =
    "Card Payment,Current,$date 08:00:00,$date 08:00:01,$text,$amount,0.00,CZK,COMPLETED,0.00"

private val headphonesOrder = OrderInput(
    shop = "testshop",
    number = "1001",
    orderedOn = LocalDate(2026, 11, 6),
    totalMinor = 1_500_00,
    currency = "CZK",
    merchant = "Alza",
    items = listOf(
        OrderItemInput("Headphones", 1_200_00),
        OrderItemInput("USB cable", 300_10, quantity = 2.0),
        OrderItemInput("Shipping", 69_00, kind = OrderItemKind.SHIPPING),
        OrderItemInput("Free shipping", -69_00, kind = OrderItemKind.DISCOUNT),
    ),
)

/** Reads "Order <number>, total <crowns>" mails from the test shop; the body lists "<name>: <crowns>" items. */
private object TestShopParser : OrderParser {
    override val shop = "testshop"
    override fun parse(email: OrderEmail): OrderInput? {
        val number = Regex("Order (\\d+)").find(email.subject)?.groupValues?.get(1) ?: return null
        val total = email.lines.firstNotNullOfOrNull { Regex("Total: (\\d+)").find(it) }?.groupValues?.get(1)?.toLong() ?: return null
        val items = email.lines.mapNotNull { Regex("^Item (.+): (\\d+)$").find(it) }
            .map { OrderItemInput(it.groupValues[1], it.groupValues[2].toLong() * 100) }
        return OrderInput(shop, number, LocalDate(2026, 11, 6), total * 100, "CZK", "Alza", items)
    }
}

private fun eml(subject: String, html: String) = """
    From: "Test Shop" <orders@shop.example>
    To: someone@example.com
    Subject: $subject
    Date: Fri, 6 Nov 2026 10:00:00 +0100
    MIME-Version: 1.0
    Content-Type: multipart/alternative; boundary="b1"

    --b1
    Content-Type: text/html; charset=utf-8
    Content-Transfer-Encoding: base64

    ${Base64.getMimeEncoder().encodeToString(html.toByteArray()).replace("\r\n", "\n    ")}
    --b1--
""".trimIndent().replace("\n", "\r\n")

class OrderTest {
    private suspend fun HttpClient.pending(base: String) = get("$base/bank-rows").body<List<BankRow>>()

    @Test
    fun anOrderMatchesItsRowAndDraftsItsSplit() = apiTest {
        val client = newClient()
        val me = client.signup()
        val base = "/api/households/${client.createHousehold().id}"
        val account = client.post("$base/accounts") { setBody(AccountInput("Current")) }.body<Account>()
        val feed = client.post("$base/bank-feeds") {
            setBody(BankFeedInput(account.id, "Current", BankInstitution.REVOLUT, currency = "CZK"))
        }.body<BankFeed>()
        val tech = client.post("$base/categories") { setBody(CategoryInput("Electronics")) }.body<Category>()
        val home = client.post("$base/categories") { setBody(CategoryInput("Home")) }.body<Category>()

        // The email comes first: nothing to match yet.
        val saved = client.post("$base/orders") { setBody(headphonesOrder) }.body<Order>()
        assertNull(saved.bankRowId)
        assertEquals(emptyList(), saved.candidates)
        assertEquals(listOf(false, false, false, false), saved.items.map { it.assigned })

        // The bank brings two rows of the amount; the one naming the shop is it.
        client.post("$base/bank-feeds/${feed.id}/statements") {
            setBody(
                StatementUpload(
                    "$HEADER\n" + statementRow("2026-11-05", "Alza.cz", "-1500.00") + "\n" +
                        statementRow("2026-11-07", "Lidl", "-1500.00"),
                ),
            )
        }
        val alzaRow = client.pending(base).single { it.text.contains("Alza") }
        val matched = client.get("$base/orders?bankRowId=${alzaRow.id}").body<List<Order>>().single()
        assertEquals(alzaRow.id, matched.bankRowId)
        // Undecided items go under the merchant's default (none yet), joint, adding up to the row.
        val firstDraft = alzaRow.draft!!
        assertEquals("Alza order 1001", firstDraft.source)
        assertEquals(listOf(ExpenseLineInput(null, 1_500_00, "Headphones; 2× USB cable", null)), firstDraft.lines)

        // Deciding the items splits the draft by category and whose; shipping and its discount stay together.
        val assigned = client.put("$base/orders/${matched.id}/items") {
            setBody(
                OrderItemsInput(
                    listOf(
                        OrderItemAssignment(matched.items[0].id, tech.id, me.id),
                        OrderItemAssignment(matched.items[1].id, home.id, null),
                    ),
                    matched.version,
                ),
            )
        }.body<Order>()
        assertEquals(matched.version + 1, assigned.version)
        val split = client.pending(base).single { it.id == alzaRow.id }.draft!!
        assertEquals(listOf(tech.id, home.id), split.lines.map { it.categoryId })
        assertEquals(listOf(me.id, null), split.lines.map { it.paidByUserId })
        assertEquals(1_500_00, split.lines.sumOf { it.amountMinor })

        // A later order learns from items of the same name; sending one again keeps what was decided.
        val next = client.post("$base/orders") {
            setBody(headphonesOrder.copy(number = "1002", totalMinor = 1_200_00, items = headphonesOrder.items.take(1)))
        }.body<Order>()
        assertEquals(tech.id to me.id, next.items.single().let { it.categoryId to it.paidByUserId })
        val again = client.post("$base/orders") { setBody(headphonesOrder) }.body<Order>()
        assertEquals(matched.id, again.id)
        assertEquals(listOf(true, true, false, false), again.items.map { it.assigned })

        // The only row left is matched, but a draft from elsewhere isn't overwritten.
        val lidlRow = client.pending(base).single { it.text.contains("Lidl") }
        val own = SplitDraft(lines = listOf(ExpenseLineInput(home.id, 1_500_00)), source = "by hand")
        client.post("$base/bank-rows/draft") { setBody(DraftBankRow(BankRowRef(lidlRow.id, lidlRow.version), own)) }
        val third = client.post("$base/orders") { setBody(headphonesOrder.copy(number = "1003", merchant = "Lidl")) }.body<Order>()
        assertEquals(lidlRow.id, third.bankRowId)
        assertEquals(own, client.pending(base).single { it.id == lidlRow.id }.draft)

        // Unmatching clears the order's own draft; the matcher then leaves the order alone.
        val unmatched = client.post("$base/orders/${matched.id}/match") { setBody(MatchOrder(null, again.version)) }.body<Order>()
        assertNull(unmatched.bankRowId)
        assertTrue(unmatched.matchedByHand)
        assertNull(client.pending(base).single { it.id == alzaRow.id }.draft)
        client.post("$base/bank-rows/apply-rules")
        assertNull(client.get("$base/orders?bankRowId=${alzaRow.id}").body<List<Order>>().singleOrNull())

        // Matched again and filed, the expense keeps its order.
        val rematched = client.post("$base/orders/${matched.id}/match") {
            setBody(MatchOrder(BankRowRef(alzaRow.id, client.pending(base).single { it.id == alzaRow.id }.version), unmatched.version))
        }.body<Order>()
        val row = client.pending(base).single { it.id == alzaRow.id }
        val expense = client.post("$base/expenses") {
            setBody(
                ExpenseInput(
                    row.date, 1_500_00, merchant = "Alza", accountId = account.id,
                    lines = row.draft!!.lines, bankRows = listOf(BankRowRef(row.id, row.version)),
                ),
            )
        }.body<dev.spendingtracker.shared.api.Expense>()
        val ofExpense = client.get("$base/orders?expenseId=${expense.id}").body<List<Order>>().single()
        assertEquals(rematched.id, ofExpense.id)
        assertEquals(expense.id, ofExpense.expenseId)

        client.delete("$base/orders/${next.id}?version=${next.version + 1}").assertError(HttpStatusCode.Conflict, "version_conflict")
        client.delete("$base/orders/${next.id}?version=${next.version}").also { assertEquals(HttpStatusCode.NoContent, it.status) }
    }

    @Test
    fun anAutoRuleLeavesARowWithAnOrderDraftForAPerson() = apiTest {
        val client = newClient().also { it.signup() }
        val base = "/api/households/${client.createHousehold().id}"
        val account = client.post("$base/accounts") { setBody(AccountInput("Current")) }.body<Account>()
        val feed = client.post("$base/bank-feeds") {
            setBody(BankFeedInput(account.id, "Current", BankInstitution.REVOLUT, currency = "CZK"))
        }.body<BankFeed>()
        val tech = client.post("$base/categories") { setBody(CategoryInput("Electronics")) }.body<Category>()
        client.post("$base/inbox-rules") {
            setBody(
                dev.spendingtracker.shared.api.InboxRuleInput(
                    name = "Alza", textContains = "alza", outcome = dev.spendingtracker.shared.api.RuleOutcome.EXPENSE,
                    merchant = "Alza", categoryId = tech.id, auto = true,
                ),
            )
        }
        client.post("$base/orders") { setBody(headphonesOrder) }
        client.post("$base/bank-feeds/${feed.id}/statements") {
            setBody(
                StatementUpload(
                    "$HEADER\n" + statementRow("2026-11-05", "Alza.cz", "-1500.00") + "\n" +
                        statementRow("2026-11-20", "Alza.cz", "-99.00"),
                ),
            )
        }
        // The order's payment waits with its split; another Alza payment is filed by the rule.
        val pending = client.pending(base)
        assertEquals(listOf(-1_500_00L), pending.map { it.amountMinor })
        assertEquals("Alza order 1001", pending.single().draft!!.source)
    }

    @Test
    fun ordersAreReadFromEmailsByTheFirstParserThatKnowsThem() = apiTest(orderParsers = listOf(TestShopParser)) {
        val client = newClient().also { it.signup() }
        val base = "/api/households/${client.createHousehold().id}"
        val html = "<html><head><style>p{color:red}</style></head><body><table><tr><td>Item Mixér</td><td>: 999</td></tr></table>" +
            "<p>Item Mixér: 999</p><p>Total:&nbsp;999</p></body></html>"

        val order = client.post("$base/orders/email") { setBody(OrderEmailInput(eml("Order 77", html))) }.body<Order>()
        assertEquals("77" to 999_00L, order.number to order.totalMinor)
        assertEquals(listOf("Mixér"), order.items.map { it.name })

        // Pasted text works the same way, but this one has no order number in a subject.
        client.post("$base/orders/email") { setBody(OrderEmailInput("Total: 5\nItem Pen: 5")) }
            .assertError(HttpStatusCode.UnprocessableEntity, "unrecognised_email")
        client.post("$base/orders/email") { setBody(OrderEmailInput(" ")) }.assertError(HttpStatusCode.BadRequest, "empty_email")
    }

    @Test
    fun emailsDecodeToLinesOfText() {
        val email = readOrderEmail(eml("=?utf-8?B?${Base64.getEncoder().encodeToString("Objednávka č. 5".toByteArray())}?=", "<div>Ahoj <b>světe</b></div><br><p> Celkem&nbsp;10 Kč </p>"))
        assertEquals("orders@shop.example", email.from)
        assertEquals("Objednávka č. 5", email.subject)
        assertEquals(listOf("Ahoj světe", "Celkem 10 Kč"), email.lines)
        assertEquals(listOf("a", "b"), readOrderEmail("  a \n\n b").lines)
    }

    @Test
    fun scalingKeepsTheTotal() {
        assertEquals(listOf(119_993L, 30_007L, 0L), scaleTo(listOf(120_000, 30_010, 0), 150_000))
        // Discounts cancelling the items: the parts stay, the difference goes on the largest.
        assertEquals(listOf(150L, -100L, 0L), scaleTo(listOf(100, -100, 0), 50))
    }
}
