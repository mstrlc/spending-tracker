package dev.spendingtracker.android.households

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.spendingtracker.android.data.HouseholdsApi
import dev.spendingtracker.android.data.SessionApi
import dev.spendingtracker.shared.api.Household
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface HouseholdsUiState {
    data object Loading : HouseholdsUiState
    data class Ready(val households: List<Household>, val error: String? = null) : HouseholdsUiState
    data class Error(val message: String) : HouseholdsUiState
}

sealed interface HouseholdsEvent {
    /** One household: no need to pick, go straight to its overview. */
    data class Open(val household: Household) : HouseholdsEvent
}

/** Lists the user's households; a single one is opened right away. */
class HouseholdsViewModel(
    private val sessionApi: SessionApi,
    private val householdsApi: HouseholdsApi,
) : ViewModel() {
    private val _uiState = MutableStateFlow<HouseholdsUiState>(HouseholdsUiState.Loading)
    val uiState: StateFlow<HouseholdsUiState> = _uiState.asStateFlow()

    private val _events = Channel<HouseholdsEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.value = HouseholdsUiState.Loading
            try {
                val households = householdsApi.listHouseholds()
                if (households.size == 1) {
                    sessionApi.selectHousehold(households.first())
                    _events.send(HouseholdsEvent.Open(households.first()))
                } else {
                    _uiState.value = HouseholdsUiState.Ready(households)
                }
            } catch (e: Exception) {
                _uiState.value = HouseholdsUiState.Error(e.message ?: "Could not load households.")
            }
        }
    }

    fun pick(household: Household) {
        sessionApi.selectHousehold(household)
        viewModelScope.launch { _events.send(HouseholdsEvent.Open(household)) }
    }
}
