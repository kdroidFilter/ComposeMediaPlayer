package sample.app.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
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

@Composable
fun AppTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = DarkScheme,
        content = content,
    )
}
