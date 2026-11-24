package dev.spendingtracker.android.di

import dev.spendingtracker.android.data.AccountsApi
import dev.spendingtracker.android.data.AccountsApiImpl
import dev.spendingtracker.android.data.AuthApi
import dev.spendingtracker.android.data.AuthApiImpl
import dev.spendingtracker.android.data.CategoriesApi
import dev.spendingtracker.android.data.CategoriesApiImpl
import dev.spendingtracker.android.data.EncryptedTokenStore
import dev.spendingtracker.android.data.ExpensesApi
import dev.spendingtracker.android.data.ExpensesApiImpl
import dev.spendingtracker.android.data.HouseholdsApi
import dev.spendingtracker.android.data.HouseholdsApiImpl
import dev.spendingtracker.android.data.OverviewApi
import dev.spendingtracker.android.data.OverviewApiImpl
import dev.spendingtracker.android.data.ServerUrlStore
import dev.spendingtracker.android.account.AccountViewModel
import dev.spendingtracker.android.data.SessionApi
import dev.spendingtracker.android.data.SessionManager
import dev.spendingtracker.android.expenses.ExpenseEditorViewModel
import dev.spendingtracker.android.expenses.ExpensesViewModel
import dev.spendingtracker.android.households.HouseholdsViewModel
import dev.spendingtracker.android.login.LoginViewModel
import dev.spendingtracker.android.overview.OverviewViewModel
import dev.spendingtracker.shared.client.TokenStore
import org.koin.dsl.module

val appModule = module {
    single<TokenStore> { EncryptedTokenStore(get()) }
    single { ServerUrlStore(get()) }
    // One SessionManager instance, exposed both as itself (for the API impls) and as SessionApi (for VMs).
    single { SessionManager(get(), get()) }
    single<SessionApi> { get<SessionManager>() }

    // The API implementations read the session at call time; screens only compose once a session exists.
    factory<AuthApi> { AuthApiImpl(get()) }
    factory<HouseholdsApi> { HouseholdsApiImpl(get()) }
    factory<OverviewApi> { OverviewApiImpl(get()) }
    factory<ExpensesApi> { ExpensesApiImpl(get()) }
    factory<CategoriesApi> { CategoriesApiImpl(get()) }
    factory<AccountsApi> { AccountsApiImpl(get()) }

    // ViewModels. ExpensesViewModel is app-scoped so the editor shares its list and undo delete.
    factory { LoginViewModel(get()) }
    factory { HouseholdsViewModel(get(), get()) }
    factory { AccountViewModel(get()) }
    factory { params -> OverviewViewModel(get(), get(), get(), params.get()) }
    single { ExpensesViewModel(get(), get(), get(), get(), get()) }
    factory { params ->
        ExpenseEditorViewModel(get(), get(), get(), get(), get(), get(), params.get<String>(0), params.get<String?>(1))
    }
}
