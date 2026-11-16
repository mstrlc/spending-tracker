package dev.spendingtracker.server.order

import dev.spendingtracker.server.apiJson
import dev.spendingtracker.server.common.badRequest
import dev.spendingtracker.server.common.requiredText
import dev.spendingtracker.server.db.BankRows
import dev.spendingtracker.server.db.Categories
import dev.spendingtracker.server.db.Merchants
import dev.spendingtracker.server.db.OrderItems
import dev.spendingtracker.server.db.Orders
import dev.spendingtracker.server.household.isMember
import dev.spendingtracker.server.inbox.normalizeText
import dev.spendingtracker.server.inbox.pendingRows
import dev.spendingtracker.shared.api.BankRowRef
import dev.spendingtracker.shared.api.ExpenseLineInput
import dev.spendingtracker.shared.api.Order
import dev.spendingtracker.shared.api.OrderInput
import dev.spendingtracker.shared.api.OrderItem
import dev.spendingtracker.shared.api.OrderItemKind
import dev.spendingtracker.shared.api.SplitDraft
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.between
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** How far a payment may be from the order's date: card payments come before the email, cash on pickup after. */
private val MATCH_BEFORE = DatePeriod(days = 14)
private val MATCH_AFTER = DatePeriod(days = 7)

private val KIND_DB = OrderItemKind.entries.associateWith { it.name.lowercase() }
private fun kindFrom(value: String) = KIND_DB.entries.first { it.value == value }.key

/** What [source] says on a draft an order made, so the order recognises (and may replace or clear) its own. */
fun draftSource(merchant: String, shop: String, number: String) = "${merchant.ifEmpty { shop }} order $number"

private fun ResultRow.draftSource() = draftSource(this[Orders.merchant], this[Orders.shop], this[Orders.number])

/** [input] checked: a shop, a number, a currency, at least one item, names and amounts. */
fun OrderInput.validated(): OrderInput {
    if (items.isEmpty()) throw badRequest("no_items", "An order needs at least one item", "items")
    if (totalMinor == 0L) throw badRequest("invalid_amount", "The total can't be zero", "totalMinor")
    if (!Regex("[A-Z]{3}").matches(currency)) throw badRequest("invalid_currency", "Currency must be a 3-letter code", "currency")
    items.forEachIndexed { i, item ->
        if (item.quantity <= 0) throw badRequest("invalid_quantity", "Quantity must be positive", "items[$i].quantity")
    }
    return copy(
        shop = requiredText(shop, "shop", max = 40).lowercase(),
        number = requiredText(number, "number", max = 100),
        merchant = merchant.trim(),
        items = items.mapIndexed { i, item -> item.copy(name = requiredText(item.name, "items[$i].name", max = 500)) },
    )
}

/**
 * Saves [input] as a new order or, when the household has the shop's order with that number, replaces its items,
 * keeping what was decided about items of the same name. New items take their category and whose from the latest
 * decided item of the same name in an earlier order. Returns the order's id. Call inside a transaction.
 */
fun saveOrder(householdId: Uuid, userId: Uuid, input: OrderInput): Uuid {
    val existing = Orders.selectAll()
        .where { (Orders.householdId eq householdId) and (Orders.shop eq input.shop) and (Orders.number eq input.number) }
        .forUpdate().singleOrNull()
    val orderId = existing?.get(Orders.id) ?: Uuid.random()
    val kept = if (existing == null) emptyMap() else OrderItems.selectAll()
        .where { (OrderItems.orderId eq orderId) and (OrderItems.assigned eq true) }
        .associate { it[OrderItems.name].lowercase() to (it[OrderItems.categoryId] to it[OrderItems.paidByUserId]) }
    if (existing == null) {
        Orders.insert {
            it[id] = orderId
            it[Orders.householdId] = householdId
            it[shop] = input.shop
            it[number] = input.number
            it[orderedOn] = input.orderedOn
            it[totalMinor] = input.totalMinor
            it[currency] = input.currency
            it[merchant] = input.merchant
            it[raw] = input.raw
            it[createdBy] = userId
        }
    } else {
        Orders.update({ Orders.id eq orderId }) {
            it[orderedOn] = input.orderedOn
            it[totalMinor] = input.totalMinor
            it[currency] = input.currency
            it[merchant] = input.merchant
            if (input.raw != null) it[raw] = input.raw
            it[version] = Orders.version + 1
            it[updatedAt] = Clock.System.now()
        }
        OrderItems.deleteWhere { OrderItems.orderId eq orderId }
    }
    val learned = learnedAssignments(householdId, input.items.map { it.name }.filter { it.lowercase() !in kept })
    OrderItems.batchInsert(input.items.withIndex(), shouldReturnGeneratedValues = false) { (i, item) ->
        val decided = kept[item.name.lowercase()] ?: learned[item.name.lowercase()]
        this[OrderItems.id] = Uuid.random()
        this[OrderItems.householdId] = householdId
        this[OrderItems.orderId] = orderId
        this[OrderItems.position] = i
        this[OrderItems.kind] = KIND_DB.getValue(item.kind)
        this[OrderItems.name] = item.name
        this[OrderItems.quantity] = BigDecimal.valueOf(item.quantity)
        this[OrderItems.amountMinor] = item.amountMinor
        this[OrderItems.categoryId] = decided?.first
        this[OrderItems.paidByUserId] = decided?.second
        this[OrderItems.assigned] = decided != null
    }
    refreshDraft(householdId, orderId)
    return orderId
}

/** For each of [names] (any case), the category and whose of the latest decided item of that name. */
private fun learnedAssignments(householdId: Uuid, names: List<String>): Map<String, Pair<Uuid?, Uuid?>> {
    if (names.isEmpty()) return emptyMap()
    val lower = names.map { it.lowercase() }.distinct()
    return OrderItems.join(Orders, JoinType.INNER, OrderItems.orderId, Orders.id).select(OrderItems.name, OrderItems.categoryId, OrderItems.paidByUserId)
        .where {
            (OrderItems.householdId eq householdId) and (OrderItems.assigned eq true) and
                (OrderItems.name.lowerCase() inList lower)
        }
        .orderBy(Orders.orderedOn to SortOrder.ASC, Orders.updatedAt to SortOrder.ASC)
        // Later orders overwrite earlier ones.
        .associate { it[OrderItems.name].lowercase() to (it[OrderItems.categoryId] to it[OrderItems.paidByUserId]) }
}

/** Sets where items go ([assignments]: item id to category and whose), checked against the household. */
fun assignItems(householdId: Uuid, orderId: Uuid, assignments: List<Triple<Uuid, Uuid?, Uuid?>>) {
    val categoryIds = assignments.mapNotNull { it.second }.distinct()
    if (categoryIds.isNotEmpty()) {
        val kinds = Categories.select(Categories.kind)
            .where { (Categories.householdId eq householdId) and (Categories.id inList categoryIds) }
            .map { it[Categories.kind] }
        if (kinds.size < categoryIds.size) throw badRequest("invalid_category", "Unknown category", "items")
        if ("income" in kinds) throw badRequest("invalid_category", "Order items can't use an income category", "items")
    }
    if (assignments.mapNotNull { it.third }.distinct().any { !isMember(householdId, it) }) {
        throw badRequest("invalid_member", "Whose must be a member of the household", "items")
    }
    val itemIds = OrderItems.select(OrderItems.id).where { OrderItems.orderId eq orderId }.map { it[OrderItems.id] }.toSet()
    for ((itemId, categoryId, paidBy) in assignments) {
        if (itemId !in itemIds) throw badRequest("invalid_item", "The item isn't on this order", "items")
        OrderItems.update({ OrderItems.id eq itemId }) {
            it[OrderItems.categoryId] = categoryId
            it[paidByUserId] = paidBy
            it[assigned] = true
        }
    }
    refreshDraft(householdId, orderId)
}

/**
 * Matches the household's unmatched orders to pending rows that paid them ([candidates]), where one row is clearly
 * it, and fills those rows' drafts. Orders unmatched by hand are left alone. Call inside a transaction.
 */
fun matchOrders(householdId: Uuid, householdCurrency: String) {
    val open = Orders.selectAll()
        .where { (Orders.householdId eq householdId) and Orders.bankRowId.isNull() and (Orders.matchedByHand eq false) }
        .orderBy(Orders.orderedOn to SortOrder.ASC)
        .toList()
    if (open.isEmpty()) return
    val taken = takenRows(householdId).toMutableSet()
    for (order in open) {
        val pick = candidates(householdId, order, taken).singleSure() ?: continue
        Orders.update({ Orders.id eq order[Orders.id] }) {
            it[bankRowId] = pick[BankRows.id]
            it[version] = Orders.version + 1
            it[updatedAt] = Clock.System.now()
        }
        taken += pick[BankRows.id]
        refreshDraft(householdId, order[Orders.id], householdCurrency)
    }
}

private fun takenRows(householdId: Uuid): Set<Uuid> =
    Orders.select(Orders.bankRowId).where { (Orders.householdId eq householdId) and Orders.bankRowId.isNotNull() }
        .mapNotNull { it[Orders.bankRowId] }.toSet()

/** A candidate row and whether its text names the shop. */
private class Candidate(val row: ResultRow, val namesShop: Boolean, val days: Int) {
    operator fun get(column: org.jetbrains.exposed.v1.core.Column<Uuid>) = row[column]
}

/** The one candidate to match without asking: the only one, or the only one whose text names the shop. */
private fun List<Candidate>.singleSure(): Candidate? = singleOrNull() ?: filter { it.namesShop }.singleOrNull()

/**
 * Pending rows that could have paid [order]: the same amount out (in for a refund) in its currency, within
 * [MATCH_BEFORE] before to [MATCH_AFTER] after its date, not another order's. Those naming the shop come first,
 * then the closest in date.
 */
private fun candidates(householdId: Uuid, order: ResultRow, taken: Set<Uuid>): List<Candidate> {
    val on = order[Orders.orderedOn]
    val names = listOf(order[Orders.merchant], order[Orders.shop]).map(::normalizeText).filter { it.length >= 3 }
    return BankRows.selectAll()
        .where {
            (BankRows.householdId eq householdId) and pendingRows() and
                (BankRows.amountMinor eq -order[Orders.totalMinor]) and (BankRows.currency eq order[Orders.currency]) and
                BankRows.date.between(on.minus(MATCH_BEFORE), on.plus(MATCH_AFTER))
        }
        .filter { it[BankRows.id] !in taken }
        .map { row ->
            val text = normalizeText(listOf(row[BankRows.text], row[BankRows.counterpartyName], row[BankRows.message]).joinToString(" "))
            Candidate(row, names.any { it in text }, kotlin.math.abs(on.daysUntil(row[BankRows.date])))
        }
        .sortedWith(compareByDescending<Candidate> { it.namesShop }.thenBy { it.days })
}

/**
 * Writes the split draft of [orderId]'s row from its items, unless the row is decided or carries a draft from
 * somewhere else. Items are grouped by category and whose (undecided ones under the merchant's default category,
 * joint), each line's amount scaled so the lines add up to the row and its notes naming the items. A row in another
 * currency than the household's gets no draft: its lines would need converting. Call inside a transaction.
 */
fun refreshDraft(householdId: Uuid, orderId: Uuid, householdCurrency: String? = null) {
    val order = Orders.selectAll().where { Orders.id eq orderId }.single()
    val rowId = order[Orders.bankRowId] ?: return
    val row = BankRows.selectAll().where { (BankRows.id eq rowId) and pendingRows() }.singleOrNull() ?: return
    val currency = householdCurrency ?: dev.spendingtracker.server.db.Households
        .select(dev.spendingtracker.server.db.Households.currency)
        .where { dev.spendingtracker.server.db.Households.id eq householdId }.single()[dev.spendingtracker.server.db.Households.currency]
    if (row[BankRows.currency] != currency) return
    val current = row[BankRows.splitDraft]?.let { apiJson.decodeFromString<SplitDraft>(it) }
    if (current != null && current.source != order.draftSource()) return
    val draft = buildDraft(householdId, order, loadItems(listOf(orderId)).getValue(orderId), -row[BankRows.amountMinor])
    BankRows.update({ BankRows.id eq rowId }) {
        it[splitDraft] = apiJson.encodeToString(SplitDraft.serializer(), draft)
        it[version] = BankRows.version + 1
        it[updatedAt] = Clock.System.now()
    }
}

/** Clears the draft [order] wrote on its row, if it's still the order's and the row still pending. */
fun clearDraft(order: ResultRow) {
    val rowId = order[Orders.bankRowId] ?: return
    val row = BankRows.selectAll().where { (BankRows.id eq rowId) and pendingRows() }.singleOrNull() ?: return
    val current = row[BankRows.splitDraft]?.let { apiJson.decodeFromString<SplitDraft>(it) } ?: return
    if (current.source != order.draftSource()) return
    BankRows.update({ BankRows.id eq rowId }) {
        it[splitDraft] = null
        it[version] = BankRows.version + 1
        it[updatedAt] = Clock.System.now()
    }
}

private fun buildDraft(householdId: Uuid, order: ResultRow, items: List<OrderItem>, expenseMinor: Long): SplitDraft {
    val fallbackCategory = order[Orders.merchant].takeIf { it.isNotEmpty() }?.let { name ->
        Merchants.select(Merchants.defaultCategoryId)
            .where { (Merchants.householdId eq householdId) and (Merchants.name.lowerCase() eq name.lowercase()) }
            .singleOrNull()?.get(Merchants.defaultCategoryId)?.toString()
    }
    val groups = items
        .groupBy { if (it.assigned) it.categoryId to it.paidByUserId else fallbackCategory to null }
        .map { (key, groupItems) -> Triple(key, groupItems, groupItems.sumOf { it.amountMinor }) }
    val amounts = scaleTo(groups.map { it.third }, expenseMinor)
    val lines = groups.zip(amounts).map { (group, amount) ->
        val (key, groupItems) = group
        ExpenseLineInput(
            categoryId = key.first,
            amountMinor = amount,
            notes = groupItems.filter { it.kind == OrderItemKind.ITEM }.joinToString("; ") { itemLabel(it) }.take(500),
            paidByUserId = key.second,
        )
    }.filter { it.amountMinor != 0L }
    return SplitDraft(
        lines = lines.ifEmpty { listOf(ExpenseLineInput(fallbackCategory, expenseMinor)) },
        merchant = order[Orders.merchant],
        notes = if (items.count { it.kind == OrderItemKind.ITEM } == 1) itemLabel(items.first { it.kind == OrderItemKind.ITEM }) else "",
        source = order.draftSource(),
    )
}

private fun itemLabel(item: OrderItem) =
    if (item.quantity == 1.0) item.name else "${item.quantity.toBigDecimal().stripTrailingZeros().toPlainString()}× ${item.name}"

/**
 * [parts] scaled to add up to [total], in whole minor units: each in proportion, the rounding left on the largest.
 * Parts that add up to nothing (discounts cancelling items) are kept as they are, the difference on the largest.
 */
fun scaleTo(parts: List<Long>, total: Long): List<Long> {
    if (parts.isEmpty()) return emptyList()
    val sum = parts.sum()
    val scaled = if (sum == 0L) parts.toMutableList() else parts.map { (it.toDouble() * total / sum).toLong() }.toMutableList()
    val largest = scaled.indices.maxBy { kotlin.math.abs(scaled[it]) }
    scaled[largest] += total - scaled.sum()
    return scaled
}

/** Items of [orderIds], in order. */
fun loadItems(orderIds: List<Uuid>): Map<Uuid, List<OrderItem>> {
    if (orderIds.isEmpty()) return emptyMap()
    val byOrder = OrderItems.selectAll().where { OrderItems.orderId inList orderIds }
        .orderBy(OrderItems.position to SortOrder.ASC)
        .groupBy({ it[OrderItems.orderId] }) {
            OrderItem(
                id = it[OrderItems.id].toString(),
                kind = kindFrom(it[OrderItems.kind]),
                name = it[OrderItems.name],
                quantity = it[OrderItems.quantity].toDouble(),
                amountMinor = it[OrderItems.amountMinor],
                categoryId = it[OrderItems.categoryId]?.toString(),
                paidByUserId = it[OrderItems.paidByUserId]?.toString(),
                assigned = it[OrderItems.assigned],
            )
        }
    return orderIds.associateWith { byOrder[it].orEmpty() }
}

/** The household's orders matching [filter], newest first, with items, expense and (unmatched) candidates. */
fun loadOrders(householdId: Uuid, filter: Op<Boolean> = Op.TRUE): List<Order> {
    val rows = Orders.selectAll().where { (Orders.householdId eq householdId) and filter }
        .orderBy(Orders.orderedOn to SortOrder.DESC, Orders.number to SortOrder.DESC)
        .toList()
    val items = loadItems(rows.map { it[Orders.id] })
    val rowIds = rows.mapNotNull { it[Orders.bankRowId] }
    val expenses = if (rowIds.isEmpty()) emptyMap() else BankRows.select(BankRows.id, BankRows.expenseId)
        .where { BankRows.id inList rowIds }.associate { it[BankRows.id] to it[BankRows.expenseId] }
    val taken = if (rows.any { it[Orders.bankRowId] == null }) takenRows(householdId) else emptySet()
    return rows.map { order ->
        val rowId = order[Orders.bankRowId]
        Order(
            id = order[Orders.id].toString(),
            shop = order[Orders.shop],
            number = order[Orders.number],
            orderedOn = order[Orders.orderedOn],
            totalMinor = order[Orders.totalMinor],
            currency = order[Orders.currency],
            merchant = order[Orders.merchant],
            items = items.getValue(order[Orders.id]),
            bankRowId = rowId?.toString(),
            expenseId = rowId?.let { expenses[it] }?.toString(),
            matchedByHand = order[Orders.matchedByHand],
            candidates = if (rowId != null) emptyList() else candidates(householdId, order, taken)
                .map { BankRowRef(it.row[BankRows.id].toString(), it.row[BankRows.version]) },
            version = order[Orders.version],
        )
    }
}

/** Orders paid by one of [rowIds]' rows. */
fun ordersOfRows(rowIds: List<Uuid>): Op<Boolean> = Orders.bankRowId inList rowIds

/** Orders whose row became [expenseId]. */
fun ordersOfExpense(expenseId: Uuid): Op<Boolean> {
    val rowIds = BankRows.select(BankRows.id).where { BankRows.expenseId eq expenseId }.map { it[BankRows.id] }
    return Orders.bankRowId inList rowIds
}

