package com.utility.dlmsreader.ui.theme

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Every colour role the MeterLink UI uses. Screens read the roles below
 * (`Ink`, `CardSurface`, ...) instead of hard-coding light colours, so the
 * whole app follows the selected light or dark palette.
 */
@Immutable
internal data class MeterLinkPalette(
    val isDark: Boolean,
    /** Primary text. */
    val ink: Color,
    /** Secondary text and icons. */
    val muted: Color,
    /** Tertiary text such as field labels. */
    val subtleText: Color,
    /** Brand accent: buttons, links, selected chips. */
    val cobalt: Color,
    /** Text and icons drawn on top of [cobalt]. */
    val onCobalt: Color,
    /** Lighter accent for outlines on secondary buttons. */
    val accentBlue: Color,
    val softBlue: Color,
    val softBlueBorder: Color,
    /** Selected-chip outline. */
    val selectedBorder: Color,
    /** Progress-bar track. */
    val trackBlue: Color,
    /** Info panels inside cards. */
    val panelBlue: Color,
    /** Screen background behind the cards. */
    val background: Color,
    /** Cards, dialogs, menus. */
    val cardSurface: Color,
    /** Stat tiles and zebra rows inside cards. */
    val tileFill: Color,
    val hairline: Color,
    val strongBorder: Color,
    val switchTrackOff: Color,
    val successFill: Color,
    val successText: Color,
    val warningText: Color,
    val errorText: Color,
    val danger: Color,
    val healthy: Color
)

internal val LightPalette = MeterLinkPalette(
    isDark = false,
    ink = Color(0xFF0F172A),
    muted = Color(0xFF64748B),
    subtleText = Color(0xFF475569),
    cobalt = Color(0xFF1D4ED8),
    onCobalt = Color.White,
    accentBlue = Color(0xFF3B82F6),
    softBlue = Color(0xFFEFF6FF),
    softBlueBorder = Color(0xFFBFDBFE),
    selectedBorder = Color(0xFF93C5FD),
    trackBlue = Color(0xFFDBEAFE),
    panelBlue = Color(0xFFF0F7FF),
    background = Color(0xFFF8FAFC),
    cardSurface = Color.White,
    tileFill = Color(0xFFF8FAFC),
    hairline = Color(0xFFE2E8F0),
    strongBorder = Color(0xFFCBD5E1),
    switchTrackOff = Color(0xFFE2E8F0),
    successFill = Color(0xFFDCFCE7),
    successText = Color(0xFF166534),
    warningText = Color(0xFFB45309),
    errorText = Color(0xFFB91C1C),
    danger = Color(0xFFEF4444),
    healthy = Color(0xFF10B981)
)

internal val DarkPalette = MeterLinkPalette(
    isDark = true,
    ink = Color(0xFFE2E8F0),
    muted = Color(0xFF94A3B8),
    subtleText = Color(0xFFCBD5E1),
    cobalt = Color(0xFF60A5FA),
    onCobalt = Color(0xFF0B1220),
    accentBlue = Color(0xFF3B82F6),
    softBlue = Color(0xFF172554),
    softBlueBorder = Color(0xFF1E3A8A),
    selectedBorder = Color(0xFF3B82F6),
    trackBlue = Color(0xFF1E3A8A),
    panelBlue = Color(0xFF0F1E3D),
    background = Color(0xFF0B1220),
    cardSurface = Color(0xFF131C2E),
    tileFill = Color(0xFF1A2438),
    hairline = Color(0xFF26334A),
    strongBorder = Color(0xFF334155),
    switchTrackOff = Color(0xFF334155),
    successFill = Color(0xFF052E16),
    successText = Color(0xFF86EFAC),
    warningText = Color(0xFFFBBF24),
    errorText = Color(0xFFF87171),
    danger = Color(0xFFF87171),
    healthy = Color(0xFF34D399)
)

internal val LocalMeterLinkPalette = staticCompositionLocalOf { LightPalette }

/** The palette of the current composition. */
internal val Palette: MeterLinkPalette
    @Composable @ReadOnlyComposable get() = LocalMeterLinkPalette.current

// Short names kept from the original light-only palette so existing call
// sites switch with the theme without further changes.
internal val Ink: Color @Composable @ReadOnlyComposable get() = Palette.ink
internal val Muted: Color @Composable @ReadOnlyComposable get() = Palette.muted
internal val Cobalt: Color @Composable @ReadOnlyComposable get() = Palette.cobalt
internal val SoftBlue: Color @Composable @ReadOnlyComposable get() = Palette.softBlue
internal val SoftBlueBorder: Color @Composable @ReadOnlyComposable get() = Palette.softBlueBorder
internal val TileFill: Color @Composable @ReadOnlyComposable get() = Palette.tileFill
internal val Hairline: Color @Composable @ReadOnlyComposable get() = Palette.hairline
internal val CardSurface: Color @Composable @ReadOnlyComposable get() = Palette.cardSurface

internal fun meterLinkColorScheme(palette: MeterLinkPalette): ColorScheme {
    val base = if (palette.isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = palette.cobalt,
        onPrimary = palette.onCobalt,
        secondary = palette.accentBlue,
        onSecondary = Color.White,
        background = palette.background,
        onBackground = palette.ink,
        surface = palette.cardSurface,
        onSurface = palette.ink,
        surfaceVariant = palette.tileFill,
        onSurfaceVariant = palette.muted,
        surfaceTint = Color.Transparent,
        surfaceContainer = palette.cardSurface,
        surfaceContainerLow = palette.cardSurface,
        surfaceContainerHigh = palette.cardSurface,
        surfaceContainerHighest = palette.tileFill,
        secondaryContainer = palette.softBlue,
        onSecondaryContainer = palette.cobalt,
        outline = palette.hairline,
        outlineVariant = palette.hairline,
        error = palette.danger
    )
}

@Composable
internal fun ProvideMeterLinkPalette(palette: MeterLinkPalette, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalMeterLinkPalette provides palette, content = content)
}

/** User's appearance choice. SYSTEM follows the phone's dark-theme setting. */
internal enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Persisted appearance setting, observable from Compose. Stored in the app's
 * own SharedPreferences; nothing leaves the device.
 */
internal object ThemePreferences {
    private const val PREFS = "meterlink_ui"
    private const val KEY_MODE = "theme_mode"

    var mode by mutableStateOf(ThemeMode.SYSTEM)
        private set

    fun load(context: Context) {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODE, null)
        mode = ThemeMode.entries.firstOrNull { it.name == stored } ?: ThemeMode.SYSTEM
    }

    fun set(context: Context, newMode: ThemeMode) {
        mode = newMode
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, newMode.name)
            .apply()
    }

    fun isDark(systemDark: Boolean): Boolean = when (mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
}
