@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

package ua.olex.sonyanc

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
    EqualizerContent(
        eq, busy, failed && eq == null, onBack,
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
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
                        EqualizerProtocol.presets.forEach { (code, name) ->
                            FilterChip(
                                selected = state.preset == code, enabled = !busy, onClick = { onPreset(code) }, label = { Text(name) },
                                leadingIcon = if (state.preset == code) ({ HushIcon(Glyph.CHECK, Modifier.size(FilterChipDefaults.IconSize), cs.onSecondaryContainer) }) else null,
                            )
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
                Box {
                    ResponseCurve(bands.drop(1), Modifier.matchParentSize().padding(top = 28.dp, bottom = 28.dp))
                    Row(Modifier.fillMaxWidth()) {
                        bands.drop(1).forEachIndexed { i, value ->
                            BandSlider(BAND_LABELS[i] + " Hz", BAND_LABELS[i], value, !busy, Modifier.weight(1f),
                                onChange = { set(i + 1, it) }, onDone = { onBands(bands) })
                        }
                    }
                }
                HorizontalDivider(color = cs.outlineVariant.copy(alpha = .5f), modifier = Modifier.padding(horizontal = 8.dp))
                Column(Modifier.padding(horizontal = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Clear Bass", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text(signed(bands[0]), style = MaterialTheme.typography.titleMedium, color = cs.primary)
                    }
                    Slider(
                        value = bands[0].toFloat(), valueRange = -10f..10f, enabled = !busy,
                        onValueChange = { set(0, it.roundToInt()) }, onValueChangeFinished = { onBands(bands) },
                        modifier = Modifier.semantics { contentDescription = "Clear Bass" },
                    )
                }
            }
        }
    }
}

private fun signed(v: Int) = if (v > 0) "+$v" else v.toString()

private fun valueAt(y: Float, height: Float) = (10 - y / height * 20).roundToInt().coerceIn(-10, 10)

/** A tall rounded track filled from the 0 dB line to the value; drag or tap anywhere on it. */
@Composable
private fun BandSlider(
    description: String, label: String, value: Int, enabled: Boolean, modifier: Modifier,
    onChange: (Int) -> Unit, onDone: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val active = if (enabled) cs.primary else cs.onSurface.copy(alpha = .38f)
    val track = cs.surfaceContainerHighest
    // Gesture coroutines outlive recompositions; always call the latest callbacks.
    val change by rememberUpdatedState(onChange)
    val done by rememberUpdatedState(onDone)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(signed(value), style = MaterialTheme.typography.labelLarge, color = if (value != 0) cs.primary else cs.onSurfaceVariant)
        Canvas(
            Modifier.width(40.dp).height(200.dp)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectVerticalDragGestures(
                        onDragStart = { change(valueAt(it.y, size.height.toFloat())) },
                        onDragEnd = { done() }, onDragCancel = { done() },
                    ) { pointer, _ -> pointer.consume(); change(valueAt(pointer.position.y, size.height.toFloat())) }
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { change(valueAt(it.y, size.height.toFloat())); done() }
                }
                .semantics {
                    contentDescription = description
                    stateDescription = "${signed(value)} dB"
                    progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), -10f..10f, 19)
                    if (enabled) setProgress { target -> change(target.roundToInt().coerceIn(-10, 10)); done(); true }
                },
        ) {
            val w = size.width
            val r = CornerRadius(w / 2, w / 2)
            drawRoundRect(track, cornerRadius = r)
            val zero = size.height / 2
            val y = size.height * (10 - value) / 20f
            val top = minOf(zero, y)
            val bottom = maxOf(zero, y)
            if (bottom - top > 0f) drawRoundRect(active, Offset(0f, top), Size(w, bottom - top), r)
            // Centre (0 dB) tick and the value handle.
            drawLine(cs.outline.copy(alpha = .5f), Offset(w * .3f, zero), Offset(w * .7f, zero), 2.dp.toPx(), StrokeCap.Round)
            drawLine(if (enabled) cs.onPrimary else cs.surface, Offset(w * .25f, y.coerceIn(w / 2, size.height - w / 2)),
                Offset(w * .75f, y.coerceIn(w / 2, size.height - w / 2)), 4.dp.toPx(), StrokeCap.Round)
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** Smooth line through the band values behind the sliders, so the overall shape reads at a glance. */
@Composable
private fun ResponseCurve(values: List<Int>, modifier: Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        if (values.isEmpty()) return@Canvas
        val step = size.width / values.size
        val points = values.mapIndexed { i, v -> Offset(step * (i + .5f), size.height * (10 - v) / 20f) }
        val line = Path().apply {
            moveTo(0f, points.first().y)
            lineTo(points.first().x, points.first().y)
            for (i in 1 until points.size) {
                val a = points[i - 1]; val b = points[i]
                val mid = (a.x + b.x) / 2
                cubicTo(mid, a.y, mid, b.y, b.x, b.y)
            }
            lineTo(size.width, points.last().y)
        }
        val fill = Path().apply { addPath(line); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = .18f), color.copy(alpha = 0f))))
        drawPath(line, color.copy(alpha = .45f), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
    }
}
