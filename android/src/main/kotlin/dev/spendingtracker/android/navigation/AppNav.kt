package dev.spendingtracker.android.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import dev.spendingtracker.android.data.SessionApi
import dev.spendingtracker.android.expenses.ExpenseEditorScreen
import dev.spendingtracker.android.households.HouseholdsScreen
import dev.spendingtracker.android.login.LoginScreen
import dev.spendingtracker.android.util.currentMonth
import org.koin.compose.koinInject

/**
 * The app's navigation: login (skipped when a stored token still works), the household picker (skipped when
 * there's one), the main shell, and the expense editor on top.
 */
@Composable
fun AppNav(isDebugBuild: Boolean) {
    val sessionApi = koinInject<SessionApi>()
    val backStack = rememberNavBackStack(Login)
    var started by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!started) {
            started = true
            if (sessionApi.restore()) {
                backStack.clear()
                backStack.add(Households)
            }
        }
    }

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        // The ViewModelStore decorator scopes each entry's ViewModels to that entry (so the editor gets a
        // fresh ViewModel per expense, login starts blank after logout); saveable state is the default.
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = { key: NavKey -> entryFor(key, isDebugBuild, sessionApi, backStack) },
    )
}

private fun entryFor(
    key: NavKey,
    isDebugBuild: Boolean,
    sessionApi: SessionApi,
    backStack: androidx.navigation3.runtime.NavBackStack<NavKey>,
): NavEntry<NavKey> = when (key) {
    is Login -> NavEntry(key) { _ ->
        LoginScreen(
            isDebugBuild = isDebugBuild,
            onLoggedIn = {
                backStack.clear()
                backStack.add(Households)
            },
        )
    }
    is Households -> NavEntry(key) { _ ->
        HouseholdsScreen(onOpen = { backStack.add(Main(currentMonth())) })
    }
    is Main -> NavEntry(key) { _ ->
        MainScreen(
            initialMonth = key.month,
            // The month the user has paged to, not the one the entry started with.
            onOpenExpense = { month, expenseId -> backStack.add(ExpenseEditor(month, expenseId)) },
            onLogout = {
                backStack.clear()
                backStack.add(Login)
            },
        )
    }
    is ExpenseEditor -> NavEntry(key) { _ ->
        ExpenseEditorScreen(
            month = key.month,
            expenseId = key.expenseId,
            onDone = { backStack.removeLastOrNull() },
            sessionApi = sessionApi,
        )
    }
    else -> error("Unknown route: $key")
}
