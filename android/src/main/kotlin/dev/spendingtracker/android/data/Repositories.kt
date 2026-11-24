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
import dev.spendingtracker.shared.client.AccountRepository
import dev.spendingtracker.shared.client.AuthRepository
import dev.spendingtracker.shared.client.CategoryRepository
import dev.spendingtracker.shared.client.ExpenseRepository
import dev.spendingtracker.shared.client.HouseholdRepository
import dev.spendingtracker.shared.client.OverviewRepository
import kotlinx.datetime.LocalDate

/** [SessionApi]-backed implementations of the contracts, built on the shared client SDK's repositories. */

class AuthApiImpl(private val sessionManager: SessionManager) : AuthApi {
    private fun repo() = AuthRepository(sessionManager.requireApi())
    override suspend fun login(email: String, password: String): TokenResponse = repo().login(email, password)
    override suspend fun me(): Me = repo().me()
    override suspend fun logout() = repo().logout()
}

class HouseholdsApiImpl(private val sessionManager: SessionManager) : HouseholdsApi {
    private fun repo() = HouseholdRepository(sessionManager.requireApi())
    override suspend fun listHouseholds(): List<Household> = repo().listHouseholds()
    override suspend fun listMembers(householdId: String): List<Member> = repo().listMembers(householdId)
}

class OverviewApiImpl(private val sessionManager: SessionManager) : OverviewApi {
    override suspend fun get(householdId: String, month: String): Overview =
        OverviewRepository(sessionManager.requireApi()).get(householdId, month)
}

class ExpensesApiImpl(private val sessionManager: SessionManager) : ExpensesApi {
    private fun repo() = ExpenseRepository(sessionManager.requireApi())
    override suspend fun list(householdId: String, from: LocalDate, to: LocalDate): List<Expense> =
        repo().list(householdId, from, to)
    override suspend fun create(householdId: String, input: ExpenseInput): Expense = repo().create(householdId, input)
    override suspend fun update(householdId: String, id: String, input: ExpenseInput): Expense =
        repo().update(householdId, id, input)
    override suspend fun delete(householdId: String, id: String, version: Int) = repo().delete(householdId, id, version)
}

class CategoriesApiImpl(private val sessionManager: SessionManager) : CategoriesApi {
    override suspend fun list(householdId: String): List<Category> =
        CategoryRepository(sessionManager.requireApi()).list(householdId)
}

class AccountsApiImpl(private val sessionManager: SessionManager) : AccountsApi {
    override suspend fun list(householdId: String): List<Account> =
        AccountRepository(sessionManager.requireApi()).list(householdId)
}
