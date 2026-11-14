package dev.spendingtracker.server

import dev.spendingtracker.shared.api.Account
import dev.spendingtracker.shared.api.AccountKind
import dev.spendingtracker.shared.api.ApiError
import dev.spendingtracker.shared.api.Asset
import dev.spendingtracker.shared.api.AssetKind
import dev.spendingtracker.shared.api.Category
import dev.spendingtracker.shared.api.CategoryKind
import dev.spendingtracker.shared.api.Expense
import dev.spendingtracker.shared.api.HouseholdImport
import dev.spendingtracker.shared.api.ImportAccount
import dev.spendingtracker.shared.api.ImportAsset
import dev.spendingtracker.shared.api.ImportCategory
import dev.spendingtracker.shared.api.ImportContribution
import dev.spendingtracker.shared.api.ImportCounts
import dev.spendingtracker.shared.api.ImportExpense
import dev.spendingtracker.shared.api.ImportIncome
import dev.spendingtracker.shared.api.ImportPerson
import dev.spendingtracker.shared.api.ImportResult
import dev.spendingtracker.shared.api.ImportTrip
import dev.spendingtracker.shared.api.Income
import dev.spendingtracker.shared.api.IncomeKind
import dev.spendingtracker.shared.api.Member
import dev.spendingtracker.shared.api.Merchant
import dev.spendingtracker.shared.api.Trip
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImportTest {
    private val file = HouseholdImport(
        currency = "CZK",
        people = listOf(ImportPerson("Ali", email = "ALICE@example.com"), ImportPerson("Bob")),
        accounts = listOf(
            ImportAccount("Joint", owner = null),
            ImportAccount("Bob's card", AccountKind.CREDIT_CARD, owner = "bob", iban = "CZ65 0800 0000 1920 0014 5399"),
        ),
        categories = listOf(
            ImportCategory("Gas", parent = "Car"), // listed before its parent
            ImportCategory("Car"),
            ImportCategory("groceries"), // the household already has Groceries
        ),
        trips = listOf(ImportTrip("Alps", LocalDate(2026, 8, 1), LocalDate(2026, 8, 9))),
        expenses = listOf(
            ImportExpense("old:1", LocalDate(2026, 8, 2), 1_200_00, "OMV", "Car", "Gas", "Bob's card", "Bob", trip = "Alps"),
            ImportExpense("old:2", LocalDate(2026, 8, 3), 300_00, "omv", "Groceries", account = "Joint"),
            ImportExpense("old:3", LocalDate(2026, 8, 4), 500_00, "OMV", "Car", "Gas", "Joint", "ali", notes = "full tank"),
        ),
        income = listOf(
            ImportIncome("old:i1", LocalDate(2026, 9, 1), 50_000_00, "Employer", "2026-08", recipient = "Bob"),
            ImportIncome("old:i2", LocalDate(2026, 8, 8), 3_143_00, "ZPMV", recipient = "Bob", kind = IncomeKind.DEDUCTION),
        ),
        assets = listOf(
            ImportAsset(
                "Pension · Bob", AssetKind.SCHEME, owner = "Bob",
                contributions = listOf(
                    ImportContribution(LocalDate(2026, 8, 16), 1_700_00, account = "Joint"),
                    ImportContribution(LocalDate(2026, 9, 16), 1_700_00),
                ),
            ),
        ),
    )

    @Test
    fun importsByNameAndSkipsWhatIsAlreadyThere() = apiTest {
        val client = newClient().also { it.signup() }
        val base = "/api/households/${client.createHousehold().id}"

        // A dry run reports what it would do and saves nothing.
        val preview = client.post("$base/import?dryRun=true") { setBody(file) }.body<ImportResult>()
        assertTrue(preview.dryRun)
        val expected = ImportCounts(
            members = 1, accounts = 2, categories = 2, trips = 1, merchants = 1, expenses = 3, income = 2, assets = 1, contributions = 2, bankFeeds = 0, bankRows = 0,
        )
        assertEquals(expected, preview.created)
        assertEquals(emptyList(), client.get("$base/expenses").body<List<Expense>>())
        assertEquals(1, client.get("$base/members").body<List<Member>>().size)

        val result = client.post("$base/import") { setBody(file) }.body<ImportResult>()
        assertEquals(expected, result.created)
        // Alice matched by email, Groceries by name.
        assertEquals(1, result.existing.members)
        assertEquals(1, result.existing.categories)

        val members = client.get("$base/members").body<List<Member>>()
        val alice = members.single { it.displayName == "Alice" }
        val bob = members.single { it.displayName == "Bob" }
        assertEquals(false, bob.hasLogin)
        val accounts = client.get("$base/accounts").body<List<Account>>().associateBy { it.name }
        assertEquals(bob.userId, accounts.getValue("Bob's card").ownerUserId)
        assertEquals("CZ6508000000192000145399", accounts.getValue("Bob's card").iban)
        val categories = client.get("$base/categories").body<List<Category>>()
        val car = categories.single { it.name == "Car" }
        val gas = categories.single { it.name == "Gas" }
        assertEquals(car.id, gas.parentId)
        assertEquals(CategoryKind.CONSUMPTION, gas.kind)
        val trip = client.get("$base/trips").body<List<Trip>>().single()
        assertEquals(1_200_00L to 1, trip.spentMinor to trip.expenseCount)

        val expenses = client.get("$base/expenses").body<List<Expense>>().sortedBy { it.date }
        assertEquals(listOf("OMV", "OMV", "OMV"), expenses.map { it.merchant })
        assertEquals(listOf(bob.userId, null, alice.userId), expenses.map { it.paidByUserId })
        assertEquals(listOf(gas.id, categories.single { it.name == "Groceries" }.id, gas.id), expenses.map { it.categoryId })
        // A new merchant is filed under the category its expenses use most.
        assertEquals(gas.id, client.get("$base/merchants").body<List<Merchant>>().single().defaultCategoryId)

        val income = client.get("$base/income").body<List<Income>>().associateBy { it.kind }
        val salary = income.getValue(IncomeKind.EARNING)
        assertEquals("2026-08" to bob.userId, salary.earnedMonth to salary.recipientUserId)
        assertEquals("ZPMV", income.getValue(IncomeKind.DEDUCTION).source)

        val asset = client.get("$base/assets").body<List<Asset>>().single()
        assertEquals(Triple(AssetKind.SCHEME, bob.userId, 3_400_00L), Triple(asset.kind, asset.ownerUserId, asset.contributedMinor))

        // Importing the same file again creates nothing.
        val again = client.post("$base/import") { setBody(file) }.body<ImportResult>()
        assertEquals(ImportCounts(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), again.created)
        assertEquals(3 to 2, again.existing.expenses to again.existing.contributions)
    }

    @Test
    fun anErrorNamesTheEntryAndSavesNothing() = apiTest {
        val client = newClient().also { it.signup() }
        val base = "/api/households/${client.createHousehold().id}"

        val broken = file.copy(expenses = file.expenses + ImportExpense("old:4", LocalDate(2026, 8, 5), 1_00, account = "Savings"))
        val response = client.post("$base/import") { setBody(broken) }
        response.assertError(HttpStatusCode.BadRequest, "unknown_account")
        assertEquals("expenses[3].account", response.body<ApiError>().field)
        assertEquals(1, client.get("$base/members").body<List<Member>>().size)
        assertEquals(emptyList(), client.get("$base/trips").body<List<Trip>>())

        client.post("$base/import") { setBody(file.copy(currency = "EUR")) }.assertError(HttpStatusCode.BadRequest, "currency_mismatch")
    }
}
