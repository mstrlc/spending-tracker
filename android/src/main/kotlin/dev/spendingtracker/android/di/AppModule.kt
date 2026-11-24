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
import dev.spendingtracker.android.data.SessionApi
import dev.spendingtracker.android.data.SessionManager
import dev.spendingtracker.shared.client.TokenStore
import org.koin.dsl.module

val appModule = module {
    single<TokenStore> { EncryptedTokenStore(get()) }
    single { ServerUrlStore(get()) }
    single<SessionApi> { SessionManager(get(), get()) }
    // The API implementations read the session at call time; screens only compose once a session exists.
    single { SessionManager(get(), get()) }
    factory<AuthApi> { AuthApiImpl(get()) }
    factory<HouseholdsApi> { HouseholdsApiImpl(get()) }
    factory<OverviewApi> { OverviewApiImpl(get()) }
    factory<ExpensesApi> { ExpensesApiImpl(get()) }
    factory<CategoriesApi> { CategoriesApiImpl(get()) }
    factory<AccountsApi> { AccountsApiImpl(get()) }
}
