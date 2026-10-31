package dev.spendingtracker.server.category

import dev.spendingtracker.server.common.badRequest
import dev.spendingtracker.server.common.checkVersioned
import dev.spendingtracker.server.common.conflict
import dev.spendingtracker.server.common.parseId
import dev.spendingtracker.server.common.pathId
import dev.spendingtracker.server.common.requireVersion
import dev.spendingtracker.server.common.requiredText
import dev.spendingtracker.server.common.versionParam
import dev.spendingtracker.server.db.Categories
import dev.spendingtracker.server.db.dbQuery
import dev.spendingtracker.server.db.isForeignKeyViolation
import dev.spendingtracker.server.db.isUniqueViolation
import dev.spendingtracker.server.household.household
import dev.spendingtracker.shared.api.ApiError
import dev.spendingtracker.shared.api.Category
import dev.spendingtracker.shared.api.CategoryInput
import dev.spendingtracker.shared.api.CategoryKind
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.github.smiley4.ktoropenapi.put
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.uuid.Uuid

private val DEFAULT_CATEGORIES = listOf(
    "Housing", "Groceries", "Eating out", "Transport", "Health", "Shopping",
    "Leisure", "Travel", "Subscriptions", "Gifts", "Other",
).map { it to CategoryKind.CONSUMPTION } + ("Investments" to CategoryKind.INVESTMENT)

/** Gives a new household a starter set of top-level categories. Call inside a transaction. */
fun seedDefaultCategories(householdId: Uuid) {
    Categories.batchInsert(DEFAULT_CATEGORIES.withIndex()) { (index, category) ->
        this[Categories.id] = Uuid.random()
        this[Categories.householdId] = householdId
        this[Categories.name] = category.first
        this[Categories.kind] = category.second.dbValue
        this[Categories.sortOrder] = index
        this[Categories.archived] = false
        this[Categories.version] = 1
    }
}

/** Must be inside a HouseholdAccess scope. */
fun Route.categoryRoutes() = route("/categories") {
    get({
        operationId = "listCategories"
        description = "All categories, archived ones included, as a flat list (subcategories carry parentId)."
        response { code(HttpStatusCode.OK) { body<List<Category>>() } }
    }) {
        val categories = dbQuery {
            Categories.selectAll()
                .where { Categories.householdId eq call.household.id }
                .orderBy(Categories.sortOrder to SortOrder.ASC, Categories.name to SortOrder.ASC)
                .map { it.toCategory() }
        }
        call.respond(categories)
    }

    post({
        operationId = "createCategory"
        request { body<CategoryInput>() }
        response {
            code(HttpStatusCode.Created) { body<Category>() }
            code(HttpStatusCode.BadRequest) { body<ApiError>() }
            code(HttpStatusCode.Conflict) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val input = call.receive<CategoryInput>()
        val name = requiredText(input.name, "name", max = 100)
        val parentId = parseId(input.parentId, "parentId")
        val id = Uuid.random()
        val category = uniqueName {
            dbQuery {
                val kind = parentId?.let { parentKind(householdId, it) } ?: input.kind
                Categories.insert {
                    it[Categories.id] = id
                    it[Categories.householdId] = householdId
                    it[Categories.parentId] = parentId
                    it[Categories.name] = name
                    it[Categories.kind] = kind.dbValue
                    it[archived] = input.archived
                    it[sortOrder] = input.sortOrder
                    it[version] = 1
                }
                Categories.selectAll().where { Categories.id eq id }.single().toCategory()
            }
        }
        call.respond(HttpStatusCode.Created, category)
    }

    put("/{categoryId}", {
        operationId = "updateCategory"
        description = "Changing a top-level category's kind also changes its subcategories."
        request {
            pathParameter<String>("categoryId")
            body<CategoryInput>()
        }
        response {
            code(HttpStatusCode.OK) { body<Category>() }
            code(HttpStatusCode.BadRequest) { body<ApiError>() }
            code(HttpStatusCode.NotFound) { body<ApiError>() }
            code(HttpStatusCode.Conflict) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val id = call.pathId("categoryId", "Category")
        val input = call.receive<CategoryInput>()
        val version = requireVersion(input.version)
        val name = requiredText(input.name, "name", max = 100)
        val parentId = parseId(input.parentId, "parentId")
        if (parentId == id) throw badRequest("invalid_parent", "A category can't be its own parent", "parentId")
        val category = uniqueName {
            dbQuery {
                val kind = if (parentId != null) {
                    if (hasChildren(householdId, id)) {
                        throw badRequest("has_children", "A category with subcategories can't become a subcategory", "parentId")
                    }
                    parentKind(householdId, parentId)
                } else {
                    input.kind
                }
                val now = Clock.System.now()
                val affected = Categories.update({ match(householdId, id) and (Categories.version eq version) }) {
                    it[Categories.parentId] = parentId
                    it[Categories.name] = name
                    it[Categories.kind] = kind.dbValue
                    it[archived] = input.archived
                    it[sortOrder] = input.sortOrder
                    it[Categories.version] = version + 1
                    it[updatedAt] = now
                }
                checkVersioned(affected, { exists(householdId, id) }, "Category")
                if (parentId == null) {
                    // Subcategories inherit kind; bump their version so stale edits of them are caught.
                    Categories.update({ (Categories.householdId eq householdId) and (Categories.parentId eq id) }) {
                        it[Categories.kind] = kind.dbValue
                        it[Categories.version] = Categories.version + 1
                        it[updatedAt] = now
                    }
                }
                Categories.selectAll().where { Categories.id eq id }.single().toCategory()
            }
        }
        call.respond(category)
    }

    delete("/{categoryId}", {
        operationId = "deleteCategory"
        description = "Fails with 409 category_in_use while expenses or subcategories reference it; archive it instead."
        request {
            pathParameter<String>("categoryId")
            queryParameter<Int>("version") { required = true }
        }
        response {
            code(HttpStatusCode.NoContent) {}
            code(HttpStatusCode.NotFound) { body<ApiError>() }
            code(HttpStatusCode.Conflict) { body<ApiError>() }
        }
    }) {
        val householdId = call.household.id
        val id = call.pathId("categoryId", "Category")
        val version = call.versionParam()
        try {
            dbQuery {
                val affected = Categories.deleteWhere { match(householdId, id) and (Categories.version eq version) }
                checkVersioned(affected, { exists(householdId, id) }, "Category")
            }
        } catch (e: Exception) {
            if (e.isForeignKeyViolation()) {
                throw conflict("category_in_use", "Expenses or subcategories use this category. Archive it instead.")
            }
            throw e
        }
        call.respond(HttpStatusCode.NoContent)
    }
}

/** The kind a subcategory of [parentId] inherits. The parent must be a top-level category of this household. */
private fun parentKind(householdId: Uuid, parentId: Uuid): CategoryKind {
    val parent = Categories.selectAll().where { match(householdId, parentId) }.singleOrNull()
    if (parent == null || parent[Categories.parentId] != null) {
        throw badRequest("invalid_parent", "Parent must be a top-level category", "parentId")
    }
    return kindFrom(parent[Categories.kind])
}

private fun hasChildren(householdId: Uuid, id: Uuid) =
    !Categories.selectAll().where { (Categories.householdId eq householdId) and (Categories.parentId eq id) }.empty()

private suspend fun <T> uniqueName(block: suspend () -> T): T =
    try {
        block()
    } catch (e: Exception) {
        if (e.isUniqueViolation()) throw conflict("name_taken", "A category with this name already exists here", "name")
        throw e
    }

private fun match(householdId: Uuid, id: Uuid) = (Categories.id eq id) and (Categories.householdId eq householdId)

private fun exists(householdId: Uuid, id: Uuid) = !Categories.selectAll().where { match(householdId, id) }.empty()

private val CategoryKind.dbValue get() = if (this == CategoryKind.INVESTMENT) "investment" else "consumption"

private fun kindFrom(value: String) = if (value == "investment") CategoryKind.INVESTMENT else CategoryKind.CONSUMPTION

private fun ResultRow.toCategory() = Category(
    id = this[Categories.id].toString(),
    parentId = this[Categories.parentId]?.toString(),
    name = this[Categories.name],
    kind = kindFrom(this[Categories.kind]),
    archived = this[Categories.archived],
    sortOrder = this[Categories.sortOrder],
    version = this[Categories.version],
)
