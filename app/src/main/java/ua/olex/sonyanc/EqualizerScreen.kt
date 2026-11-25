@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

package ua.olex.sonyanc

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlin.math.abs
import kotlin.math.roundToInt

private class EqualizerWrite(val shown: EqualizerProtocol.State, val preset: Int?, val bands: List<Int>?)

private data class EqEdit(val index: Int, val gain: Int)

@Composable
fun EqualizerScreen(onBack: () -> Unit, resumeTick: Int = 0) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val eq by Headphones.equalizer.collectAsState()
    val scope = rememberCoroutineScope()
    // Only the first live read gates editing. Refreshes leave active controls in place.
    var busy by remember { mutableStateOf(eq == null) }
    var failed by remember { mutableStateOf(false) }
    var refused by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<EqualizerWrite?>(null) }
    val writes = remember { Channel<EqualizerWrite>(Channel.CONFLATED) }
    LaunchedEffect(writes) {
        for (request in writes) {
            val confirmed = Headphones.writeEqualizer(request.preset, request.bands)
            if (pending === request) {
                refused = !confirmed
                pending = null
            }
        }
    }
    suspend fun load() {
        busy = eq == null
        try { failed = Headphones.readEqualizer() == null } finally { busy = false }
    }
    LaunchedEffect(resumeTick) { load() }
    fun write(preset: Int? = null, bands: List<Int>? = null) {
        val base = eq ?: return
        val previous = pending
        if (previous != null && previous.preset == preset && previous.bands == bands) return
        if (previous == null && ((bands == null && preset == base.preset) ||
                (bands != null && base.preset == CUSTOM && bands == base.bands))) return
        val shown = if (bands != null) EqualizerProtocol.State(CUSTOM, bands)
            else EqualizerProtocol.State(requireNotNull(preset), emptyList())
        val request = EqualizerWrite(shown, preset, bands)
        pending = request
        refused = false
        writes.trySend(request)
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val state = eq ?: return@rememberLauncherForActivityResult
        if (uri != null) scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                        output.write(EqualizerFile.write(state).toByteArray())
                    } != null
                }.getOrDefault(false)
            }
            toast(ctx, if (saved) "Sound exported" else "Couldn't save the file")
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val bands = withContext(Dispatchers.IO) {
                val text = runCatching {
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        input.readNBytes(32_769).takeIf { it.size <= 32_768 }?.decodeToString()
                    }
                }.getOrNull()
                text?.let(EqualizerFile::read)
            }
            if (bands == null) toast(ctx, "This file isn't an Earbuds sound") else write(bands = bands)
        }
    }
    // The last known sound shows at once while the earbuds are asked again; controls stay
    // disabled until the live answer arrives (busy), so nothing is ever written from the cache.
    val cached = remember { Prefs.cachedEqualizer(ctx) }
    EqualizerContent(
        pending?.shown ?: eq ?: cached.takeIf { busy }, busy, failed && eq == null, onBack,
        onRetry = { scope.launch { load() } },
        onPreset = { write(preset = it) },
        onBands = { write(bands = it) },
        onToggle = { write(preset = if (it) Prefs.lastEnabledEqualizerPreset(ctx) else OFF) },
        refused = refused,
        applyingPreset = pending?.preset != null,
        exportReady = pending == null && eq?.bands?.size == 6,
        pendingBands = eq?.bands.takeIf { pending?.preset != null },
        haptics = remember { Prefs.haptics(ctx) },
        onExport = { exporter.launch("Earbuds sound.json") },
        onImport = { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
    )
}

private fun toast(ctx: android.content.Context, text: String) = android.widget.Toast.makeText(ctx, text, android.widget.Toast.LENGTH_SHORT).show()

/**
 * A sound saved as a small JSON file: the five bands and Clear Bass, -10..10 each. Importing
 * always writes it as Custom, so any preset someone tuned travels as its exact curve.
 */
internal object EqualizerFile {
    private const val FORMAT = "earbuds-sound"
    fun write(state: EqualizerProtocol.State): String {
        val o = org.json.JSONObject().put("format", FORMAT).put("schema", 1)
            .put("preset", EqualizerProtocol.presets[state.preset] ?: "Custom")
        if (state.bands.size == 6) o.put("clearBass", state.bands[0]).put("bands", org.json.JSONArray(state.bands.drop(1)))
        return o.toString(2)
    }
    fun read(text: String): List<Int>? = runCatching {
        val o = org.json.JSONObject(text)
        if (o.optString("format") != FORMAT) return null
        val a = o.getJSONArray("bands")
        if (a.length() != 5) return null
        val bands = listOf(o.optInt("clearBass", 0)) + List(5) { a.getInt(it) }
        bands.takeIf { b -> b.all { it in -10..10 } }
    }.getOrNull()
}

/** The five bands' centres, as Sony's app labels them. */
private val BAND_HZ = listOf(400.0, 1_000.0, 2_500.0, 6_300.0, 16_000.0)
private const val OFF = 0x00
private const val CUSTOM = 0xA0

private fun bandLabel(hz: Double) = if (hz >= 1_000) "${String.format(java.util.Locale.ROOT, "%.1f", hz / 1_000).removeSuffix(".0")} kHz" else "${hz.toInt()} Hz"
private fun signed(v: Int) = if (v > 0) "+$v" else v.toString()

/** The curve and Clear Bass share one edit with six underlying gains, in Sony wire order. */
@Composable
fun EqualizerContent(
    state: EqualizerProtocol.State?, busy: Boolean, unavailable: Boolean, onBack: () -> Unit = {},
    onRetry: () -> Unit = {}, onPreset: (Int) -> Unit = {}, onBands: (List<Int>) -> Unit = {}, refused: Boolean = false,
    onExport: () -> Unit = {}, onImport: () -> Unit = {},
    applyingPreset: Boolean = false, exportReady: Boolean = state?.bands?.size == 6, pendingBands: List<Int>? = null,
    haptics: Boolean = true,
    onToggle: (Boolean) -> Unit = { onPreset(if (it) CUSTOM else OFF) },
) {
    val cs = MaterialTheme.colorScheme
    PageScaffold("Equalizer", onBack, actions = {
        if (busy) LoadingIndicator(Modifier.padding(end = 4.dp).size(32.dp))
        var menu by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.semantics { contentDescription = "More options" }) {
                // more_vert, drawn: three dots (the app's symbol subset has no overflow icon).
                val dot = cs.onSurfaceVariant
                Canvas(Modifier.size(24.dp)) {
                    val r = 2.dp.toPx()
                    for (y in listOf(6f, 12f, 18f)) drawCircle(dot, r, Offset(size.width / 2, y.dp.toPx()))
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = RoundedCornerShape(16.dp)) {
                DropdownMenuItem(text = { Text("Export sound") }, enabled = exportReady, onClick = { menu = false; onExport() })
                DropdownMenuItem(text = { Text("Import sound") }, enabled = !busy && state != null, onClick = { menu = false; onImport() })
            }
        }
    }) {
        when {
            state == null && unavailable -> SoundCard {
                Text("Equalizer unavailable", style = MaterialTheme.typography.titleMediumEmphasized)
                Text("Check the earbud connection.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                FilledTonalButton(onClick = onRetry, enabled = !busy) { Text("Try again") }
            }
            state == null -> Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
            else -> {
                val on = state.preset != OFF
                val gains = state.bands.takeIf { it.size == 6 && on }
                // Keep only the locally edited control. Other read-back values remain live.
                var edit by remember { mutableStateOf<EqEdit?>(null) }
                val shownBands = gains ?: pendingBands?.takeIf { on && it.size == 6 }
                val displayed = shownBands?.let { confirmed ->
                    edit?.let { change -> confirmed.toMutableList().also { it[change.index] = change.gain } } ?: confirmed
                }
                val activeBand = edit?.index?.minus(1)
                val controlsReady = !busy && !applyingPreset && on && gains != null
                LaunchedEffect(controlsReady) { if (!controlsReady) edit = null }
                fun commit(index: Int, gain: Int) {
                    val latest = gains.orEmpty()
                    if (controlsReady && latest[index] != gain) {
                        onBands(latest.toMutableList().also { it[index] = gain })
                    }
                    edit = null
                }
                val flat = state.preset == CUSTOM && gains != null && gains.subList(1, 6).all { it == 0 }
                val editingSound = edit?.let { gains?.get(it.index) != it.gain } == true
                val presetName = when {
                    editingSound && on -> "Custom"
                    flat -> "Flat"
                    else -> EqualizerProtocol.presets[state.preset] ?: "Custom"
                }

                SoundCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(presetName, style = MaterialTheme.typography.headlineSmallEmphasized)
                            if (applyingPreset || refused) {
                                Text(
                                    if (refused) "Change not confirmed" else "Applying preset",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (refused) cs.error else cs.onSurfaceVariant,
                                )
                            }
                        }
                        Switch(checked = on, enabled = !busy, onCheckedChange = onToggle,
                            thumbContent = if (on) ({ HushIcon(Glyph.CHECK, Modifier.size(SwitchDefaults.IconSize), cs.primary) }) else null,
                            modifier = Modifier.semantics { contentDescription = "Equalizer" })
                    }
                    Box(contentAlignment = Alignment.Center) {
                        EqCurve(
                            gains = displayed?.subList(1, 6), enabled = controlsReady && edit?.index != 0,
                            activeBand = activeBand, haptics = haptics,
                            onGain = { index, value -> edit = EqEdit(index + 1, value) },
                            onFinish = { index, value -> if (value != null) commit(index + 1, value) else edit = null },
                        )
                        if (shownBands == null && !applyingPreset) {
                            Text(
                                when {
                                    !on -> "Turn on to edit bands"
                                    else -> "Choose Custom to edit bands"
                                },
                                style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                    }
                    BandReadouts(displayed, activeBand, controlsReady && edit == null) { index, gain -> commit(index + 1, gain) }
                }
                SoundCard {
                    ClearBass(
                        gain = displayed?.first(),
                        enabled = controlsReady && (edit == null || edit?.index == 0),
                        editing = edit?.index == 0, haptics = haptics,
                        onGain = { edit = EqEdit(0, it) },
                        onFinish = { commit(0, it) },
                    )
                }
                SoundCard {
                    PresetGroups(
                        active = state.preset, flat = flat,
                        enabled = !busy && edit == null,
                        onPreset = onPreset,
                        onFlat = { onBands(listOf(gains?.first() ?: 0) + List(5) { 0 }) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BandReadouts(gains: List<Int>?, active: Int?, editable: Boolean, onGain: (Int, Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        BAND_HZ.forEachIndexed { index, hz ->
            val label = bandLabel(hz)
            val gain = gains?.get(index + 1)
            val chosen = active == index
            Surface(
                color = if (chosen) cs.primaryContainer else cs.surfaceContainerHighest,
                contentColor = if (chosen) cs.onPrimaryContainer else cs.onSurfaceVariant,
                shape = RoundedCornerShape(
                    topStart = if (index == 0) 14.dp else 4.dp, bottomStart = if (index == 0) 14.dp else 4.dp,
                    topEnd = if (index == BAND_HZ.lastIndex) 14.dp else 4.dp, bottomEnd = if (index == BAND_HZ.lastIndex) 14.dp else 4.dp,
                ),
                modifier = Modifier.weight(1f).clearAndSetSemantics {
                    contentDescription = "$label, ${gain?.let { "${signed(it)} dB" } ?: "unavailable"}"
                    if (editable && gain != null) {
                        progressBarRangeInfo = ProgressBarRangeInfo(gain.toFloat(), -10f..10f, 19)
                        setProgress { value ->
                            onGain(index, value.roundToInt().coerceIn(-10, 10))
                            true
                        }
                    }
                },
            ) {
                Column(Modifier.padding(horizontal = 2.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                        autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(9.sp, MaterialTheme.typography.labelSmall.fontSize, .5.sp))
                    Text(gain?.let(::signed) ?: "–", style = MaterialTheme.typography.labelLarge.withTabularFigures(), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun ClearBass(gain: Int?, enabled: Boolean, editing: Boolean, haptics: Boolean, onGain: (Int) -> Unit, onFinish: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Clear Bass", style = MaterialTheme.typography.titleMediumEmphasized, modifier = Modifier.weight(1f))
        Text(gain?.let(::signed) ?: "–", style = MaterialTheme.typography.titleMedium.withTabularFigures(),
            color = if (gain != null && gain != 0) cs.primary else cs.onSurfaceVariant)
    }
    val slider = remember { SliderState(value = gain?.toFloat() ?: 0f, steps = 19, trackRange = -10f..10f) }
    LaunchedEffect(gain, editing) {
        if (!editing) slider.value = gain?.toFloat() ?: 0f
    }
    val feedback = LocalHapticFeedback.current
    Slider(
        state = slider, enabled = enabled,
        onValueChange = {
            val old = slider.value.roundToInt()
            slider.value = it
            val step = slider.value.roundToInt()
            if (step != old) {
                if (haptics) feedback.performHapticFeedback(if (step == 0) HapticFeedbackType.SegmentTick else HapticFeedbackType.SegmentFrequentTick)
                onGain(step)
            }
        },
        onValueChangeFinished = { onFinish(slider.value.roundToInt()) },
        track = { SliderDefaults.CenteredTrack(sliderState = it, enabled = enabled, drawTick = { _, _ -> }) },
        modifier = Modifier.semantics { contentDescription = "Clear Bass" },
    )
}

@Composable
private fun PresetGroups(active: Int, flat: Boolean, enabled: Boolean, onPreset: (Int) -> Unit, onFlat: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    @Composable fun Group(title: String, rows: List<List<Int>>) {
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 2.dp).semantics { heading() })
            rows.forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                    row.forEachIndexed { index, code ->
                        val label = if (code == -1) "Flat" else EqualizerProtocol.presets.getValue(code)
                        val selected = if (code == -1) flat else active == code && (code != CUSTOM || !flat)
                        PresetButton(label, selected, enabled, index, row.size, Modifier.weight(1f)) {
                            if (code == -1) onFlat() else onPreset(code)
                        }
                    }
                }
            }
        }
    }
    Group("Your sound", listOf(listOf(CUSTOM, -1, 0xA1, 0xA2)))
    Group("Presets", listOf(listOf(0x10, 0x11, 0x12), listOf(0x13, 0x14, 0x17), listOf(0x15, 0x16)))
}

/** A rounded card for one part of the sound, as in Music. */
@Composable
private fun SoundCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = UiTokens.cardShape, color = Surfaces.card, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(UiTokens.cardInset), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

/** One preset in a connected row: joined corners at rest, a pill when chosen, with Material's springs. */
@Composable
private fun PresetButton(name: String, selected: Boolean, enabled: Boolean, index: Int, count: Int, modifier: Modifier, onClick: () -> Unit) {
    val shapes = when (index) {
        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
        count - 1 -> ButtonGroupDefaults.connectedTrailingButtonShapes()
        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
    }
    val cs = MaterialTheme.colorScheme
    ToggleButton(
        checked = selected, onCheckedChange = { if (!selected) onClick() }, enabled = enabled, shapes = shapes,
        colors = ToggleButtonDefaults.colors(containerColor = cs.surfaceContainerHighest, contentColor = cs.onSurfaceVariant),
        contentPadding = PaddingValues(horizontal = 8.dp),
        modifier = modifier.heightIn(min = UiTokens.minTouchTarget).semantics { role = Role.RadioButton; this.selected = selected },
    ) {
        Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 1,
            autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = MaterialTheme.typography.labelLarge.fontSize, stepSize = 0.5.sp))
    }
}

private fun bandX(index: Int, width: Float) = width * ((index + .5f) / BAND_HZ.size)

/** The five band centers are spaced logarithmically; ends extend flat to the plot edges. */
@Composable
private fun EqCurve(
    gains: List<Int>?, enabled: Boolean, activeBand: Int?, haptics: Boolean,
    onGain: (Int, Int) -> Unit, onFinish: (Int, Int?) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val feedback = LocalHapticFeedback.current
    val motion = MaterialTheme.motionScheme
    val shown = BAND_HZ.indices.map { index ->
        animateFloatAsState(
            gains?.get(index)?.toFloat() ?: 0f,
            if (activeBand == index || gains == null) snap() else motion.fastSpatialSpec(), label = "band $index",
        )
    }
    val latest by rememberUpdatedState(gains)
    val animatedGains by rememberUpdatedState(shown)
    val hasGains = gains != null
    val updateGain by rememberUpdatedState(onGain)
    val finish by rememberUpdatedState(onFinish)
    val feedbackEnabled by rememberUpdatedState(haptics)
    val tick by rememberUpdatedState(feedback)
    var dragPoint by remember { mutableStateOf<Pair<Int, Float>?>(null) }
    val radius = animateFloatAsState(if (activeBand != null && activeBand >= 0) 8f else 5f, motion.fastSpatialSpec(), label = "Band handle")
    val range = 10f
    val padding = 24.dp
    Box(Modifier.fillMaxWidth().height(216.dp)) {
        Spacer(
            Modifier.matchParentSize().semantics { contentDescription = "Equalizer curve" }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val source = latest ?: return@awaitEachGesture
                        val perDb = (size.height / 2f - padding.toPx()) / range
                        val index = BAND_HZ.indices.minByOrNull { band ->
                            val dx = bandX(band, size.width.toFloat()) - down.position.x
                            val dy = size.height / 2f - shown[band].value * perDb - down.position.y
                            dx * dx + dy * dy
                        } ?: return@awaitEachGesture
                        val x = bandX(index, size.width.toFloat())
                        val y = size.height / 2f - shown[index].value * perDb
                        if (abs(x - down.position.x) > 36.dp.toPx() || abs(y - down.position.y) > 36.dp.toPx()) return@awaitEachGesture
                        var initial = 0f
                        val start = awaitVerticalTouchSlopOrCancellation(down.id) { change, over ->
                            initial = over
                            change.consume()
                        } ?: return@awaitEachGesture

                        val original = source[index]
                        val startValue = shown[index].value
                        var raw = startValue
                        var last = original
                        dragPoint = index to raw
                        updateGain(index, last)
                        fun drag(dy: Float) {
                            raw = (raw - dy / perDb).coerceIn(-range, range)
                            dragPoint = index to raw
                            val step = raw.roundToInt()
                            if (step != last) {
                                last = step
                                if (feedbackEnabled) tick.performHapticFeedback(
                                    if (step == 0) HapticFeedbackType.SegmentTick else HapticFeedbackType.SegmentFrequentTick,
                                )
                                updateGain(index, step)
                            }
                        }
                        var result: Int? = null
                        try {
                            drag(initial)
                            if (verticalDrag(start.id) { change ->
                                drag(change.position.y - change.previousPosition.y)
                                change.consume()
                            } && last != original) result = last
                        } finally {
                            val gain = result
                            finish(index, gain)
                            dragPoint = null
                        }
                    }
                }
                .drawWithCache {
                    val width = size.width
                    val gridValues = listOf(-10, -5, 0, 5, 10)
                    val mid = size.height / 2f
                    val perDb = (mid - padding.toPx()) / range
                    val xs = FloatArray(BAND_HZ.size) { bandX(it, width) }
                    val ys = FloatArray(BAND_HZ.size)
                    val line = Path()
                    val area = Path()
                    val stroke = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round)
                    val grid = cs.outlineVariant.copy(alpha = .6f)
                    val tone = if (enabled) cs.primary else cs.outline
                    onDrawBehind {
                        for (x in xs) drawLine(grid, Offset(x, mid - range * perDb), Offset(x, mid + range * perDb), 1.dp.toPx())
                        for (db in gridValues) {
                            val y = mid - db * perDb
                            drawLine(if (db == 0) cs.outlineVariant else grid, Offset(0f, y), Offset(width, y), 1.dp.toPx())
                        }
                        if (!hasGains) return@onDrawBehind
                        val dragging = dragPoint
                        for (i in ys.indices) {
                            val value = if (dragging?.first == i) dragging.second else animatedGains[i].value
                            ys[i] = mid - value.coerceIn(-range, range) * perDb
                        }
                        line.reset()
                        line.moveTo(0f, ys.first())
                        line.lineTo(xs.first(), ys.first())
                        for (i in 0 until xs.lastIndex) {
                            val dx = (xs[i + 1] - xs[i]) / 3f
                            line.cubicTo(xs[i] + dx, ys[i], xs[i + 1] - dx, ys[i + 1], xs[i + 1], ys[i + 1])
                        }
                        line.lineTo(width, ys.last())
                        area.reset()
                        area.addPath(line)
                        area.lineTo(width, mid)
                        area.lineTo(0f, mid)
                        area.close()
                        drawPath(area, tone.copy(alpha = .12f))
                        drawPath(line, tone, style = stroke)
                        for (i in xs.indices) {
                            val handle = (if (dragging?.first == i) radius.value else 5f).dp.toPx()
                            val center = Offset(xs[i], ys[i])
                            drawCircle(cs.surfaceContainerHigh, handle + 2.dp.toPx(), center)
                            drawCircle(tone, handle, center)
                        }
                    }
                },
        )
        if (gains != null) {
            Text("+10 dB", Modifier.align(Alignment.TopStart), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            Text("−10 dB", Modifier.align(Alignment.BottomStart), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
    }
}
