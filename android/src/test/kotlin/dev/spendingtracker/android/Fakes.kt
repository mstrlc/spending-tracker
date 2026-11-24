package dev.spendingtracker.android

import dev.spendingtracker.android.data.AccountsApi
import dev.spendingtracker.android.data.CategoriesApi
import dev.spendingtracker.android.data.ExpensesApi
import dev.spendingtracker.android.data.HouseholdsApi
import dev.spendingtracker.android.data.OverviewApi
import dev.spendingtracker.android.data.SessionApi
import dev.spendingtracker.android.data.Session
import dev.spendingtracker.shared.api.Account
import dev.spendingtracker.shared.api.AccountKind
import dev.spendingtracker.shared.api.AccountStatus
import dev.spendingtracker.shared.api.Category
import dev.spendingtracker.shared.api.CategoryKind
import dev.spendingtracker.shared.api.Expense
import dev.spendingtracker.shared.api.ExpenseInput
import dev.spendingtracker.shared.api.Household
import dev.spendingtracker.shared.api.HouseholdRole
import dev.spendingtracker.shared.api.JointFunding
import dev.spendingtracker.shared.api.Me
import dev.spendingtracker.shared.api.Member
import dev.spendingtracker.shared.api.Overview
import dev.spendingtracker.shared.client.ApiException
import dev.spendingtracker.shared.api.ApiError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.LocalDate

val fakeHousehold = Household("h1", "Home", "CZK", HouseholdRole.OWNER, null, JointFunding.POT)
val fakeMe = Me("u1", "a@b.c", "Ann")
val fakeMembers = listOf(
    Member("u1", "Ann", HouseholdRole.OWNER, true),
    Member("u2", "Bob", HouseholdRole.MEMBER, true),
)
val fakeCategories = listOf(
    Category("c1", null, "Groceries", CategoryKind.CONSUMPTION, false, 0, 1),
    Category("c2", null, "Transport", CategoryKind.CONSUMPTION, false, 1, 1),
)
val fakeAccounts = listOf(
    Account("a1", "Main", null, AccountStatus.ACTIVE, 1, AccountKind.BANK, null),
)

fun fakeExpense(id: String, amountMinor: Long = -12990, date: LocalDate = LocalDate(2026, 11, 20), version: Int = 1) =
    Expense(
        id = id,
        date = date,
        amountMinor = amountMinor,
        merchantId = "m1",
        merchant = "Alza",
        categoryId = "c1",
        accountId = "a1",
        paidByUserId = "u1",
        notes = "",
        version = version,
        tripId = null,
        originalAmountMinor = null,
        originalCurrency = null,
        split = null,
        createsDebt = true,
        debts = emptyList(),
        lines = emptyList(),
        refundOfId = null,
        refundedMinor = 0,
    )

class FakeSessionApi(
    initialUrl: String = "http://10.0.2.2:8080",
    var loginResult: Result<Me> = Result.success(fakeMe),
) : SessionApi {
    override val serverUrl: StateFlow<String> = MutableStateFlow(initialUrl)
    override val session: StateFlow<Session?> = MutableStateFlow(null)
    override val household: StateFlow<Household?> = MutableStateFlow(fakeHousehold)
    var lastLogin: Triple<String, String, String>? = null

    override suspend fun restore(): Boolean = false
    override suspend fun login(serverUrl: String, email: String, password: String): Result<Me> {
        lastLogin = Triple(serverUrl, email, password)
        return loginResult
    }
    override suspend fun logout() {}
    override fun selectHousehold(household: Household) {}
}

class FakeHouseholdsApi(var households: List<Household> = listOf(fakeHousehold)) : HouseholdsApi {
    override suspend fun listHouseholds(): List<Household> = households
    override suspend fun listMembers(householdId: String): List<Member> = fakeMembers
}

class FakeOverviewApi(var overview: Overview? = null) : OverviewApi {
    override suspend fun get(householdId: String, month: String): Overview =
        overview ?: error("no overview")
}

class FakeExpensesApi(
    var expenses: MutableList<Expense> = mutableListOf(),
    var failDeleteWith: ApiException? = null,
    var failUpdateWith: ApiException? = null,
) : ExpensesApi {
    val deleted = mutableListOf<Pair<String, Int>>()
    var lastInput: ExpenseInput? = null

    override suspend fun list(householdId: String, from: LocalDate, to: LocalDate): List<Expense> = expenses.toList()
    override suspend fun create(householdId: String, input: ExpenseInput): Expense {
        lastInput = input
        val e = fakeExpense("new", input.amountMinor, input.date, 1)
        expenses.add(e)
        return e
    }
    override suspend fun update(householdId: String, id: String, input: ExpenseInput): Expense {
        failUpdateWith?.let { throw it }
        lastInput = input
        val e = fakeExpense(id, input.amountMinor, input.date, 2)
        expenses.removeAll { it.id == id }
        expenses.add(e)
        return e
    }
    override suspend fun delete(householdId: String, id: String, version: Int) {
        failDeleteWith?.let { throw it }
        deleted.add(id to version)
        expenses.removeAll { it.id == id }
    }
}

class FakeCategoriesApi : CategoriesApi {
    override suspend fun list(householdId: String): List<Category> = fakeCategories
}

class FakeAccountsApi : AccountsApi {
    override suspend fun list(householdId: String): List<Account> = fakeAccounts
}

fun apiException(status: Int, code: String, message: String? = null) = ApiException(status, ApiError(code, message))
