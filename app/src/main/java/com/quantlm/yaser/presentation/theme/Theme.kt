package com.quantlm.yaser.presentation.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.quantlm.yaser.data.local.AppPreferences

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    secondary = Color(0xFFCCC2DC),
    tertiary = Color(0xFFEFB8C8),
    background = Color(0xFF1C1B1F),
    surface = Color(0xFF1C1B1F),
    onPrimary = Color(0xFF381E72),
    onSecondary = Color(0xFF332D41),
    onTertiary = Color(0xFF492532),
    onBackground = Color(0xFFE6E1E5),
    onSurface = Color(0xFFE6E1E5),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF6750A4),
    secondary = Color(0xFF625B71),
    tertiary = Color(0xFF7D5260),
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
)

@Composable
fun QuantLMTheme(
    themeMode: AppPreferences.ThemeMode = AppPreferences.ThemeMode.FOLLOW_SYSTEM,
    colorSource: AppPreferences.ThemeColorSource = AppPreferences.ThemeColorSource.FOLLOW_SYSTEM,
    content: @Composable () -> Unit
) {
    val useDarkTheme = when (themeMode) {
        AppPreferences.ThemeMode.FOLLOW_SYSTEM -> isSystemInDarkTheme()
        AppPreferences.ThemeMode.LIGHT -> false
        AppPreferences.ThemeMode.DARK -> true
    }

    val context = LocalContext.current

    val colorScheme = when (colorSource) {
        AppPreferences.ThemeColorSource.FOLLOW_SYSTEM -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (useDarkTheme) {
                    dynamicDarkColorScheme(context)
                } else {
                    dynamicLightColorScheme(context)
                }
            } else if (useDarkTheme) {
                DarkColorScheme
            } else {
                LightColorScheme
            }
        }

        else -> {
            val option = ThemePaletteRegistry.manualOptionFor(colorSource)
            when {
                option != null && useDarkTheme -> option.darkScheme
                option != null -> option.lightScheme
                useDarkTheme -> DarkColorScheme
                else -> LightColorScheme
            }
        }
    }
    
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.primary.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !useDarkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
