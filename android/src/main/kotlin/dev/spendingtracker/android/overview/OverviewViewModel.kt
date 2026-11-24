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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

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

    /** True while a reload is in flight on top of existing content — drives the pull-to-refresh spinner. */
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private var loadJob: Job? = null

    /**
     * Loads [month] (YYYY-MM); the screen calls this when the month in the top bar changes. A new call cancels
     * the one in flight, so paging quickly can't let an older month's response land last.
     */
    fun load(month: String) {
        currentMonth = month
        val household = sessionApi.household.value ?: return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            // First load (or after an error) has nothing to show: full-screen spinner. Otherwise keep the
            // old month on screen and just show the PTR indicator.
            if (_uiState.value !is OverviewUiState.Ready) _uiState.value = OverviewUiState.Loading
            _refreshing.value = true
            try {
                val overview = overviewApi.get(household.id, month)
                val categories = categoriesApi.list(household.id)
                _uiState.value = OverviewUiState.Ready(overview, categories)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_uiState.value !is OverviewUiState.Ready) {
                    _uiState.value = OverviewUiState.Error(e.message ?: "Could not load the overview.")
                }
            } finally {
                // A cancelled load leaves the spinner to the load that replaced it.
                if (loadJob == coroutineContext[Job]) _refreshing.value = false
            }
        }
    }

    /** Reloads the current month (pull to refresh). */
    fun load() = load(currentMonth)
}
