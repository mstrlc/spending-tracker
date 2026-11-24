package dev.spendingtracker.android

import android.app.Application
import com.google.crypto.tink.aead.AeadConfig
import dev.spendingtracker.android.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class SpendingTrackerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Registers Tink's AEAD primitives; without this the first keyset build fails with
        // "No key manager found for key type …AesGcmKey".
        AeadConfig.register()
        startKoin {
            androidContext(this@SpendingTrackerApp)
            modules(appModule)
        }
    }
}
