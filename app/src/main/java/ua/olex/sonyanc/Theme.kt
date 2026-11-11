@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package ua.olex.sonyanc

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Typeface

/**
 * Pixel phones ship Google Sans as a system font family. Use it when present so the app
 * reads like Google's own; anywhere else this returns null and the default sans is kept.
 */
private fun systemFamily(vararg names: String): FontFamily? {
    for (name in names) {
        val typeface = android.graphics.Typeface.create(name, android.graphics.Typeface.NORMAL)
        // An unknown family name resolves to the default typeface object itself.
        if (typeface != android.graphics.Typeface.DEFAULT) return FontFamily(Typeface(typeface))
    }
    return null
}

@Composable
private fun appTypography(): Typography = remember {
    val base = Typography()
    val display = systemFamily("google-sans-flex", "google-sans")
    val text = systemFamily("google-sans-text", "google-sans-flex", "google-sans") ?: display
    if (display == null) return@remember base
    val body = text ?: display
    base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = display),
        displayMedium = base.displayMedium.copy(fontFamily = display),
        displaySmall = base.displaySmall.copy(fontFamily = display),
        headlineLarge = base.headlineLarge.copy(fontFamily = display),
        headlineMedium = base.headlineMedium.copy(fontFamily = display),
        headlineSmall = base.headlineSmall.copy(fontFamily = display),
        titleLarge = base.titleLarge.copy(fontFamily = display),
        titleMedium = base.titleMedium.copy(fontFamily = display),
        titleSmall = base.titleSmall.copy(fontFamily = display),
        bodyLarge = base.bodyLarge.copy(fontFamily = body),
        bodyMedium = base.bodyMedium.copy(fontFamily = body),
        bodySmall = base.bodySmall.copy(fontFamily = body),
        labelLarge = base.labelLarge.copy(fontFamily = display),
        labelMedium = base.labelMedium.copy(fontFamily = display),
        labelSmall = base.labelSmall.copy(fontFamily = display),
        // M3 Expressive's emphasized styles (heavier weights) in the same Google Sans families.
        displayLargeEmphasized = base.displayLargeEmphasized.copy(fontFamily = display),
        displayMediumEmphasized = base.displayMediumEmphasized.copy(fontFamily = display),
        displaySmallEmphasized = base.displaySmallEmphasized.copy(fontFamily = display),
        headlineLargeEmphasized = base.headlineLargeEmphasized.copy(fontFamily = display),
        headlineMediumEmphasized = base.headlineMediumEmphasized.copy(fontFamily = display),
        headlineSmallEmphasized = base.headlineSmallEmphasized.copy(fontFamily = display),
        titleLargeEmphasized = base.titleLargeEmphasized.copy(fontFamily = display),
        titleMediumEmphasized = base.titleMediumEmphasized.copy(fontFamily = display),
        titleSmallEmphasized = base.titleSmallEmphasized.copy(fontFamily = display),
        labelLargeEmphasized = base.labelLargeEmphasized.copy(fontFamily = display),
        labelMediumEmphasized = base.labelMediumEmphasized.copy(fontFamily = display),
        labelSmallEmphasized = base.labelSmallEmphasized.copy(fontFamily = display),
        bodyLargeEmphasized = base.bodyLargeEmphasized.copy(fontFamily = body),
        bodyMediumEmphasized = base.bodyMediumEmphasized.copy(fontFamily = body),
        bodySmallEmphasized = base.bodySmallEmphasized.copy(fontFamily = body),
    )
}

@Composable
fun AppTheme(dynamic: Boolean = true, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colors = when {
        dynamic -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(
            primary = Color(0xFFAAC7FF), onPrimary = Color(0xFF0A305F),
            primaryContainer = Color(0xFF284777), onPrimaryContainer = Color(0xFFD6E3FF),
            surface = Color(0xFF111318), surfaceContainerLowest = Color(0xFF0C0E13),
            surfaceContainerLow = Color(0xFF191C20), surfaceContainer = Color(0xFF1D2024),
            surfaceContainerHigh = Color(0xFF282A2F), surfaceContainerHighest = Color(0xFF33353A),
            onSurface = Color(0xFFE2E2E9), onSurfaceVariant = Color(0xFFC4C6D0),
            secondaryContainer = Color(0xFF3E4759), onSecondaryContainer = Color(0xFFDAE2F9),
            outlineVariant = Color(0xFF44474F),
        )
        else -> lightColorScheme(
            primary = Color(0xFF415F91), onPrimary = Color.White,
            primaryContainer = Color(0xFFD6E3FF), onPrimaryContainer = Color(0xFF284777),
            surface = Color(0xFFF9F9FF), surfaceContainerLowest = Color.White,
            surfaceContainerLow = Color(0xFFF3F3FA), surfaceContainer = Color(0xFFEDEDF4),
            surfaceContainerHigh = Color(0xFFE7E8EE), surfaceContainerHighest = Color(0xFFE2E2E9),
            onSurface = Color(0xFF191C20), onSurfaceVariant = Color(0xFF44474E),
            secondaryContainer = Color(0xFFDAE2F9), onSecondaryContainer = Color(0xFF3E4759),
            outlineVariant = Color(0xFFC4C6D0),
        )
    }
    MaterialExpressiveTheme(colorScheme = colors, typography = appTypography(), motionScheme = MotionScheme.expressive(), content = content)
}

/** Page and card surfaces, as in Android settings: cards sit one step above the page. */
object Surfaces {
    val page: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainer
    val card: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLowest
}
