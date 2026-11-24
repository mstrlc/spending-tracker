package dev.spendingtracker.android.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore("settings")

/**
 * Where the app remembers the server it talks to. The default is the host's API from an Android emulator
 * (10.0.2.2); on a device use `adb reverse tcp:<PORT> tcp:<PORT>` and localhost.
 */
class ServerUrlStore(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val urlKey = stringPreferencesKey("server_url")

    val url: StateFlow<String> = appContext.settingsStore.data
        .map { it[urlKey] ?: DEFAULT_URL }
        .stateIn(scope, SharingStarted.Eagerly, DEFAULT_URL)

    suspend fun setUrl(url: String) {
        appContext.settingsStore.edit { it[urlKey] = url }
    }

    suspend fun current(): String = url.first()

    companion object {
        const val DEFAULT_URL = "http://10.0.2.2:8080"
    }
}
