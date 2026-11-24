package dev.spendingtracker.shared.client

import dev.spendingtracker.shared.api.Account
import dev.spendingtracker.shared.api.Category
import dev.spendingtracker.shared.api.Expense
import dev.spendingtracker.shared.api.ExpenseInput
import dev.spendingtracker.shared.api.Household
import dev.spendingtracker.shared.api.LoginRequest
import dev.spendingtracker.shared.api.Me
import dev.spendingtracker.shared.api.Member
import dev.spendingtracker.shared.api.Overview
import dev.spendingtracker.shared.api.TokenResponse
import io.ktor.client.request.url
import io.ktor.http.parameters
import kotlinx.datetime.LocalDate

/**
 * Thin suspend-function wrappers over [ApiClient], one per area the app uses. (The plan mentioned Flows too;
 * these are all plain request/response calls, so suspend functions suffice — reactive streams come with the UI.)
 */

/** Login, current user, logout. Token login sets no cookie, so it's the one mobile clients use. */
class AuthRepository(private val api: ApiClient) {
    suspend fun login(email: String, password: String): TokenResponse =
        api.post("/api/auth/token") { jsonBody(LoginRequest(email, password)) }

    suspend fun me(): Me = api.get("/api/auth/me")

    /** The server's logout route is POST (it ends cookie sessions too); it answers 204. */
    suspend fun logout() = api.postNoContent("/api/auth/logout")
}

class HouseholdRepository(private val api: ApiClient) {
    suspend fun listHouseholds(): List<Household> = api.get("/api/households")

    suspend fun listMembers(householdId: String): List<Member> =
        api.get("/api/households/$householdId/members")
}

/** The month at a glance; [month] is YYYY-MM. */
class OverviewRepository(private val api: ApiClient) {
    suspend fun get(householdId: String, month: String): Overview =
        api.get("/api/households/$householdId/overview") {
            url { parameters.append("month", month) }
        }
}

/** Expenses of a household; updates and deletes carry the row's [version] (a stale one is a 409). */
class ExpenseRepository(private val api: ApiClient) {
    suspend fun list(householdId: String, from: LocalDate, to: LocalDate): List<Expense> =
        api.get("/api/households/$householdId/expenses") {
            url {
                parameters.append("from", from.toString())
                parameters.append("to", to.toString())
            }
        }

    suspend fun create(householdId: String, input: ExpenseInput): Expense =
        api.post("/api/households/$householdId/expenses") { jsonBody(input) }

    suspend fun update(householdId: String, id: String, input: ExpenseInput): Expense =
        api.put("/api/households/$householdId/expenses/$id") { jsonBody(input) }

    suspend fun delete(householdId: String, id: String, version: Int) =
        api.delete("/api/households/$householdId/expenses/$id") {
            url { parameters.append("version", version.toString()) }
        }
}

class CategoryRepository(private val api: ApiClient) {
    suspend fun list(householdId: String): List<Category> = api.get("/api/households/$householdId/categories")
}

class AccountRepository(private val api: ApiClient) {
    suspend fun list(householdId: String): List<Account> = api.get("/api/households/$householdId/accounts")
}
