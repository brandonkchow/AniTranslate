package com.example.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val EditorialColorScheme = darkColorScheme(
    primary = EditorialLavender,
    onPrimary = EditorialDeepViolet,
    primaryContainer = EditorialVioletContainer,
    onPrimaryContainer = EditorialOnVioletContainer,

    secondary = Color(0xFFCCC2DC),
    onSecondary = Color(0xFF332D41),
    secondaryContainer = EditorialSurfaceAlt,
    onSecondaryContainer = Color(0xFFE8DEF8),

    tertiary = EditorialAmber,
    onTertiary = Color(0xFF492500),
    tertiaryContainer = Color(0xFF673600),
    onTertiaryContainer = Color(0xFFFFDDB3),

    background = EditorialCanvas,
    onBackground = EditorialTextPrimary,
    surface = EditorialSurface,
    onSurface = EditorialTextPrimary,
    surfaceVariant = EditorialSurfaceAlt,
    onSurfaceVariant = EditorialTextSecondary,
    surfaceContainer = EditorialSurface,
    surfaceContainerHigh = EditorialSurfaceAlt,

    error = EditorialCrimsonLight,
    onError = EditorialCrimson,
    errorContainer = EditorialCrimson,
    onErrorContainer = EditorialCrimsonText,

    outline = EditorialSurfaceBorder,
    outlineVariant = EditorialSurfaceBorder.copy(alpha = 0.35f)
)

@Composable
fun BubbleforgeTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = EditorialColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = false
                controller.isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) = BubbleforgeTheme(darkTheme, dynamicColor, content)
