package dev.spendingtracker.shared.api

import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class OrderItemKind {
    @SerialName("item") ITEM,
    @SerialName("shipping") SHIPPING,
    /** A payment or handling fee. */
    @SerialName("fee") FEE,
    /** Negative: a voucher, a member discount, a discount on one item. */
    @SerialName("discount") DISCOUNT,
}

/**
 * An e-shop order read from the shop's email. [totalMinor] is what the shop charged in [currency]; the items'
 * amounts may differ from it by rounding. While its bank row ([bankRowId]) is pending, the order fills that row's
 * split draft; once the row becomes an expense, [expenseId] is that expense and the items stay with it.
 */
@Serializable
data class Order(
    val id: String,
    val shop: String,
    val number: String,
    val orderedOn: LocalDate,
    val totalMinor: Long,
    val currency: String,
    val merchant: String,
    val items: List<OrderItem>,
    val bankRowId: String?,
    val expenseId: String?,
    /** Matched by hand, so the matcher leaves it alone. */
    val matchedByHand: Boolean,
    /** Pending rows it could be, the likeliest first; empty once matched. */
    val candidates: List<BankRowRef>,
    val version: Int,
)

/**
 * One line of an order, [amountMinor] its total in the order's currency. [assigned] says whether [categoryId] and
 * [paidByUserId] were decided (by a person or from earlier orders): a null whose on an assigned item is joint.
 */
@Serializable
data class OrderItem(
    val id: String,
    val kind: OrderItemKind,
    val name: String,
    val quantity: Double,
    val amountMinor: Long,
    val categoryId: String?,
    val paidByUserId: String?,
    val assigned: Boolean,
)

/**
 * An order as a parser read it, for `POST /orders`: what an email parser produces, and what anything else reading
 * orders (a mailbox watcher, another shop's export) sends. [shop] names the parser (e.g. "alza") and with [number]
 * identifies the order: sending it again replaces its items, keeping what was decided about items of the same name.
 */
@Serializable
data class OrderInput(
    val shop: String,
    val number: String,
    val orderedOn: LocalDate,
    val totalMinor: Long,
    val currency: String,
    val merchant: String = "",
    val items: List<OrderItemInput>,
    /** The text it was read from, kept to read again later. */
    val raw: String? = null,
)

@Serializable
data class OrderItemInput(
    val name: String,
    val amountMinor: Long,
    val quantity: Double = 1.0,
    val kind: OrderItemKind = OrderItemKind.ITEM,
)

/** An order's email as a whole `.eml` file (MIME), or the text of one pasted from a mail app. */
@Serializable
data class OrderEmailInput(val email: String)

/** Where each item goes: by item id, its category and whose (null for joint). Items left out stay as they are. */
@Serializable
data class OrderItemsInput(val items: List<OrderItemAssignment>, val version: Int)

@Serializable
data class OrderItemAssignment(val itemId: String, val categoryId: String?, val paidByUserId: String?)

/** Matches an order to a pending [row] by hand, or with null unmatches it (and the matcher then leaves it alone). */
@Serializable
data class MatchOrder(val row: BankRowRef?, val version: Int)
