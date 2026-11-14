package com.prakash.pmeeting

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prakash.pmeeting.ui.PMeetingApp
import com.prakash.pmeeting.ui.theme.PMeetingTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val themePreference = (application as PMeetingApplication).container.themePreference
        setContent {
            val darkOverride by themePreference.darkTheme.collectAsStateWithLifecycle()
            val darkTheme = darkOverride ?: isSystemInDarkTheme()
            // The app theme can differ from the system night setting. Re-derive
            // the system-bar icon colours from the resolved app theme so the
            // clock/status icons stay readable in both directions.
            SideEffect {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        Color.TRANSPARENT,
                        Color.TRANSPARENT
                    ) { darkTheme }
                )
            }
            PMeetingTheme(darkTheme = darkTheme) {
                PMeetingApp()
            }
        }
    }
}
