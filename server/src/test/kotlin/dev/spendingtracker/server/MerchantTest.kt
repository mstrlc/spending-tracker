package dev.spendingtracker.server

import dev.spendingtracker.shared.api.ActivityPage
import dev.spendingtracker.shared.api.Category
import dev.spendingtracker.shared.api.CategoryInput
import dev.spendingtracker.shared.api.Expense
import dev.spendingtracker.shared.api.ExpenseInput
import dev.spendingtracker.shared.api.Merchant
import dev.spendingtracker.shared.api.MerchantInput
import dev.spendingtracker.shared.api.MerchantMerge
import dev.spendingtracker.shared.api.Overview
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
import kotlin.test.assertNull

class MerchantTest {
    @Test
    fun expensesShareOneMerchantWhateverTheSpelling() = apiTest {
        val client = newClient().also { it.signup() }
        val base = "/api/households/${client.createHousehold().id}"
        val groceries = client.post("$base/categories") { setBody(CategoryInput("Weekly shop")) }.body<Category>()

        // The first expense creates the merchant, filed under its category; later spellings find it.
        val first = client.post("$base/expenses") {
            setBody(ExpenseInput(LocalDate(2026, 10, 3), 500_00, " Albert ", categoryId = groceries.id))
        }.body<Expense>()
        val second = client.post("$base/expenses") { setBody(ExpenseInput(LocalDate(2026, 10, 9), 300_00, "ALBERT")) }.body<Expense>()
        assertEquals(first.merchantId, second.merchantId)
        assertEquals("Albert", second.merchant)
        val none = client.post("$base/expenses") { setBody(ExpenseInput(LocalDate(2026, 10, 9), 1_00, "  ")) }.body<Expense>()
        assertEquals(null to "", none.merchantId to none.merchant)

        val albert = client.get("$base/merchants").body<List<Merchant>>().single()
        assertEquals(groceries.id, albert.defaultCategoryId)
        assertEquals(Triple(800_00L, 2, LocalDate(2026, 10, 9)), Triple(albert.spentMinor, albert.expenseCount, albert.lastDate))

        // Renamed, it's renamed on every expense.
        val renamed = client.put("$base/merchants/${albert.id}") {
            setBody(MerchantInput("Albert Hypermarket", groceries.id, version = albert.version))
        }.body<Merchant>()
        val listed = client.get("$base/expenses?merchantId=${albert.id}").body<List<Expense>>()
        assertEquals(listOf("Albert Hypermarket", "Albert Hypermarket"), listed.map { it.merchant })

        // A second spelling as its own merchant can't take a name in use, but merges into the first.
        val typo = client.post("$base/merchants") { setBody(MerchantInput("Albrt")) }.body<Merchant>()
        client.put("$base/merchants/${typo.id}") { setBody(MerchantInput("albert hypermarket", version = typo.version)) }
            .assertError(HttpStatusCode.Conflict, "name_taken")
        val typoExpense = client.post("$base/expenses") { setBody(ExpenseInput(LocalDate(2026, 10, 12), 100_00, "albrt")) }.body<Expense>()
        assertEquals(typo.id, typoExpense.merchantId)
        client.delete("$base/merchants/${typo.id}?version=${typo.version}").assertError(HttpStatusCode.Conflict, "merchant_in_use")
        client.post("$base/merchants/${typo.id}/merge") { setBody(MerchantMerge(typo.id, typo.version)) }
            .assertError(HttpStatusCode.BadRequest, "invalid_merge")
        val merged = client.post("$base/merchants/${typo.id}/merge") { setBody(MerchantMerge(renamed.id, typo.version)) }.body<Merchant>()
        assertEquals("Albert Hypermarket" to 3, merged.name to merged.expenseCount)
        assertEquals(listOf(renamed.id), client.get("$base/merchants").body<List<Merchant>>().map { it.id })
        val moved = client.get("$base/expenses?merchantId=${renamed.id}").body<List<Expense>>().first { it.id == typoExpense.id }
        assertEquals(typoExpense.version + 1, moved.version)
        // Its activity is labelled with the merchant it was merged into.
        val typoHistory = client.get("$base/activity?entity=expense&entityId=${typoExpense.id}").body<ActivityPage>()
        assertEquals(listOf("Albert Hypermarket", "Albert Hypermarket"), typoHistory.entries.map { it.label })

        // The overview counts it as one merchant.
        val overview = client.get("$base/overview?month=2026-10&today=2026-10-31").body<Overview>()
        assertEquals(listOf("Albert Hypermarket" to 900_00L), overview.topMerchants.map { it.name to it.amountMinor })

        // The activity log names expenses by their merchant's current name.
        val history = client.get("$base/activity?entity=expense&entityId=${first.id}").body<ActivityPage>()
        assertEquals("Albert Hypermarket", history.entries.single().label)

        // Deleting the default category only clears it; an unused merchant can go.
        val unused = client.post("$base/merchants") { setBody(MerchantInput("Lidl", groceries.id)) }.body<Merchant>()
        client.post("$base/merchants") { setBody(MerchantInput("Lidl")) }.assertError(HttpStatusCode.Conflict, "name_taken")
        for (e in client.get("$base/expenses").body<List<Expense>>()) {
            client.put("$base/expenses/${e.id}") {
                setBody(ExpenseInput(e.date, e.amountMinor, e.merchant, categoryId = null, version = e.version))
            }
        }
        client.put("$base/merchants/${renamed.id}") { setBody(MerchantInput("Albert Hypermarket", version = merged.version)) }
        assertEquals(HttpStatusCode.NoContent, client.delete("$base/categories/${groceries.id}?version=${groceries.version}").status)
        val cleared = client.get("$base/merchants").body<List<Merchant>>().first { it.id == unused.id }
        assertNull(cleared.defaultCategoryId)
        assertEquals(HttpStatusCode.NoContent, client.delete("$base/merchants/${cleared.id}?version=${cleared.version}").status)
    }
}
