package com.quantlm.yaser.presentation.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.quantlm.yaser.data.local.AppPreferences

data class ThemePaletteButtonColors(
    val top: Color,
    val bottomStart: Color,
    val bottomEnd: Color
)

data class ThemePaletteOption(
    val source: AppPreferences.ThemeColorSource,
    val label: String,
    val buttonColors: ThemePaletteButtonColors,
    val lightScheme: ColorScheme,
    val darkScheme: ColorScheme
)

object ThemePaletteRegistry {

    private fun mix(start: Color, end: Color, amount: Float): Color {
        return lerp(start, end, amount.coerceIn(0f, 1f))
    }

    private fun onColor(background: Color): Color {
        return if (background.luminance() > 0.45f) Color(0xFF161616) else Color.White
    }

    private fun makeLightScheme(primary: Color, secondary: Color, tertiary: Color): ColorScheme {
        val primaryContainer = mix(primary, Color.White, 0.78f)
        val secondaryContainer = mix(secondary, Color.White, 0.78f)
        val tertiaryContainer = mix(tertiary, Color.White, 0.78f)

        return lightColorScheme(
            primary = primary,
            onPrimary = onColor(primary),
            primaryContainer = primaryContainer,
            onPrimaryContainer = mix(primary, Color.Black, 0.62f),
            secondary = secondary,
            onSecondary = onColor(secondary),
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = mix(secondary, Color.Black, 0.62f),
            tertiary = tertiary,
            onTertiary = onColor(tertiary),
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = mix(tertiary, Color.Black, 0.62f),
            background = Color(0xFFFFFBFE),
            onBackground = Color(0xFF1C1B1F),
            surface = Color(0xFFFFFBFE),
            onSurface = Color(0xFF1C1B1F),
            surfaceVariant = Color(0xFFF0E8EC),
            onSurfaceVariant = Color(0xFF4D4449)
        )
    }

    private fun makeDarkScheme(primary: Color, secondary: Color, tertiary: Color): ColorScheme {
        val p = mix(primary, Color.White, 0.26f)
        val s = mix(secondary, Color.White, 0.26f)
        val t = mix(tertiary, Color.White, 0.26f)

        return darkColorScheme(
            primary = p,
            onPrimary = mix(primary, Color.Black, 0.72f),
            primaryContainer = mix(primary, Color.Black, 0.56f),
            onPrimaryContainer = mix(primary, Color.White, 0.68f),
            secondary = s,
            onSecondary = mix(secondary, Color.Black, 0.72f),
            secondaryContainer = mix(secondary, Color.Black, 0.56f),
            onSecondaryContainer = mix(secondary, Color.White, 0.68f),
            tertiary = t,
            onTertiary = mix(tertiary, Color.Black, 0.72f),
            tertiaryContainer = mix(tertiary, Color.Black, 0.56f),
            onTertiaryContainer = mix(tertiary, Color.White, 0.68f),
            background = Color(0xFF1B1B1F),
            onBackground = Color(0xFFE4E1E6),
            surface = Color(0xFF1B1B1F),
            onSurface = Color(0xFFE4E1E6),
            surfaceVariant = Color(0xFF2D2A2F),
            onSurfaceVariant = Color(0xFFCCC4CB)
        )
    }

    val manualOptions: List<ThemePaletteOption> = listOf(
        ThemePaletteOption(
            source = AppPreferences.ThemeColorSource.SUNSET,
            label = "Sunset",
            buttonColors = ThemePaletteButtonColors(
                top = Color(0xFF9C4D45),
                bottomStart = Color(0xFFE7B8AF),
                bottomEnd = Color(0xFFD2A154)
            ),
            lightScheme = makeLightScheme(
                primary = Color(0xFF9C4D45),
                secondary = Color(0xFFD2A154),
                tertiary = Color(0xFFE7B8AF)
            ),
            darkScheme = makeDarkScheme(
                primary = Color(0xFF9C4D45),
                secondary = Color(0xFFD2A154),
                tertiary = Color(0xFFE7B8AF)
            )
        ),
        ThemePaletteOption(
            source = AppPreferences.ThemeColorSource.MONOCHROME,
            label = "Mono",
            buttonColors = ThemePaletteButtonColors(
                top = Color(0xFF000000),
                bottomStart = Color(0xFFCBCDD0),
                bottomEnd = Color(0xFFA8AAAE)
            ),
            lightScheme = makeLightScheme(
                primary = Color(0xFF303236),
                secondary = Color(0xFF595D62),
                tertiary = Color(0xFF7B8086)
            ),
            darkScheme = makeDarkScheme(
                primary = Color(0xFF303236),
                secondary = Color(0xFF595D62),
                tertiary = Color(0xFF7B8086)
            )
        ),
        ThemePaletteOption(
            source = AppPreferences.ThemeColorSource.ROSE_TAUPE,
            label = "Rose",
            buttonColors = ThemePaletteButtonColors(
                top = Color(0xFF7B6468),
                bottomStart = Color(0xFFD8C8C6),
                bottomEnd = Color(0xFFC89AAF)
            ),
            lightScheme = makeLightScheme(
                primary = Color(0xFF7B6468),
                secondary = Color(0xFF9E7D85),
                tertiary = Color(0xFFC89AAF)
            ),
            darkScheme = makeDarkScheme(
                primary = Color(0xFF7B6468),
                secondary = Color(0xFF9E7D85),
                tertiary = Color(0xFFC89AAF)
            )
        ),
        ThemePaletteOption(
            source = AppPreferences.ThemeColorSource.CRIMSON_ORCHID,
            label = "Crimson",
            buttonColors = ThemePaletteButtonColors(
                top = Color(0xFFD11100),
                bottomStart = Color(0xFFE7B9C2),
                bottomEnd = Color(0xFFB883E6)
            ),
            lightScheme = makeLightScheme(
                primary = Color(0xFFB3271A),
                secondary = Color(0xFFD25D6E),
                tertiary = Color(0xFF9B63CF)
            ),
            darkScheme = makeDarkScheme(
                primary = Color(0xFFB3271A),
                secondary = Color(0xFFD25D6E),
                tertiary = Color(0xFF9B63CF)
            )
        ),
        ThemePaletteOption(
            source = AppPreferences.ThemeColorSource.CLAY_CYAN,
            label = "Clay/Cyan",
            buttonColors = ThemePaletteButtonColors(
                top = Color(0xFFB23C31),
                bottomStart = Color(0xFF9ECAE0),
                bottomEnd = Color(0xFF17A9D0)
            ),
            lightScheme = makeLightScheme(
                primary = Color(0xFFB23C31),
                secondary = Color(0xFF2F95B9),
                tertiary = Color(0xFF45B8DA)
            ),
            darkScheme = makeDarkScheme(
                primary = Color(0xFFB23C31),
                secondary = Color(0xFF2F95B9),
                tertiary = Color(0xFF45B8DA)
            )
        ),
        ThemePaletteOption(
            source = AppPreferences.ThemeColorSource.FOREST_MOSS,
            label = "Forest",
            buttonColors = ThemePaletteButtonColors(
                top = Color(0xFF2F6F3F),
                bottomStart = Color(0xFFB8CFB3),
                bottomEnd = Color(0xFFA8AE63)
            ),
            lightScheme = makeLightScheme(
                primary = Color(0xFF2F6F3F),
                secondary = Color(0xFF5F8C4D),
                tertiary = Color(0xFF8E9C4A)
            ),
            darkScheme = makeDarkScheme(
                primary = Color(0xFF2F6F3F),
                secondary = Color(0xFF5F8C4D),
                tertiary = Color(0xFF8E9C4A)
            )
        ),
        ThemePaletteOption(
            source = AppPreferences.ThemeColorSource.SAGE_STONE,
            label = "Sage",
            buttonColors = ThemePaletteButtonColors(
                top = Color(0xFF5F6A5F),
                bottomStart = Color(0xFFBFC0B8),
                bottomEnd = Color(0xFF88AE95)
            ),
            lightScheme = makeLightScheme(
                primary = Color(0xFF5F6A5F),
                secondary = Color(0xFF6D8772),
                tertiary = Color(0xFF88AE95)
            ),
            darkScheme = makeDarkScheme(
                primary = Color(0xFF5F6A5F),
                secondary = Color(0xFF6D8772),
                tertiary = Color(0xFF88AE95)
            )
        )
    )

    fun manualOptionFor(source: AppPreferences.ThemeColorSource): ThemePaletteOption? {
        return manualOptions.firstOrNull { it.source == source }
    }
}
