package de.synvyropic.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import de.synvyropic.app.ui.SynvyropicNavHost
import de.synvyropic.app.ui.screens.SplashScreen
import de.synvyropic.app.ui.theme.SynvyropicTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SynvyropicTheme {
                // Logo nur beim echten App-Start zeigen, nicht nach Drehen des Geräts
                var showSplash by rememberSaveable { mutableStateOf(savedInstanceState == null) }
                if (showSplash) SplashScreen(onDone = { showSplash = false }) else SynvyropicNavHost()
            }
        }
    }
}
