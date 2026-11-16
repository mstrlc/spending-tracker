package dev.spendingtracker.server.order

import dev.spendingtracker.server.auth.userId
import dev.spendingtracker.server.common.ApiException
import dev.spendingtracker.server.common.badRequest
import dev.spendingtracker.server.common.conflict
import dev.spendingtracker.server.common.notFound
import dev.spendingtracker.server.common.parseId
import dev.spendingtracker.server.common.pathId
import dev.spendingtracker.server.common.versionConflict
import dev.spendingtracker.server.common.versionParam
import dev.spendingtracker.server.db.BankRows
import dev.spendingtracker.server.db.Orders
import dev.spendingtracker.server.db.dbQuery
import dev.spendingtracker.server.household.household
import dev.spendingtracker.server.inbox.lockRows
import dev.spendingtracker.server.inbox.isPending
import dev.spendingtracker.shared.api.ApiError
import dev.spendingtracker.shared.api.MatchOrder
import dev.spendingtracker.shared.api.Order
import dev.spendingtracker.shared.api.OrderEmailInput
import dev.spendingtracker.shared.api.OrderInput
import dev.spendingtracker.shared.api.OrderItemsInput
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.github.smiley4.ktoropenapi.put
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.util.AttributeKey
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.uuid.Uuid

private val ParsersKey = AttributeKey<List<OrderParser>>("OrderParsers")

/** The parsers `POST /orders/email` tries; set by the module. */
var Application.orderParsers: List<OrderParser>
    get() = attributes.getOrNull(ParsersKey) ?: defaultOrderParsers
    set(value) = attributes.put(ParsersKey, value)

/** Must be inside a HouseholdAccess scope. */
fun Route.orderRoutes() = route("/orders") {
    get({
        operationId = "listOrders"
        description = "E-shop orders, newest first, with their items, the row that paid each and, unmatched, the rows it could be."
        request {
            queryParameter<String>("bankRowId") { description = "Only the order this row paid" }
            queryParameter<String>("expenseId") { description = "Only orders whose row became this expense" }
            queryParameter<Boolean>("unmatched") { description = "Only orders no row is matched to" }
        }
        response {
            code(HttpStatusCode.OK) { body<List<Order>>() }
            code(HttpStatusCode.BadRequest) { body<ApiError>() }
        }
    }) {
        val params = call.request.queryParameters
        val rowId = parseId(params["bankRowId"], "bankRowId")
        val expenseId = parseId(params["expenseId"], "expenseId")
        val unmatched = params["unmatched"] == "true"
        val orders = dbQuery {
            var filter: Op<Boolean> = Op.TRUE
            if (rowId != null) filter = filter and ordersOfRows(listOf(rowId))
            if (expenseId != null) filter = filter and ordersOfExpense(expenseId)
            if (unmatched) filter = filter and Orders.bankRowId.isNull()
            loadOrders(call.household.id, filter)
        }
        call.respond(orders)
    }

    post({
        operationId = "saveOrder"
        description = "Adds an order as a parser read it, or replaces the items of the shop's order with that number " +
            "(keeping what was decided about items of the same name), then matches it to the row that paid it."
        request { body<OrderInput>() }
        response {
            code(HttpStatusCode.OK) { body<Order>() }
            code(HttpStatusCode.BadRequest) { body<ApiError>() }
        }
    }) {
        val input = call.receive<OrderInput>().validated()
        call.respond(save(call.household.id, call.household.currency, call.userId, input))
    }

    post("/email", {
        operationId = "readOrderEmail"
        description = "Reads an order from a shop's email (a whole .eml file, or text pasted from a mail app) with the " +
            "first parser that recognises it, then saves and matches it as POST /orders does."
        request { body<OrderEmailInput>() }
        response {
            code(HttpStatusCode.OK) { body<Order>() }
            code(HttpStatusCode.BadRequest) { body<ApiError>() }
            code(HttpStatusCode.UnprocessableEntity) { body<ApiError>() }
        }
    }) {
        val raw = call.receive<OrderEmailInput>().email
        if (raw.isBlank()) throw badRequest("empty_email", "Paste or upload an order email", "email")
        if (raw.length > 5_000_000) throw badRequest("email_too_large", "The email is over 5 MB", "email")
        val email = readOrderEmail(raw)
        val parsed = call.application.orderParsers.firstNotNullOfOrNull { it.parse(email) }
            ?: throw ApiException(
                HttpStatusCode.UnprocessableEntity,
                "unrecognised_email",
                "This doesn't look like an order email from a shop the app can read",
                "email",
            )
        val input = parsed.copy(raw = raw).validated()
        call.respond(save(call.household.id, call.household.currency, call.userId, input))
    }

    route("/{orderId}") {
        put("/items", {
            operationId = "assignOrderItems"
            description = "Sets each item's category and whose (null for joint) and refreshes the draft of the row it matched."
            request { body<OrderItemsInput>() }
            response {
                code(HttpStatusCode.OK) { body<Order>() }
                code(HttpStatusCode.BadRequest) { body<ApiError>() }
                code(HttpStatusCode.NotFound) { body<ApiError>() }
                code(HttpStatusCode.Conflict) { body<ApiError>() }
            }
        }) {
            val householdId = call.household.id
            val orderId = call.pathId("orderId", "Order")
            val input = call.receive<OrderItemsInput>()
            val assignments = input.items.map {
                Triple(
                    parseId(it.itemId, "items")!!,
                    parseId(it.categoryId, "items"),
                    parseId(it.paidByUserId, "items"),
                )
            }
            val order = dbQuery {
                lockOrder(householdId, orderId, input.version)
                assignItems(householdId, orderId, assignments)
                bumpVersion(orderId)
                loadOrders(householdId, Orders.id eq orderId).single()
            }
            call.respond(order)
        }

        post("/match", {
            operationId = "matchOrder"
            description = "Matches the order to a pending row by hand (filling its draft), or with a null row unmatches it; " +
                "either way the matcher then leaves it alone."
            request { body<MatchOrder>() }
            response {
                code(HttpStatusCode.OK) { body<Order>() }
                code(HttpStatusCode.BadRequest) { body<ApiError>() }
                code(HttpStatusCode.NotFound) { body<ApiError>() }
                code(HttpStatusCode.Conflict) { body<ApiError>() }
            }
        }) {
            val household = call.household
            val orderId = call.pathId("orderId", "Order")
            val input = call.receive<MatchOrder>()
            val order = dbQuery {
                val current = lockOrder(household.id, orderId, input.version)
                val rowId = input.row?.let { ref ->
                    val row = lockRows(household.id, listOf(ref), "row").single()
                    if (!row.isPending()) throw conflict("already_decided", "The bank row was already decided. Reload the inbox.", "row")
                    val other = Orders.selectAll().where { (Orders.bankRowId eq row[BankRows.id]) }.singleOrNull()
                    if (other != null && other[Orders.id] != orderId) {
                        throw conflict("row_has_order", "Another order is matched to this row", "row")
                    }
                    row[BankRows.id]
                }
                if (current[Orders.bankRowId] != rowId) clearDraft(current)
                Orders.update({ Orders.id eq orderId }) {
                    it[bankRowId] = rowId
                    it[matchedByHand] = true
                    it[version] = Orders.version + 1
                    it[updatedAt] = Clock.System.now()
                }
                refreshDraft(household.id, orderId, household.currency)
                loadOrders(household.id, Orders.id eq orderId).single()
            }
            call.respond(order)
        }

        delete({
            operationId = "deleteOrder"
            description = "Deletes the order and clears the draft it made on its row, if the row is still pending."
            request { queryParameter<Int>("version") { required = true } }
            response {
                code(HttpStatusCode.NoContent) { }
                code(HttpStatusCode.NotFound) { body<ApiError>() }
                code(HttpStatusCode.Conflict) { body<ApiError>() }
            }
        }) {
            val householdId = call.household.id
            val orderId = call.pathId("orderId", "Order")
            val version = call.versionParam()
            dbQuery {
                val current = lockOrder(householdId, orderId, version)
                clearDraft(current)
                Orders.deleteWhere { Orders.id eq orderId }
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private suspend fun save(householdId: Uuid, currency: String, userId: Uuid, input: OrderInput): Order = dbQuery {
    val orderId = saveOrder(householdId, userId, input)
    matchOrders(householdId, currency)
    loadOrders(householdId, Orders.id eq orderId).single()
}

private fun lockOrder(householdId: Uuid, orderId: Uuid, version: Int) =
    Orders.selectAll().where { (Orders.id eq orderId) and (Orders.householdId eq householdId) }.forUpdate().singleOrNull()
        ?.also { if (it[Orders.version] != version) throw versionConflict() }
        ?: throw notFound("Order")

private fun bumpVersion(orderId: Uuid) {
    Orders.update({ Orders.id eq orderId }) {
        it[version] = Orders.version + 1
        it[updatedAt] = Clock.System.now()
    }
}
