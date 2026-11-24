package dev.spendingtracker.android.expenses

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.spendingtracker.android.data.AccountsApi
import dev.spendingtracker.android.data.CategoriesApi
import dev.spendingtracker.android.data.ExpensesApi
import dev.spendingtracker.android.data.HouseholdsApi
import dev.spendingtracker.android.data.SessionApi
import dev.spendingtracker.android.util.monthRange
import dev.spendingtracker.shared.api.Account
import dev.spendingtracker.shared.api.Category
import dev.spendingtracker.shared.api.Expense
import dev.spendingtracker.shared.api.Member
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ExpensesUiState {
    data object Loading : ExpensesUiState
    data class Ready(
        val month: String,
        val expenses: List<Expense>,
        val categories: List<Category>,
        val accounts: List<Account>,
        val members: List<Member>,
    ) : ExpensesUiState
    data class Error(val month: String, val message: String) : ExpensesUiState
}

sealed interface ExpensesEvent {
    /** The row is gone from the list; the snackbar's Undo cancels [undoDelete]. */
    data class Deleted(val expense: Expense) : ExpensesEvent
    data class DeleteFailed(val message: String) : ExpensesEvent
    data class Restored(val expense: Expense) : ExpensesEvent
}

/** How long the Undo snackbar keeps a deleted row before the DELETE goes out (the web's 6s). */
const val DELETE_UNDO_MILLIS = 6000L

/**
 * The expenses of one month, grouped by day on the screen. App-scoped, so the editor shares it and can delete
 * with the same undo: the row leaves the list at once and the DELETE goes out only after the snackbar closes.
 */
class ExpensesViewModel(
    private val sessionApi: SessionApi,
    private val expensesApi: ExpensesApi,
    private val categoriesApi: CategoriesApi,
    private val accountsApi: AccountsApi,
    private val householdsApi: HouseholdsApi,
) : ViewModel() {
    private val _uiState = MutableStateFlow<ExpensesUiState>(ExpensesUiState.Loading)
    val uiState: StateFlow<ExpensesUiState> = _uiState.asStateFlow()

    private val _events = Channel<ExpensesEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val pendingDeletes = mutableMapOf<String, Job>()
    private var loadedMonth: String? = null

    /** True while a reload is in flight on top of existing content — drives the pull-to-refresh spinner. */
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    fun loadMonth(month: String) {
        if (loadedMonth == month) return
        loadedMonth = month
        val household = sessionApi.household.value ?: return
        viewModelScope.launch {
            // First load (or after an error) has nothing to show: full-screen spinner. Otherwise keep the
            // old month on screen and just show the PTR indicator.
            if (_uiState.value !is ExpensesUiState.Ready) _uiState.value = ExpensesUiState.Loading
            _refreshing.value = true
            try {
                val (from, to) = monthRange(month)
                val expenses = expensesApi.list(household.id, from, to)
                    .sortedWith(compareByDescending<Expense> { it.date }.thenByDescending { it.id })
                val categories = categoriesApi.list(household.id)
                val accounts = accountsApi.list(household.id)
                val members = householdsApi.listMembers(household.id)
                _uiState.value = ExpensesUiState.Ready(month, expenses, categories, accounts, members)
            } catch (e: Exception) {
                if (_uiState.value !is ExpensesUiState.Ready) {
                    _uiState.value = ExpensesUiState.Error(month, e.message ?: "Could not load expenses.")
                }
            } finally {
                _refreshing.value = false
            }
        }
    }

    fun refresh() {
        val state = _uiState.value
        val month = when (state) {
            is ExpensesUiState.Ready -> state.month
            is ExpensesUiState.Error -> state.month
            is ExpensesUiState.Loading -> return
        }
        loadedMonth = null
        loadMonth(month)
    }

    /** Removes the row now and sends the DELETE after [DELETE_UNDO_MILLIS]; [undoDelete] cancels it. */
    fun deleteWithUndo(expense: Expense) {
        val household = sessionApi.household.value ?: return
        removeFromList(expense.id)
        viewModelScope.launch { _events.send(ExpensesEvent.Deleted(expense)) }
        pendingDeletes[expense.id]?.cancel()
        pendingDeletes[expense.id] = viewModelScope.launch {
            delay(DELETE_UNDO_MILLIS)
            try {
                expensesApi.delete(household.id, expense.id, expense.version)
            } catch (e: Exception) {
                restoreToList(expense)
                _events.send(ExpensesEvent.DeleteFailed((e as? dev.spendingtracker.shared.client.ApiException)?.error?.message ?: e.message ?: "Could not delete the expense."))
            } finally {
                pendingDeletes.remove(expense.id)
            }
        }
    }

    fun undoDelete(expense: Expense) {
        pendingDeletes.remove(expense.id)?.cancel()
        restoreToList(expense)
        viewModelScope.launch { _events.send(ExpensesEvent.Restored(expense)) }
    }

    /** Puts a freshly saved expense into the list (create or update), keeping the day order. */
    fun upsert(expense: Expense) {
        _uiState.update { state ->
            if (state is ExpensesUiState.Ready) {
                val others = state.expenses.filter { it.id != expense.id }
                state.copy(expenses = (others + expense).sortedWith(compareByDescending<Expense> { it.date }.thenByDescending { it.id }))
            } else state
        }
    }

    private fun removeFromList(id: String) {
        _uiState.update { state ->
            if (state is ExpensesUiState.Ready) state.copy(expenses = state.expenses.filter { it.id != id }) else state
        }
    }

    private fun restoreToList(expense: Expense) = upsert(expense)
}
