package com.personal.fitnessledger.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

val DeepGreen = Color(0xFF086B40)
val ForestGreen = Color(0xFF0A5A38)
val SoftGreen = Color(0xFFE2F2E8)
val Amber = Color(0xFF8A5700)
val WarmBackground = Color(0xFFF8F7F3)
val Ink = Color(0xFF1A1C1B)

private val LightColors = lightColorScheme(
    primary = DeepGreen,
    onPrimary = Color.White,
    primaryContainer = SoftGreen,
    onPrimaryContainer = ForestGreen,
    secondary = Amber,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFEBC5),
    onSecondaryContainer = Color(0xFF4A3200),
    background = WarmBackground,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F5F0),
    surfaceContainer = Color(0xFFF1EFE9),
    surfaceContainerHigh = Color(0xFFECEAE4),
    surfaceContainerHighest = Color(0xFFE6E3DD),
    surfaceVariant = Color(0xFFF0F1ED),
    onSurfaceVariant = Color(0xFF545954),
    outline = Color(0xFFBEC6BE),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7DDBA7),
    onPrimary = Color(0xFF003922),
    primaryContainer = Color(0xFF005232),
    onPrimaryContainer = Color(0xFF9EF8C2),
    secondary = Color(0xFFFFC44F),
    onSecondary = Color(0xFF452B00),
    secondaryContainer = Color(0xFF654000),
    onSecondaryContainer = Color(0xFFFFDEA0),
    background = Color(0xFF101411),
    onBackground = Color(0xFFE0E4DE),
    // Raised cards must remain distinguishable from the page in dark mode.
    surface = Color(0xFF1C211D),
    onSurface = Color(0xFFE0E4DE),
    surfaceContainerLowest = Color(0xFF0B0F0C),
    surfaceContainerLow = Color(0xFF181D19),
    surfaceContainer = Color(0xFF1C211D),
    surfaceContainerHigh = Color(0xFF272B27),
    surfaceContainerHighest = Color(0xFF313632),
    surfaceVariant = Color(0xFF404842),
    onSurfaceVariant = Color(0xFFBEC9C0),
    outline = Color(0xFF89928B),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun FitnessLedgerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        val window = (view.context as Activity).window
        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
    }
    MaterialTheme(
        colorScheme = colors,
        content = content,
    )
}
