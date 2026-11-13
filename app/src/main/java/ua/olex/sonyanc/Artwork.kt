package ua.olex.sonyanc

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * Every icon in the app, as a Material Symbols Rounded codepoint. The glyphs come from
 * res/font/material_symbols.ttf: the official variable font, subset to these icons with its
 * FILL and wght axes, so an icon can fill in and change weight smoothly (docs/icon/symbols.py
 * makes the widget drawables from the same font).
 */
enum class Glyph(val codepoint: Int) {
    QUIET(0xF8A8),      // noise_control_on: the ear sealed in
    AMBIENT(0xE023),    // hearing
    OFF(0xEBF3),        // noise_control_off
    POWER(0xF8C7),      // power_settings_new
    TUNE(0xE429), BACK(0xE5C4), REFRESH(0xE5D5), CHECK(0xE668), BLUETOOTH(0xE1A7), WIDGET(0xE1BD),
    COPY(0xE14D), EARBUDS(0xF003), PALETTE(0xE40A), HAPTICS(0xF2CB), INFO(0xE88E), EQ(0xE1B8),
    SETTINGS(0xE8B8), EDIT(0xF097), BUG(0xE868), CLEAR(0xE16C), ADD(0xE145), FIND(0xF1DB),
    BATTERY(0xF304), BATTERY_CHARGING(0xF305), BATTERY_LOW(0xF306), BATTERY_UNKNOWN(0xF302),
    LINK(0xE1A8), TIMER(0xE425), CHAT(0xE91F), HOME(0xE9B2), LOCATION(0xE55C), DONE(0xF0BE), HEADPHONES(0xF01F);

    val text: String = String(Character.toChars(codepoint))
}

internal object SymbolFont {
    @Volatile private var typeface: Typeface? = null
    fun get(ctx: android.content.Context): Typeface =
        typeface ?: ctx.resources.getFont(R.font.material_symbols).also { typeface = it }
}

/**
 * A Material Symbol that moves like the ones in Google's apps: [filled] fills it in (for icons
 * with a filled form), [bold] thickens the stroke (a pressed or chosen control), and turning
 * filled or bold gives it a small spring "pop". It animates only when one of those changes.
 */
@Composable
fun HushIcon(
    glyph: Glyph, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface,
    filled: Boolean = false, bold: Boolean = false,
) {
    val ctx = LocalContext.current
    val motion = MaterialTheme.motionScheme
    val fill by animateFloatAsState(if (filled) 1f else 0f, motion.defaultEffectsSpec(), label = "fill")
    val weight by animateFloatAsState(if (bold) 600f else 400f, motion.fastSpatialSpec(), label = "weight")
    val pop = remember { Animatable(1f) }
    val emphasis = filled || bold
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(emphasis) {
        if (first[0]) { first[0] = false; return@LaunchedEffect }
        if (emphasis) { pop.snapTo(0.82f); pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 500f)) }
    }
    val paint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = SymbolFont.get(ctx); textAlign = Paint.Align.CENTER }
    }
    Canvas(modifier.size(24.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value }) {
        // The font has 960 units per em with the icon standing on the baseline: an em-sized
        // glyph drawn with its baseline at the bottom fills the box exactly.
        val em = size.minDimension
        paint.textSize = em
        paint.color = color.toArgb()
        paint.fontVariationSettings = String.format(Locale.ROOT, "'FILL' %.3f, 'wght' %.1f", fill, weight)
        drawContext.canvas.nativeCanvas.drawText(glyph.text, size.width / 2, (size.height + em) / 2, paint)
    }
}

/** The symbol for each noise-control mode, the same in the app, the widgets and the tile. */
val SonyProtocol.Mode.glyph: Glyph
    get() = when (this) {
        SonyProtocol.Mode.ANC -> Glyph.QUIET
        SonyProtocol.Mode.AMBIENT -> Glyph.AMBIENT
        SonyProtocol.Mode.OFF -> Glyph.OFF
    }
