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

private fun signed(v: Int) = if (v > 0) "+$v" else v.toString()

/**
 * One band: Material's vertical slider with the centered track, so the fill grows from 0 dB up or
 * down. Whole-dB steps without tick marks (twenty dots would only be noise); a tick of haptics per
 * dB and a firmer one at 0 dB.
 */
@Composable
private fun BandSlider(
    description: String, label: String, value: Int, enabled: Boolean, modifier: Modifier,
    onChange: (Int) -> Unit, onDone: () -> Unit,
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
            modifier = Modifier.height(208.dp).semantics { contentDescription = description; stateDescription = "${signed(value)} dB" },
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
