package dev.spendingtracker.android.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.spendingtracker.android.data.SessionApi
import dev.spendingtracker.android.expenses.ExpensesScreen
import dev.spendingtracker.android.overview.OverviewScreen
import dev.spendingtracker.android.util.currentMonth
import dev.spendingtracker.android.util.formatMonth
import dev.spendingtracker.android.util.shiftMonth
import org.koin.compose.koinInject

private enum class Tab(val label: String) {
    Overview("Overview"),
    Expenses("Expenses"),
}

/**
 * The app's main shell: bottom navigation between the month's Overview and its Expenses, with previous/next
 * month arrows in the top bar. The editor opens as a separate route on top.
 */
@Composable
fun MainScreen(
    initialMonth: String,
    onOpenExpense: (String?) -> Unit,
) {
    val sessionApi = koinInject<SessionApi>()
    var month by rememberSaveable { mutableStateOf(initialMonth) }
    var tab by rememberSaveable { mutableStateOf(Tab.Overview) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(formatMonth(month)) },
                actions = {
                    IconButton(onClick = { month = shiftMonth(month, -1) }) {
                        Icon(Icons.Outlined.ChevronLeft, contentDescription = "Previous month")
                    }
                    IconButton(onClick = { month = shiftMonth(month, 1) }) {
                        Icon(Icons.Outlined.ChevronRight, contentDescription = "Next month")
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == Tab.Overview,
                    onClick = { tab = Tab.Overview },
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text(Tab.Overview.label) },
                )
                NavigationBarItem(
                    selected = tab == Tab.Expenses,
                    onClick = { tab = Tab.Expenses },
                    icon = { Icon(Icons.Default.Receipt, contentDescription = null) },
                    label = { Text(Tab.Expenses.label) },
                )
            }
        },
    ) { padding ->
        androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {
            when (tab) {
                Tab.Overview -> OverviewScreen(month = month, sessionApi = sessionApi)
                Tab.Expenses -> ExpensesScreen(
                    month = month,
                    onAddExpense = { onOpenExpense(null) },
                    onOpenExpense = { onOpenExpense(it) },
                    sessionApi = sessionApi,
                )
            }
        }
    }
}
