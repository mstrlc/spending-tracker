package dev.spendingtracker.shared.client

/**
 * Where a mobile client keeps its bearer token. Each platform supplies an implementation:
 * Android stores it in DataStore encrypted with an Android Keystore key, iOS in the Keychain.
 */
interface TokenStore {
    suspend fun get(): String?
    suspend fun set(token: String)
    suspend fun clear()
}
