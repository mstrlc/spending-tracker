package dev.spendingtracker.android.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.crypto.tink.Aead
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadKeyTemplates
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import dev.spendingtracker.shared.client.TokenStore
import java.util.Base64
import kotlinx.coroutines.flow.first

private val Context.sessionStore: DataStore<Preferences> by preferencesDataStore("session")

/**
 * The bearer token in DataStore, encrypted with a Tink AEAD key whose master key lives in the Android Keystore
 * (the plan's "DataStore, encrypted with an Android Keystore key"). An attacker with the file but not the
 * keystore can't read the token.
 */
class EncryptedTokenStore(context: Context) : TokenStore {
    private val appContext = context.applicationContext

    private val aead: Aead by lazy {
        val keysetHandle = AndroidKeysetManager.Builder()
            .withSharedPref(appContext, KEYSET_PREFS, MASTER_KEY_PREFS)
            .withKeyTemplate(AeadKeyTemplates.AES256_GCM)
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
        keysetHandle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    private val tokenKey = stringPreferencesKey("token")

    override suspend fun get(): String? =
        appContext.sessionStore.data.first()[tokenKey]?.let { encrypted ->
            runCatching {
                String(aead.decrypt(Base64.getDecoder().decode(encrypted), ByteArray(0)))
            }.getOrNull()
        }

    override suspend fun set(token: String) {
        val encrypted = Base64.getEncoder().encodeToString(aead.encrypt(token.toByteArray(), ByteArray(0)))
        appContext.sessionStore.edit { it[tokenKey] = encrypted }
    }

    override suspend fun clear() {
        appContext.sessionStore.edit { it.remove(tokenKey) }
    }

    private companion object {
        const val KEYSET_PREFS = "token_store_keyset"
        const val MASTER_KEY_PREFS = "token_store_master_key"
        const val MASTER_KEY_URI = "android-keystore://token_store_master_key"
    }
}
