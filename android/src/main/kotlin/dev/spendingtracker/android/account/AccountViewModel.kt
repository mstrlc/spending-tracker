package dev.spendingtracker.android.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.spendingtracker.android.data.SessionApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

sealed interface AccountEvent {
    /** The session is gone; the app should go back to the login screen. */
    data object LoggedOut : AccountEvent
}

/**
 * The account tab: who's logged in, on which server and in which household, and logging out. The session and
 * household are read straight from [SessionApi]'s flows, so this only owns the logout action.
 */
class AccountViewModel(
    private val sessionApi: SessionApi,
) : ViewModel() {
    private val _events = Channel<AccountEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun logout() {
        viewModelScope.launch {
            sessionApi.logout()
            _events.send(AccountEvent.LoggedOut)
        }
    }
}
