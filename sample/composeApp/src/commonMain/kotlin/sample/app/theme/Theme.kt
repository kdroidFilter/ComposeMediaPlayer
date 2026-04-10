package sample.app.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFB8A9FF),
    onPrimary = Color(0xFF2B1A6E),
    primaryContainer = Color(0xFF423899),
    onPrimaryContainer = Color(0xFFE5DEFF),
    secondary = Color(0xFF7EE8E8),
    onSecondary = Color(0xFF003737),
    secondaryContainer = Color(0xFF004F4F),
    onSecondaryContainer = Color(0xFFA4F1F1),
    tertiary = Color(0xFFFFB4A8),
    onTertiary = Color(0xFF561E10),
    tertiaryContainer = Color(0xFF723425),
    onTertiaryContainer = Color(0xFFFFDAD4),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF121218),
    onBackground = Color(0xFFE5E1EC),
    surface = Color(0xFF121218),
    onSurface = Color(0xFFE5E1EC),
    surfaceVariant = Color(0xFF1E1E2A),
    onSurfaceVariant = Color(0xFFCAC4D0),
    outline = Color(0xFF938F99),
    outlineVariant = Color(0xFF49454F),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF5B4FC4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8DEFF),
    onPrimaryContainer = Color(0xFF1A0063),
    secondary = Color(0xFF006A6A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF9CF1F0),
    onSecondaryContainer = Color(0xFF002020),
    tertiary = Color(0xFF8C4A3B),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDAD4),
    onTertiaryContainer = Color(0xFF3A0905),
    background = Color(0xFFFCF8FF),
    onBackground = Color(0xFF1C1B1F),
    surface = Color(0xFFFCF8FF),
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFE8E0F0),
    onSurfaceVariant = Color(0xFF49454F),
)

@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content,
    )
}
