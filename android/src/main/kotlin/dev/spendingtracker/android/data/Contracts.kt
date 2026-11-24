package dev.spendingtracker.android.data

import dev.spendingtracker.shared.api.Account
import dev.spendingtracker.shared.api.Category
import dev.spendingtracker.shared.api.Expense
import dev.spendingtracker.shared.api.ExpenseInput
import dev.spendingtracker.shared.api.Household
import dev.spendingtracker.shared.api.Me
import dev.spendingtracker.shared.api.Member
import dev.spendingtracker.shared.api.Overview
import dev.spendingtracker.shared.api.TokenResponse
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.LocalDate

/**
 * The app's view of the API, one interface per area. The real implementations delegate to the shared client
 * SDK's repositories; tests fake these. (The shared repositories are final, so the app talks to them behind
 * interfaces rather than subclassing them.)
 */

interface SessionApi {
    val serverUrl: StateFlow<String>
    val session: StateFlow<Session?>
    val household: StateFlow<Household?>

    /** True when a stored token still works, so the app can skip the login screen. */
    suspend fun restore(): Boolean

    suspend fun login(serverUrl: String, email: String, password: String): Result<Me>

    suspend fun logout()

    fun selectHousehold(household: Household)
}

interface AuthApi {
    suspend fun login(email: String, password: String): TokenResponse
    suspend fun me(): Me
    suspend fun logout()
}

interface HouseholdsApi {
    suspend fun listHouseholds(): List<Household>
    suspend fun listMembers(householdId: String): List<Member>
}

interface OverviewApi {
    suspend fun get(householdId: String, month: String): Overview
}

interface ExpensesApi {
    suspend fun list(householdId: String, from: LocalDate, to: LocalDate): List<Expense>
    suspend fun create(householdId: String, input: ExpenseInput): Expense
    suspend fun update(householdId: String, id: String, input: ExpenseInput): Expense
    suspend fun delete(householdId: String, id: String, version: Int)
}

interface CategoriesApi {
    suspend fun list(householdId: String): List<Category>
}

interface AccountsApi {
    suspend fun list(householdId: String): List<Account>
}
