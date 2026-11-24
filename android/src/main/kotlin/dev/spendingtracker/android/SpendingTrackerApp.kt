package dev.spendingtracker.android

import android.app.Application
import dev.spendingtracker.android.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class SpendingTrackerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@SpendingTrackerApp)
            modules(appModule)
        }
    }
}
