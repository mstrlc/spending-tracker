package dev.spendingtracker.android.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.spendingtracker.android.data.CategoriesApi
import dev.spendingtracker.android.data.OverviewApi
import dev.spendingtracker.android.data.SessionApi
import dev.spendingtracker.shared.api.Category
import dev.spendingtracker.shared.api.Overview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface OverviewUiState {
    data object Loading : OverviewUiState
    data class Ready(val overview: Overview, val categories: List<Category>) : OverviewUiState
    data class Error(val message: String) : OverviewUiState
}

/** One month of the overview; [initialMonth] is YYYY-MM and [load] switches months. Categories come along for the top-category names and hues. */
class OverviewViewModel(
    private val sessionApi: SessionApi,
    private val overviewApi: OverviewApi,
    private val categoriesApi: CategoriesApi,
    initialMonth: String,
) : ViewModel() {
    private var currentMonth: String = initialMonth
    private val _uiState = MutableStateFlow<OverviewUiState>(OverviewUiState.Loading)
    val uiState: StateFlow<OverviewUiState> = _uiState.asStateFlow()

    /** Loads [month] (YYYY-MM); the screen calls this when the month in the top bar changes. */
    fun load(month: String) {
        currentMonth = month
        val household = sessionApi.household.value ?: return
        viewModelScope.launch {
            _uiState.value = OverviewUiState.Loading
            try {
                val overview = overviewApi.get(household.id, month)
                val categories = categoriesApi.list(household.id)
                _uiState.value = OverviewUiState.Ready(overview, categories)
            } catch (e: Exception) {
                _uiState.value = OverviewUiState.Error(e.message ?: "Could not load the overview.")
            }
        }
    }

    /** Reloads the current month (pull to refresh). */
    fun load() = load(currentMonth)
}
