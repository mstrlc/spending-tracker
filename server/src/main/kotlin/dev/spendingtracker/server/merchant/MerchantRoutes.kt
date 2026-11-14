package dev.spendingtracker.server.merchant

import dev.spendingtracker.server.auth.userId
import dev.spendingtracker.server.common.badRequest
import dev.spendingtracker.server.common.checkVersioned
import dev.spendingtracker.server.common.conflict
import dev.spendingtracker.server.common.notFound
import dev.spendingtracker.server.common.parseId
import dev.spendingtracker.server.common.pathId
import dev.spendingtracker.server.common.requireVersion
import dev.spendingtracker.server.common.requiredText
import dev.spendingtracker.server.common.versionConflict
import dev.spendingtracker.server.common.versionParam
import dev.spendingtracker.server.db.Categories
import dev.spendingtracker.server.db.Expenses
import dev.spendingtracker.server.db.Merchants
import dev.spendingtracker.server.db.dbQuery
import dev.spendingtracker.server.db.isForeignKeyViolation
import dev.spendingtracker.server.db.isUniqueViolation
import dev.spendingtracker.server.household.household
import dev.spendingtracker.shared.api.ApiError
import dev.spendingtracker.shared.api.Merchant
import dev.spendingtracker.shared.api.MerchantInput
import dev.spendingtracker.shared.api.MerchantMerge
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.github.smiley4.ktoropenapi.put
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import org.jetbrains.exposed.v1.core.LowerCase
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.stringParam
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Must be inside a HouseholdAccess scope. */
fun Route.merchantRoutes() = route("/merchants") {
    get({
        operationId = "listMerchants"
        description = "The household's merchants by name, each with what its expenses add up to and when it was last used."
        response { code(HttpStatusCode.OK) { body<List<Merchant>>() } }
    }) {
        val merchants = dbQuery { loadMerchants(call.household.id) }
        call.respond(merchants)
    }

    post({
        operationId = "createMerchant"
        request { body<MerchantInput>() }
        response {
            code(HttpStatusCode.Created) { body<Merchant>() }
            code(HttpStatusCode.BadRequest) { body<ApiError>() }
            code(HttpStatusCode.Conflict) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val input = call.receive<MerchantInput>().validated()
        val id = Uuid.random()
        val merchant = uniqueName {
            dbQuery {
                input.checkCategory(householdId)
                Merchants.insert {
                    it[Merchants.id] = id
                    it[Merchants.householdId] = householdId
                    it[name] = input.name
                    it[defaultCategoryId] = input.defaultCategoryId
                    it[createdBy] = call.userId
                }
                loadMerchants(householdId, id).single()
            }
        }
        call.respond(HttpStatusCode.Created, merchant)
    }

    put("/{merchantId}", {
        operationId = "updateMerchant"
        description = "Renaming a merchant renames it on all its expenses. A name another merchant has is a 409 name_taken: merge them instead."
        request {
            pathParameter<String>("merchantId")
            body<MerchantInput>()
        }
        response {
            code(HttpStatusCode.OK) { body<Merchant>() }
            code(HttpStatusCode.BadRequest) { body<ApiError>() }
            code(HttpStatusCode.NotFound) { body<ApiError>() }
            code(HttpStatusCode.Conflict) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val id = call.pathId("merchantId", "Merchant")
        val raw = call.receive<MerchantInput>()
        val version = requireVersion(raw.version)
        val input = raw.validated()
        val merchant = uniqueName {
            dbQuery {
                input.checkCategory(householdId)
                val affected = Merchants.update({ match(householdId, id) and (Merchants.version eq version) }) {
                    it[name] = input.name
                    it[defaultCategoryId] = input.defaultCategoryId
                    it[Merchants.version] = version + 1
                    it[updatedAt] = Clock.System.now()
                }
                checkVersioned(affected, { exists(householdId, id) }, "Merchant")
                loadMerchants(householdId, id).single()
            }
        }
        call.respond(merchant)
    }

    post("/{merchantId}/merge", {
        operationId = "mergeMerchant"
        description = "Moves every expense of this merchant to intoId and deletes it, for two spellings of one place. " +
            "The merchant merged into keeps its name, and takes this one's default category if it has none."
        request {
            pathParameter<String>("merchantId")
            body<MerchantMerge>()
        }
        response {
            code(HttpStatusCode.OK) { body<Merchant>() }
            code(HttpStatusCode.BadRequest) { body<ApiError>() }
            code(HttpStatusCode.NotFound) { body<ApiError>() }
            code(HttpStatusCode.Conflict) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val id = call.pathId("merchantId", "Merchant")
        val body = call.receive<MerchantMerge>()
        val intoId = parseId(body.intoId, "intoId")!!
        if (intoId == id) throw badRequest("invalid_merge", "Pick another merchant to merge into", "intoId")
        val merged = dbQuery {
            val source = Merchants.selectAll().where { match(householdId, id) }.forUpdate().singleOrNull() ?: throw notFound("Merchant")
            if (source[Merchants.version] != body.version) {
                throw versionConflict()
            }
            val target = Merchants.selectAll().where { match(householdId, intoId) }.forUpdate().singleOrNull()
                ?: throw badRequest("invalid_merchant", "Unknown merchant", "intoId")
            val now = Clock.System.now()
            // Each expense keeps its version bump, so an editor open on one sees it moved.
            Expenses.update({ (Expenses.householdId eq householdId) and (Expenses.merchantId eq id) }) {
                it[merchantId] = intoId
                it[Expenses.version] = Expenses.version + 1
                it[Expenses.updatedAt] = now
            }
            if (target[Merchants.defaultCategoryId] == null && source[Merchants.defaultCategoryId] != null) {
                Merchants.update({ match(householdId, intoId) }) {
                    it[defaultCategoryId] = source[Merchants.defaultCategoryId]
                    it[version] = target[Merchants.version] + 1
                    it[updatedAt] = now
                }
            }
            Merchants.deleteWhere { match(householdId, id) }
            loadMerchants(householdId, intoId).single()
        }
        call.respond(merged)
    }

    delete("/{merchantId}", {
        operationId = "deleteMerchant"
        description = "Fails with 409 merchant_in_use while expenses use the merchant; merge it into another instead."
        request {
            pathParameter<String>("merchantId")
            queryParameter<Int>("version") { required = true }
        }
        response {
            code(HttpStatusCode.NoContent) {}
            code(HttpStatusCode.NotFound) { body<ApiError>() }
            code(HttpStatusCode.Conflict) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val id = call.pathId("merchantId", "Merchant")
        val version = call.versionParam()
        try {
            dbQuery {
                val affected = Merchants.deleteWhere { match(householdId, id) and (Merchants.version eq version) }
                checkVersioned(affected, { exists(householdId, id) }, "Merchant")
            }
        } catch (e: Exception) {
            if (e.isForeignKeyViolation()) {
                throw conflict("merchant_in_use", "Expenses use this merchant. Merge it into another one instead.")
            }
            throw e
        }
        call.respond(HttpStatusCode.NoContent)
    }
}

/**
 * The household's merchant called [name] (ignoring case), created filed under [categoryId] if there is none;
 * null for a blank name. Call inside a transaction.
 */
fun resolveMerchant(householdId: Uuid, name: String, categoryId: Uuid?, userId: Uuid, importId: Uuid? = null): Uuid? {
    if (name.isBlank()) return null
    findByName(householdId, name)?.let { return it }
    val id = Uuid.random()
    Merchants.insert {
        it[Merchants.id] = id
        it[Merchants.householdId] = householdId
        it[Merchants.name] = name
        it[defaultCategoryId] = categoryId
        it[Merchants.importId] = importId
        it[createdBy] = userId
    }
    return id
}

/** Current names of the household's merchants. Call inside a transaction. */
fun merchantNames(householdId: Uuid): Map<Uuid, String> =
    Merchants.select(Merchants.id, Merchants.name).where { Merchants.householdId eq householdId }
        .associate { it[Merchants.id] to it[Merchants.name] }

private fun findByName(householdId: Uuid, name: String): Uuid? =
    Merchants.select(Merchants.id)
        .where { (Merchants.householdId eq householdId) and (Merchants.name.lowerCase() eq LowerCase(stringParam(name))) }
        .singleOrNull()?.get(Merchants.id)

private data class ValidMerchant(val name: String, val defaultCategoryId: Uuid?)

private fun MerchantInput.validated() =
    ValidMerchant(requiredText(name, "name", max = 200), parseId(defaultCategoryId, "defaultCategoryId"))

private fun ValidMerchant.checkCategory(householdId: Uuid) {
    if (defaultCategoryId != null &&
        Categories.selectAll().where { (Categories.id eq defaultCategoryId) and (Categories.householdId eq householdId) }.empty()
    ) {
        throw badRequest("invalid_category", "Unknown category", "defaultCategoryId")
    }
}

/** The household's merchants (or just [id]) with their expense totals. Call inside a transaction. */
private fun loadMerchants(householdId: Uuid, id: Uuid? = null): List<Merchant> {
    val spent = Expenses.amountMinor.sum()
    val count = Expenses.id.count()
    val last = Expenses.date.max()
    var expenseFilter = (Expenses.householdId eq householdId) and Expenses.merchantId.isNotNull()
    if (id != null) expenseFilter = expenseFilter and (Expenses.merchantId eq id)
    val totals = Expenses.select(Expenses.merchantId, spent, count, last)
        .where(expenseFilter)
        .groupBy(Expenses.merchantId)
        .associateBy { it[Expenses.merchantId]!! }
    var filter = Merchants.householdId eq householdId
    if (id != null) filter = filter and (Merchants.id eq id)
    return Merchants.selectAll().where(filter)
        .orderBy(Merchants.name.lowerCase() to SortOrder.ASC)
        .map { row ->
            val total = totals[row[Merchants.id]]
            row.toMerchant(total?.get(spent) ?: 0L, total?.get(count)?.toInt() ?: 0, total?.get(last))
        }
}

private fun ResultRow.toMerchant(spentMinor: Long, expenseCount: Int, lastDate: kotlinx.datetime.LocalDate?) = Merchant(
    id = this[Merchants.id].toString(),
    name = this[Merchants.name],
    defaultCategoryId = this[Merchants.defaultCategoryId]?.toString(),
    version = this[Merchants.version],
    spentMinor = spentMinor,
    expenseCount = expenseCount,
    lastDate = lastDate,
)

private suspend fun <T> uniqueName(block: suspend () -> T): T =
    try {
        block()
    } catch (e: Exception) {
        if (e.isUniqueViolation()) {
            throw conflict("name_taken", "A merchant with this name already exists. Merge the two instead.", "name")
        }
        throw e
    }

private fun match(householdId: Uuid, id: Uuid) = (Merchants.id eq id) and (Merchants.householdId eq householdId)

private fun exists(householdId: Uuid, id: Uuid) = !Merchants.selectAll().where { match(householdId, id) }.empty()
