@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package ua.olex.sonyanc

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Google Sans Flex at its standard roundedness, with optical sizing for system text scale. */
private fun Density.flex(style: TextStyle): TextStyle {
    val weight = style.fontWeight ?: FontWeight.Normal
    val size = if (style.fontSize.isSp) style.fontSize.toDp().value else 14f
    val family = FontFamily(
        Font(
            R.font.google_sans_flex, weight = weight,
            variationSettings = FontVariation.Settings(
                FontVariation.weight(weight.weight),
                FontVariation.Setting("opsz", size.coerceIn(6f, 144f)),
            ),
        ),
    )
    return style.copy(fontFamily = family)
}

@Composable
private fun appTypography(): Typography {
    val density = LocalDensity.current
    return remember(density) {
        with(density) {
            val t = Typography()
            Typography(
                displayLarge = flex(t.displayLarge), displayMedium = flex(t.displayMedium), displaySmall = flex(t.displaySmall),
                headlineLarge = flex(t.headlineLarge), headlineMedium = flex(t.headlineMedium), headlineSmall = flex(t.headlineSmall),
                titleLarge = flex(t.titleLarge), titleMedium = flex(t.titleMedium), titleSmall = flex(t.titleSmall),
                bodyLarge = flex(t.bodyLarge), bodyMedium = flex(t.bodyMedium), bodySmall = flex(t.bodySmall),
                labelLarge = flex(t.labelLarge), labelMedium = flex(t.labelMedium), labelSmall = flex(t.labelSmall),
                displayLargeEmphasized = flex(t.displayLargeEmphasized), displayMediumEmphasized = flex(t.displayMediumEmphasized),
                displaySmallEmphasized = flex(t.displaySmallEmphasized), headlineLargeEmphasized = flex(t.headlineLargeEmphasized),
                headlineMediumEmphasized = flex(t.headlineMediumEmphasized), headlineSmallEmphasized = flex(t.headlineSmallEmphasized),
                titleLargeEmphasized = flex(t.titleLargeEmphasized), titleMediumEmphasized = flex(t.titleMediumEmphasized),
                titleSmallEmphasized = flex(t.titleSmallEmphasized), bodyLargeEmphasized = flex(t.bodyLargeEmphasized),
                bodyMediumEmphasized = flex(t.bodyMediumEmphasized), bodySmallEmphasized = flex(t.bodySmallEmphasized),
                labelLargeEmphasized = flex(t.labelLargeEmphasized), labelMediumEmphasized = flex(t.labelMediumEmphasized),
                labelSmallEmphasized = flex(t.labelSmallEmphasized),
            )
        }
    }
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

internal fun TextStyle.withTabularFigures() = copy(fontFeatureSettings = "tnum", letterSpacing = 0.sp)

/** Shared surface levels, with a quiet tint for the main battery estimate. */
object Surfaces {
    val page: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.surface
    val card: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.surfaceContainer
    val control: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.surfaceContainerHigh
    val estimate: Color
        @Composable @ReadOnlyComposable
        get() = with(MaterialTheme.colorScheme) { lerp(surfaceContainerHigh, primaryContainer, .26f) }
}
