package dev.spendingtracker.server

import dev.spendingtracker.shared.api.Category
import dev.spendingtracker.shared.api.CategoryInput
import dev.spendingtracker.shared.api.CategoryKind
import dev.spendingtracker.shared.api.ExpenseInput
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class CategoryTest {
    private suspend fun HttpClient.create(base: String, input: CategoryInput) =
        post(base) { setBody(input) }.body<Category>()

    @Test
    fun subcategoriesInheritKindAndFollowParentChanges() = apiTest {
        val client = newClient().also { it.signup() }
        val base = "/api/households/${client.createHousehold().id}/categories"
        val savings = client.create(base, CategoryInput("Savings", kind = CategoryKind.INVESTMENT))

        val etf = client.create(base, CategoryInput("ETF", parentId = savings.id, kind = CategoryKind.CONSUMPTION))
        assertEquals(CategoryKind.INVESTMENT, etf.kind)

        client.put("$base/${savings.id}") { setBody(CategoryInput("Savings", kind = CategoryKind.CONSUMPTION, version = 1)) }
        val reloaded = client.get(base).body<List<Category>>().single { it.id == etf.id }
        assertEquals(CategoryKind.CONSUMPTION, reloaded.kind)
        assertEquals(2, reloaded.version)
    }

    @Test
    fun treeIsAtMostTwoLevels() = apiTest {
        val client = newClient().also { it.signup() }
        val base = "/api/households/${client.createHousehold().id}/categories"
        val food = client.create(base, CategoryInput("Food"))
        val snacks = client.create(base, CategoryInput("Snacks", parentId = food.id))
        val drinks = client.create(base, CategoryInput("Drinks"))

        client.post(base) { setBody(CategoryInput("Chips", parentId = snacks.id)) }
            .assertError(HttpStatusCode.BadRequest, "invalid_parent")
        client.put("$base/${food.id}") { setBody(CategoryInput("Food", parentId = drinks.id, version = 1)) }
            .assertError(HttpStatusCode.BadRequest, "has_children")
        client.put("$base/${food.id}") { setBody(CategoryInput("Food", parentId = food.id, version = 1)) }
            .assertError(HttpStatusCode.BadRequest, "invalid_parent")
    }

    @Test
    fun siblingNamesAreUniqueIncludingTopLevel() = apiTest {
        val client = newClient().also { it.signup() }
        val base = "/api/households/${client.createHousehold().id}/categories"
        val food = client.create(base, CategoryInput("Food"))
        client.create(base, CategoryInput("Other", parentId = food.id)) // "Other" also exists at top level: fine

        client.post(base) { setBody(CategoryInput("Food")) }.assertError(HttpStatusCode.Conflict, "name_taken")
        client.post(base) { setBody(CategoryInput("Other", parentId = food.id)) }
            .assertError(HttpStatusCode.Conflict, "name_taken")
    }

    @Test
    fun categoryInUseCannotBeDeletedButUnusedCan() = apiTest {
        val client = newClient().also { it.signup() }
        val household = "/api/households/${client.createHousehold().id}"
        val used = client.create("$household/categories", CategoryInput("Used"))
        val unused = client.create("$household/categories", CategoryInput("Unused"))
        client.post("$household/expenses") { setBody(ExpenseInput(LocalDate(2026, 10, 1), 500, categoryId = used.id)) }

        client.delete("$household/categories/${used.id}?version=1")
            .assertError(HttpStatusCode.Conflict, "category_in_use")
        assertEquals(HttpStatusCode.NoContent, client.delete("$household/categories/${unused.id}?version=1").status)
    }
}
