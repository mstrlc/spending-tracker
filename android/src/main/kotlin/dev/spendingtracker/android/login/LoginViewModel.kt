package dev.spendingtracker.android.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.spendingtracker.android.data.SessionApi
import dev.spendingtracker.android.util.normalizeServerUrl
import dev.spendingtracker.shared.client.ApiException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val serverUrl: String = "",
    val email: String = "",
    val password: String = "",
    val loading: Boolean = false,
    val error: String? = null,
) {
    val canSubmit: Boolean
        get() = serverUrl.isNotBlank() && email.isNotBlank() && password.isNotEmpty() && !loading
}

sealed interface LoginEvent {
    data object LoggedIn : LoginEvent
}

/**
 * The login screen's state. The server URL is remembered across launches (default: the host's API from an
 * emulator); a release build refuses plain http — only the debug build's network security config allows it.
 */
class LoginViewModel(private val sessionApi: SessionApi) : ViewModel() {
    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    private val _events = Channel<LoginEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        _uiState.update { it.copy(serverUrl = sessionApi.serverUrl.value) }
    }

    fun onServerUrlChange(value: String) = _uiState.update { it.copy(serverUrl = value, error = null) }
    fun onEmailChange(value: String) = _uiState.update { it.copy(email = value, error = null) }
    fun onPasswordChange(value: String) = _uiState.update { it.copy(password = value, error = null) }

    fun submit(isDebugBuild: Boolean = true) {
        val state = _uiState.value
        if (!state.canSubmit) return
        val serverUrl = normalizeServerUrl(state.serverUrl)
        if (!isDebugBuild && serverUrl.startsWith("http://")) {
            _uiState.update { it.copy(error = "This build only accepts https:// server URLs.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            val result = sessionApi.login(serverUrl, state.email, state.password)
            _uiState.update { it.copy(loading = false) }
            result.fold(
                onSuccess = { _events.send(LoginEvent.LoggedIn) },
                onFailure = { e ->
                    val message = when (e) {
                        is ApiException -> e.error.message ?: "Could not log in."
                        // ConnectException, timeout, …: the server wasn't reached at all.
                        else -> "Couldn't reach ${state.serverUrl} — check the URL and that the server is running."
                    }
                    _uiState.update { it.copy(error = message) }
                },
            )
        }
    }
}
