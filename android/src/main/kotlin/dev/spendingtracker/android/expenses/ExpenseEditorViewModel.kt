package dev.spendingtracker.android.expenses

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.spendingtracker.android.data.AccountsApi
import dev.spendingtracker.android.data.CategoriesApi
import dev.spendingtracker.android.data.ExpensesApi
import dev.spendingtracker.android.data.HouseholdsApi
import dev.spendingtracker.android.data.SessionApi
import dev.spendingtracker.android.util.monthRange
import dev.spendingtracker.android.util.parseMoney
import dev.spendingtracker.android.util.toInputValue
import dev.spendingtracker.shared.api.Account
import dev.spendingtracker.shared.api.Category
import dev.spendingtracker.shared.api.Expense
import dev.spendingtracker.shared.api.ExpenseInput
import dev.spendingtracker.shared.api.Member
import dev.spendingtracker.shared.client.ApiException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

data class EditorState(
    val date: LocalDate,
    val amountText: String,
    val merchant: String,
    val categoryId: String?,
    val accountId: String?,
    val paidByUserId: String?,
    val notes: String,
    val categories: List<Category>,
    val accounts: List<Account>,
    val members: List<Member>,
    val saving: Boolean = false,
    val error: String? = null,
) {
    val canSave: Boolean
        get() = !saving && amountText.isNotBlank()
}

sealed interface EditorUiState {
    data object Loading : EditorUiState
    data class Ready(val editor: EditorState, val expenseId: String?, val version: Int?) : EditorUiState
    data class Error(val message: String) : EditorUiState
}

sealed interface EditorEvent {
    data class Saved(val expense: Expense) : EditorEvent
    data class Deleted(val expense: Expense) : EditorEvent
}

/**
 * The expense editor. Create ([expenseId] null) or edit; saving sends [version] on update, and a 409
 * `version_conflict` reloads the expense and says so. Deleting hands the row to [expensesList]'s undo delete.
 */
class ExpenseEditorViewModel(
    private val sessionApi: SessionApi,
    private val expensesApi: ExpensesApi,
    private val categoriesApi: CategoriesApi,
    private val accountsApi: AccountsApi,
    private val householdsApi: HouseholdsApi,
    private val expensesList: ExpensesViewModel,
    private val month: String,
    private val expenseId: String?,
) : ViewModel() {
    private val _uiState = MutableStateFlow<EditorUiState>(EditorUiState.Loading)
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private val _events = Channel<EditorEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        load()
    }

    private fun load(errorAfter: String? = null) {
        val household = sessionApi.household.value ?: return
        viewModelScope.launch {
            _uiState.value = EditorUiState.Loading
            try {
                val (from, to) = monthRange(month)
                val expense = expenseId?.let { id -> expensesApi.list(household.id, from, to).find { it.id == id } }
                val categories = categoriesApi.list(household.id).filter { it.kind != dev.spendingtracker.shared.api.CategoryKind.INCOME && !it.archived }
                val accounts = accountsApi.list(household.id).filter { it.status == dev.spendingtracker.shared.api.AccountStatus.ACTIVE }
                val members = householdsApi.listMembers(household.id)
                val currency = household.currency
                val editor = EditorState(
                    date = expense?.date ?: monthRange(month).first,
                    amountText = expense?.let { toInputValue(it.amountMinor, currency) } ?: "",
                    merchant = expense?.merchant ?: "",
                    categoryId = expense?.categoryId,
                    accountId = expense?.accountId,
                    paidByUserId = expense?.paidByUserId,
                    notes = expense?.notes ?: "",
                    categories = categories,
                    accounts = accounts,
                    members = members,
                )
                _uiState.value = EditorUiState.Ready(editor, expenseId, expense?.version)
                if (errorAfter != null) {
                    _uiState.update { state ->
                        if (state is EditorUiState.Ready) state.copy(editor = state.editor.copy(error = errorAfter)) else state
                    }
                }
            } catch (e: Exception) {
                _uiState.value = EditorUiState.Error(e.message ?: "Could not load the expense.")
            }
        }
    }

    fun onDateChange(value: LocalDate) = update { it.copy(date = value) }
    fun onAmountChange(value: String) = update { it.copy(amountText = value, error = null) }
    fun onMerchantChange(value: String) = update { it.copy(merchant = value) }
    fun onCategoryChange(value: String?) = update { it.copy(categoryId = value) }
    fun onAccountChange(value: String?) = update { it.copy(accountId = value) }
    fun onWhoseChange(value: String?) = update { it.copy(paidByUserId = value) }
    fun onNotesChange(value: String) = update { it.copy(notes = value) }

    private fun update(block: (EditorState) -> EditorState) {
        _uiState.update { state -> if (state is EditorUiState.Ready) state.copy(editor = block(state.editor)) else state }
    }

    fun save() {
        val state = _uiState.value as? EditorUiState.Ready ?: return
        val household = sessionApi.household.value ?: return
        val editor = state.editor
        // The user enters what they paid (positive); expenses are stored negative, like the web form.
        val parsed = parseMoney(editor.amountText, household.currency)
        if (parsed == null || parsed == 0L) {
            update { it.copy(error = "Enter an amount like 129.90.") }
            return
        }
        val amountMinor = -kotlin.math.abs(parsed)
        viewModelScope.launch {
            update { it.copy(saving = true, error = null) }
            try {
                val input = ExpenseInput(
                    date = editor.date,
                    amountMinor = amountMinor,
                    merchant = editor.merchant.trim(),
                    categoryId = editor.categoryId,
                    accountId = editor.accountId,
                    paidByUserId = editor.paidByUserId,
                    notes = editor.notes.trim(),
                    version = state.version,
                )
                try {
                    val saved = if (state.expenseId == null) {
                        expensesApi.create(household.id, input)
                    } else {
                        expensesApi.update(household.id, state.expenseId, input)
                    }
                    expensesList.upsert(saved)
                    _events.send(EditorEvent.Saved(saved))
                } catch (e: ApiException) {
                    if (e.status == 409 && e.error.code == "version_conflict") {
                        load(errorAfter = "Changed on another device — reloaded.")
                    } else {
                        update { it.copy(saving = false, error = e.error.message ?: "Could not save.") }
                    }
                } catch (e: Exception) {
                    update { it.copy(saving = false, error = e.message ?: "Could not save.") }
                }
            } finally {
                // On success the screen navigates away; make sure a failure never leaves the spinner on.
                update { it.copy(saving = false) }
            }
        }
    }

    /** Deletes through the list's undo delete, so the row comes back on Undo. */
    fun delete() {
        val state = _uiState.value as? EditorUiState.Ready ?: return
        val expense = expensesList.uiState.value.let { list ->
            (list as? ExpensesUiState.Ready)?.expenses?.find { it.id == state.expenseId }
        } ?: return
        expensesList.deleteWithUndo(expense)
        viewModelScope.launch { _events.send(EditorEvent.Deleted(expense)) }
    }
}
