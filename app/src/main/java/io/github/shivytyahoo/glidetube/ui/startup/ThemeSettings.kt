package io.github.shivytyahoo.glidetube.ui.startup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.font.FontFamily
import io.github.shivytyahoo.glidetube.data.local.AppFontPreferences
import io.github.shivytyahoo.glidetube.data.local.LocalDataManager
import io.github.shivytyahoo.glidetube.ui.theme.CustomTheme
import io.github.shivytyahoo.glidetube.ui.theme.GlideFontFamily
import io.github.shivytyahoo.glidetube.ui.theme.GlideTheme
import io.github.shivytyahoo.glidetube.ui.theme.ThemeMode
import io.github.shivytyahoo.glidetube.ui.theme.ThemeVariant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** Every stored theme choice the root theme needs, read as one value so the first frame has all of them. */
@Immutable
data class ThemeSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val themeVariant: ThemeVariant = ThemeVariant.DARK,
    val customTheme: CustomTheme? = null,
    val customLightTheme: CustomTheme? = null,
    val customDarkTheme: CustomTheme? = null,
    val systemLightThemeMode: ThemeMode = ThemeMode.DARK,
    val systemDarkThemeMode: ThemeMode = ThemeMode.DARK,
    val systemDarkThemeVariant: ThemeVariant = ThemeVariant.DARK,
    val fontFamily: FontFamily = GlideFontFamily,
)

fun LocalDataManager.themeSettings(fonts: AppFontPreferences): Flow<ThemeSettings> =
    combine(themeMode, themeVariant, activeCustomTheme, activeLightCustomTheme, activeDarkCustomTheme) { mode, variant, custom, customLight, customDark ->
        ThemeSettings(
            themeMode = mode,
            themeVariant = variant,
            customTheme = custom,
            customLightTheme = customLight,
            customDarkTheme = customDark,
        )
    }.combine(systemLightThemeMode) { settings, light -> settings.copy(systemLightThemeMode = light) }
        .combine(systemDarkThemeMode) { settings, dark -> settings.copy(systemDarkThemeMode = dark) }
        .combine(systemDarkThemeVariant) { settings, darkVariant -> settings.copy(systemDarkThemeVariant = darkVariant) }
        .combine(fonts.fontFamily) { settings, family -> settings.copy(fontFamily = family) }

@Composable
fun GlideTheme(
    settings: ThemeSettings,
    content: @Composable () -> Unit,
) = GlideTheme(
    themeMode = settings.themeMode,
    themeVariant = settings.themeVariant,
    customTheme = settings.customTheme,
    customLightTheme = settings.customLightTheme,
    customDarkTheme = settings.customDarkTheme,
    systemLightThemeMode = settings.systemLightThemeMode,
    systemDarkThemeMode = settings.systemDarkThemeMode,
    systemDarkThemeVariant = settings.systemDarkThemeVariant,
    fontFamily = settings.fontFamily,
    content = content,
)
