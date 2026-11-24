package dev.spendingtracker.android.data

import dev.spendingtracker.shared.api.Household
import dev.spendingtracker.shared.api.Me
import dev.spendingtracker.shared.client.ApiClient
import dev.spendingtracker.shared.client.ApiException
import dev.spendingtracker.shared.client.AuthRepository
import dev.spendingtracker.shared.client.TokenStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A logged-in session: the server it talks to, the API client, and who's logged in. */
data class Session(val baseUrl: String, val api: ApiClient, val me: Me)

/**
 * The app's session state: restores a stored token at start, logs in and out, and remembers the household the
 * user picked (skipped when they belong to only one).
 */
class SessionManager(
    private val tokenStore: TokenStore,
    private val serverUrlStore: ServerUrlStore,
) : SessionApi {
    private val _session = MutableStateFlow<Session?>(null)
    private val _household = MutableStateFlow<Household?>(null)

    override val session: StateFlow<Session?> = _session.asStateFlow()
    override val household: StateFlow<Household?> = _household.asStateFlow()
    override val serverUrl: StateFlow<String> = serverUrlStore.url

    /** The API client of the current session; screens only exist once there is one. */
    fun requireApi(): ApiClient = _session.value?.api ?: error("No session: log in first")

    override suspend fun restore(): Boolean {
        val url = serverUrlStore.current()
        val token = tokenStore.get()
        if (token == null) return false
        val api = ApiClient(url, tokenStore)
        return try {
            // A 401 clears the token in the client itself; anything else (server down) keeps it for later.
            _session.value = Session(url, api, AuthRepository(api).me())
            true
        } catch (e: ApiException) {
            _session.value = null
            false
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun login(serverUrl: String, email: String, password: String): Result<Me> {
        val normalized = serverUrl.trim().trimEnd('/')
        val api = ApiClient(normalized, tokenStore)
        return try {
            val response = AuthRepository(api).login(email, password)
            tokenStore.set(response.token)
            serverUrlStore.setUrl(normalized)
            _session.value = Session(normalized, api, response.me)
            Result.success(response.me)
        } catch (e: ApiException) {
            Result.failure(e)
        }
    }

    override suspend fun logout() {
        _session.value?.let { runCatching { AuthRepository(it.api).logout() } }
        tokenStore.clear()
        _household.value = null
        _session.value = null
    }

    override fun selectHousehold(household: Household) {
        _household.value = household
    }
}
