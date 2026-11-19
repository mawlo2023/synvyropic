package de.synvyropic.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** RGB-Akzentfarben – Markenzeichen der App. */
object Rgb {
    val Red = Color(0xFFFF2D55)
    val Green = Color(0xFF2DFFB4)
    val Blue = Color(0xFF2D7DFF)
    val gradient = Brush.linearGradient(listOf(Red, Blue, Green))
}

private val Colors = darkColorScheme(
    primary = Rgb.Red,
    onPrimary = Color.White,
    secondary = Rgb.Blue,
    onSecondary = Color.White,
    tertiary = Rgb.Green,
    onTertiary = Color.Black,
    background = Color(0xFF0B0B12),
    onBackground = Color(0xFFEDEDF5),
    surface = Color(0xFF15151F),
    onSurface = Color(0xFFEDEDF5),
    surfaceVariant = Color(0xFF1E1E2B),
    onSurfaceVariant = Color(0xFFA9A9BC),
    outline = Color(0xFF3A3A4E),
)

@Composable
fun SynvyropicTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, typography = Typography(), content = content)
}
