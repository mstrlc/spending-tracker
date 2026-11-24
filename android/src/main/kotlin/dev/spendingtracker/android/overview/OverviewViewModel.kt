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

/** One month of the overview; [month] is YYYY-MM. Categories come along for the top-category names and hues. */
class OverviewViewModel(
    private val sessionApi: SessionApi,
    private val overviewApi: OverviewApi,
    private val categoriesApi: CategoriesApi,
    private val month: String,
) : ViewModel() {
    private val _uiState = MutableStateFlow<OverviewUiState>(OverviewUiState.Loading)
    val uiState: StateFlow<OverviewUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
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
}
