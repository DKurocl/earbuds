@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package ua.olex.sonyanc

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HushMark(Modifier.size(28.dp)); Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = onSettings, modifier = Modifier.size(48.dp).semantics { contentDescription = "Settings" }) {
                HushIcon(Glyph.SETTINGS, color = cs.onSurfaceVariant)
            }
        }
    }) { insets ->
        Column(
            Modifier.padding(insets).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Header(deviceName, status, codec, batteryRefreshing, onRename)

            // Setup cards already explain a missing permission or device; the banner is for link problems.
            AnimatedVisibility(error != null && granted && hasDevice, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                ErrorBanner(error.orEmpty(), onRetry = onConnect, onBluetooth = onBluetooth)
            }

            when {
                !granted -> SetupCard("Allow Bluetooth access", "Earbuds talks to your WF-1000XM5 directly. Nothing leaves your phone.", "Allow", Glyph.BLUETOOTH, onPermission)
                !hasDevice -> SetupCard("Choose your earbuds", "Pair your WF-1000XM5 in Android Bluetooth settings, then pick it here.", "Choose earbuds", Glyph.EARBUDS, onPick)
                else -> {
                    Batteries(battery ?: SonyProtocol.Battery(), batteryParts, ready, onBattery)

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        BigAction(Glyph.BLUETOOTH, "Bluetooth", onClick = onBluetooth)
                        if (ready) BigAction(Glyph.CLOSE, "Disconnect", onClick = onDisconnect)
                        else BigAction(Glyph.REFRESH, if (status == Headphones.Status.CONNECTING) "Connecting…" else "Connect",
                            enabled = status != Headphones.Status.CONNECTING, onClick = onConnect)
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionTitle("Noise control")
                        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Mode.entries.forEachIndexed { index, mode ->
                                ModeSegment(mode, index, Mode.entries.size, state?.mode == mode, pending?.mode == mode, Modifier.weight(1f)) {
                                    choose((state ?: NcState(mode)).copy(mode = mode))
                                }
                            }
                        }
                        AnimatedVisibility(
                            state?.mode == Mode.AMBIENT,
                            enter = expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec(), expandFrom = Alignment.Top) + fadeIn(),
                            exit = shrinkVertically(MaterialTheme.motionScheme.defaultSpatialSpec(), shrinkTowards = Alignment.Top) + fadeOut(),
                        ) {
                            AmbientPanel((state ?: NcState(Mode.AMBIENT)).copy(mode = Mode.AMBIENT), ::choose)
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
private fun Header(name: String, status: Headphones.Status, codec: CodecProtocol.State?, busy: Boolean, onRename: () -> Unit) {
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
                else -> statusText(status)
            }
            Text(line, style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (status == Headphones.Status.CONNECTING || busy) LoadingIndicator(Modifier.size(20.dp))
        }
    }
}

@Composable
private fun Batteries(b: SonyProtocol.Battery, live: Set<Int>, ready: Boolean, onRefresh: () -> Unit) {
    // One decode of the product photo shared by both earbud rings.
    val buds = ImageBitmap.imageResource(R.drawable.wf1000xm5_silver)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).clickable(enabled = ready, onClickLabel = "Refresh battery", onClick = onRefresh)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        BatteryRing("Left", b.left, b.leftCharging, 0 in live) { BudArt(buds, mirrored = false) }
        BatteryRing("Case", b.case, b.caseCharging, 2 in live) {
            Image(painterResource(R.drawable.wf1000xm5_case), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        BatteryRing("Right", b.right, b.rightCharging, 1 in live) { BudArt(buds, mirrored = true) }
    }
}

/** Ring with a status chip in the top gap, like Pixel's device details. */
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
    val progress by animateFloatAsState((value ?: 0) / 100f, tween(700, easing = FastOutSlowInEasing), label = "battery $label")
    Column(
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = "$label battery"
            stateDescription = (value?.let { "$it percent" } ?: "Unavailable") +
                if (charging) ", charging" else if (!live && value != null) ", last known" else ""
        },
    ) {
        Box(Modifier.size(100.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize().padding(6.dp)) {
                val stroke = 6.dp.toPx()
                val gap = 44f
                val start = -90f + gap / 2
                val sweep = 360f - gap
                val inset = Offset(stroke / 2, stroke / 2)
                val arc = Size(size.width - stroke, size.height - stroke)
                drawArc(track, start, sweep, false, topLeft = inset, size = arc, style = Stroke(stroke, cap = StrokeCap.Round))
                if (progress > 0f) drawArc(color, start, sweep * progress, false, topLeft = inset, size = arc, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Box(Modifier.size(54.dp), contentAlignment = Alignment.Center) { art() }
            Surface(shape = CircleShape, color = if (value == null) track else color, modifier = Modifier.align(Alignment.TopCenter).size(26.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        when {
                            value == null -> Icons.AutoMirrored.Rounded.BatteryUnknown
                            charging -> Icons.Rounded.BatteryChargingFull
                            value <= 20 -> Icons.Rounded.BatteryAlert
                            else -> Icons.Rounded.BatteryFull
                        }, contentDescription = null, tint = chipContent, modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value?.let { "$it%" } ?: "—", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium,
                color = if (value != null && value <= 20 && !charging) cs.error else cs.onSurface)
            Text(label, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}

/** One bud cropped out of the product photo; the right one is the same shot mirrored. */
@Composable
private fun BudArt(image: ImageBitmap, mirrored: Boolean) {
    Image(
        image, null, contentScale = ContentScale.Crop, alignment = Alignment.CenterEnd,
        modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = if (mirrored) -1f else 1f }.clip(RoundedCornerShape(4.dp)),
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

/** Connected button group segment: outer corners round, inner tight; the selected one morphs to a pill. */
@Composable
private fun ModeSegment(mode: Mode, index: Int, count: Int, selected: Boolean, applying: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val spec = spring<androidx.compose.ui.unit.Dp>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)
    val outer = 32.dp
    val inner = 10.dp
    fun target(isOuter: Boolean) = when { pressed -> 14.dp; selected -> outer; isOuter -> outer; else -> inner }
    val start by animateDpAsState(target(index == 0), spec, label = "start")
    val end by animateDpAsState(target(index == count - 1), spec, label = "end")
    val bg by animateColorAsState(if (selected) cs.primary else cs.surfaceContainerHighest, tween(200), label = "bg")
    val fg by animateColorAsState(if (selected) cs.onPrimary else cs.onSurfaceVariant, tween(200), label = "fg")
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.fillMaxWidth().height(64.dp)
                .clip(RoundedCornerShape(topStart = start, bottomStart = start, topEnd = end, bottomEnd = end))
                .background(bg)
                .selectable(selected = selected, interactionSource = interaction, indication = ripple(), role = Role.RadioButton, onClick = onClick)
                .semantics { contentDescription = mode.label },
            contentAlignment = Alignment.Center,
        ) {
            if (applying) LoadingIndicator(Modifier.size(28.dp), color = fg)
            else HushIcon(when (mode) { Mode.ANC -> Glyph.QUIET; Mode.AMBIENT -> Glyph.AMBIENT; Mode.OFF -> Glyph.OFF }, Modifier.size(26.dp), fg)
        }
        Text(mode.short, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 2,
            color = if (selected) cs.onSurface else cs.onSurfaceVariant)
    }
}

@Composable
private fun AmbientPanel(state: NcState, onChange: (NcState) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var level by remember { mutableFloatStateOf(state.level.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(state.level, dragging) { if (!dragging) level = state.level.toFloat() }
    Surface(shape = RoundedCornerShape(24.dp), color = Surfaces.card, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Ambient level", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("${level.roundToInt()}", style = MaterialTheme.typography.titleMedium, color = cs.primary)
            }
            Slider(
                value = level, valueRange = 0f..20f,
                onValueChange = { dragging = true; level = it.roundToInt().toFloat() },
                onValueChangeFinished = { onChange(state.copy(level = level.roundToInt())); dragging = false },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Ambient sound level"; stateDescription = "${level.roundToInt()} of 20" },
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("Low" to 5, "Medium" to 12, "High" to 20).forEachIndexed { index, (label, value) ->
                    SegmentedButton(selected = state.level == value, onClick = { onChange(state.copy(level = value)) },
                        shape = SegmentedButtonDefaults.itemShape(index, 3), icon = {}) { Text(label) }
                }
            }
            HorizontalDivider(color = cs.outlineVariant.copy(alpha = .5f), modifier = Modifier.padding(vertical = 4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Voice focus", style = MaterialTheme.typography.titleMedium)
                    Text("Lets voices through, lowers other sounds", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                }
                Switch(
                    checked = state.voice, onCheckedChange = { onChange(state.copy(voice = it)) },
                    thumbContent = if (state.voice) ({ HushIcon(Glyph.CHECK, Modifier.size(SwitchDefaults.IconSize), cs.primary) }) else null,
                    modifier = Modifier.semantics { contentDescription = "Voice focus" },
                )
            }
        }
    }
}

@Composable
private fun SetupCard(title: String, body: String, action: String, glyph: Glyph, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(28.dp), color = Surfaces.card, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(shape = RoundedCornerShape(20.dp), color = cs.primaryContainer, modifier = Modifier.size(64.dp)) {
                Box(contentAlignment = Alignment.Center) { HushIcon(glyph, Modifier.size(32.dp), cs.onPrimaryContainer) }
            }
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(body, style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
            Button(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(action) }
        }
    }
}
