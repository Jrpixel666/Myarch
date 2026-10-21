package dev.myarch.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import dev.myarch.runtime.theme.BuiltInThemes
import dev.myarch.runtime.theme.OmarchyTheme
import dev.myarch.runtime.theme.parseHexColor

/** The Omarchy theme in force, available to any composable. */
public val LocalOmarchyTheme: androidx.compose.runtime.ProvidableCompositionLocal<OmarchyTheme> =
    staticCompositionLocalOf { BuiltInThemes.default }

/** Converts a palette hex string into a Compose colour. */
public fun OmarchyTheme.color(hex: String): Color = Color(parseHexColor(hex))

/**
 * Applies an Omarchy palette to the launcher's own UI.
 *
 * The point of threading the same palette through both sides is that the
 * launcher and the Linux session should never look like two different
 * products: pick Gruvbox in the app and the home screen, the app drawer, the
 * installer and the Quickshell bar all change together.
 */
@Composable
public fun MyarchTheme(
    theme: OmarchyTheme,
    content: @Composable () -> Unit,
) {
    val palette = theme.palette
    val background = Color(parseHexColor(palette.background))
    val surface = Color(parseHexColor(palette.surface))
    val foreground = Color(parseHexColor(palette.foreground))
    val accent = Color(parseHexColor(palette.accent))
    val accentAlt = Color(parseHexColor(palette.accentAlt))
    val error = Color(parseHexColor(palette.error))
    val muted = Color(parseHexColor(palette.muted))

    val scheme = if (theme.isLight) {
        lightColorScheme(
            primary = accent,
            onPrimary = background,
            secondary = accentAlt,
            onSecondary = background,
            background = background,
            onBackground = foreground,
            surface = surface,
            onSurface = foreground,
            surfaceVariant = surface,
            onSurfaceVariant = muted,
            error = error,
            onError = background,
            outline = muted,
        )
    } else {
        darkColorScheme(
            primary = accent,
            onPrimary = background,
            secondary = accentAlt,
            onSecondary = background,
            background = background,
            onBackground = foreground,
            surface = surface,
            onSurface = foreground,
            surfaceVariant = surface,
            onSurfaceVariant = muted,
            error = error,
            onError = background,
            outline = muted,
        )
    }

    CompositionLocalProvider(LocalOmarchyTheme provides theme) {
        MaterialTheme(
            colorScheme = scheme,
            typography = myarchTypography(),
            content = content,
        )
    }
}

/**
 * Monospace throughout.
 *
 * Omarchy is a terminal-first setup and its own UI is monospaced; matching it
 * is what makes the launcher read as part of the same system rather than a
 * generic Android app wrapped around it.
 */
private fun myarchTypography(): Typography {
    val mono = FontFamily.Monospace
    val base = Typography()
    return base.copy(
        displaySmall = base.displaySmall.monospace(mono),
        headlineLarge = base.headlineLarge.monospace(mono),
        headlineMedium = base.headlineMedium.monospace(mono),
        headlineSmall = base.headlineSmall.monospace(mono),
        titleLarge = base.titleLarge.monospace(mono),
        titleMedium = base.titleMedium.monospace(mono),
        titleSmall = base.titleSmall.monospace(mono),
        bodyLarge = base.bodyLarge.monospace(mono),
        bodyMedium = base.bodyMedium.monospace(mono),
        bodySmall = base.bodySmall.monospace(mono),
        labelLarge = base.labelLarge.monospace(mono),
        labelMedium = base.labelMedium.monospace(mono),
        labelSmall = base.labelSmall.copy(fontFamily = mono, fontSize = 10.sp),
    )
}

private fun TextStyle.monospace(family: FontFamily): TextStyle = copy(fontFamily = family)

/** True when the device itself is in dark mode; used only for the splash. */
@Composable
public fun systemPrefersDark(): Boolean = isSystemInDarkTheme()
