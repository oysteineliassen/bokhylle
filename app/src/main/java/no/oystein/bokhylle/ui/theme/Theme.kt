package no.oystein.bokhylle.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF7A4B2A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF2DCC8),
    onPrimaryContainer = Color(0xFF2E1500),
    secondary = Color(0xFF3F6E5A),
    secondaryContainer = Color(0xFFCDE8D9),
    tertiary = Color(0xFFB5763A),
    background = Color(0xFFFBF6EE),
    surface = Color(0xFFFBF6EE),
    surfaceVariant = Color(0xFFEFE4D6),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFF9DEDC),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFE6BB95),
    onPrimary = Color(0xFF452A12),
    primaryContainer = Color(0xFF5E3A1D),
    onPrimaryContainer = Color(0xFFF2DCC8),
    secondary = Color(0xFFA6D0BC),
    secondaryContainer = Color(0xFF26503F),
    tertiary = Color(0xFFE8B98A),
    background = Color(0xFF1C1814),
    surface = Color(0xFF1C1814),
    surfaceVariant = Color(0xFF3A322A),
)

@Composable
fun BokhylleTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}

/** Farger for flaggede sider og faktabokser, felles for flere skjermer. */
object Accent {
    val flag = Color(0xFFD9822B)
    val boxLight = Color(0xFFE7F0E9)
    val boxDark = Color(0xFF26352C)
}
