@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package ua.olex.sonyanc

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

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
        titleLarge = flex(t.titleLarge, true), titleMedium = flex(t.titleMedium, false), titleSmall = flex(t.titleSmall, false),
        bodyLarge = flex(t.bodyLarge, false), bodyMedium = flex(t.bodyMedium, false), bodySmall = flex(t.bodySmall, false),
        labelLarge = flex(t.labelLarge, false), labelMedium = flex(t.labelMedium, false), labelSmall = flex(t.labelSmall, false),
        displayLargeEmphasized = flex(t.displayLargeEmphasized, true), displayMediumEmphasized = flex(t.displayMediumEmphasized, true),
        displaySmallEmphasized = flex(t.displaySmallEmphasized, true), headlineLargeEmphasized = flex(t.headlineLargeEmphasized, true),
        headlineMediumEmphasized = flex(t.headlineMediumEmphasized, true), headlineSmallEmphasized = flex(t.headlineSmallEmphasized, true),
        titleLargeEmphasized = flex(t.titleLargeEmphasized, true), titleMediumEmphasized = flex(t.titleMediumEmphasized, false),
        titleSmallEmphasized = flex(t.titleSmallEmphasized, false), bodyLargeEmphasized = flex(t.bodyLargeEmphasized, false),
        bodyMediumEmphasized = flex(t.bodyMediumEmphasized, false), bodySmallEmphasized = flex(t.bodySmallEmphasized, false),
        labelLargeEmphasized = flex(t.labelLargeEmphasized, false), labelMediumEmphasized = flex(t.labelMediumEmphasized, false),
        labelSmallEmphasized = flex(t.labelSmallEmphasized, false),
    )
}

// Material tonal colors derived from the coral launcher mark for use without wallpaper colors.
private val brandDark = darkColorScheme(
    primary = Color(0xFFFFB59F), onPrimary = Color(0xFF561F0F),
    primaryContainer = Color(0xFF723523), onPrimaryContainer = Color(0xFFFFDBD0),
    surface = Color(0xFF1A110F), surfaceContainerLowest = Color(0xFF140C0A),
    surfaceContainerLow = Color(0xFF231917), surfaceContainer = Color(0xFF271D1B),
    surfaceContainerHigh = Color(0xFF322825), surfaceContainerHighest = Color(0xFF3D3230),
    onSurface = Color(0xFFF1DFDA), onSurfaceVariant = Color(0xFFD8C2BC),
    secondary = Color(0xFFE7BDB2), onSecondary = Color(0xFF442A22),
    secondaryContainer = Color(0xFF5D4037), onSecondaryContainer = Color(0xFFFFDBD0),
    tertiary = Color(0xFFD8C58D), onTertiary = Color(0xFF3B2F05),
    tertiaryContainer = Color(0xFF534619), onTertiaryContainer = Color(0xFFF5E1A7),
    outlineVariant = Color(0xFF53433F),
    primaryFixed = Color(0xFFFFDBD0), primaryFixedDim = Color(0xFFFFB59F),
    onPrimaryFixed = Color(0xFF3A0B01), onPrimaryFixedVariant = Color(0xFF723523),
    secondaryFixed = Color(0xFFFFDBD0), secondaryFixedDim = Color(0xFFE7BDB2),
    onSecondaryFixed = Color(0xFF2C150F), onSecondaryFixedVariant = Color(0xFF5D4037),
    tertiaryFixed = Color(0xFFF5E1A7), tertiaryFixedDim = Color(0xFFD8C58D),
    onTertiaryFixed = Color(0xFF231B00), onTertiaryFixedVariant = Color(0xFF534619),
    background = Color(0xFF1A110F), onBackground = Color(0xFFF1DFDA),
    surfaceVariant = Color(0xFF53433F), surfaceDim = Color(0xFF1A110F), surfaceBright = Color(0xFF423734),
    outline = Color(0xFFA08D87), inverseSurface = Color(0xFFF1DFDA),
    inverseOnSurface = Color(0xFF362E2B), inversePrimary = Color(0xFF8F4C38),
)

private val brandLight = lightColorScheme(
    primary = Color(0xFF8F4C38), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBD0), onPrimaryContainer = Color(0xFF723523),
    surface = Color(0xFFFFF8F6), surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFFF1ED), surfaceContainer = Color(0xFFFCEAE5),
    surfaceContainerHigh = Color(0xFFF7E4E0), surfaceContainerHighest = Color(0xFFF1DFDA),
    onSurface = Color(0xFF231917), onSurfaceVariant = Color(0xFF53433F),
    secondary = Color(0xFF77574E), onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDBD0), onSecondaryContainer = Color(0xFF5D4037),
    tertiary = Color(0xFF6C5D2F), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF5E1A7), onTertiaryContainer = Color(0xFF534619),
    outlineVariant = Color(0xFFD8C2BC),
    primaryFixed = Color(0xFFFFDBD0), primaryFixedDim = Color(0xFFFFB59F),
    onPrimaryFixed = Color(0xFF3A0B01), onPrimaryFixedVariant = Color(0xFF723523),
    secondaryFixed = Color(0xFFFFDBD0), secondaryFixedDim = Color(0xFFE7BDB2),
    onSecondaryFixed = Color(0xFF2C150F), onSecondaryFixedVariant = Color(0xFF5D4037),
    tertiaryFixed = Color(0xFFF5E1A7), tertiaryFixedDim = Color(0xFFD8C58D),
    onTertiaryFixed = Color(0xFF231B00), onTertiaryFixedVariant = Color(0xFF534619),
    background = Color(0xFFFFF8F6), onBackground = Color(0xFF231917),
    surfaceVariant = Color(0xFFF5DED7), surfaceDim = Color(0xFFE8D7D2), surfaceBright = Color(0xFFFFF8F6),
    outline = Color(0xFF85736D), inverseSurface = Color(0xFF362E2B),
    inverseOnSurface = Color(0xFFFCEEE9), inversePrimary = Color(0xFFFFB59F),
)

@Composable
fun AppTheme(dynamic: Boolean = true, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val dark = isSystemInDarkTheme()
    val colors = remember(context, configuration, dynamic, dark) {
        if (dynamic) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            if (dark) brandDark else brandLight
        }
    }
    MaterialExpressiveTheme(
        colorScheme = colors,
        typography = appTypography(),
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}

/** Shared dimensions for the app's pages, cards and controls. */
internal object UiTokens {
    val pageInset = 16.dp
    val pageSectionGap = 20.dp
    val homeSectionGap = 24.dp
    val sectionLabelGap = 8.dp
    val minTouchTarget = 48.dp
    val symbolSize = 24.dp
    val cardInset = 16.dp
    val cardRadius = 28.dp
    val cardShape = RoundedCornerShape(cardRadius)
    val compactCardShape = RoundedCornerShape(24.dp)
}

internal val batteryIndicator: Pair<Color, Color>
    @Composable
    get() = if (isSystemInDarkTheme()) Color(0xFF6DD58C) to Color(0xFF0A3818)
        else Color(0xFF1E8E3E) to Color.White

internal fun TextStyle.withTabularFigures() = copy(fontFeatureSettings = "tnum")

/** Page and card surfaces, as in Android settings: cards sit one step above the page. */
object Surfaces {
    // As Music: the page is the plain surface and rows sit on it in the tinted container, in both
    // themes, so the two apps read as one family.
    val page: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.surface
    val card: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.surfaceContainerHigh
}
