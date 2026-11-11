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
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.BatteryUnknown
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.Path
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
import androidx.compose.ui.unit.sp
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
    deviceName: String = "WF-1000XM5", onAppSettings: () -> Unit = onSettings,
    batteryParts: Set<Int> = if (batteryFresh) setOf(0, 1, 2) else emptySet(), equalizerPreset: String? = null,
    batterySeenAt: Long? = null,
    onFind: () -> Unit = {}, lastSeen: Long? = null, onBatteryDetails: () -> Unit = {},
    speakToChat: Boolean? = null, onSpeakToChat: (Boolean) -> Unit = {},
    ambientMinutes: Int = 5, ambientUntil: Long? = null, onAmbientFor: () -> Unit = {}, onStayInAmbient: () -> Unit = {},
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
        if (granted && hasDevice) ModeDock(state, pending, ::choose, DockExtras(speakToChat, onSpeakToChat, ambientMinutes, ambientUntil, onAmbientFor, onStayInAmbient))
    }) { insets ->
        // Pull down to read battery and codec again (also connects if needed).
        val pull = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = batteryRefreshing || codecRefreshing, onRefresh = onBattery, state = pull,
            modifier = Modifier.padding(insets).fillMaxSize(),
            indicator = {
                PullToRefreshDefaults.LoadingIndicator(state = pull, isRefreshing = batteryRefreshing || codecRefreshing,
                    modifier = Modifier.align(Alignment.TopCenter))
            },
        ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Header(deviceName, status, codec, batteryRefreshing, lastSeen)

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
                        ListeningTime(battery, state?.mode, live = batteryParts.isNotEmpty(), seenAt = batterySeenAt, onClick = onBatteryDetails)
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        // Only what is used day to day. The control link opens and closes by itself (no
                        // Disconnect); Android's Bluetooth settings are one level down in Device info.
                        BigAction(Glyph.FIND, "Find", onClick = onFind)
                        if (!ready) BigAction(Glyph.REFRESH, if (status == Headphones.Status.CONNECTING) "Connecting…" else "Connect",
                            enabled = status != Headphones.Status.CONNECTING, onClick = onConnect)
                    }

                    ListGroup("More controls") {
                        // Battery opens from the listening-time tiles above; no second entry here.
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
}

@Composable
private fun Header(name: String, status: Headphones.Status, codec: CodecProtocol.State?, busy: Boolean, lastSeen: Long? = null) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Renaming is rare: it lives in App settings, not next to the name.
        Text(name, style = MaterialTheme.typography.headlineMediumEmphasized, textAlign = TextAlign.Center, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
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
        // Equal thirds: each label has a bounded width to shrink into at the largest font sizes.
        Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) { BatteryRing("Left", b.left, b.leftCharging, 0 in live) { BudArt(bud, mirrored = false) } }
        Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
            BatteryRing("Case", b.case, b.caseCharging, 2 in live) {
                Image(painterResource(R.drawable.wf1000xm5_case), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) { BatteryRing("Right", b.right, b.rightCharging, 1 in live) { BudArt(bud, mirrored = true) } }
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
    // Runs only while this ring is charging; otherwise no animation frame is ever requested.
    val phase = if (charging && value != null) {
        val waves = rememberInfiniteTransition(label = "charging $label")
        waves.animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "phase").value
    } else 0f
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
                if (progress > 0f && charging && value != null) {
                    // Charging: the arc breathes as a gentle wave travelling along the ring.
                    val amp = RING_WAVE.toPx()
                    val wave = Path()
                    val steps = 120
                    for (i in 0..steps) {
                        val deg = start + sweep * progress * i / steps
                        val rad = Math.toRadians(deg.toDouble())
                        val r = radius + amp * kotlin.math.sin(rad * 14 + phase).toFloat()
                        val p = Offset(center.x + r * kotlin.math.cos(rad).toFloat(), center.y + r * kotlin.math.sin(rad).toFloat())
                        if (i == 0) wave.moveTo(p.x, p.y) else wave.lineTo(p.x, p.y)
                    }
                    drawPath(wave, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
                } else if (progress > 0f) drawArc(color, start, sweep * progress, false, arcTopLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
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
            autoSize = fitText(MaterialTheme.typography.titleMedium),
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
private val RING_WAVE = 1.4.dp
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

/** A home action: Material's medium tonal icon button (wide); it squares off while pressed. */
@Composable
private fun BigAction(glyph: Glyph, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalIconButton(
            onClick = onClick, enabled = enabled,
            shapes = IconButtonDefaults.shapes(),
            modifier = Modifier.size(IconButtonDefaults.mediumContainerSize(IconButtonDefaults.IconButtonWidthOption.Wide))
                .semantics { contentDescription = label },
        ) { HushIcon(glyph, Modifier.size(IconButtonDefaults.mediumIconSize), LocalContentColor.current) }
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * The noise-control dock: a floating card above the navigation bar. Ambient options open
 * above the three modes, so the mode buttons never move under the thumb.
 */
/** What the dock offers besides the modes: Speak-to-Chat and "Ambient for a moment". */
class DockExtras(
    val speakToChat: Boolean?, val onSpeakToChat: (Boolean) -> Unit,
    val minutes: Int, val until: Long?, val onAmbientFor: () -> Unit, val onStay: () -> Unit,
)

@Composable
private fun ModeDock(state: NcState?, pending: NcState?, onChoose: (NcState) -> Unit, extras: DockExtras) {
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
                AmbientControls((state ?: NcState(Mode.AMBIENT)).copy(mode = Mode.AMBIENT), onChoose, extras)
            }
            // With noise cancelling on: talk without taking the earbuds out.
            AnimatedVisibility(
                state?.mode == Mode.ANC,
                enter = expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec(), expandFrom = Alignment.Bottom) + fadeIn(),
                exit = shrinkVertically(MaterialTheme.motionScheme.defaultSpatialSpec(), shrinkTowards = Alignment.Bottom) + fadeOut(),
            ) {
                Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    extras.speakToChat?.let { on ->
                        SoftChip("Speak-to-Chat", selected = on, glyph = if (on) Glyph.CHECK else Glyph.CHAT, modifier = Modifier.weight(1f), kind = ChipKind.SWITCH) {
                            extras.onSpeakToChat(!on)
                        }
                    }
                    SoftChip("Ambient · ${extras.minutes} min", selected = false, glyph = Glyph.TIMER, modifier = Modifier.weight(1f), kind = ChipKind.BUTTON,
                        description = "Ambient for ${extras.minutes} minutes, then back to noise cancelling") { extras.onAmbientFor() }
                }
            }
            // Material's connected button group: the pressed button widens and its neighbours give
            // way (animateWidth), the chosen one morphs into a pill.
            val interactions = remember { Mode.entries.map { MutableInteractionSource() } }
            ButtonGroup(
                overflowIndicator = {}, // three buttons always fit
                modifier = Modifier.fillMaxWidth().selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            ) {
                Mode.entries.forEachIndexed { index, mode ->
                    customItem(
                        buttonGroupContent = {
                            ModeButton(
                                mode, index, Mode.entries.size, state?.mode == mode, pending?.mode == mode, interactions[index],
                                Modifier.weight(1f).animateWidth(interactions[index]),
                            ) { onChoose((state ?: NcState(mode)).copy(mode = mode)) }
                        },
                        menuContent = {},
                    )
                }
            }
        }
    }
}

/** One mode in the connected group: Material's toggle button with the connected shapes. */
@Composable
private fun ModeButton(
    mode: Mode, index: Int, count: Int, selected: Boolean, applying: Boolean,
    interaction: MutableInteractionSource, modifier: Modifier, onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    ToggleButton(
        checked = selected,
        onCheckedChange = { onClick() },
        shapes = when (index) {
            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
            count - 1 -> ButtonGroupDefaults.connectedTrailingButtonShapes()
            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
        },
        colors = ToggleButtonDefaults.colors(containerColor = cs.surfaceContainerHighest, contentColor = cs.onSurface),
        contentPadding = PaddingValues(horizontal = 12.dp),
        interactionSource = interaction,
        modifier = modifier.heightIn(min = ButtonDefaults.MediumContainerHeight)
            // One of three, like radio buttons: announced and tested as selected, not as on/off.
            .semantics { role = Role.RadioButton; this.selected = selected; contentDescription = mode.label },
    ) {
        if (applying) LoadingIndicator(Modifier.size(24.dp), color = LocalContentColor.current)
        else HushIcon(when (mode) { Mode.ANC -> Glyph.QUIET; Mode.AMBIENT -> Glyph.AMBIENT; Mode.OFF -> Glyph.OFF }, Modifier.size(24.dp), LocalContentColor.current)
        Spacer(Modifier.width(ToggleButtonDefaults.IconSpacing))
        Text(mode.short, style = MaterialTheme.typography.titleMedium, maxLines = 1, autoSize = fitText(MaterialTheme.typography.titleMedium))
    }
}

/** Level (with a haptic tick per step), soft preset pills and voice focus, compact for the dock. */
@Composable
private fun AmbientControls(state: NcState, onChange: (NcState) -> Unit, extras: DockExtras) {
    val cs = MaterialTheme.colorScheme
    val feedback = LocalHapticFeedback.current
    var level by remember { mutableFloatStateOf(state.level.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(state.level, dragging) { if (!dragging) level = state.level.toFloat() }
    val presets = listOf("Low" to 5, "Medium" to 12, "High" to 20)
    Column(Modifier.padding(start = 12.dp, end = 8.dp, top = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // A running "Ambient for a moment": the way back, and one tap to stay.
        extras.until?.let { until ->
            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(until) { while (now < until) { kotlinx.coroutines.delay(1000); now = System.currentTimeMillis() } }
            Surface(
                onClick = extras.onStay, shape = CircleShape, color = cs.tertiaryContainer, contentColor = cs.onTertiaryContainer,
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp).semantics { contentDescription = "Back to noise cancelling soon. Tap to stay in Ambient" },
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    HushIcon(Glyph.TIMER, Modifier.size(18.dp), cs.onTertiaryContainer)
                    Spacer(Modifier.width(8.dp))
                    Text("Back to ANC in " + AmbientTimer.countdown(until - now), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    Text("Stay", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Ambient level", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text("${level.roundToInt()}", style = MaterialTheme.typography.titleMedium, color = cs.primary)
        }
        val slider = remember { SliderState(value = level, steps = 19, trackRange = 0f..20f) }
        LaunchedEffect(level) { if (slider.value != level) slider.value = level }
        Slider(
            state = slider,
            onValueChange = {
                dragging = true
                slider.value = it
                val step = slider.value.roundToInt()
                if (step != level.roundToInt()) feedback.performHapticFeedback(
                    if (presets.any { p -> p.second == step }) HapticFeedbackType.SegmentTick else HapticFeedbackType.SegmentFrequentTick,
                )
                level = step.toFloat()
            },
            onValueChangeFinished = { onChange(state.copy(level = level.roundToInt())); dragging = false },
            track = { SliderDefaults.Track(sliderState = it, trackCornerSize = androidx.compose.ui.unit.Dp.Unspecified, drawTick = { _, _ -> }) },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Ambient sound level"; stateDescription = "${level.roundToInt()} of 20" },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The three levels are one choice: a connected group. Voice focus is its own switch.
            Row(Modifier.weight(3f).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                presets.forEachIndexed { i, (label, value) ->
                    SoftChip(label, selected = state.level == value, modifier = Modifier.weight(1f), shapes = when (i) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        presets.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    }) { onChange(state.copy(level = value)) }
                }
            }
            Spacer(Modifier.width(8.dp))
            SoftChip("Voice", selected = state.voice, glyph = if (state.voice) Glyph.CHECK else null, modifier = Modifier.weight(1.15f), kind = ChipKind.SWITCH,
                description = "Voice focus") { onChange(state.copy(voice = !state.voice)) }
        }
    }
}

/** What a pill is to TalkBack: a plain action, one choice of several, or an on/off switch. */
private enum class ChipKind { BUTTON, CHOICE, SWITCH }

/**
 * A small dock control, built from Material's buttons by what it does: an action is a tonal
 * button, an on/off is a toggle button, a choice is a toggle button (connected when [shapes]
 * are given). Toggle buttons morph from round to a rounded square when checked.
 */
@Composable
private fun SoftChip(
    label: String, selected: Boolean, modifier: Modifier = Modifier, glyph: Glyph? = null, description: String = label,
    kind: ChipKind = ChipKind.CHOICE, shapes: ToggleButtonShapes? = null, onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val padding = PaddingValues(horizontal = 12.dp)
    val content: @Composable RowScope.() -> Unit = {
        if (glyph != null) { HushIcon(glyph, Modifier.size(18.dp), LocalContentColor.current); Spacer(Modifier.width(ToggleButtonDefaults.IconSpacing)) }
        Text(label, maxLines = 1, autoSize = fitText(MaterialTheme.typography.labelLarge))
    }
    val sized = modifier.heightIn(min = 40.dp)
    when (kind) {
        ChipKind.BUTTON -> FilledTonalButton(onClick = onClick, contentPadding = padding, content = content,
            modifier = sized.semantics { contentDescription = description })
        else -> ToggleButton(
            checked = selected, onCheckedChange = { onClick() },
            shapes = shapes ?: ToggleButtonDefaults.shapesFor(ButtonDefaults.MinHeight),
            colors = ToggleButtonDefaults.colors(containerColor = cs.surfaceContainerHighest, contentColor = cs.onSurface),
            contentPadding = padding, content = content,
            modifier = sized.semantics {
                contentDescription = description
                if (kind == ChipKind.CHOICE) { role = Role.RadioButton; this.selected = selected } else role = Role.Switch
            },
        )
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

/**
 * Listening time as two equal tiles under the rings: what the earbuds have left, and what the
 * case adds. Big numbers on tonal icons, centered; the age of the reading only when it is old.
 */
@Composable
private fun ListeningTime(battery: SonyProtocol.Battery?, mode: Mode?, live: Boolean, seenAt: Long?, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // The owner's measured drain once known; Sony's rating until then.
    val rate = remember(battery, mode) { Prefs.drainRate(ctx, mode) }
    val buds = battery?.let { BatteryLife.budsMinutes(it, mode, rate) } ?: return
    val case = BatteryLife.caseMinutes(battery, mode, rate)?.takeIf { it >= 10 }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(BatteryLife.short(buds), "Listening left", Modifier.weight(1f), low = buds < 60, onClick = onClick) {
                HushIcon(Glyph.EARBUDS, Modifier.size(22.dp), it)
            }
            if (case != null) StatTile("+" + BatteryLife.short(case), "From the case", Modifier.weight(1f), onClick = onClick) {
                Icon(painterResource(R.drawable.ic_case_mark), null, tint = it, modifier = Modifier.size(22.dp))
            }
        }
        if (!live && seenAt != null) Text(
            "Last reading " + DateUtils.getRelativeTimeSpanString(seenAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
            style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatTile(value: String, label: String, modifier: Modifier, low: Boolean = false, onClick: () -> Unit = {}, icon: @Composable (Color) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val accent = if (low) cs.errorContainer else cs.primaryContainer
    val onAccent = if (low) cs.onErrorContainer else cs.onPrimaryContainer
    // Tapping either tile opens the Battery page (history and measured battery life), which
    // grows out of the tile itself (container transform, Navigation.kt) and returns into it.
    val origins = LocalPageOrigins.current
    var bounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    Surface(onClick = { origins.mark(bounds); onClick() }, shape = RoundedCornerShape(PageMotion.CARD_CORNER), color = Surfaces.card,
        modifier = modifier.fillMaxHeight()
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .semantics(mergeDescendants = true) { onClick(label = "Battery history", action = null) }) {
        Column(
            Modifier.padding(vertical = 16.dp, horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Surface(shape = CircleShape, color = accent, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) { icon(onAccent) }
            }
            Text(value, style = MaterialTheme.typography.headlineSmallEmphasized, maxLines = 1,
                autoSize = fitText(MaterialTheme.typography.headlineSmallEmphasized),
                color = if (low) cs.error else cs.onSurface)
            Text(label, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, maxLines = 1)
        }
    }
}

/**
 * With the largest font sizes a one-line label shrinks just enough to fit instead of being cut;
 * at normal sizes it keeps [style]'s size exactly.
 */
private fun fitText(style: androidx.compose.ui.text.TextStyle) =
    androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 7.sp, maxFontSize = style.fontSize, stepSize = 0.5.sp)
