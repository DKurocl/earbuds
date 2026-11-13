@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

package ua.olex.sonyanc

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun EqualizerScreen(onBack: () -> Unit, resumeTick: Int = 0) {
    val eq by Headphones.equalizer.collectAsState()
    val scope = rememberCoroutineScope()
    val show = LocalMessages.current
    var busy by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    suspend fun load() {
        busy = true
        try { failed = Headphones.readEqualizer() == null } finally { busy = false }
    }
    LaunchedEffect(resumeTick) { load() }
    fun write(preset: Int? = null, bands: List<Int>? = null) {
        scope.launch {
            busy = true
            try { if (!Headphones.writeEqualizer(preset, bands)) show("Change not confirmed. Your earbuds may still use the previous sound.") }
            finally { busy = false }
        }
    }
    // The last known sound shows at once while the earbuds are asked again; controls stay
    // disabled until the live answer arrives (busy), so nothing is ever written from the cache.
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val cached = remember { Prefs.cachedEqualizer(ctx) }
    EqualizerContent(
        eq ?: cached.takeIf { busy }, busy, failed && eq == null, onBack,
        onRetry = { scope.launch { load() } },
        onPreset = { write(preset = it) },
        onBands = { write(bands = it) },
    )
}

private val BAND_LABELS = listOf("400", "1k", "2.5k", "6.3k", "16k")

/** Stateless equalizer page; [state] bands are Clear Bass followed by five bands, each -10..10. */
@Composable
fun EqualizerContent(
    state: EqualizerProtocol.State?, busy: Boolean, unavailable: Boolean, onBack: () -> Unit = {},
    onRetry: () -> Unit = {}, onPreset: (Int) -> Unit = {}, onBands: (List<Int>) -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    PageScaffold("Equalizer", onBack, actions = { if (busy) LoadingIndicator(Modifier.padding(end = 12.dp).size(32.dp)) }) {
        when {
            state == null && unavailable -> Surface(shape = RoundedCornerShape(24.dp), color = Surfaces.card, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Equalizer unavailable", style = MaterialTheme.typography.titleMedium)
                    Text("The earbuds didn't answer. Check that they're connected, then try again.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    FilledTonalButton(onClick = onRetry, enabled = !busy) { Text("Try again") }
                }
            }
            state == null -> Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
            else -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle("Preset")
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        EqualizerProtocol.presets.forEach { (code, name) ->
                            PresetPill(name, state.preset == code, !busy) { onPreset(code) }
                        }
                    }
                }
                if (state.bands.size == 6) BandsCard(state, busy, onBands)
                else Surface(shape = RoundedCornerShape(24.dp), color = Surfaces.card, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("This preset has fixed bands", style = MaterialTheme.typography.titleMedium)
                        Text("Choose Custom to shape the sound yourself.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                        FilledTonalButton(onClick = { onPreset(0xA0) }, enabled = !busy) { Text("Use Custom") }
                    }
                }
            }
        }
    }
}

@Composable
private fun BandsCard(state: EqualizerProtocol.State, busy: Boolean, onBands: (List<Int>) -> Unit) {
    val cs = MaterialTheme.colorScheme
    // Local copy while dragging; replaced whenever the earbuds report new values.
    var bands by remember(state) { mutableStateOf(state.bands) }
    fun set(index: Int, value: Int) { bands = bands.toMutableList().also { it[index] = value } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SectionTitle("Bands") }
            TextButton(enabled = !busy && bands.any { it != 0 }, onClick = { onBands(List(6) { 0 }) }) { Text("Reset") }
        }
        Surface(shape = RoundedCornerShape(24.dp), color = Surfaces.card, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                // Where the sliders' tracks sit inside the row, for the curve drawn behind them.
                var row by remember { mutableStateOf<LayoutCoordinates?>(null) }
                var track by remember { mutableStateOf(0f to 0f) }
                val curve = bands.drop(1).mapIndexed { i, v ->
                    animateFloatAsState(v.toFloat(), MaterialTheme.motionScheme.fastSpatialSpec(), label = "curve $i").value
                }
                val curveColor = cs.primary
                Row(
                    Modifier.fillMaxWidth().onPlaced { row = it }
                        .drawBehind { if (track.second > 0f) drawResponse(curve, track.first, track.second, curveColor) },
                ) {
                    bands.drop(1).forEachIndexed { i, value ->
                        BandSlider(BAND_LABELS[i] + " Hz", BAND_LABELS[i], value, !busy, Modifier.weight(1f),
                            onChange = { set(i + 1, it) }, onDone = { onBands(bands) },
                            onTrack = { c -> row?.let { r -> if (c.isAttached && r.isAttached) {
                                val top = r.localPositionOf(c, Offset.Zero).y
                                track = top to c.size.height.toFloat()
                            } } })
                    }
                }
                HorizontalDivider(color = cs.outlineVariant.copy(alpha = .5f), modifier = Modifier.padding(horizontal = 8.dp))
                Column(Modifier.padding(horizontal = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Clear Bass", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text(signed(bands[0]), style = MaterialTheme.typography.titleMedium, color = cs.primary)
                    }
                    val clearBass = remember { SliderState(value = bands[0].toFloat(), steps = 19, trackRange = -10f..10f) }
                    LaunchedEffect(bands[0]) { if (clearBass.value.roundToInt() != bands[0]) clearBass.value = bands[0].toFloat() }
                    Slider(
                        state = clearBass, enabled = !busy,
                        onValueChange = { clearBass.value = it; set(0, clearBass.value.roundToInt()) },
                        onValueChangeFinished = { onBands(bands) },
                        track = { SliderDefaults.CenteredTrack(sliderState = it, enabled = !busy, drawTick = { _, _ -> }) },
                        modifier = Modifier.semantics { contentDescription = "Clear Bass" },
                    )
                }
            }
        }
    }
}

/**
 * The sound the bands make, as one smooth line through the five thumbs with a soft fill to 0 dB,
 * drawn behind the sliders. It follows a drag with a quick spring, so it trails the finger a
 * little and settles; a preset change flows into the new shape.
 */
private fun DrawScope.drawResponse(values: List<Float>, top: Float, height: Float, color: Color) {
    // Material's vertical slider: the 4 dp thumb travels the track inset by half its height.
    val inset = 2.dp.toPx()
    fun y(v: Float) = top + inset + (1f - (v + 10f) / 20f) * (height - 2 * inset)
    val step = size.width / values.size
    val points = values.mapIndexed { i, v -> Offset(step * (i + .5f), y(v)) }
    val ends = listOf(Offset(0f, points.first().y)) + points + Offset(size.width, points.last().y)
    val line = Path().apply {
        moveTo(ends[0].x, ends[0].y)
        // Catmull-Rom through every point, as cubic Béziers.
        for (i in 0 until ends.lastIndex) {
            val p0 = ends[(i - 1).coerceAtLeast(0)]; val p1 = ends[i]; val p2 = ends[i + 1]; val p3 = ends[(i + 2).coerceAtMost(ends.lastIndex)]
            cubicTo(p1.x + (p2.x - p0.x) / 6, p1.y + (p2.y - p0.y) / 6, p2.x - (p3.x - p1.x) / 6, p2.y - (p3.y - p1.y) / 6, p2.x, p2.y)
        }
    }
    val zero = y(0f)
    val area = Path().apply { addPath(line); lineTo(size.width, zero); lineTo(0f, zero); close() }
    drawPath(area, color.copy(alpha = .10f))
    drawPath(line, color.copy(alpha = .45f), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
}

private fun signed(v: Int) = if (v > 0) "+$v" else v.toString()

/**
 * One band: Material's vertical slider with the centered track, so the fill grows from 0 dB up or
 * down. Whole-dB steps without tick marks (twenty dots would only be noise); a tick of haptics per
 * dB and a firmer one at 0 dB.
 */
@Composable
private fun BandSlider(
    description: String, label: String, value: Int, enabled: Boolean, modifier: Modifier,
    onChange: (Int) -> Unit, onDone: () -> Unit, onTrack: (LayoutCoordinates) -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    val feedback = LocalHapticFeedback.current
    val state = remember { SliderState(value = value.toFloat(), steps = 19, trackRange = -10f..10f) }
    LaunchedEffect(value) { if (state.value.roundToInt() != value) state.value = value.toFloat() }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(signed(value), style = MaterialTheme.typography.labelLarge, color = if (value != 0) cs.primary else cs.onSurfaceVariant)
        VerticalSlider(
            state = state,
            enabled = enabled,
            topToBottom = false,
            onValueChange = { v ->
                state.value = v
                val step = state.value.roundToInt()
                if (step != value) {
                    feedback.performHapticFeedback(if (step == 0) HapticFeedbackType.SegmentTick else HapticFeedbackType.SegmentFrequentTick)
                    onChange(step)
                }
            },
            onValueChangeFinished = onDone,
            track = { SliderDefaults.CenteredTrack(sliderState = it, enabled = enabled, drawTick = { _, _ -> }) },
            modifier = Modifier.height(208.dp).onPlaced(onTrack).semantics { contentDescription = description; stateDescription = "${signed(value)} dB" },
        )
        Text(label, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** A preset: Material's toggle button, round at rest; the chosen one morphs to a rounded square. */
@Composable
private fun PresetPill(name: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    ToggleButton(
        checked = selected, onCheckedChange = { onClick() }, enabled = enabled,
        colors = ToggleButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        modifier = Modifier.semantics { this.selected = selected; role = Role.RadioButton },
    ) { Text(name, maxLines = 1) }
}
