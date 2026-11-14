package dev.spendingtracker.server.activity

import dev.spendingtracker.server.common.badRequest
import dev.spendingtracker.server.common.parseId
import dev.spendingtracker.server.db.AuditLog
import dev.spendingtracker.server.db.Assets
import dev.spendingtracker.server.db.Expenses
import dev.spendingtracker.server.db.Users
import dev.spendingtracker.server.db.dbQuery
import dev.spendingtracker.server.household.household
import dev.spendingtracker.server.merchant.merchantNames
import dev.spendingtracker.shared.api.ActivityAction
import dev.spendingtracker.shared.api.ActivityEntity
import dev.spendingtracker.shared.api.ActivityEntry
import dev.spendingtracker.shared.api.ActivityPage
import dev.spendingtracker.shared.api.ApiError
import dev.spendingtracker.shared.api.FieldChange
import io.github.smiley4.ktoropenapi.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

private const val DEFAULT_LIMIT = 50
private const val MAX_LIMIT = 200

// Bookkeeping columns: they change with every write or never, and say nothing a person would look for.
private val HIDDEN_FIELDS = setOf(
    "id", "household_id", "user_id", "version", "created_at", "updated_at", "joined_at",
    "created_by", "external_ref", "invited_by", "accepted_by", "asset_id", "rule_id", "expense_id",
)

/** Must be inside a HouseholdAccess scope. */
fun Route.activityRoutes() = route("/activity") {
    get({
        operationId = "listActivity"
        description = "The household's audit log, newest first: every create, update and delete, and who made it. " +
            "Narrow it to one record with entity and entityId."
        request {
            queryParameter<Long>("before") { description = "Only entries older than this id (nextBefore of the previous page)" }
            queryParameter<Int>("limit") { description = "Page size, 1–$MAX_LIMIT (default $DEFAULT_LIMIT)" }
            queryParameter<ActivityEntity>("entity")
            queryParameter<String>("entityId")
        }
        response {
            code(HttpStatusCode.OK) { body<ActivityPage>() }
            code(HttpStatusCode.BadRequest) { body<ApiError>() }
        }
    }) {
        val params = call.request.queryParameters
        val before = params["before"]?.let { it.toLongOrNull() ?: throw badRequest("invalid_before", "before must be a number", "before") }
        val limit = params["limit"]?.let {
            it.toIntOrNull()?.takeIf { n -> n in 1..MAX_LIMIT } ?: throw badRequest("invalid_limit", "limit must be 1–$MAX_LIMIT", "limit")
        } ?: DEFAULT_LIMIT
        val entity = params["entity"]?.let { value ->
            ActivityEntity.entries.find { it.dbValue() == value } ?: throw badRequest("invalid_entity", "Unknown entity", "entity")
        }
        val entityId = parseId(params["entityId"], "entityId")

        val householdId = call.household.id
        val page = dbQuery {
            var filter: Op<Boolean> = AuditLog.householdId eq householdId
            if (before != null) filter = filter and (AuditLog.id less before)
            filter = filter and if (entity != null) {
                AuditLog.entity eq entity.dbValue()
            } else {
                // Rows written by a newer build (a kind this one doesn't know yet) are left out rather than failing the page.
                AuditLog.entity inList ActivityEntity.entries.map { it.dbValue() }
            }
            if (entityId != null) filter = filter and (AuditLog.entityId eq entityId)
            // One extra row tells whether there is another page.
            val rows = AuditLog.selectAll().where(filter).orderBy(AuditLog.id to SortOrder.DESC).limit(limit + 1).toList()
            val shown = rows.take(limit)
            val names = userNames(shown)
            val holdings = assetNames(shown)
            val merchants = merchantNames(householdId)
            val expenseMerchants = expenseMerchants(shown)
            ActivityPage(
                entries = shown.map { it.toEntry(names, holdings, merchants, expenseMerchants) },
                nextBefore = if (rows.size > limit) shown.last()[AuditLog.id] else null,
            )
        }
        call.respond(page)
    }
}

fun ActivityEntity.dbValue() = name.lowercase()

/**
 * Display names of everyone an entry mentions: the actors, the users that member entries are about, the member a
 * split share belongs to and the two sides of a settlement.
 */
private fun userNames(rows: List<ResultRow>): Map<Uuid, String> {
    val ids = rows.flatMap { row ->
        val record = when (row[AuditLog.entity]) {
            "split_share", "settlement" -> (row[AuditLog.after] ?: row[AuditLog.before])?.let(::parseRow).orEmpty()
            else -> emptyMap()
        }
        listOfNotNull(row[AuditLog.actorUserId], row[AuditLog.entityId].takeIf { row[AuditLog.entity] == "member" }) +
            listOf("user_id", "from_user_id", "to_user_id").mapNotNull { record.text(it)?.let(Uuid::parse) }
    }.distinct()
    if (ids.isEmpty()) return emptyMap()
    return Users.selectAll().where { Users.id inList ids }.associate { it[Users.id] to it[Users.displayName] }
}

/** Current names of the holdings that contribution and valuation entries belong to; deleted ones are missing. */
private fun assetNames(rows: List<ResultRow>): Map<String, String> {
    val ids = rows.filter { it[AuditLog.entity].startsWith("asset_") }
        .mapNotNull { row -> (row[AuditLog.after] ?: row[AuditLog.before])?.let(::parseRow)?.text("asset_id") }
        .distinct()
        .map(Uuid::parse)
    if (ids.isEmpty()) return emptyMap()
    return Assets.selectAll().where { Assets.id inList ids }.associate { it[Assets.id].toString() to it[Assets.name] }
}

/** The current merchant of each expense the entries are about, for entries whose own merchant was merged away. */
private fun expenseMerchants(rows: List<ResultRow>): Map<Uuid, Uuid> {
    val ids = rows.mapNotNull { row ->
        when (row[AuditLog.entity]) {
            "expense" -> row[AuditLog.entityId]
            "expense_line" -> (row[AuditLog.after] ?: row[AuditLog.before])?.let(::parseRow)?.text("expense_id")?.let(Uuid::parse)
            else -> null
        }
    }.distinct()
    if (ids.isEmpty()) return emptyMap()
    return Expenses.select(Expenses.id, Expenses.merchantId).where { Expenses.id inList ids }
        .mapNotNull { row -> row[Expenses.merchantId]?.let { row[Expenses.id] to it } }
        .toMap()
}

private fun ResultRow.toEntry(
    names: Map<Uuid, String>,
    holdings: Map<String, String>,
    merchants: Map<Uuid, String>,
    expenseMerchants: Map<Uuid, Uuid>,
): ActivityEntry {
    val entity = ActivityEntity.entries.first { it.dbValue() == this[AuditLog.entity] }
    val before = this[AuditLog.before]?.let(::parseRow)
    val after = this[AuditLog.after]?.let(::parseRow)
    val current = after ?: before.orEmpty()
    val label = when (entity) {
        // The merchant the entry recorded, or the one it was merged into; entries from before merchants were
        // records (V15) carry the name itself.
        ActivityEntity.EXPENSE -> current.text("merchant_id")?.let(Uuid::parse)
            ?.let { merchants[it] ?: expenseMerchants[this[AuditLog.entityId]]?.let(merchants::get) }
            ?: current.text("merchant")
        ActivityEntity.EXPENSE_LINE -> current.text("notes")?.takeIf { it.isNotBlank() }
            ?: current.text("expense_id")?.let(Uuid::parse)?.let { expenseMerchants[it]?.let(merchants::get) }
        ActivityEntity.INCOME -> current.text("source")
        ActivityEntity.INVITE -> current.text("email")
        ActivityEntity.MEMBER -> names[this[AuditLog.entityId]]
        ActivityEntity.ASSET_CONTRIBUTION, ActivityEntity.ASSET_VALUATION, ActivityEntity.ASSET_FEE ->
            current.text("asset_id")?.let(holdings::get)
        ActivityEntity.SPLIT_RULE -> when {
            current.text("expense_id") != null -> "an expense's split"
            current.text("category_id") != null -> "a category's split"
            else -> "the household split"
        }
        ActivityEntity.SPLIT_SHARE -> current.text("user_id")?.let(Uuid::parse)?.let(names::get)
        ActivityEntity.SETTLEMENT -> listOfNotNull(
            current.text("from_user_id")?.let(Uuid::parse)?.let(names::get),
            current.text("to_user_id")?.let(Uuid::parse)?.let(names::get),
        ).joinToString(" → ")
        ActivityEntity.BANK_ROW -> current.text("text")?.takeIf { it.isNotBlank() }
            ?: current.text("counterparty_name")?.takeIf { it.isNotBlank() }
            ?: current.text("message")?.takeIf { it.isNotBlank() }
        ActivityEntity.HOUSEHOLD, ActivityEntity.ACCOUNT, ActivityEntity.CATEGORY, ActivityEntity.ASSET,
        ActivityEntity.TRIP, ActivityEntity.MERCHANT -> current.text("name")
    }
    val actor = this[AuditLog.actorUserId]
    return ActivityEntry(
        id = this[AuditLog.id],
        at = this[AuditLog.at].toString(),
        actorUserId = actor?.toString(),
        actorName = actor?.let(names::get),
        entity = entity,
        entityId = this[AuditLog.entityId].toString(),
        action = ActivityAction.entries.first { it.name.lowercase() == this[AuditLog.action] },
        label = label.orEmpty(),
        changes = changes(before, after),
    )
}

private fun parseRow(json: String): Map<String, JsonElement> = Json.parseToJsonElement(json).jsonObject

private fun Map<String, JsonElement>.text(key: String): String? = this[key]?.asText()

private fun JsonElement.asText(): String? = when (this) {
    JsonNull -> null
    is JsonPrimitive -> content
    else -> toString()
}

// jsonb keeps keys in its own order; this is the order a person reads a record in. Unlisted fields go last.
private val FIELD_ORDER = listOf(
    "name", "symbol", "date", "amount_minor", "quantity", "value_minor", "merchant_id", "merchant", "source", "email", "role", "category_id", "default_category_id", "parent_id", "kind",
    "valid_from", "from_user_id", "to_user_id", "weight",
    "account_id", "paid_by_user_id", "recipient_user_id", "owner_user_id", "currency", "status", "archived",
    "sort_order", "notes", "expires_at", "accepted_at",
)

/** Visible fields in reading order; for updates, only those whose value moved. */
private fun changes(before: Map<String, JsonElement>?, after: Map<String, JsonElement>?): List<FieldChange> {
    val keys = (after.orEmpty().keys + before.orEmpty().keys)
        .filter { it !in HIDDEN_FIELDS }
        .sortedBy { FIELD_ORDER.indexOf(it).takeIf { i -> i >= 0 } ?: FIELD_ORDER.size }
    return keys.mapNotNull { key ->
        val old = before?.get(key)?.asText()
        val new = after?.get(key)?.asText()
        if (before != null && after != null && old == new) null else FieldChange(camelCase(key), old, new)
    }
}

private fun camelCase(column: String): String =
    column.split('_').mapIndexed { i, part -> if (i == 0) part else part.replaceFirstChar(Char::uppercase) }.joinToString("")
