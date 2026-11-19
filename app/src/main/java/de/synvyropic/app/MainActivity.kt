package de.synvyropic.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import de.synvyropic.app.ui.SynvyropicNavHost
import de.synvyropic.app.ui.theme.SynvyropicTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SynvyropicTheme {
                SynvyropicNavHost()
            }
        }
    }
}
