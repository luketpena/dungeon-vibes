package com.dungeonvibes.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

/*
 * The app's own palette: dark indigo surfaces with gold accents. Fixed (no wallpaper colors), so
 * every phone sees the same app, and dark only: it's used at a dim table.
 */

/* Gold: the accent. Buttons, switches, the FAB, tabs, and settings-box headers. */
private val Gold = Color(0xFFF2C14E)
private val GoldInk = Color(0xFF3E2E00)
private val GoldDeep = Color(0xFF5A4300)
private val GoldPale = Color(0xFFFFE08E)

/* Lavender: the quiet partner to gold. Selected rows, tonal buttons, chips. */
private val Lavender = Color(0xFFC6C0F5)
private val LavenderInk = Color(0xFF2E2860)
private val LavenderDeep = Color(0xFF444078)
private val LavenderPale = Color(0xFFE4DFFF)

/* Ember: a warm third accent for the occasional highlight. */
private val Ember = Color(0xFFFFB68F)
private val EmberInk = Color(0xFF542100)
private val EmberDeep = Color(0xFF77330F)
private val EmberPale = Color(0xFFFFDBCA)

/* Indigo: the surfaces, darkest (page) to lightest (raised). */
private val IndigoLowest = Color(0xFF0E0C22)
private val IndigoPage = Color(0xFF13112A)
private val IndigoLow = Color(0xFF1B1934)
private val Indigo = Color(0xFF1F1D3A)
private val IndigoHigh = Color(0xFF2A2846)
private val IndigoHighest = Color(0xFF353352)
private val IndigoBright = Color(0xFF3A3758)
private val IndigoVariant = Color(0xFF3F3C5E)

private val Moonlight = Color(0xFFE7E4F7)
private val MoonlightDim = Color(0xFFC6C2DE)
private val Outline = Color(0xFF908CAA)
private val OutlineDim = Color(0xFF45425F)

val AppColors = darkColorScheme(
    primary = Gold,
    onPrimary = GoldInk,
    primaryContainer = GoldDeep,
    onPrimaryContainer = GoldPale,
    inversePrimary = Color(0xFF7A5B00),
    secondary = Lavender,
    onSecondary = LavenderInk,
    secondaryContainer = LavenderDeep,
    onSecondaryContainer = LavenderPale,
    tertiary = Ember,
    onTertiary = EmberInk,
    tertiaryContainer = EmberDeep,
    onTertiaryContainer = EmberPale,
    background = IndigoPage,
    onBackground = Moonlight,
    surface = IndigoPage,
    onSurface = Moonlight,
    surfaceVariant = IndigoVariant,
    onSurfaceVariant = MoonlightDim,
    surfaceTint = Gold,
    inverseSurface = Moonlight,
    inverseOnSurface = Color(0xFF2F2D48),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Outline,
    outlineVariant = OutlineDim,
    scrim = Color.Black,
    surfaceBright = IndigoBright,
    surfaceDim = IndigoPage,
    surfaceContainerLowest = IndigoLowest,
    surfaceContainerLow = IndigoLow,
    surfaceContainer = Indigo,
    surfaceContainerHigh = IndigoHigh,
    surfaceContainerHighest = IndigoHighest,
)
