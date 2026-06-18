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
        ),
        // ── Image 1: pastel duos ──────────────────────────────────────────
        ThemePaletteOption(
            source = AppPreferences.ThemeColorSource.SOFT_LAVENDER,
            label = "Lavender",
            buttonColors = ThemePaletteButtonColors(
                top = Color(0xFF7060B8),
                bottomStart = Color(0xFFB0A0F0),
                bottomEnd = Color(0xFF9080D0)
            ),
            lightScheme = makeLightScheme(
                primary = Color(0xFF7060B8),
                secondary = Color(0xFF9080D0),
                tertiary = Color(0xFFB0A0F0)
            ),
            darkScheme = makeDarkScheme(
                primary = Color(0xFF7060B8),
                secondary = Color(0xFF9080D0),
                tertiary = Color(0xFFB0A0F0)
            )
        ),
        ThemePaletteOption(
            source = AppPreferences.ThemeColorSource.BUBBLEGUM,
            label = "Bubblegum",
            buttonColors = ThemePaletteButtonColors(
                top = Color(0xFFC050A8),
                bottomStart = Color(0xFFF090D8),
                bottomEnd = Color(0xFFE070C0)
            ),
            lightScheme = makeLightScheme(
                primary = Color(0xFFC050A8),
                secondary = Color(0xFFE070C0),
                tertiary = Color(0xFFF090D8)
            ),
            darkScheme = makeDarkScheme(
                primary = Color(0xFFC050A8),
                secondary = Color(0xFFE070C0),
                tertiary = Color(0xFFF090D8)
            )
        ),
        ThemePaletteOption(
            source = AppPreferences.ThemeColorSource.PEACH_TANGERINE,
            label = "Peach Tangerine",
            buttonColors = ThemePaletteButtonColors(
                top = Color(0xFFD08030),
                bottomStart = Color(0xFFF8C870),
                bottomEnd = Color(0xFFF0A060)
            ),
            lightScheme = makeLightScheme(
                primary = Color(0xFFD08030),
                secondary = Color(0xFFF0A060),
                tertiary = Color(0xFFF8C870)
            ),
            darkScheme = makeDarkScheme(
                primary = Color(0xFFD08030),
                secondary = Color(0xFFF0A060),
                tertiary = Color(0xFFF8C870)
            )
        ),
        ThemePaletteOption(
            source = AppPreferences.ThemeColorSource.MINT_LIME,
            label = "Mint Lime",
            buttonColors = ThemePaletteButtonColors(
                top = Color(0xFF48A840),
                bottomStart = Color(0xFF80D870),
                bottomEnd = Color(0xFFC8C830)
            ),
            lightScheme = makeLightScheme(
                primary = Color(0xFF48A840),
                secondary = Color(0xFF80D870),
                tertiary = Color(0xFFC8C830)
            ),
            darkScheme = makeDarkScheme(
                primary = Color(0xFF48A840),
                secondary = Color(0xFF80D870),
                tertiary = Color(0xFFC8C830)
            )
        ),
        ThemePaletteOption(
            source = AppPreferences.ThemeColorSource.SKY_TEAL,
            label = "Sky Teal",
            buttonColors = ThemePaletteButtonColors(
                top = Color(0xFF2898A8),
                bottomStart = Color(0xFF78D0E8),
                bottomEnd = Color(0xFF50C090)
            ),
            lightScheme = makeLightScheme(
                primary = Color(0xFF2898A8),
                secondary = Color(0xFF50C090),
                tertiary = Color(0xFF78D0E8)
            ),
            darkScheme = makeDarkScheme(
                primary = Color(0xFF2898A8),
                secondary = Color(0xFF50C090),
                tertiary = Color(0xFF78D0E8)
            )
        ),
        ThemePaletteOption(
            source = AppPreferences.ThemeColorSource.BLUE_LAVENDER,
            label = "Blue Lavender",
            buttonColors = ThemePaletteButtonColors(
                top = Color(0xFF5868C8),
                bottomStart = Color(0xFFB8C0F0),
                bottomEnd = Color(0xFF8898D8)
            ),
            lightScheme = makeLightScheme(
                primary = Color(0xFF5868C8),
                secondary = Color(0xFF8898D8),
                tertiary = Color(0xFFB8C0F0)
            ),
            darkScheme = makeDarkScheme(
                primary = Color(0xFF5868C8),
                secondary = Color(0xFF8898D8),
                tertiary = Color(0xFFB8C0F0)
            )
        ),
        ThemePaletteOption(
            source = AppPreferences.ThemeColorSource.ROSE_BLUSH,
            label = "Rose Blush",
            buttonColors = ThemePaletteButtonColors(
                top = Color(0xFFC06090),
                bottomStart = Color(0xFFF0B8D8),
                bottomEnd = Color(0xFFE090C0)
            ),
            lightScheme = makeLightScheme(
                primary = Color(0xFFC06090),
                secondary = Color(0xFFE090C0),
                tertiary = Color(0xFFF0B8D8)
            ),
            darkScheme = makeDarkScheme(
                primary = Color(0xFFC06090),
                secondary = Color(0xFFE090C0),
                tertiary = Color(0xFFF0B8D8)
            )
        ),
        // ── Image 2: muted pastels ────────────────────────────────────────
        ThemePaletteOption(
            source = AppPreferences.ThemeColorSource.WARM_SAND,
            label = "Warm Sand",
            buttonColors = ThemePaletteButtonColors(
                top = Color(0xFF807055),
                bottomStart = Color(0xFFC0BAA0),
                bottomEnd = Color(0xFFA89070)
            ),
            lightScheme = makeLightScheme(
                primary = Color(0xFF807055),
                secondary = Color(0xFFA89070),
                tertiary = Color(0xFFC0BAA0)
            ),
            darkScheme = makeDarkScheme(
                primary = Color(0xFF807055),
                secondary = Color(0xFFA89070),
                tertiary = Color(0xFFC0BAA0)
            )
        ),
        ThemePaletteOption(
            source = AppPreferences.ThemeColorSource.SAGE_MIST,
            label = "Sage Mist",
            buttonColors = ThemePaletteButtonColors(
                top = Color(0xFF507870),
                bottomStart = Color(0xFFA8BEB8),
                bottomEnd = Color(0xFF789898)
            ),
            lightScheme = makeLightScheme(
                primary = Color(0xFF507870),
                secondary = Color(0xFF789898),
                tertiary = Color(0xFFA8BEB8)
            ),
            darkScheme = makeDarkScheme(
                primary = Color(0xFF507870),
                secondary = Color(0xFF789898),
                tertiary = Color(0xFFA8BEB8)
            )
        ),
        ThemePaletteOption(
            source = AppPreferences.ThemeColorSource.DUSTY_LAVENDER,
            label = "Dusty Lavender",
            buttonColors = ThemePaletteButtonColors(
                top = Color(0xFF706080),
                bottomStart = Color(0xFFC0B8D8),
                bottomEnd = Color(0xFF9888A8)
            ),
            lightScheme = makeLightScheme(
                primary = Color(0xFF706080),
                secondary = Color(0xFF9888A8),
                tertiary = Color(0xFFC0B8D8)
            ),
            darkScheme = makeDarkScheme(
                primary = Color(0xFF706080),
                secondary = Color(0xFF9888A8),
                tertiary = Color(0xFFC0B8D8)
            )
        ),
        ThemePaletteOption(
            source = AppPreferences.ThemeColorSource.SOFT_SAGE,
            label = "Soft Sage",
            buttonColors = ThemePaletteButtonColors(
                top = Color(0xFF508040),
                bottomStart = Color(0xFFA8C888),
                bottomEnd = Color(0xFF78A860)
            ),
            lightScheme = makeLightScheme(
                primary = Color(0xFF508040),
                secondary = Color(0xFF78A860),
                tertiary = Color(0xFFA8C888)
            ),
            darkScheme = makeDarkScheme(
                primary = Color(0xFF508040),
                secondary = Color(0xFF78A860),
                tertiary = Color(0xFFA8C888)
            )
        ),
        ThemePaletteOption(
            source = AppPreferences.ThemeColorSource.PERIWINKLE,
            label = "Periwinkle",
            buttonColors = ThemePaletteButtonColors(
                top = Color(0xFF5858A0),
                bottomStart = Color(0xFFA8A8D8),
                bottomEnd = Color(0xFF8080B8)
            ),
            lightScheme = makeLightScheme(
                primary = Color(0xFF5858A0),
                secondary = Color(0xFF8080B8),
                tertiary = Color(0xFFA8A8D8)
            ),
            darkScheme = makeDarkScheme(
                primary = Color(0xFF5858A0),
                secondary = Color(0xFF8080B8),
                tertiary = Color(0xFFA8A8D8)
            )
        ),
        ThemePaletteOption(
            source = AppPreferences.ThemeColorSource.OLIVE_GROVE,
            label = "Olive",
            buttonColors = ThemePaletteButtonColors(
                top = Color(0xFF6B7818),
                bottomStart = Color(0xFFB8CA48),
                bottomEnd = Color(0xFF909830)
            ),
            lightScheme = makeLightScheme(
                primary = Color(0xFF6B7818),
                secondary = Color(0xFF909830),
                tertiary = Color(0xFFB8CA48)
            ),
            darkScheme = makeDarkScheme(
                primary = Color(0xFF6B7818),
                secondary = Color(0xFF909830),
                tertiary = Color(0xFFB8CA48)
            )
        ),
        ThemePaletteOption(
            source = AppPreferences.ThemeColorSource.POWDER_BLUE,
            label = "Powder Blue",
            buttonColors = ThemePaletteButtonColors(
                top = Color(0xFF4878B0),
                bottomStart = Color(0xFFA8C0E8),
                bottomEnd = Color(0xFF7898C8)
            ),
            lightScheme = makeLightScheme(
                primary = Color(0xFF4878B0),
                secondary = Color(0xFF7898C8),
                tertiary = Color(0xFFA8C0E8)
            ),
            darkScheme = makeDarkScheme(
                primary = Color(0xFF4878B0),
                secondary = Color(0xFF7898C8),
                tertiary = Color(0xFFA8C0E8)
            )
        ),
        // ── Image 3: bright pastels ───────────────────────────────────────
        ThemePaletteOption(
            source = AppPreferences.ThemeColorSource.SALMON,
            label = "Salmon",
            buttonColors = ThemePaletteButtonColors(
                top = Color(0xFFB85860),
                bottomStart = Color(0xFFF0A0A8),
                bottomEnd = Color(0xFFD87880)
            ),
            lightScheme = makeLightScheme(
                primary = Color(0xFFB85860),
                secondary = Color(0xFFD87880),
                tertiary = Color(0xFFF0A0A8)
            ),
            darkScheme = makeDarkScheme(
                primary = Color(0xFFB85860),
                secondary = Color(0xFFD87880),
                tertiary = Color(0xFFF0A0A8)
            )
        ),
        ThemePaletteOption(
            source = AppPreferences.ThemeColorSource.BLUSH,
            label = "Blush",
            buttonColors = ThemePaletteButtonColors(
                top = Color(0xFFB86868),
                bottomStart = Color(0xFFF0B0B0),
                bottomEnd = Color(0xFFD88888)
            ),
            lightScheme = makeLightScheme(
                primary = Color(0xFFB86868),
                secondary = Color(0xFFD88888),
                tertiary = Color(0xFFF0B0B0)
            ),
            darkScheme = makeDarkScheme(
                primary = Color(0xFFB86868),
                secondary = Color(0xFFD88888),
                tertiary = Color(0xFFF0B0B0)
            )
        ),
        ThemePaletteOption(
            source = AppPreferences.ThemeColorSource.APRICOT,
            label = "Apricot",
            buttonColors = ThemePaletteButtonColors(
                top = Color(0xFFC07030),
                bottomStart = Color(0xFFF8B880),
                bottomEnd = Color(0xFFE09060)
            ),
            lightScheme = makeLightScheme(
                primary = Color(0xFFC07030),
                secondary = Color(0xFFE09060),
                tertiary = Color(0xFFF8B880)
            ),
            darkScheme = makeDarkScheme(
                primary = Color(0xFFC07030),
                secondary = Color(0xFFE09060),
                tertiary = Color(0xFFF8B880)
            )
        ),
        ThemePaletteOption(
            source = AppPreferences.ThemeColorSource.AMBER,
            label = "Amber",
            buttonColors = ThemePaletteButtonColors(
                top = Color(0xFFA07818),
                bottomStart = Color(0xFFD8B040),
                bottomEnd = Color(0xFFC09028)
            ),
            lightScheme = makeLightScheme(
                primary = Color(0xFFA07818),
                secondary = Color(0xFFC09028),
                tertiary = Color(0xFFD8B040)
            ),
            darkScheme = makeDarkScheme(
                primary = Color(0xFFA07818),
                secondary = Color(0xFFC09028),
                tertiary = Color(0xFFD8B040)
            )
        ),
        ThemePaletteOption(
            source = AppPreferences.ThemeColorSource.APPLE_GREEN,
            label = "Apple Green",
            buttonColors = ThemePaletteButtonColors(
                top = Color(0xFF508030),
                bottomStart = Color(0xFF90C870),
                bottomEnd = Color(0xFF70A840)
            ),
            lightScheme = makeLightScheme(
                primary = Color(0xFF508030),
                secondary = Color(0xFF70A840),
                tertiary = Color(0xFF90C870)
            ),
            darkScheme = makeDarkScheme(
                primary = Color(0xFF508030),
                secondary = Color(0xFF70A840),
                tertiary = Color(0xFF90C870)
            )
        ),
        ThemePaletteOption(
            source = AppPreferences.ThemeColorSource.CORNFLOWER,
            label = "Cornflower",
            buttonColors = ThemePaletteButtonColors(
                top = Color(0xFF4060B8),
                bottomStart = Color(0xFF90B8F0),
                bottomEnd = Color(0xFF6888D0)
            ),
            lightScheme = makeLightScheme(
                primary = Color(0xFF4060B8),
                secondary = Color(0xFF6888D0),
                tertiary = Color(0xFF90B8F0)
            ),
            darkScheme = makeDarkScheme(
                primary = Color(0xFF4060B8),
                secondary = Color(0xFF6888D0),
                tertiary = Color(0xFF90B8F0)
            )
        ),
        ThemePaletteOption(
            source = AppPreferences.ThemeColorSource.LILAC,
            label = "Lilac",
            buttonColors = ThemePaletteButtonColors(
                top = Color(0xFF6848B8),
                bottomStart = Color(0xFFC0A8F0),
                bottomEnd = Color(0xFF9070D0)
            ),
            lightScheme = makeLightScheme(
                primary = Color(0xFF6848B8),
                secondary = Color(0xFF9070D0),
                tertiary = Color(0xFFC0A8F0)
            ),
            darkScheme = makeDarkScheme(
                primary = Color(0xFF6848B8),
                secondary = Color(0xFF9070D0),
                tertiary = Color(0xFFC0A8F0)
            )
        )
    )

    fun manualOptionFor(source: AppPreferences.ThemeColorSource): ThemePaletteOption? {
        return manualOptions.firstOrNull { it.source == source }
    }
}
