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
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlin.math.log10
import kotlin.math.roundToInt

private class EqualizerWrite(val shown: EqualizerProtocol.State, val preset: Int?, val bands: List<Int>?)

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
        val shown = if (bands != null) EqualizerProtocol.State(CUSTOM, bands) else base.copy(preset = preset ?: base.preset)
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

/**
 * The equalizer in Music's language: one card for the equalizer (its switch in the header, the
 * presets as chips, the sound as a curve to drag) and one for Clear Bass. [state] bands are Clear
 * Bass followed by the five bands, each -10..10.
 */
@Composable
fun EqualizerContent(
    state: EqualizerProtocol.State?, busy: Boolean, unavailable: Boolean, onBack: () -> Unit = {},
    onRetry: () -> Unit = {}, onPreset: (Int) -> Unit = {}, onBands: (List<Int>) -> Unit = {}, refused: Boolean = false,
    onExport: () -> Unit = {}, onImport: () -> Unit = {},
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
                DropdownMenuItem(text = { Text("Export sound") }, enabled = state?.bands?.size == 6, onClick = { menu = false; onExport() })
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
                val editable = !busy
                val on = state.preset != OFF
                var held by remember { mutableStateOf<Int?>(null) }
                var adjustingBass by remember { mutableStateOf(false) }
                var bands by remember { mutableStateOf(state.bands) }
                LaunchedEffect(state, held, adjustingBass) {
                    if (held == null && !adjustingBass) bands = state.bands
                }
                SoundCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Equalizer", style = MaterialTheme.typography.titleMediumEmphasized)
                            Text(if (on) EqualizerProtocol.presets[state.preset] ?: "Custom" else "Off",
                                style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                        }
                        Switch(checked = on, enabled = editable, onCheckedChange = onToggle,
                            thumbContent = if (on) ({ HushIcon(Glyph.CHECK, Modifier.size(SwitchDefaults.IconSize), cs.primary) }) else null,
                            modifier = Modifier.semantics { contentDescription = "Equalizer" })
                    }
                    if (refused) Text("Change not confirmed", style = MaterialTheme.typography.bodyMedium, color = cs.error)
                    // Two groups, each as rows of connected buttons (M3E button groups): your own
                    // sounds first, then Sony's presets. The chosen one turns into a full pill.
                    val flat = state.preset == CUSTOM && bands.size == 6 && bands.drop(1).all { it == 0 }
                    fun pick(code: Int) = Triple(EqualizerProtocol.presets[code] ?: "", state.preset == code && !(code == CUSTOM && flat)) { onPreset(code) }
                    val yours = listOf(pick(CUSTOM), Triple("Flat", flat) { onBands(listOf(bands.getOrElse(0) { 0 }) + List(5) { 0 }) }, pick(0xA1), pick(0xA2))
                    val sony = listOf(0x10, 0x11, 0x12, 0x13, 0x14, 0x17, 0x15, 0x16).map { pick(it) }
                    @Composable fun Rows(title: String, rows: List<List<Triple<String, Boolean, () -> Unit>>>) {
                        Text(title, style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, start = 4.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            rows.forEach { row ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                                    row.forEachIndexed { i, (name, selected, choose) ->
                                        PresetButton(name, selected, editable, i, row.size, Modifier.weight(1f), choose)
                                    }
                                }
                            }
                        }
                    }
                    Rows("Your sound", listOf(yours))
                    Rows("Presets", listOf(sony.take(3), sony.subList(3, 6), sony.drop(6)))
                    if (bands.size == 6) {
                        EqCurve(bands.drop(1), editable && on, on, held,
                            onHold = { held = it },
                            onGain = { i, v -> bands = bands.toMutableList().also { it[i + 1] = v } },
                            onDone = {
                                val index = held?.plus(1)
                                if (index != null && state.bands.size == 6) {
                                    onBands(state.bands.toMutableList().also { it[index] = bands[index] })
                                }
                            })
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
                    } else {
                        Text("Choose Custom to adjust the bands.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    }
                }
                if (bands.size == 6) SoundCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Clear Bass", style = MaterialTheme.typography.titleMediumEmphasized)
                        }
                        Text(signed(bands[0]), style = MaterialTheme.typography.titleMedium, color = if (bands[0] != 0) cs.primary else cs.onSurfaceVariant)
                    }
                    val clearBass = remember { SliderState(value = bands[0].toFloat(), steps = 19, trackRange = -10f..10f) }
                    LaunchedEffect(bands[0]) { if (!adjustingBass) clearBass.value = bands[0].toFloat() }
                    val feedback = LocalHapticFeedback.current
                    Slider(
                        state = clearBass, enabled = editable && on,
                        onValueChange = {
                            adjustingBass = true
                            clearBass.value = it
                            val step = clearBass.value.roundToInt()
                            if (step != bands[0]) {
                                feedback.performHapticFeedback(if (step == 0) HapticFeedbackType.SegmentTick else HapticFeedbackType.SegmentFrequentTick)
                                bands = bands.toMutableList().also { b -> b[0] = step }
                            }
                        },
                        onValueChangeFinished = {
                            adjustingBass = false
                            val gain = clearBass.value.roundToInt()
                            if (state.bands.size == 6 && gain != state.bands[0]) {
                                onBands(state.bands.toMutableList().also { it[0] = gain })
                            }
                        },
                        track = { SliderDefaults.CenteredTrack(sliderState = it, enabled = editable && on, drawTick = { _, _ -> }) },
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
    val shown = values.mapIndexed { i, v ->
        animateFloatAsState(v.toFloat(), if (held == i) snap() else MaterialTheme.motionScheme.fastSpatialSpec(), label = "band $i")
    }
    val latest by rememberUpdatedState(values)
    val updateHold by rememberUpdatedState(onHold)
    val updateGain by rememberUpdatedState(onGain)
    val finish by rememberUpdatedState(onDone)
    val haptics by rememberUpdatedState(feedback)
    var dragPoint by remember { mutableStateOf<Pair<Int, Float>?>(null) }
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
                    val original = latest[index]
                    var last = original
                    dragPoint = index to raw
                    updateHold(index)
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    fun drag(dy: Float) {
                        raw = (raw - dy / perDb).coerceIn(-range, range)
                        dragPoint = index to raw
                        val step = raw.roundToInt()
                        if (step != last) {
                            last = step
                            haptics.performHapticFeedback(if (step == 0) HapticFeedbackType.SegmentTick else HapticFeedbackType.SegmentFrequentTick)
                            updateGain(index, step)
                        }
                    }
                    var completed = false
                    try {
                        drag(initial)
                        completed = verticalDrag(start.id) { change -> drag(change.position.y - change.previousPosition.y); change.consume() }
                        if (completed && last != original) finish()
                    } finally {
                        if (!completed && last != original) updateGain(index, original)
                        updateHold(null)
                        dragPoint = null
                    }
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
        val dragging = dragPoint
        val points = BAND_HZ.mapIndexed { i, hz ->
            val gain = if (dragging?.first == i) dragging.second else shown[i].value
            Offset(xOf(hz, w), yOf(gain))
        }
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
            // A held band grows into a cookie (the app's sound shape family, Scallop.kt), so the
            // finger's point reads as picked up; the others stay small round dots.
            if (dragging?.first == i) {
                val r = 13.dp.toPx()
                drawPath(Scallop.path(c.x, c.y, r + 2.dp.toPx(), 8, 0.08f), dot)
                drawPath(Scallop.path(c.x, c.y, r, 8, 0.08f), tone)
            } else {
                val r = 6.dp.toPx()
                drawCircle(dot, r + 2.dp.toPx(), c)
                drawCircle(tone, r, c)
            }
            if (dragging?.first == i) {
                val layout = measurer.measure("${signed(dragging.second.roundToInt())} dB · ${bandLabel(BAND_HZ[i])}", valueStyle)
                val bw = layout.size.width + 20.dp.toPx(); val bh = layout.size.height + 10.dp.toPx()
                val bx = (c.x - bw / 2f).coerceIn(0f, (w - bw).coerceAtLeast(0f))
                val by = if (c.y > mid) c.y - bh - 20.dp.toPx() else c.y + 20.dp.toPx()
                drawRoundRect(cs.primary, Offset(bx, by), Size(bw, bh), CornerRadius(12.dp.toPx()))
                drawText(layout, topLeft = Offset(bx + 10.dp.toPx(), by + 5.dp.toPx()))
            }
        }
    }
}
