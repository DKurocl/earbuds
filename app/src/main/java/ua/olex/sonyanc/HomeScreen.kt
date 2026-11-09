@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)

package ua.olex.sonyanc

import android.text.format.DateUtils

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.BatteryUnknown
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ua.olex.sonyanc.SonyProtocol.Mode
import ua.olex.sonyanc.SonyProtocol.NcState
import kotlin.math.roundToInt

/** Pixel-Buds-style device page: header, battery rings, quick actions, noise control, more controls. */
@Composable
fun HushScreen(
    status: Headphones.Status, nc: NcState?, pending: NcState?, fresh: Boolean, error: String?, battery: SonyProtocol.Battery?,
    granted: Boolean, hasDevice: Boolean, onMode: (NcState) -> Unit, onSettings: () -> Unit, onConnect: () -> Unit,
    onPermission: () -> Unit, onBluetooth: () -> Unit, onPick: () -> Unit, onEqualizer: () -> Unit = {}, onEarbuds: () -> Unit = {},
    onConnection: () -> Unit = {}, haptics: Boolean = true,
    batteryRefreshing: Boolean = false, batteryFresh: Boolean = true, onBattery: () -> Unit = {},
    codec: CodecProtocol.State? = null, codecRefreshing: Boolean = false,
    deviceName: String = "WF-1000XM5", onRename: () -> Unit = {}, onDisconnect: () -> Unit = {}, onAppSettings: () -> Unit = onSettings,
    batteryParts: Set<Int> = if (batteryFresh) setOf(0, 1, 2) else emptySet(), equalizerPreset: String? = null,
    batterySeenAt: Long? = null, scenes: List<Scene> = emptyList(), equalizer: EqualizerProtocol.State? = null,
    onScene: (Scene) -> Unit = {}, onSaveScene: () -> Unit = {}, onEditScene: (Scene) -> Unit = {},
    onFind: () -> Unit = {}, lastSeen: Long? = null,
) {
    val cs = MaterialTheme.colorScheme
    val state = pending ?: nc
    val ready = status == Headphones.Status.CONNECTED
    val feedback = LocalHapticFeedback.current
    fun choose(value: NcState) {
        if (haptics) feedback.performHapticFeedback(HapticFeedbackType.SegmentTick)
        onMode(value)
    }
    Scaffold(containerColor = Surfaces.page, topBar = {
        // No logo or app name: the device header below says what this is. Settings stay in reach.
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(end = 8.dp, top = 8.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            IconButton(onClick = onSettings, modifier = Modifier.size(48.dp).semantics { contentDescription = "Settings" }) {
                HushIcon(Glyph.SETTINGS, color = cs.onSurfaceVariant)
            }
        }
    }, bottomBar = {
        // Noise control lives in a dock at the bottom: on a 6.8" phone that is where the thumb is.
        if (granted && hasDevice) ModeDock(state, pending, ::choose)
    }) { insets ->
        Column(
            Modifier.padding(insets).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Header(deviceName, status, codec, batteryRefreshing, onRename, lastSeen)

            // Setup cards already explain a missing permission or device; the banner is for link problems.
            AnimatedVisibility(error != null && granted && hasDevice, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                ErrorBanner(error.orEmpty(), onRetry = onConnect, onBluetooth = onBluetooth)
            }

            when {
                !granted -> SetupCard("Allow Bluetooth access", "Earbuds talks to your WF-1000XM5 directly. Nothing leaves your phone.", "Allow", Glyph.BLUETOOTH, onPermission)
                !hasDevice -> SetupCard("Choose your earbuds", "Pair your WF-1000XM5 in Android Bluetooth settings, then pick it here.", "Choose earbuds", Glyph.EARBUDS, onPick)
                else -> {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Batteries(battery ?: SonyProtocol.Battery(), batteryParts, ready, onBattery)
                        BatteryNote(battery, state?.mode, live = batteryParts.isNotEmpty(), seenAt = batterySeenAt)
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        // The control link opens and closes by itself, so there is no Disconnect here:
                        // it would not disconnect the audio and only made the next tap slower.
                        BigAction(Glyph.BLUETOOTH, "Bluetooth", onClick = onBluetooth)
                        BigAction(Glyph.FIND, "Find", onClick = onFind)
                        if (!ready) BigAction(Glyph.REFRESH, if (status == Headphones.Status.CONNECTING) "Connecting…" else "Connect",
                            enabled = status != Headphones.Status.CONNECTING, onClick = onConnect)
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionTitle("Scenes")
                        // Scrolls to the screen edges (past the page margin), like Google's own carousels.
                        Row(
                            Modifier.fillMaxWidth().layout { measurable, constraints ->
                                val margin = 16.dp.roundToPx()
                                val wide = constraints.copy(minWidth = constraints.minWidth + 2 * margin, maxWidth = constraints.maxWidth + 2 * margin)
                                val placeable = measurable.measure(wide)
                                layout(placeable.width - 2 * margin, placeable.height) { placeable.place(-margin, 0) }
                            }.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            scenes.forEach { scene ->
                                SceneCard(scene.name, scene.summary, sceneGlyph(scene), scene.matches(state, equalizer),
                                    onClick = { if (haptics) feedback.performHapticFeedback(HapticFeedbackType.Confirm); onScene(scene) },
                                    onLongClick = { onEditScene(scene) })
                            }
                            if (scenes.size < Scenes.MAX) SceneCard("Save current", "Make a scene", Glyph.ADD, false, onClick = onSaveScene, onLongClick = null)
                        }
                    }

                    ListGroup("More controls") {
                        item { NavRow(it, Glyph.EQ, "Equalizer", equalizerPreset ?: "Presets, custom bands and Clear Bass", onClick = onEqualizer) }
                        item { NavRow(it, Glyph.TUNE, "Earbud settings", "Touch controls, Speak-to-Chat, voice prompts", onClick = onEarbuds) }
                        item {
                            val codecLine = if (ready) codec?.label ?: if (codecRefreshing) "Reading codec…" else null else null
                            NavRow(it, Glyph.INFO, "Device info", codecLine?.let { c -> "Codec · $c" } ?: "Codec, firmware, power", onClick = onConnection)
                        }
                        item { NavRow(it, Glyph.SETTINGS, "App settings", "Appearance, shortcuts, device", onClick = onAppSettings) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Header(name: String, status: Headphones.Status, codec: CodecProtocol.State?, busy: Boolean, onRename: () -> Unit, lastSeen: Long? = null) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(40.dp))
            Text(name, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
            IconButton(onClick = onRename, modifier = Modifier.size(40.dp).semantics { contentDescription = "Rename device" }) {
                HushIcon(Glyph.EDIT, Modifier.size(18.dp), cs.onSurfaceVariant)
            }
        }
        Row(Modifier.heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val line = when (status) {
                Headphones.Status.CONNECTED -> "Connected" + (codec?.takeIf { it.isKnown }?.let { " · ${it.label}" } ?: "")
                Headphones.Status.CONNECTING -> statusText(status)
                // Android reported the earbuds gone (case, range or Bluetooth off).
                else -> lastSeen?.let { "Disconnected · " + DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS) }
                    ?: statusText(status)
            }
            Text(line, style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (status == Headphones.Status.CONNECTING || busy) LoadingIndicator(Modifier.size(20.dp))
        }
    }
}

@Composable
private fun Batteries(b: SonyProtocol.Battery, live: Set<Int>, ready: Boolean, onRefresh: () -> Unit) {
    // One decode of the bud cutout shared by both earbud rings; the right one is mirrored.
    val bud = ImageBitmap.imageResource(R.drawable.wf1000xm5_bud)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).clickable(enabled = ready, onClickLabel = "Refresh battery", onClick = onRefresh)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        BatteryRing("Left", b.left, b.leftCharging, 0 in live) { BudArt(bud, mirrored = false) }
        BatteryRing("Case", b.case, b.caseCharging, 2 in live) {
            Image(painterResource(R.drawable.wf1000xm5_case), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        BatteryRing("Right", b.right, b.rightCharging, 1 in live) { BudArt(bud, mirrored = true) }
    }
}

/**
 * Pixel "Device details" battery: the product on a filled disc, a thin ring around it with a
 * gap, and a status chip sitting on the ring at the top. The ring starts and ends at the chip's
 * outline, so the chip reads as cut out of the ring rather than stacked on it.
 */
@Composable
private fun BatteryRing(label: String, value: Int?, charging: Boolean, live: Boolean, art: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val dark = isSystemInDarkTheme()
    val good = if (dark) Color(0xFF6DD58C) else Color(0xFF1E8E3E)
    val onGood = if (dark) Color(0xFF0A3818) else Color.White
    val (ringColor, chipContent) = when {
        value == null -> cs.outlineVariant to cs.onSurfaceVariant
        value <= 20 && !charging -> cs.error to cs.onError
        else -> good to onGood
    }
    val color = if (live || value == null) ringColor else ringColor.copy(alpha = .55f)
    val track = cs.surfaceContainerHighest
    val disc = cs.surfaceContainerHigh
    val page = Surfaces.page
    val progress by animateFloatAsState((value ?: 0) / 100f, MaterialTheme.motionScheme.slowEffectsSpec(), label = "battery $label")
    Column(
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = "$label battery"
            stateDescription = (value?.let { "$it percent" } ?: "Unavailable") +
                if (charging) ", charging" else if (!live && value != null) ", last known" else ""
        },
    ) {
        Box(Modifier.size(RING_BOX), contentAlignment = Alignment.TopCenter) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = RING_STROKE.toPx()
                val radius = RING_RADIUS.toPx()
                val center = Offset(size.width / 2, CHIP.toPx() / 2 + radius)
                // Filled disc behind the art, separated from the ring by a hairline of page color.
                drawCircle(disc, radius - stroke / 2 - RING_GAP.toPx(), center)
                // The ring stops at the chip's outline, plus room for the round caps.
                val chipOuter = CHIP.toPx() / 2 + CHIP_OUTLINE.toPx()
                val half = Math.toDegrees(kotlin.math.asin((chipOuter / radius).coerceAtMost(1f).toDouble())).toFloat() +
                    Math.toDegrees((stroke / 2 / radius).toDouble()).toFloat()
                val start = -90f + half
                val sweep = 360f - 2 * half
                val arcTopLeft = Offset(center.x - radius, center.y - radius)
                val arcSize = Size(radius * 2, radius * 2)
                drawArc(track, start, sweep, false, arcTopLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                if (progress > 0f) drawArc(color, start, sweep * progress, false, arcTopLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                // Chip outline in page color, so it never touches the ring ends.
                drawCircle(page, chipOuter, Offset(center.x, CHIP.toPx() / 2))
                drawCircle(if (value == null) track else color, CHIP.toPx() / 2, Offset(center.x, CHIP.toPx() / 2))
            }
            Box(
                Modifier.padding(top = CHIP / 2 + RING_RADIUS - ART / 2).size(ART),
                contentAlignment = Alignment.Center,
            ) { art() }
            Icon(
                when {
                    value == null -> Icons.AutoMirrored.Rounded.BatteryUnknown
                    charging -> Icons.Rounded.BatteryChargingFull
                    value <= 20 -> Icons.Rounded.BatteryAlert
                    else -> Icons.Rounded.BatteryFull
                }, contentDescription = null, tint = chipContent,
                modifier = Modifier.padding(top = (CHIP - 16.dp) / 2).size(16.dp),
            )
        }
        Text(
            "$label: " + (value?.let { "$it%" } ?: "—"), style = MaterialTheme.typography.titleMedium, maxLines = 1,
            color = if (value != null && value <= 20 && !charging) cs.error else cs.onSurface,
        )
    }
}

// Ring geometry, measured from Pixel's Bluetooth device details on a Pixel 10 Pro XL.
private val CHIP = 26.dp
private val CHIP_OUTLINE = 3.dp
private val RING_RADIUS = 38.dp
private val RING_STROKE = 5.dp
private val RING_GAP = 3.dp
private val RING_BOX = RING_RADIUS * 2 + RING_STROKE + CHIP / 2 + 4.dp
// A square at 1/√2 of the disc fits inside it entirely, so the product is never clipped.
private val ART = (RING_RADIUS - RING_STROKE / 2 - RING_GAP) * 2 * 0.70f

/** The bud cutout, centered; the right bud is the same shot mirrored. */
@Composable
private fun BudArt(image: ImageBitmap, mirrored: Boolean) {
    Image(
        image, null, contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = if (mirrored) -1f else 1f },
    )
}

@Composable
private fun BigAction(glyph: Glyph, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            onClick = onClick, enabled = enabled, shape = RoundedCornerShape(50), color = cs.secondaryContainer, contentColor = cs.onSecondaryContainer,
            modifier = Modifier.size(width = 76.dp, height = 56.dp).semantics { contentDescription = label },
        ) {
            Box(contentAlignment = Alignment.Center) {
                HushIcon(glyph, Modifier.size(24.dp), if (enabled) cs.onSecondaryContainer else cs.onSecondaryContainer.copy(alpha = .5f))
            }
        }
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * The noise-control dock: a floating card above the navigation bar. Ambient options open
 * above the three modes, so the mode buttons never move under the thumb.
 */
@Composable
private fun ModeDock(state: NcState?, pending: NcState?, onChoose: (NcState) -> Unit) {
    Surface(
        shape = RoundedCornerShape(32.dp), color = Surfaces.card, shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
    ) {
        Column(Modifier.padding(8.dp)) {
            AnimatedVisibility(
                state?.mode == Mode.AMBIENT,
                enter = expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec(), expandFrom = Alignment.Bottom) + fadeIn(),
                exit = shrinkVertically(MaterialTheme.motionScheme.defaultSpatialSpec(), shrinkTowards = Alignment.Bottom) + fadeOut(),
            ) {
                AmbientControls((state ?: NcState(Mode.AMBIENT)).copy(mode = Mode.AMBIENT), onChoose)
            }
            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Mode.entries.forEachIndexed { index, mode ->
                    ModeSegment(mode, index, Mode.entries.size, state?.mode == mode, pending?.mode == mode, Modifier.weight(1f)) {
                        onChoose((state ?: NcState(mode)).copy(mode = mode))
                    }
                }
            }
        }
    }
}

/**
 * Connected button group segment, M3 Expressive: soft corners everywhere (outer full,
 * inner 16 dp), the selected one morphs to a full pill, a press squeezes to 18 dp.
 */
@Composable
private fun ModeSegment(mode: Mode, index: Int, count: Int, selected: Boolean, applying: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val spec = MaterialTheme.motionScheme.fastSpatialSpec<androidx.compose.ui.unit.Dp>()
    val full = 28.dp
    val inner = 16.dp
    fun target(isOuter: Boolean) = when { pressed -> 18.dp; selected || isOuter -> full; else -> inner }
    val start by animateDpAsState(target(index == 0), spec, label = "start")
    val end by animateDpAsState(target(index == count - 1), spec, label = "end")
    val bg by animateColorAsState(if (selected) cs.primary else cs.surfaceContainerHighest, MaterialTheme.motionScheme.fastEffectsSpec(), label = "bg")
    val fg by animateColorAsState(if (selected) cs.onPrimary else cs.onSurface, MaterialTheme.motionScheme.fastEffectsSpec(), label = "fg")
    Row(
        modifier.height(56.dp)
            .clip(RoundedCornerShape(topStart = start, bottomStart = start, topEnd = end, bottomEnd = end))
            .background(bg)
            .selectable(selected = selected, interactionSource = interaction, indication = ripple(), role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = mode.label }
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        if (applying) LoadingIndicator(Modifier.size(24.dp), color = fg)
        else HushIcon(when (mode) { Mode.ANC -> Glyph.QUIET; Mode.AMBIENT -> Glyph.AMBIENT; Mode.OFF -> Glyph.OFF }, Modifier.size(22.dp), fg)
        Spacer(Modifier.width(8.dp))
        Text(mode.short, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1, overflow = TextOverflow.Clip)
    }
}

/** Level (with a haptic tick per step), soft preset pills and voice focus, compact for the dock. */
@Composable
private fun AmbientControls(state: NcState, onChange: (NcState) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val feedback = LocalHapticFeedback.current
    var level by remember { mutableFloatStateOf(state.level.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(state.level, dragging) { if (!dragging) level = state.level.toFloat() }
    val presets = listOf("Low" to 5, "Medium" to 12, "High" to 20)
    Column(Modifier.padding(start = 12.dp, end = 8.dp, top = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Ambient level", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text("${level.roundToInt()}", style = MaterialTheme.typography.titleMedium, color = cs.primary)
        }
        Slider(
            value = level, valueRange = 0f..20f,
            onValueChange = {
                dragging = true
                val step = it.roundToInt()
                if (step != level.roundToInt()) feedback.performHapticFeedback(
                    if (presets.any { p -> p.second == step }) HapticFeedbackType.SegmentTick else HapticFeedbackType.SegmentFrequentTick,
                )
                level = step.toFloat()
            },
            onValueChangeFinished = { onChange(state.copy(level = level.roundToInt())); dragging = false },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Ambient sound level"; stateDescription = "${level.roundToInt()} of 20" },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            presets.forEach { (label, value) ->
                SoftChip(label, selected = state.level == value, modifier = Modifier.weight(1f)) { onChange(state.copy(level = value)) }
            }
            SoftChip("Voice", selected = state.voice, glyph = if (state.voice) Glyph.CHECK else null, modifier = Modifier.weight(1.15f),
                description = "Voice focus") { onChange(state.copy(voice = !state.voice)) }
        }
    }
}

/** A fully rounded toggle pill; selected fills with the secondary container. */
@Composable
private fun SoftChip(label: String, selected: Boolean, modifier: Modifier = Modifier, glyph: Glyph? = null, description: String = label, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (selected) cs.secondaryContainer else cs.surfaceContainerHighest, MaterialTheme.motionScheme.fastEffectsSpec(), label = "chip")
    Surface(
        onClick = onClick, shape = CircleShape, color = bg,
        contentColor = if (selected) cs.onSecondaryContainer else cs.onSurfaceVariant,
        modifier = modifier.height(40.dp).semantics { contentDescription = description; stateDescription = if (selected) "On" else "Off" },
    ) {
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp)) {
            if (glyph != null) { HushIcon(glyph, Modifier.size(16.dp), LocalContentColor.current); Spacer(Modifier.width(4.dp)) }
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

@Composable
private fun SetupCard(title: String, body: String, action: String, glyph: Glyph, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(28.dp), color = Surfaces.card, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(shape = CircleShape, color = cs.primaryContainer, modifier = Modifier.size(72.dp)) {
                Box(contentAlignment = Alignment.Center) { HushIcon(glyph, Modifier.size(32.dp), cs.onPrimaryContainer) }
            }
            Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(body, style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
            Button(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(action) }
        }
    }
}

/** Estimated listening time, and how old the reading is when it is not live. */
@Composable
private fun BatteryNote(battery: SonyProtocol.Battery?, mode: Mode?, live: Boolean, seenAt: Long?) {
    val cs = MaterialTheme.colorScheme
    val estimate = battery?.let { BatteryLife.summary(it, mode) }
    val age = if (!live && seenAt != null) "Last seen " + DateUtils.getRelativeTimeSpanString(seenAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS) else null
    if (estimate == null && age == null) return
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        estimate?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface, textAlign = TextAlign.Center) }
        age?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
    }
}

private fun sceneGlyph(scene: Scene) = when (scene.nc.mode) { Mode.ANC -> Glyph.QUIET; Mode.AMBIENT -> Glyph.AMBIENT; Mode.OFF -> Glyph.OFF }

/** A scene: tap applies it, long-press edits it. Highlighted while the earbuds match it. */
@Composable
private fun SceneCard(title: String, subtitle: String, glyph: Glyph, selected: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    val bg by animateColorAsState(if (selected) cs.primaryContainer else Surfaces.card, tween(200), label = "scene bg")
    val fg = if (selected) cs.onPrimaryContainer else cs.onSurface
    Surface(
        shape = shape, color = bg,
        modifier = Modifier.clip(shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = if (onLongClick != null) "Edit scene" else null, role = Role.Button)
            .semantics { this.selected = selected },
    ) {
        Column(Modifier.widthIn(min = 128.dp, max = 200.dp).padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HushIcon(glyph, Modifier.size(20.dp), if (selected) cs.onPrimaryContainer else cs.primary)
            Text(title, style = MaterialTheme.typography.titleSmall, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = if (selected) cs.onPrimaryContainer else cs.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Create or edit a scene. A new scene captures the current sound; an edited one can be re-captured. */
@Composable
fun SceneDialog(
    editing: Scene?, current: String, eqName: String?, onDismiss: () -> Unit,
    onSave: (name: String, recapture: Boolean, includeEq: Boolean) -> Unit, onDelete: () -> Unit = {},
) {
    var name by remember { mutableStateOf(editing?.name ?: "") }
    var recapture by remember { mutableStateOf(editing == null) }
    var includeEq by remember { mutableStateOf(editing?.eqPreset != null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editing == null) "New scene" else "Edit scene") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = Scenes.cleanName(it) }, singleLine = true, label = { Text("Name") })
                if (editing != null) {
                    Text(editing.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    CheckRow("Replace with current sound", "Now: $current", recapture) { recapture = it }
                } else Text("Saves: $current", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (eqName != null && recapture) CheckRow("Include equalizer", eqName, includeEq) { includeEq = it }
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim(), recapture, includeEq) }) { Text("Save") } },
        dismissButton = {
            Row {
                if (editing != null) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun CheckRow(title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).toggleable(checked, role = Role.Checkbox, onValueChange = onChange).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
