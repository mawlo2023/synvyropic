package de.synvyropic.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Markenfarben aus dem Synvyropic-Logo: Cyan → Blau → Violett → Magenta, Orange als Glanzlicht,
 * auf tiefem Nachtblau. (Die Namen Red/Green/Blue sind historisch – sie bezeichnen die drei Akzentrollen.)
 */
object Rgb {
    val Cyan = Color(0xFF27D3FF)
    val ElectricBlue = Color(0xFF3D7BFF)
    val Violet = Color(0xFF9B4DFF)
    val Magenta = Color(0xFFE445E6)
    val Orange = Color(0xFFFF8A3D)

    /** Hauptakzent (Magenta) */
    val Red = Magenta
    /** Positiv/aktiv (Cyan) */
    val Green = Cyan
    /** Sekundärakzent (Violett) */
    val Blue = Violet

    val gradient = Brush.linearGradient(listOf(Cyan, ElectricBlue, Violet, Magenta))
    val gradientWarm = Brush.linearGradient(listOf(Violet, Magenta, Orange))
}

private val Colors = darkColorScheme(
    primary = Color(0xFFB04CFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3A1E6E),
    onPrimaryContainer = Color(0xFFEBDCFF),
    secondary = Rgb.Cyan,
    onSecondary = Color(0xFF00161F),
    secondaryContainer = Color(0xFF123A57),
    onSecondaryContainer = Color(0xFFCDEFFF),
    tertiary = Rgb.Orange,
    onTertiary = Color(0xFF2A0F00),
    background = Color(0xFF05060F),
    onBackground = Color(0xFFECEBFA),
    surface = Color(0xFF0E1022),
    onSurface = Color(0xFFECEBFA),
    surfaceVariant = Color(0xFF181B36),
    onSurfaceVariant = Color(0xFFA9ACCF),
    surfaceContainer = Color(0xFF12152B),
    surfaceContainerHigh = Color(0xFF181B36),
    surfaceContainerLow = Color(0xFF0B0D1D),
    outline = Color(0xFF34386A),
    outlineVariant = Color(0xFF23264A),
)

@Composable
fun SynvyropicTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, typography = Typography(), content = content)
}
