@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

package ua.olex.sonyanc

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
                Row(Modifier.fillMaxWidth()) {
                    bands.drop(1).forEachIndexed { i, value ->
                        BandSlider(BAND_LABELS[i] + " Hz", BAND_LABELS[i], value, !busy, Modifier.weight(1f),
                            onChange = { set(i + 1, it) }, onDone = { onBands(bands) })
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

/**
 * A tall soft track with a fixed-size pill thumb at the value and a thin fill from 0 dB to it,
 * so +1 and +6 look like the same control at different heights. Drag or tap anywhere; a tick
 * per dB step and a firmer one at 0 dB.
 */
@Composable
private fun BandSlider(
    description: String, label: String, value: Int, enabled: Boolean, modifier: Modifier,
    onChange: (Int) -> Unit, onDone: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val feedback = LocalHapticFeedback.current
    val active = if (enabled) cs.primary else cs.onSurface.copy(alpha = .38f)
    val track = cs.surfaceContainerHighest
    val current by rememberUpdatedState(value)
    // Gesture coroutines outlive recompositions; always call the latest callbacks.
    val change by rememberUpdatedState { v: Int ->
        if (v != current) feedback.performHapticFeedback(if (v == 0) HapticFeedbackType.SegmentTick else HapticFeedbackType.SegmentFrequentTick)
        onChange(v)
    }
    val done by rememberUpdatedState(onDone)
    val shown by animateFloatAsState(value.toFloat(), MaterialTheme.motionScheme.fastSpatialSpec(), label = description)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(signed(value), style = MaterialTheme.typography.labelLarge, color = if (value != 0) cs.primary else cs.onSurfaceVariant)
        Canvas(
            Modifier.width(44.dp).height(208.dp)
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
            val thumbH = 22.dp.toPx()
            val thumbW = w - 8.dp.toPx()
            drawRoundRect(track, cornerRadius = CornerRadius(w / 2, w / 2))
            // Value range kept inside the rounded ends so the thumb never touches them.
            val inset = w / 2
            val zero = size.height / 2
            val y = inset + (size.height - 2 * inset) * (10 - shown) / 20f
            val bar = 8.dp.toPx()
            val top = minOf(zero, y); val bottom = maxOf(zero, y)
            if (bottom - top > 1f) drawRoundRect(active.copy(alpha = .45f), Offset((w - bar) / 2, top), Size(bar, bottom - top), CornerRadius(bar / 2, bar / 2))
            drawLine(cs.outline.copy(alpha = .6f), Offset(w * .32f, zero), Offset(w * .68f, zero), 2.dp.toPx(), StrokeCap.Round)
            drawRoundRect(active, Offset((w - thumbW) / 2, y - thumbH / 2), Size(thumbW, thumbH), CornerRadius(thumbH / 2, thumbH / 2))
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** A soft, fully rounded preset pill: tonal when chosen, quiet otherwise. */
@Composable
private fun PresetPill(name: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (selected) cs.primary else cs.surfaceContainerHighest, MaterialTheme.motionScheme.fastEffectsSpec(), label = name)
    Surface(
        onClick = onClick, enabled = enabled, shape = CircleShape, color = bg,
        contentColor = if (selected) cs.onPrimary else cs.onSurface,
        modifier = Modifier.heightIn(min = 40.dp).semantics { this.selected = selected; role = Role.RadioButton },
    ) {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
            Text(name, style = MaterialTheme.typography.labelLarge)
        }
    }
}
