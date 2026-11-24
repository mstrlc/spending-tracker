package dev.spendingtracker.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.spendingtracker.android.navigation.AppNav
import dev.spendingtracker.android.theme.SpendingTrackerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SpendingTrackerTheme {
                AppNav(isDebugBuild = BuildConfig.DEBUG)
            }
        }
    }
}
