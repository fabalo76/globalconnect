package one.globalconnect.keyinjection.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Color scheme used when the system is in dark mode. */
private val DarkColorScheme = darkColorScheme(
    primary = GlobalConnectAqua,
    secondary = GlobalConnectTeal,
    tertiary = GlobalConnectNavy,
    background = Color(0xFF071B2A),
    surface = Color(0xFF0B2942)
)

/** Default light theme color scheme. */
private val LightColorScheme = lightColorScheme(
    primary = GlobalConnectTeal,
    secondary = GlobalConnectAqua,
    tertiary = GlobalConnectNavy,
    background = Color(0xFFF6FAFA),
    surface = Color.White,
    onPrimary = Color.White,
    onBackground = GlobalConnectNavy,
    onSurface = GlobalConnectNavy
)

/**
 * Applies the Global Connect key injection theme to its [content].
 *
 * @param darkTheme whether dark mode should be used
 * @param content composable hierarchy to theme
 *
 * Author: Fabian Ramirez
 */
@Composable
fun GlobalConnectKeyInjectionTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
