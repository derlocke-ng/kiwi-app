package io.github.derlocke_ng.kiwi.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// The kiwi green, used where the system offers no dynamic color (before Android 12).
private val KiwiLight = lightColorScheme(
    primary = Color(0xFF446B1C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC4F094),
    onPrimaryContainer = Color(0xFF0F2000),
    secondary = Color(0xFF57624A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDBE7C8),
    onSecondaryContainer = Color(0xFF151E0B),
    tertiary = Color(0xFF7A5731),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDDB9),
    onTertiaryContainer = Color(0xFF2B1700),
    background = Color(0xFFFAFAF1),
    onBackground = Color(0xFF1A1C16),
    surface = Color(0xFFFAFAF1),
    onSurface = Color(0xFF1A1C16),
    surfaceVariant = Color(0xFFE1E4D5),
    onSurfaceVariant = Color(0xFF44483D),
    outline = Color(0xFF75796C),
)

private val KiwiDark = darkColorScheme(
    primary = Color(0xFFA9D37B),
    onPrimary = Color(0xFF1E3700),
    primaryContainer = Color(0xFF2D5005),
    onPrimaryContainer = Color(0xFFC4F094),
    secondary = Color(0xFFBFCBAD),
    onSecondary = Color(0xFF2A331F),
    secondaryContainer = Color(0xFF404A34),
    onSecondaryContainer = Color(0xFFDBE7C8),
    tertiary = Color(0xFFECBE90),
    onTertiary = Color(0xFF452B08),
    tertiaryContainer = Color(0xFF5F411C),
    onTertiaryContainer = Color(0xFFFFDDB9),
    background = Color(0xFF12140E),
    onBackground = Color(0xFFE3E3D8),
    surface = Color(0xFF12140E),
    onSurface = Color(0xFFE3E3D8),
    surfaceVariant = Color(0xFF44483D),
    onSurfaceVariant = Color(0xFFC5C8BA),
    outline = Color(0xFF8F9285),
)

@Composable
fun KiwiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> KiwiDark
        else -> KiwiLight
    }
    MaterialTheme(colorScheme = colors, content = content)
}
