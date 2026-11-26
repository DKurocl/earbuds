@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

package ua.olex.sonyanc

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
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

private class EqualizerWrite(val shown: EqualizerProtocol.State, val preset: Int?, val bands: List<Int>?, val slot: Int?)

private data class EqEdit(val index: Int, val gain: Int)

@Composable
fun EqualizerScreen(onBack: () -> Unit, resumeTick: Int = 0) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val eq by Headphones.equalizer.collectAsState()
    val scope = rememberCoroutineScope()
    val pair = Prefs.deviceAddress(ctx)
    var cached by remember(pair) { mutableStateOf(Prefs.cachedEqualizer(ctx)) }
    LaunchedEffect(eq, pair) { if (eq != null) cached = eq }
    var slots by remember(pair) { mutableStateOf((1..2).associateWith { Prefs.savedSound(ctx, it) }) }
    var lastSlot by remember(pair) { mutableStateOf<Int?>(null) }
    var toSave by remember(pair) { mutableStateOf<List<Int>?>(null) }
    var overwrite by remember(pair) { mutableStateOf<Int?>(null) }
    // Only the first live read gates editing. Refreshes leave active controls in place.
    var busy by remember { mutableStateOf(eq == null && cached == null) }
    var failed by remember { mutableStateOf(false) }
    val showMessage by rememberUpdatedState(LocalMessages.current)
    var pending by remember { mutableStateOf<EqualizerWrite?>(null) }
    val writes = remember { Channel<EqualizerWrite>(Channel.CONFLATED) }
    LaunchedEffect(writes) {
        for (request in writes) {
            val confirmed = Headphones.writeEqualizer(request.preset, request.bands)
            if (pending === request) {
                pending = null
                if (confirmed) lastSlot = request.slot else showMessage("Change not confirmed")
            }
        }
    }
    suspend fun load() {
        busy = eq == null && cached == null
        try { failed = Headphones.readEqualizer() == null } finally { busy = false }
    }
    LaunchedEffect(resumeTick) { load() }
    fun write(preset: Int? = null, bands: List<Int>? = null, slot: Int? = null) {
        val base = eq ?: return
        val previous = pending
        if (previous != null && previous.preset == preset && previous.bands == bands && previous.slot == slot) return
        if (previous == null && ((bands == null && preset == base.preset) ||
                (bands != null && base.preset == CUSTOM && bands == base.bands))) {
            lastSlot = slot
            return
        }
        val shown = if (bands != null) EqualizerProtocol.State(CUSTOM, bands)
            else EqualizerProtocol.State(requireNotNull(preset), emptyList())
        val request = EqualizerWrite(shown, preset, bands, slot)
        pending = request
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
            if (bands == null) showMessage("Choose an Earbuds JSON or a GraphicEQ text file.")
            else if (Prefs.deviceAddress(ctx) == pair) toSave = bands
        }
    }
    fun save(slot: Int, bands: List<Int>) {
        if (Prefs.deviceAddress(ctx) != pair) return
        Prefs.saveSound(ctx, slot, bands)
        slots = slots + (slot to bands)
        toSave = null
        overwrite = null
        showMessage("Saved to Saved $slot")
    }
    toSave?.let { bands ->
        val replacing = overwrite
        if (replacing != null) AlertDialog(
            onDismissRequest = { overwrite = null },
            title = { Text("Replace Saved $replacing?") },
            confirmButton = { TextButton(onClick = { save(replacing, bands) }) { Text("Replace") } },
            dismissButton = { TextButton(onClick = { overwrite = null }) { Text("Back") } },
        ) else AlertDialog(
            onDismissRequest = { toSave = null },
            title = { Text("Save preset") },
            text = { Column {
                Text("Presets are stored in Earbuds. Tap a saved slot to apply it. GraphicEQ curves are fitted to the earbuds’ six controls.")
                for (slot in 1..2) {
                    TextButton(onClick = {
                        if (slots[slot] == null) save(slot, bands) else overwrite = slot
                    }) { Text("Saved $slot") }
                }
            } },
            confirmButton = { TextButton(onClick = { toSave = null }) { Text("Cancel") } },
        )
    }
    val shown = pending?.shown ?: eq ?: cached
    val slot = pending?.slot ?: lastSlot
    val selectedSlot = if (shown?.preset == CUSTOM) {
        slot?.takeIf { slots[it] == shown.bands } ?: slots.entries.firstOrNull { it.value == shown.bands }?.key
    } else null
    EqualizerContent(
        shown, busy, failed && shown == null, onBack,
        onRetry = { scope.launch { load() } },
        onPreset = { preset ->
            val index = preset - 0xA0
            val stored = slots[index]
            if (stored != null) write(bands = stored, slot = index) else write(preset = preset)
        },
        onBands = { write(bands = it) },
        onToggle = { write(preset = if (it) Prefs.lastEnabledEqualizerPreset(ctx) else OFF) },
        editable = eq != null,
        selectedSlot = selectedSlot,
        onSave = shown?.bands?.takeIf { it.size == 6 && pending == null }?.let { bands -> { toSave = bands } },
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
 * Earbuds JSON maps directly to Sony gains. GraphicEQ is sampled on a logarithmic frequency axis.
 */
internal object EqualizerFile {
    private const val FORMAT = "earbuds-sound"
    fun write(state: EqualizerProtocol.State): String {
        val o = org.json.JSONObject().put("format", FORMAT).put("schema", 1)
            .put("preset", EqualizerProtocol.presets[state.preset] ?: "Custom")
        if (state.bands.size == 6) o.put("clearBass", state.bands[0]).put("bands", org.json.JSONArray(state.bands.drop(1)))
        return o.toString(2)
    }
    fun read(text: String): List<Int>? {
        val input = text.trim().removePrefix("\uFEFF").trim()
        return if (input.startsWith("GraphicEQ:", ignoreCase = true)) readGraphicEq(input) else readJson(input)
    }

    private fun readJson(text: String): List<Int>? = runCatching {
        val o = org.json.JSONObject(text)
        if (o.optString("format") != FORMAT) return null
        val a = o.getJSONArray("bands")
        if (a.length() != 5) return null
        val bands = listOf(o.optInt("clearBass", 0)) + List(5) { a.getInt(it) }
        bands.takeIf { b -> b.all { it in -10..10 } }
    }.getOrNull()
    private val pairSeparator = Regex("\\s+")

    private fun readGraphicEq(text: String): List<Int>? {
        val points = text.substringAfter(':').split(';').filter { it.isNotBlank() }.map { entry ->
            val fields = entry.trim().split(pairSeparator)
            if (fields.size != 2) return null
            val hz = fields[0].toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: return null
            val gain = fields[1].toDoubleOrNull()?.takeIf { it.isFinite() && it in -120.0..120.0 } ?: return null
            hz to gain
        }
        if (points.size < 2 || points.zipWithNext().any { (a, b) -> a.first >= b.first }) return null
        return (listOf(63.0) + BAND_HZ).map { frequency ->
            val i = points.indexOfFirst { it.first >= frequency }
            val gain = when {
                i < 0 -> points.last().second
                i == 0 -> points.first().second
                else -> {
                    val (lo, a) = points[i - 1]
                    val (hi, b) = points[i]
                    val position = kotlin.math.ln(frequency / lo) / kotlin.math.ln(hi / lo)
                    a + (b - a) * position
                }
            }
            gain.roundToInt().coerceIn(-10, 10)
        }
    }

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
    onRetry: () -> Unit = {}, onPreset: (Int) -> Unit = {}, onBands: (List<Int>) -> Unit = {},
    onExport: () -> Unit = {}, onImport: () -> Unit = {},
    applyingPreset: Boolean = false, exportReady: Boolean = state?.bands?.size == 6, pendingBands: List<Int>? = null,
    haptics: Boolean = true, editable: Boolean = true,
    selectedSlot: Int? = null, onSave: (() -> Unit)? = null,
    onToggle: (Boolean) -> Unit = { onPreset(if (it) CUSTOM else OFF) },
) {
    val cs = MaterialTheme.colorScheme
    PageScaffold("Equalizer", onBack, actions = {
        HeaderActionButton(HeaderAction(Glyph.COPY, "Export preset", onExport, exportReady))

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
                val controlsReady = editable && !busy && !applyingPreset && on && gains != null
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
                    selectedSlot != null -> "Saved $selectedSlot"
                    flat -> "Flat"
                    else -> EqualizerProtocol.presets[state.preset] ?: "Custom"
                }

                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // As a Settings switch row: what it is, which sound is on, and the switch.
                    Surface(shape = UiTokens.cardShape, color = Surfaces.card, modifier = Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("Use equalizer", style = MaterialTheme.typography.titleLarge)
                                Text(if (on) presetName else "Off", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                            }
                            Switch(checked = on, enabled = editable && !busy, onCheckedChange = onToggle,
                                thumbContent = if (on) ({ HushIcon(Glyph.CHECK, Modifier.size(SwitchDefaults.IconSize), cs.primary) }) else null,
                                modifier = Modifier.semantics { contentDescription = "Equalizer" })
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Box(contentAlignment = Alignment.Center) {
                        EqCurve(
                            gains = displayed?.subList(1, 6), enabled = controlsReady && edit?.index != 0,
                            activeBand = activeBand, haptics = haptics,
                            onGain = { index, value -> edit = EqEdit(index + 1, value) },
                            onFinish = { index, value -> if (value != null) commit(index + 1, value) else edit = null },
                        )
                        if (shownBands == null && !applyingPreset) {
                            Text(
                                if (!on) "Turn on to edit bands" else "Choose Custom to edit bands",
                                style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                    }
                    BandReadouts(displayed, activeBand, controlsReady && edit == null) { index, gain -> commit(index + 1, gain) }
                }
                ClearBass(
                    gain = displayed?.first(),
                    enabled = controlsReady && (edit == null || edit?.index == 0),
                    editing = edit?.index == 0, haptics = haptics,
                    onGain = { edit = EqEdit(0, it) },
                    onFinish = { commit(0, it) },
                )
                PresetGroups(
                    active = selectedSlot?.let { 0xA0 + it } ?: state.preset, flat = flat && selectedSlot == null,
                    enabled = editable && !busy && edit == null,
                    onPreset = onPreset,
                    onFlat = { onBands(listOf(gains?.first() ?: 0) + List(5) { 0 }) },
                )
            }
        }
        ListGroup("Your presets") {
            item { NavRow(it, Glyph.ADD, "Save current sound", "Keep it as Saved 1 or Saved 2", enabled = onSave != null) { onSave?.invoke() } }
            item { NavRow(it, Glyph.EDIT, "Import a sound", "From an Earbuds or GraphicEQ file", onClick = onImport) }
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
                color = if (chosen) cs.primaryContainer else androidx.compose.ui.graphics.Color.Transparent,
                contentColor = if (chosen) cs.onPrimaryContainer else cs.onSurfaceVariant,
                shape = RoundedCornerShape(14.dp),
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
                Column(
                    Modifier.heightIn(min = UiTokens.minTouchTarget).padding(horizontal = 2.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
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
    val slider = remember { SliderState(value = gain?.toFloat() ?: 0f, steps = 19, trackRange = -10f..10f) }
    LaunchedEffect(gain, editing) {
        if (!editing) slider.value = gain?.toFloat() ?: 0f
    }
    val feedback = LocalHapticFeedback.current
    Surface(
        shape = UiTokens.compactCardShape,
        color = Surfaces.control,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Clear Bass", Modifier.weight(1f), style = MaterialTheme.typography.titleSmallEmphasized)
                Text(
                    gain?.let(::signed) ?: "–",
                    style = MaterialTheme.typography.titleSmall.withTabularFigures(),
                    color = if (gain != null && gain != 0) cs.primary else cs.onSurfaceVariant,
                )
            }
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
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Clear Bass" },
            )
        }
    }
}

@Composable
private fun PresetGroups(active: Int, flat: Boolean, enabled: Boolean, onPreset: (Int) -> Unit, onFlat: () -> Unit) {
    // One scrolling row of chips under the sound, as Pixel Buds lays out its presets: your own
    // sounds first, then Sony's. The chosen chip fills with a check.
    val codes = listOf(CUSTOM, -1, 0xA1, 0xA2, 0x10, 0x11, 0x12, 0x13, 0x14, 0x17, 0x15, 0x16)
    val scroll = androidx.compose.foundation.rememberScrollState()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("Presets")
        Row(
            Modifier.fillMaxWidth().horizontalScroll(scroll).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            codes.forEach { code ->
                val label = if (code == -1) "Flat" else EqualizerProtocol.presets.getValue(code)
                val selected = if (code == -1) flat else active == code && (code != CUSTOM || !flat)
                FilterChip(
                    selected = selected, enabled = enabled,
                    onClick = { if (!selected) { if (code == -1) onFlat() else onPreset(code) } },
                    label = { Text(label, maxLines = 1) },
                    leadingIcon = if (selected) ({ HushIcon(Glyph.CHECK, Modifier.size(FilterChipDefaults.IconSize), LocalContentColor.current) }) else null,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.heightIn(min = 40.dp).semantics { role = Role.RadioButton; this.selected = selected },
                )
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
        colors = ToggleButtonDefaults.colors(
            containerColor = Surfaces.card, contentColor = cs.onSurfaceVariant,
            checkedContainerColor = cs.primary, checkedContentColor = cs.onPrimary,
        ),
        contentPadding = PaddingValues(horizontal = 4.dp),
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
    val padding = 20.dp
    val plotHeight = (LocalConfiguration.current.screenHeightDp - 670).coerceIn(148, 216).dp
    val backing = Surfaces.page
    Box(Modifier.fillMaxWidth().height(plotHeight)) {
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
                    val gridValues = listOf(-10, 0, 10)
                    val mid = size.height / 2f
                    val perDb = (mid - padding.toPx()) / range
                    val xs = FloatArray(BAND_HZ.size) { bandX(it, width) }
                    val ys = FloatArray(BAND_HZ.size)
                    val line = Path()
                    val area = Path()
                    val stroke = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round)
                    val grid = cs.outlineVariant.copy(alpha = .6f)
                    val tone = cs.primary
                    onDrawBehind {
                        for (db in gridValues) {
                            val y = mid - db * perDb
                            drawLine(if (db == 0) cs.outlineVariant else grid, Offset(0f, y), Offset(width, y), 1.dp.toPx())
                        }
                        if (!hasGains) return@onDrawBehind
                        for (x in xs) {
                            drawLine(grid, Offset(x, mid - range * perDb), Offset(x, mid + range * perDb), 1.dp.toPx())
                        }
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
                            drawCircle(backing, handle + 1.5.dp.toPx(), center)
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
