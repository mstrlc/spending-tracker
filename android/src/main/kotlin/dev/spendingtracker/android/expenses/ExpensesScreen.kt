package dev.spendingtracker.android.expenses

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.spendingtracker.android.data.SessionApi
import dev.spendingtracker.android.theme.LocalPalette
import dev.spendingtracker.android.ui.Avatar
import dev.spendingtracker.android.ui.CategoryLabel
import dev.spendingtracker.android.ui.MoneyText
import dev.spendingtracker.android.util.formatDay
import dev.spendingtracker.shared.api.Expense
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.koinInject

/** The month's expenses grouped by day, with pull to refresh and an undo snackbar on delete. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExpensesScreen(
    month: String,
    onAddExpense: () -> Unit,
    onOpenExpense: (String) -> Unit,
    sessionApi: SessionApi,
    viewModel: ExpensesViewModel = koinInject(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val household by sessionApi.household.collectAsStateWithLifecycle()
    val currency = household?.currency ?: "CZK"
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(month) { viewModel.loadMonth(month) }

    LaunchedEffect(viewModel) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is ExpensesEvent.Deleted -> {
                    val result = snackbarHostState.showSnackbar(
                        message = "Expense deleted",
                        actionLabel = "Undo",
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) viewModel.undoDelete(event.expense)
                }
                is ExpensesEvent.DeleteFailed -> snackbarHostState.showSnackbar(event.message)
                is ExpensesEvent.Restored -> snackbarHostState.showSnackbar("Expense restored")
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onAddExpense) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Add")
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing || state is ExpensesUiState.Loading,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (val s = state) {
                is ExpensesUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                is ExpensesUiState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(s.message, color = MaterialTheme.colorScheme.error)
                }
                is ExpensesUiState.Ready -> {
                    val byDay = s.expenses.groupBy { it.date }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 96.dp),
                    ) {
                        byDay.forEach { (date, expenses) ->
                            stickyHeader {
                                Text(
                                    formatDay(date),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                            items(expenses.size, key = { expenses[it].id }) { index ->
                                ExpenseRow(expenses[index], s, currency) { onOpenExpense(expenses[index].id) }
                            }
                        }
                        if (s.expenses.isEmpty()) {
                            item {
                                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                    Text("No expenses this month.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpenseRow(
    expense: Expense,
    state: ExpensesUiState.Ready,
    currency: String,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                expense.merchant.ifEmpty { expense.notes.ifEmpty { "Expense" } },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryLabel(state.categories, expense.categoryId, short = true)
                Spacer(Modifier.width(8.dp))
                Avatar(state.members, expense.paidByUserId)
            }
        }
        // Money in (refunds) green, money out plain — the web's signedAmountClass.
        val amountColor = if (expense.amountMinor > 0) palette.income else MaterialTheme.colorScheme.onSurface
        MoneyText(expense.amountMinor, currency, color = amountColor)
    }
}
