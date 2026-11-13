@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package ua.olex.sonyanc

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight

/**
 * Google Sans Flex, bundled (SIL Open Font License, assets/licenses), so the app reads the same
 * on any Pixel firmware and in the screenshot renders. One variable font, subset to Latin: weight
 * and optical size follow each Material style; display and headline text get the rounded cut
 * (ROND 100) that Material 3 Expressive uses for large type, smaller text stays crisp (ROND 0).
 */
private fun flex(style: TextStyle, round: Boolean): TextStyle {
    val weight = style.fontWeight ?: FontWeight.Normal
    val size = if (style.fontSize.isSp) style.fontSize.value else 14f
    val family = FontFamily(
        Font(
            R.font.google_sans_flex, weight = weight,
            variationSettings = FontVariation.Settings(
                FontVariation.weight(weight.weight),
                FontVariation.Setting("opsz", size.coerceIn(6f, 144f)),
                FontVariation.Setting("ROND", if (round) 100f else 0f),
            ),
        ),
    )
    return style.copy(fontFamily = family)
}

@Composable
private fun appTypography(): Typography = remember {
    val t = Typography()
    Typography(
        displayLarge = flex(t.displayLarge, true), displayMedium = flex(t.displayMedium, true), displaySmall = flex(t.displaySmall, true),
        headlineLarge = flex(t.headlineLarge, true), headlineMedium = flex(t.headlineMedium, true), headlineSmall = flex(t.headlineSmall, true),
        titleLarge = flex(t.titleLarge, false), titleMedium = flex(t.titleMedium, false), titleSmall = flex(t.titleSmall, false),
        bodyLarge = flex(t.bodyLarge, false), bodyMedium = flex(t.bodyMedium, false), bodySmall = flex(t.bodySmall, false),
        labelLarge = flex(t.labelLarge, false), labelMedium = flex(t.labelMedium, false), labelSmall = flex(t.labelSmall, false),
        displayLargeEmphasized = flex(t.displayLargeEmphasized, true), displayMediumEmphasized = flex(t.displayMediumEmphasized, true),
        displaySmallEmphasized = flex(t.displaySmallEmphasized, true), headlineLargeEmphasized = flex(t.headlineLargeEmphasized, true),
        headlineMediumEmphasized = flex(t.headlineMediumEmphasized, true), headlineSmallEmphasized = flex(t.headlineSmallEmphasized, true),
        titleLargeEmphasized = flex(t.titleLargeEmphasized, false), titleMediumEmphasized = flex(t.titleMediumEmphasized, false),
        titleSmallEmphasized = flex(t.titleSmallEmphasized, false), bodyLargeEmphasized = flex(t.bodyLargeEmphasized, false),
        bodyMediumEmphasized = flex(t.bodyMediumEmphasized, false), bodySmallEmphasized = flex(t.bodySmallEmphasized, false),
        labelLargeEmphasized = flex(t.labelLargeEmphasized, false), labelMediumEmphasized = flex(t.labelMediumEmphasized, false),
        labelSmallEmphasized = flex(t.labelSmallEmphasized, false),
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
