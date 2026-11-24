package dev.spendingtracker.android.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.spendingtracker.android.account.AccountScreen
import dev.spendingtracker.android.data.SessionApi
import dev.spendingtracker.android.expenses.ExpensesScreen
import dev.spendingtracker.android.overview.OverviewScreen
import dev.spendingtracker.android.util.currentMonth
import dev.spendingtracker.android.util.formatMonth
import dev.spendingtracker.android.util.shiftMonth
import dev.spendingtracker.android.ui.AppTopBar
import org.koin.compose.koinInject

private enum class Tab(val label: String) {
    Overview("Overview"),
    Expenses("Expenses"),
    Account("Account"),
}

/**
 * The app's main shell: bottom navigation between the month's Overview and its Expenses, with previous/next
 * month arrows in the top bar. The editor opens as a separate route on top.
 */
@Composable
fun MainScreen(
    initialMonth: String,
    onOpenExpense: (month: String, expenseId: String?) -> Unit,
    onLogout: () -> Unit,
) {
    val sessionApi = koinInject<SessionApi>()
    var month by rememberSaveable { mutableStateOf(initialMonth) }
    var tab by rememberSaveable { mutableStateOf(Tab.Overview) }

    Scaffold(
        topBar = {
            AppTopBar(
                title = if (tab == Tab.Account) "Account" else formatMonth(month),
                actions = {
                    if (tab != Tab.Account) {
                        IconButton(onClick = { month = shiftMonth(month, -1) }) {
                            Icon(Icons.Outlined.ChevronLeft, contentDescription = "Previous month")
                        }
                        IconButton(onClick = { month = shiftMonth(month, 1) }) {
                            Icon(Icons.Outlined.ChevronRight, contentDescription = "Next month")
                        }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                // The web marks the active tab with the foreground color on a muted pill; the default
                // secondaryContainer is near-white and invisible on the white bar, so set colors explicitly.
                val colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    indicatorColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
                NavigationBarItem(
                    selected = tab == Tab.Overview,
                    onClick = { tab = Tab.Overview },
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text(Tab.Overview.label) },
                    colors = colors,
                )
                NavigationBarItem(
                    selected = tab == Tab.Expenses,
                    onClick = { tab = Tab.Expenses },
                    icon = { Icon(Icons.Default.Receipt, contentDescription = null) },
                    label = { Text(Tab.Expenses.label) },
                    colors = colors,
                )
                NavigationBarItem(
                    selected = tab == Tab.Account,
                    onClick = { tab = Tab.Account },
                    icon = { Icon(Icons.Default.Person, contentDescription = null) },
                    label = { Text(Tab.Account.label) },
                    colors = colors,
                )
            }
        },
    ) { padding ->
        androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {
            when (tab) {
                Tab.Overview -> OverviewScreen(month = month, sessionApi = sessionApi)
                Tab.Expenses -> ExpensesScreen(
                    month = month,
                    onAddExpense = { onOpenExpense(month, null) },
                    onOpenExpense = { onOpenExpense(month, it) },
                    sessionApi = sessionApi,
                )
                Tab.Account -> AccountScreen(sessionApi = sessionApi, onLogout = onLogout)
            }
        }
    }
}
