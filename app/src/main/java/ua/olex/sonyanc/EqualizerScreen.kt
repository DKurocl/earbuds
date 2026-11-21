@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

package ua.olex.sonyanc

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.log10
import kotlin.math.roundToInt

@Composable
fun EqualizerScreen(onBack: () -> Unit, resumeTick: Int = 0) {
    val eq by Headphones.equalizer.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var refused by remember { mutableStateOf(false) }
    suspend fun load() {
        busy = true
        try { failed = Headphones.readEqualizer() == null } finally { busy = false }
    }
    LaunchedEffect(resumeTick) { load() }
    fun write(preset: Int? = null, bands: List<Int>? = null) {
        scope.launch {
            busy = true; refused = false
            try { refused = !Headphones.writeEqualizer(preset, bands) } finally { busy = false }
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
        refused = refused,
    )
}

/** The five bands' centres, as Sony's app labels them. */
private val BAND_HZ = listOf(400.0, 1_000.0, 2_500.0, 6_300.0, 16_000.0)
private const val OFF = 0x00
private const val CUSTOM = 0xA0

private fun bandLabel(hz: Double) = if (hz >= 1_000) "${String.format(java.util.Locale.ROOT, "%.1f", hz / 1_000).removeSuffix(".0")} kHz" else "${hz.toInt()} Hz"
private fun signed(v: Int) = if (v > 0) "+$v" else v.toString()

/**
 * The equalizer in Music's language: one card for the equalizer (its switch in the header, the
 * presets as chips, the sound as a curve to drag) and one for Clear Bass. [state] bands are Clear
 * Bass followed by the five bands, each -10..10.
 */
@Composable
fun EqualizerContent(
    state: EqualizerProtocol.State?, busy: Boolean, unavailable: Boolean, onBack: () -> Unit = {},
    onRetry: () -> Unit = {}, onPreset: (Int) -> Unit = {}, onBands: (List<Int>) -> Unit = {}, refused: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    PageScaffold("Equalizer", onBack, actions = { if (busy) LoadingIndicator(Modifier.padding(end = 12.dp).size(32.dp)) }) {
        when {
            state == null && unavailable -> SoundCard {
                Text("Equalizer unavailable", style = MaterialTheme.typography.titleMediumEmphasized)
                Text("The earbuds didn't answer. Check that they're connected, then try again.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                FilledTonalButton(onClick = onRetry, enabled = !busy) { Text("Try again") }
            }
            state == null -> Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
            else -> {
                val editable = !busy
                // Local copy while dragging; replaced whenever the earbuds report new values.
                var bands by remember(state) { mutableStateOf(state.bands) }
                val on = state.preset != OFF
                SoundCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Equalizer", style = MaterialTheme.typography.titleMediumEmphasized)
                            Text(if (on) EqualizerProtocol.presets[state.preset] ?: "Custom" else "Off · the earbuds play as tuned",
                                style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                        }
                        Switch(checked = on, enabled = editable, onCheckedChange = { onPreset(if (it) CUSTOM else OFF) },
                            thumbContent = if (on) ({ HushIcon(Glyph.CHECK, Modifier.size(SwitchDefaults.IconSize), cs.primary) }) else null,
                            modifier = Modifier.semantics { contentDescription = "Equalizer" })
                    }
                    if (refused) Text("The earbuds didn't take this change", style = MaterialTheme.typography.bodyMedium, color = cs.error)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        EqualizerProtocol.presets.filterKeys { it != OFF }.forEach { (code, name) ->
                            PresetChip(name, state.preset == code, editable) { onPreset(code) }
                        }
                        PresetChip("Flat", state.preset == CUSTOM && bands.drop(1).all { it == 0 }, editable) { onBands(listOf(bands[0]) + List(5) { 0 }) }
                    }
                    if (bands.size == 6) {
                        var held by remember { mutableStateOf<Int?>(null) }
                        EqCurve(bands.drop(1), editable, on, held,
                            onHold = { held = it },
                            onGain = { i, v -> bands = bands.toMutableList().also { it[i + 1] = v } },
                            onDone = { held = null; onBands(bands) })
                        // Each band's value, under its place on the curve.
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            BAND_HZ.forEachIndexed { i, hz ->
                                Surface(shape = RoundedCornerShape(14.dp), color = if (held == i) cs.primaryContainer else cs.surfaceContainerHighest,
                                    modifier = Modifier.weight(1f).semantics { contentDescription = "${bandLabel(hz)}, ${signed(bands[i + 1])} dB" }) {
                                    Column(Modifier.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(bandLabel(hz), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                                        Text(signed(bands[i + 1]), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        Text("Drag a point on the curve to shape the sound.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    } else {
                        Text("This preset has fixed bands. Choose Custom to shape the sound yourself.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    }
                }
                if (bands.size == 6) SoundCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Clear Bass", style = MaterialTheme.typography.titleMediumEmphasized)
                            Text("Deeper bass without muddying the rest", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                        }
                        Text(signed(bands[0]), style = MaterialTheme.typography.titleMedium, color = if (bands[0] != 0) cs.primary else cs.onSurfaceVariant)
                    }
                    val clearBass = remember { SliderState(value = bands[0].toFloat(), steps = 19, trackRange = -10f..10f) }
                    LaunchedEffect(bands[0]) { if (clearBass.value.roundToInt() != bands[0]) clearBass.value = bands[0].toFloat() }
                    val feedback = LocalHapticFeedback.current
                    Slider(
                        state = clearBass, enabled = editable,
                        onValueChange = {
                            clearBass.value = it
                            val step = clearBass.value.roundToInt()
                            if (step != bands[0]) {
                                feedback.performHapticFeedback(if (step == 0) HapticFeedbackType.SegmentTick else HapticFeedbackType.SegmentFrequentTick)
                                bands = bands.toMutableList().also { b -> b[0] = step }
                            }
                        },
                        onValueChangeFinished = { onBands(bands) },
                        track = { SliderDefaults.CenteredTrack(sliderState = it, enabled = editable, drawTick = { _, _ -> }) },
                        modifier = Modifier.semantics { contentDescription = "Clear Bass" },
                    )
                }
            }
        }
    }
}

/** A rounded card for one part of the sound, as in Music. */
@Composable
private fun SoundCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(28.dp), color = Surfaces.card, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

/** Music's filter chip: tonal, no outline, a pill that squares its corners a little when chosen. */
@Composable
private fun PresetChip(name: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val corner by animateDpAsState(if (selected) 12.dp else 20.dp, MaterialTheme.motionScheme.fastSpatialSpec(), label = "chip corner")
    FilterChip(
        selected = selected, onClick = onClick, enabled = enabled, label = { Text(name, maxLines = 1) },
        shape = RoundedCornerShape(corner), border = null,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = cs.surfaceContainerHighest, labelColor = cs.onSurfaceVariant,
            selectedContainerColor = cs.secondaryContainer, selectedLabelColor = cs.onSecondaryContainer,
        ),
        modifier = Modifier.semantics { role = Role.RadioButton },
    )
}

/**
 * The five bands as a curve, as Music draws its equalizer: 20 Hz to 20 kHz on a log scale, ±10 dB,
 * a smooth line through the bands filled toward 0 dB, each band a dot at its frequency. Dragging
 * near a dot moves that band in whole dB with a tick for each (a firmer one at 0); while held, its
 * value shows above it. A preset change flows into the new shape.
 */
@Composable
private fun EqCurve(values: List<Int>, enabled: Boolean, active: Boolean, held: Int?, onHold: (Int?) -> Unit, onGain: (Int, Int) -> Unit, onDone: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val feedback = LocalHapticFeedback.current
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = cs.onSurfaceVariant)
    val valueStyle = MaterialTheme.typography.labelLarge.copy(color = cs.onPrimary)
    val shown = values.mapIndexed { i, v -> animateFloatAsState(v.toFloat(), MaterialTheme.motionScheme.fastSpatialSpec(), label = "band $i").value }
    val latest by rememberUpdatedState(values)
    val range = 10f
    fun xOf(hz: Double, w: Float) = (log10(hz / 20.0) / 3.0).toFloat().coerceIn(0f, 1f) * w
    val tone = if (active) cs.primary else cs.outline
    val dot = cs.surfaceContainerHigh
    Canvas(
        Modifier.fillMaxWidth().height(200.dp).semantics { contentDescription = "Equalizer curve" }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val perDb = (size.height / 2f - 16.dp.toPx()) / range
                    val pick = 28.dp.toPx()
                    fun distance(i: Int): Float {
                        val dx = xOf(BAND_HZ[i], size.width.toFloat()) - down.position.x
                        val dy = size.height / 2f - latest[i] * perDb - down.position.y
                        return dx * dx + dy * dy
                    }
                    val index = BAND_HZ.indices.minByOrNull(::distance) ?: return@awaitEachGesture
                    // Close bands share the width: a touch anywhere in a band's column takes it.
                    if (kotlin.math.abs(xOf(BAND_HZ[index], size.width.toFloat()) - down.position.x) > pick * 1.6f) return@awaitEachGesture
                    var initial = 0f
                    val start = awaitVerticalTouchSlopOrCancellation(down.id) { change, over -> initial = over; change.consume() } ?: return@awaitEachGesture
                    var raw = latest[index].toFloat()
                    var last = latest[index]
                    onHold(index)
                    feedback.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    fun drag(dy: Float) {
                        raw = (raw - dy / perDb).coerceIn(-range, range)
                        val step = raw.roundToInt()
                        if (step != last) {
                            last = step
                            feedback.performHapticFeedback(if (step == 0) HapticFeedbackType.SegmentTick else HapticFeedbackType.SegmentFrequentTick)
                            onGain(index, step)
                        }
                    }
                    try {
                        drag(initial)
                        verticalDrag(start.id) { change -> drag(change.position.y - change.previousPosition.y); change.consume() }
                    } finally { onDone() }
                }
            },
    ) {
        val w = size.width; val h = size.height; val mid = h / 2f
        val perDb = (h / 2f - 16.dp.toPx()) / range
        fun yOf(db: Float) = mid - db * perDb
        // Grid: 0 dB stronger, ±5 dB faint; 100 Hz, 1 kHz, 10 kHz marks.
        listOf(-5f, 5f).forEach { drawLine(cs.outlineVariant.copy(alpha = .35f), Offset(0f, yOf(it)), Offset(w, yOf(it)), 1.dp.toPx()) }
        drawLine(cs.outlineVariant, Offset(0f, mid), Offset(w, mid), 1.5f.dp.toPx())
        listOf(100.0 to "100", 1_000.0 to "1k", 10_000.0 to "10k").forEach { (hz, text) ->
            val x = xOf(hz, w)
            drawLine(cs.outlineVariant.copy(alpha = .25f), Offset(x, 0f), Offset(x, h), 1.dp.toPx())
            drawText(measurer, text, Offset(x + 4.dp.toPx(), h - 16.dp.toPx()), labelStyle)
        }
        // A smooth line through the bands, flat beyond the first and last (Catmull-Rom as Béziers).
        val points = BAND_HZ.mapIndexed { i, hz -> Offset(xOf(hz, w), yOf(shown[i])) }
        val ends = listOf(Offset(0f, points.first().y)) + points + Offset(w, points.last().y)
        val line = Path().apply {
            moveTo(ends[0].x, ends[0].y)
            for (i in 0 until ends.lastIndex) {
                val p0 = ends[(i - 1).coerceAtLeast(0)]; val p1 = ends[i]; val p2 = ends[i + 1]; val p3 = ends[(i + 2).coerceAtMost(ends.lastIndex)]
                cubicTo(p1.x + (p2.x - p0.x) / 6, p1.y + (p2.y - p0.y) / 6, p2.x - (p3.x - p1.x) / 6, p2.y - (p3.y - p1.y) / 6, p2.x, p2.y)
            }
        }
        val area = Path().apply { addPath(line); lineTo(w, mid); lineTo(0f, mid); close() }
        drawPath(area, tone.copy(alpha = .16f))
        drawPath(line, tone, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        points.forEachIndexed { i, c ->
            val r = if (held == i) 11.dp.toPx() else 6.dp.toPx()
            drawCircle(dot, r + 2.dp.toPx(), c)
            drawCircle(tone, r, c)
            if (held == i) {
                val layout = measurer.measure("${signed(values[i])} dB · ${bandLabel(BAND_HZ[i])}", valueStyle)
                val bw = layout.size.width + 20.dp.toPx(); val bh = layout.size.height + 10.dp.toPx()
                val bx = (c.x - bw / 2f).coerceIn(0f, w - bw)
                val by = if (c.y > mid) c.y - bh - 20.dp.toPx() else c.y + 20.dp.toPx()
                drawRoundRect(cs.primary, Offset(bx, by), Size(bw, bh), CornerRadius(12.dp.toPx()))
                drawText(layout, topLeft = Offset(bx + 10.dp.toPx(), by + 5.dp.toPx()))
            }
        }
    }
}
