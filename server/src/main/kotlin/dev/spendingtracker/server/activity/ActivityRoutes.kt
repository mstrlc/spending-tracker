package dev.spendingtracker.server.activity

import dev.spendingtracker.server.common.badRequest
import dev.spendingtracker.server.common.parseId
import dev.spendingtracker.server.db.AuditLog
import dev.spendingtracker.server.db.Users
import dev.spendingtracker.server.db.dbQuery
import dev.spendingtracker.server.household.household
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
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

private const val DEFAULT_LIMIT = 50
private const val MAX_LIMIT = 200

// Bookkeeping columns: they change with every write or never, and say nothing a person would look for.
private val HIDDEN_FIELDS = setOf(
    "id", "household_id", "user_id", "version", "created_at", "updated_at", "joined_at",
    "created_by", "external_ref", "invited_by", "accepted_by",
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
            if (entity != null) filter = filter and (AuditLog.entity eq entity.dbValue())
            if (entityId != null) filter = filter and (AuditLog.entityId eq entityId)
            // One extra row tells whether there is another page.
            val rows = AuditLog.selectAll().where(filter).orderBy(AuditLog.id to SortOrder.DESC).limit(limit + 1).toList()
            val shown = rows.take(limit)
            val names = userNames(shown)
            ActivityPage(
                entries = shown.map { it.toEntry(names) },
                nextBefore = if (rows.size > limit) shown.last()[AuditLog.id] else null,
            )
        }
        call.respond(page)
    }
}

fun ActivityEntity.dbValue() = name.lowercase()

/** Display names of everyone an entry mentions: the actors, and the users that member entries are about. */
private fun userNames(rows: List<ResultRow>): Map<Uuid, String> {
    val ids = rows.flatMap { row ->
        listOfNotNull(row[AuditLog.actorUserId], row[AuditLog.entityId].takeIf { row[AuditLog.entity] == "member" })
    }.distinct()
    if (ids.isEmpty()) return emptyMap()
    return Users.selectAll().where { Users.id inList ids }.associate { it[Users.id] to it[Users.displayName] }
}

private fun ResultRow.toEntry(names: Map<Uuid, String>): ActivityEntry {
    val entity = ActivityEntity.entries.first { it.dbValue() == this[AuditLog.entity] }
    val before = this[AuditLog.before]?.let(::parseRow)
    val after = this[AuditLog.after]?.let(::parseRow)
    val current = after ?: before.orEmpty()
    val label = when (entity) {
        ActivityEntity.EXPENSE -> current.text("merchant")
        ActivityEntity.INCOME -> current.text("source")
        ActivityEntity.INVITE -> current.text("email")
        ActivityEntity.MEMBER -> names[this[AuditLog.entityId]]
        ActivityEntity.HOUSEHOLD, ActivityEntity.ACCOUNT, ActivityEntity.CATEGORY -> current.text("name")
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
    "name", "date", "amount_minor", "merchant", "source", "email", "role", "category_id", "parent_id", "kind",
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
