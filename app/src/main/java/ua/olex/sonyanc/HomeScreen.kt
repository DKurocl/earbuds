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
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.preferredFrameRate
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
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ua.olex.sonyanc.SonyProtocol.Mode
import ua.olex.sonyanc.SonyProtocol.NcState
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A row under More controls for a function these earbuds reported. */
data class HomeLink(val glyph: Glyph, val title: String, val summary: String, val onClick: () -> Unit)

/** Pixel-Buds-style device page: header, battery rings, quick actions, noise control, more controls. */
@Composable
fun HushScreen(
    status: Headphones.Status, nc: NcState?, pending: NcState?, fresh: Boolean, error: String?, battery: SonyProtocol.Battery?,
    granted: Boolean, hasDevice: Boolean, onMode: (NcState) -> Unit, onSettings: () -> Unit, onConnect: () -> Unit,
    onPermission: () -> Unit, onBluetooth: () -> Unit, onPick: () -> Unit, onEqualizer: () -> Unit = {}, onEarbuds: () -> Unit = {},
    onConnection: () -> Unit = {}, haptics: Boolean = true,
    batteryRefreshing: Boolean = false, batteryFresh: Boolean = true, onBattery: () -> Unit = {},
    manualRefreshing: Boolean = false,
    deviceName: String = "WF-1000XM5",
    batteryParts: Set<Int> = if (batteryFresh) setOf(0, 1, 2) else emptySet(),
    batterySeenAt: Long? = null, batteryCodec: Int? = null,
    onFind: () -> Unit = {}, lastSeen: Long? = null, onBatteryDetails: () -> Unit = {},
    speakToChat: Boolean? = null, onSpeakToChat: (Boolean) -> Unit = {},
    ambientMinutes: Int = 5, ambientUntil: Long? = null, ambientReturn: Mode = Mode.ANC, onAmbientFor: () -> Unit = {}, onStayInAmbient: () -> Unit = {},
    linked: Boolean? = null, bluetoothOn: Boolean = true, paired: Boolean = true,
    onProfiles: (() -> Unit)? = null, onPowerOff: () -> Unit = {},
    turningOff: Boolean = false,
    extras: List<HomeLink> = emptyList(),
) {
    val ready = status == Headphones.Status.CONNECTED && granted && bluetoothOn && paired && linked != false
    val connecting = status == Headphones.Status.CONNECTING && linked != false
    val canControl = granted && hasDevice && bluetoothOn && paired && linked != false
    val showModeDock = granted && hasDevice && bluetoothOn && paired
    val feedback = LocalHapticFeedback.current
    fun choose(value: NcState) {
        if (haptics) feedback.performHapticFeedback(HapticFeedbackType.SegmentTick)
        onMode(value)
    }
    var confirmPowerOff by remember { mutableStateOf(false) }
    if (confirmPowerOff) PowerOffDialog({ confirmPowerOff = false }) { confirmPowerOff = false; onPowerOff() }
    val connectionLabel = when {
        !granted -> "Bluetooth access required"
        !bluetoothOn -> "Bluetooth is off"
        !hasDevice -> "No earbuds selected"
        !paired -> "Earbuds are no longer paired"
        ready || (linked == true && status != Headphones.Status.FAILED) -> "Connected"
        linked == false -> (lastSeen ?: batterySeenAt)?.let {
            "Disconnected · " + DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        } ?: "Earbuds disconnected"
        connecting -> statusText(status)
        else -> error ?: statusText(status)
    }
    val showConnectionProgress = (connecting && linked != true) || (ready && batteryRefreshing && battery == null)
    Scaffold(containerColor = Surfaces.page, topBar = {
        DeviceTopBar(deviceName.ifBlank { "WF-1000XM5" }, subtitle = { ConnectionStatus(connectionLabel, showConnectionProgress) }) {
            val settings = HeaderAction(Glyph.SETTINGS, "Settings", onSettings)
            if (onProfiles != null) HeaderActionPair(HeaderAction(Glyph.TUNE, "Personal profiles", onProfiles), settings)
            else HeaderActionButton(settings)
        }
    }) { insets ->
        val density = LocalDensity.current
        var dockHeight by remember { mutableStateOf(0.dp) }
        Box(Modifier.padding(top = insets.calculateTopPadding()).fillMaxSize()) {
        // Pull down to read battery and codec again (also connects if needed).
        val pull = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = manualRefreshing, onRefresh = { if (canControl) onBattery() }, state = pull,
            modifier = Modifier.fillMaxSize(),
            indicator = {
                PullToRefreshDefaults.LoadingIndicator(state = pull, isRefreshing = manualRefreshing,
                    modifier = Modifier.align(Alignment.TopCenter).then(
                        if (manualRefreshing) Modifier.semantics { contentDescription = "Refreshing earbuds" } else Modifier,
                    ))
            },
        ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = UiTokens.pageInset)
                .then(if (!showModeDock) Modifier.navigationBarsPadding() else Modifier)
                .padding(bottom = if (showModeDock) dockHeight else 0.dp),
            verticalArrangement = Arrangement.spacedBy(UiTokens.homeSectionGap),
        ) {
            when {
                !granted -> SetupCard("Allow Bluetooth access", "Earbuds talks to your WF-1000XM5 directly. Nothing leaves your phone.", "Allow", Glyph.BLUETOOTH, onPermission)
                !bluetoothOn -> SetupCard("Turn on Bluetooth", "Connect your earbuds in Android Bluetooth settings.", "Bluetooth settings", Glyph.BLUETOOTH, onBluetooth)
                !hasDevice || !paired -> SetupCard("Choose your earbuds", "Pair your WF-1000XM5 in Android Bluetooth settings, then pick it here.", "Choose earbuds", Glyph.EARBUDS, onPick)
                else -> {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Batteries(
                            battery ?: SonyProtocol.Battery(), batteryParts,
                            confirmed = nc?.mode.takeIf { pending == null }, dimCached = linked != true,
                        )
                        Box(Modifier.introFade()) {
                            // The estimate follows the mode the earbuds are in, not one just tapped: nothing jumps while a
                            // change is on its way (or can't go through, with the earbuds away).
                            ListeningTime(
                                battery?.let { BatteryLife.forEstimate(it, batteryParts) }, nc?.mode, batteryCodec, ready, onBatteryDetails,
                            )
                        }
                    }

                    Row(Modifier.fillMaxWidth().introFade(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        BigAction(Glyph.FIND, "Find", onClick = onFind)
                        BigAction(Glyph.BLUETOOTH, if (linked == false || status == Headphones.Status.FAILED) "Connect" else "Bluetooth",
                            onClick = if (status == Headphones.Status.FAILED && linked == true) onConnect else onBluetooth)
                        BigAction(Glyph.POWER, "Power off", enabled = !turningOff && (ready || linked == true)) { confirmPowerOff = true }
                    }

                    Column {
                        Box(Modifier.introFade()) { ListGroup("More controls") {
                            item {
                                NavRow(it, Glyph.EQ, "Equalizer", "Presets and Clear Bass",
                                    modifier = Modifier.semantics { contentDescription = "Equalizer" }, onClick = onEqualizer)
                            }
                            item {
                                NavRow(it, Glyph.EARBUDS, "Earbud settings", "Touch controls and voice prompts",
                                    modifier = Modifier.semantics { contentDescription = "Earbud settings" }, onClick = onEarbuds)
                            }
                            extras.forEach { link ->
                                item { NavRow(it, link.glyph, link.title, link.summary, onClick = link.onClick) }
                            }
                            item {
                                NavRow(it, Glyph.INFO, "Device info", "Codec and firmware",
                                    modifier = Modifier.semantics { contentDescription = "Device info" }, onClick = onConnection)
                            }
                        } }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        }
        // Noise control floats at the bottom: on a 6.8" phone that is where the thumb is.
        if (showModeDock) Column(
            Modifier.align(Alignment.BottomCenter).introFade().onSizeChanged { dockHeight = with(density) { it.height.toDp() } },
        ) {
            val options = pending?.takeIf { it.mode == nc?.mode } ?: nc
            FloatingModeOptions(
                options.takeIf { canControl }, ::choose,
                DockExtras(speakToChat, onSpeakToChat, ambientMinutes, ambientUntil, ambientReturn, onAmbientFor, onStayInAmbient),
            )
            ModeDock(nc, pending, canControl, ::choose)
        }
        }
    }
}

@Composable
private fun ConnectionStatus(status: String, busy: Boolean) {
    Row(
        Modifier.heightIn(min = 18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.Start),
    ) {
        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).semantics { liveRegion = LiveRegionMode.Polite })
        if (busy) LoadingIndicator(Modifier.size(16.dp))
    }
}

@Composable
internal fun Batteries(
    b: SonyProtocol.Battery, live: Set<Int>,
    confirmed: Mode? = null, dimCached: Boolean = true,
) {
    // The owner's earbuds as Android shows a device in its details: the product render
    // (docs/icon/device_pictures.py).
    val budL = painterResource(R.drawable.device_bud_left)
    val budR = painterResource(R.drawable.device_bud_right)
    // When the earbuds confirm a new mode, a small swell runs through the rings, left to right:
    // the answer comes from the earbuds, not from the button that was tapped.
    val swell = remember { List(3) { Animatable(1f) } }
    var lastConfirmed by remember { mutableStateOf(confirmed) }
    SideEffect { if (confirmed != null) lastConfirmed = confirmed }
    val seen = remember { arrayOfNulls<Mode>(1) }
    val motionEnabled = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor != 0f
    val grow = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    LaunchedEffect(lastConfirmed, motionEnabled) {
        val mode = lastConfirmed
        val was = seen[0]
        seen[0] = mode
        if (!motionEnabled) {
            swell.forEach { it.snapTo(1f) }
            return@LaunchedEffect
        }
        if (mode == null || was == null || was == mode) return@LaunchedEffect
        listOf(0, 2, 1).forEachIndexed { order, ring ->
            launch {
                delay(order * 70L)
                swell[ring].animateTo(1.06f, grow)
                swell[ring].animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 380f))
            }
        }
    }
    Row(
        Modifier.fillMaxWidth().clip(UiTokens.cardShape).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        // Equal thirds: each label has a bounded width to shrink into at the largest font sizes.
        Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) { BatteryRing("Left", b.left, b.leftCharging, 0 in live, dimCached, motionEnabled, { swell[0].value }) { BudArt(budL) } }
        Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
            BatteryRing("Case", b.case, b.caseCharging, 2 in live, dimCached, motionEnabled, { swell[2].value }) {
                Image(painterResource(R.drawable.device_case), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) { BatteryRing("Right", b.right, b.rightCharging, 1 in live, dimCached, motionEnabled, { swell[1].value }) { BudArt(budR) } }
    }
}

/**
 * Pixel "Device details" battery: the product on a filled disc, a thin ring around it with a
 * gap, and a status chip sitting on the ring at the top. The ring starts and ends at the chip's
 * outline, so the chip reads as cut out of the ring rather than stacked on it.
 */
@Composable
private fun BatteryRing(
    label: String, value: Int?, charging: Boolean, live: Boolean, dimCached: Boolean, motionEnabled: Boolean,
    swell: () -> Float = { 1f }, art: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val normal = batteryIndicator
    val (ringColor, chipContent) = when {
        value == null -> cs.outlineVariant to cs.onSurfaceVariant
        value <= 20 && !charging -> cs.error to cs.onError
        else -> normal
    }
    val color = if (live || !dimCached || value == null) ringColor else ringColor.copy(alpha = .55f)
    val track = cs.surfaceContainerHighest
    val disc = cs.surfaceContainerHigh
    val page = Surfaces.page
    // The ring fills in from empty when Home opens (as Pixel's battery widget does), then follows
    // each new reading.
    val fill = remember { Animatable(0f) }
    val fillSpec = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    // On a fresh start each ring waits for its piece to land from the intro (Intro).
    val landed = Intro.isLanded(label)
    val shown by animateFloatAsState(if (landed) 1f else 0f, MaterialTheme.motionScheme.fastEffectsSpec(), label = "shown $label")
    LaunchedEffect(value, landed) { if (landed) fill.animateTo((value ?: 0) / 100f, fillSpec) }
    // Runs only while this ring is charging; otherwise no animation frame is ever requested.
    // Read only while drawing: a charging frame redraws the ring and recomposes nothing.
    val phaseState = if (motionEnabled && charging && value != null) {
        val waves = rememberInfiniteTransition(label = "charging $label")
        waves.animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "phase")
    } else null
    Column(
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = "$label battery"
            stateDescription = (value?.let { "$it percent" } ?: "Unavailable") +
                if (charging) ", charging" else if (!live && value != null) ", last known" else ""
        },
    ) {
        Box(Modifier.size(RING_BOX).graphicsLayer { val k = swell(); scaleX = k; scaleY = k }, contentAlignment = Alignment.TopCenter) {
            // While the intro plays, the ring waits dimmed, its chip hidden, until its piece lands.
            // A slow charging wave: 30 frames a second look the same and let the panel leave 120 Hz.
            Canvas(Modifier.fillMaxSize().then(if (phaseState != null) Modifier.preferredFrameRate(30f) else Modifier).graphicsLayer { alpha = 0.35f + 0.65f * shown }) {
                val progress = fill.value.coerceIn(0f, 1f)
                val phase = phaseState?.value ?: 0f
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
                drawCircle(if (value == null) track else androidx.compose.ui.graphics.lerp(track, color, shown), CHIP.toPx() / 2, Offset(center.x, CHIP.toPx() / 2))
            }
            Box(
                Modifier.padding(top = CHIP / 2 + RING_RADIUS - ART / 2).size(ART)
                    .onGloballyPositioned { Intro.targets[label] = it.boundsInRoot() }
                    // The intro draws this picture in flight; it appears here as it lands.
                    .graphicsLayer { alpha = shown },
                contentAlignment = Alignment.Center,
            ) { art() }
            HushIcon(
                when {
                    value == null -> Glyph.BATTERY_UNKNOWN
                    charging -> Glyph.BATTERY_CHARGING
                    value <= 20 -> Glyph.BATTERY_LOW
                    else -> Glyph.BATTERY
                }, Modifier.padding(top = (CHIP - 18.dp) / 2).size(18.dp).graphicsLayer { alpha = shown }, chipContent, filled = true,
            )
        }
        Row(
            Modifier.padding(horizontal = 4.dp).graphicsLayer { alpha = shown; translationY = (1 - shown) * 6.dp.toPx() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, maxLines = 1)
            RollingText(
                value?.let { "$it%" } ?: "\u2014", MaterialTheme.typography.titleMediumEmphasized,
                Modifier.weight(1f, fill = false),
                color = if (value != null && value <= 20 && !charging) cs.error else cs.onSurface,
            )
        }
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

/** An earbud's picture, centred. */
@Composable
private fun BudArt(image: androidx.compose.ui.graphics.painter.Painter) {
    Image(image, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
}

/** A home action: A compact tonal action with an expressive press shape. */
@Composable
private fun BigAction(glyph: Glyph, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalIconButton(
            onClick = onClick, enabled = enabled,
            shapes = IconButtonDefaults.shapes(), interactionSource = press,
            modifier = Modifier.size(width = 56.dp, height = 48.dp)
                .semantics { contentDescription = label },
        ) {
            // The icon fills in under the finger, as the button squares off.
            HushIcon(glyph, Modifier.size(IconButtonDefaults.mediumIconSize), LocalContentColor.current, filled = pressed, bold = pressed)
        }
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
    val minutes: Int, val until: Long?, val returnMode: Mode, val onAmbientFor: () -> Unit, val onStay: () -> Unit,
)

/** Controls float directly above the mode buttons. */
@Composable
private fun FloatingModeOptions(state: NcState?, onChoose: (NcState) -> Unit, extras: DockExtras) {
    val motion = MaterialTheme.motionScheme
    val mode = state?.mode?.takeIf { it != Mode.OFF }
    var lastMode by remember { mutableStateOf(mode) }
    if (mode != null) lastMode = mode
    AnimatedVisibility(
        visible = mode != null,
        enter = fadeIn(motion.defaultEffectsSpec()) + expandVertically(motion.defaultSpatialSpec(), expandFrom = Alignment.Top),
        exit = fadeOut(motion.fastEffectsSpec()) + shrinkVertically(motion.fastSpatialSpec(), shrinkTowards = Alignment.Top),
    ) {
        AnimatedContent(
            lastMode,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            label = "Mode controls",
            transitionSpec = {
                fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()) using
                    SizeTransform(clip = false) { _, _ -> motion.defaultSpatialSpec() }
            },
        ) { shown ->
            when (shown) {
                Mode.AMBIENT -> AmbientControls((state ?: NcState(Mode.AMBIENT)).copy(mode = Mode.AMBIENT), onChoose, extras)
                else -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    extras.speakToChat?.let { on ->
                        SoftChip("Speak-to-Chat", selected = on, glyph = if (on) Glyph.CHECK else Glyph.CHAT, modifier = Modifier.weight(1f), kind = ChipKind.SWITCH) {
                            extras.onSpeakToChat(!on)
                        }
                    }
                    SoftChip("Ambient · ${extras.minutes} min", selected = false, glyph = Glyph.TIMER, modifier = Modifier.weight(1f), kind = ChipKind.BUTTON,
                        description = "Ambient for ${extras.minutes} minutes, then back to noise cancelling") { extras.onAmbientFor() }
                }
            }
        }
    }
}

/** One floating surface for the three noise modes, above the scrollable Home content. */
@Composable
private fun ModeDock(state: NcState?, pending: NcState?, enabled: Boolean, onChoose: (NcState) -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    BoxWithConstraints(
        modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        val density = LocalDensity.current
        val textMeasurer = rememberTextMeasurer()
        val labelStyle = MaterialTheme.typography.labelLarge
        val slotWidth = (maxWidth - 20.dp) / 3
        val labelWidths = Mode.entries.map { mode ->
            with(density) { textMeasurer.measure(mode.short, style = labelStyle, maxLines = 1).size.width.toDp() }
        }
        val contentWidths = labelWidths.map { it + 24.dp + ToggleButtonDefaults.IconSpacing + 24.dp }
        val iconOnly = maxWidth < 320.dp || density.fontScale >= 1.3f || contentWidths.any { it > slotWidth }
        Surface(
            shape = CircleShape,
            color = cs.surfaceContainerHighest,
            shadowElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            var iconMotion by remember { mutableStateOf<Pair<Mode, Int>?>(null) }
            val interactions = remember { Mode.entries.map { MutableInteractionSource() } }
            ButtonGroup(
                overflowIndicator = {},
                expandedRatio = .08f,
                modifier = Modifier.fillMaxWidth().padding(6.dp).selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Mode.entries.forEachIndexed { index, mode ->
                    val contentWidth = if (iconOnly) 48.dp else contentWidths[index]
                    // Keep room for the whole label, including the case where two neighbors press.
                    val compression = ((slotWidth - contentWidth - 1.dp) / 2).coerceIn(0.dp, 8.dp)
                    customItem(
                        buttonGroupContent = {
                            ModeButton(
                                mode, state?.mode == mode, pending?.mode == mode, enabled, iconOnly,
                                iconMotion?.takeIf { it.first == mode }?.second, interactions[index],
                                Modifier.weight(1f).animateWidth(interactions[index], compression),
                            ) {
                                if (mode != (pending ?: state)?.mode) {
                                    iconMotion = mode to ((iconMotion?.second ?: 0) + 1)
                                }
                                onChoose((pending ?: state ?: NcState(mode)).copy(mode = mode))
                            }
                        },
                        menuContent = {},
                    )
                }
            }
        }
    }
}

/** One mode in the floating toolbar: Material's toggle button, filled when chosen. */
@Composable
private fun ModeButton(
    mode: Mode, selected: Boolean, applying: Boolean, enabled: Boolean, iconOnly: Boolean,
    motionRequest: Int?, interaction: MutableInteractionSource, modifier: Modifier, onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    ToggleButton(
        checked = selected,
        onCheckedChange = { onClick() },
        enabled = enabled,
        shapes = ToggleButtonShapes(shape = CircleShape, pressedShape = CircleShape, checkedShape = CircleShape),
        colors = ToggleButtonDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = cs.onSurface,
            disabledContainerColor = Color.Transparent,
        ),
        contentPadding = PaddingValues(horizontal = 12.dp),
        interactionSource = interaction,
        // The screen's one hero control, as Pixel Buds' mode button: taller than a normal button.
        modifier = modifier.heightIn(min = 64.dp)
            // One of three, like radio buttons: announced and tested as selected, not as on/off.
            .semantics { role = Role.RadioButton; this.selected = selected; contentDescription = mode.label; if (applying) stateDescription = "Applying" },
    ) {
        ModeGlyph(mode, motionRequest, selected, LocalContentColor.current)
        if (!iconOnly) {
            Spacer(Modifier.width(ToggleButtonDefaults.IconSpacing))
            Text(mode.short, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false,
                overflow = TextOverflow.Ellipsis)
        }
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
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // A running "Ambient for a moment": the way back, and one tap to stay.
        extras.until?.let { until ->
            var now by remember(until) { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(until) { while (now < until) { kotlinx.coroutines.delay(1000); now = System.currentTimeMillis() } }
            Surface(
                onClick = extras.onStay, shape = CircleShape, color = cs.tertiaryContainer, contentColor = cs.onTertiaryContainer,
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp).semantics { contentDescription = "Back to ${extras.returnMode.label.lowercase()} soon. Tap to stay in Ambient" },
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    HushIcon(Glyph.TIMER, Modifier.size(18.dp), cs.onTertiaryContainer)
                    Spacer(Modifier.width(8.dp))
                    Text("Back to ${extras.returnMode.short} in " + AmbientTimer.countdown(until - now), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    Text("Stay", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        val slider = remember { SliderState(value = level, steps = 19, trackRange = 0f..20f) }
        LaunchedEffect(level) { if (slider.value != level) slider.value = level }
        Surface(shape = CircleShape, color = cs.surfaceContainerHighest, shadowElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Ambient level", style = MaterialTheme.typography.labelLarge, maxLines = 2,
                    modifier = Modifier.widthIn(max = 108.dp))
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
                    modifier = Modifier.weight(1f).semantics { contentDescription = "Ambient sound level"; stateDescription = "${level.roundToInt()} of 20" },
                )
                RollingText("${level.roundToInt()}", MaterialTheme.typography.titleSmall, color = cs.primary)
            }
        }

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
    Surface(shape = UiTokens.cardShape, color = Surfaces.card, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(shape = CircleShape, color = cs.primaryContainer, modifier = Modifier.size(72.dp)) {
                Box(contentAlignment = Alignment.Center) { HushIcon(glyph, Modifier.size(32.dp), cs.onPrimaryContainer) }
            }
            Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(body, style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
            Button(onClick = onClick, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(action) }
        }
    }
}

/** Two equal tiles with matching surfaces for earbud and case estimates. */
@Composable
private fun ListeningTime(
    battery: SonyProtocol.Battery?, mode: Mode?, codec: Int?, connected: Boolean,
    onBattery: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val cs = MaterialTheme.colorScheme
    val rate = remember(battery, mode) { Prefs.drainRate(ctx, mode) }
    val buds = battery?.let { BatteryLife.budsMinutes(it, mode, rate, codec) }
    val case = battery?.let { BatteryLife.caseMinutes(it, mode, rate, codec) }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        val corner = PageMotion.CARD_CORNER
        HomeSummaryTile(RoundedCornerShape(corner), Modifier.weight(1f).fillMaxHeight(),
            "Battery history", onBattery) {
            // One card that leads to the Battery page, as Pixel's battery card does: the estimate on
            // the left, an arrow on the right so it reads as a way in, not a label.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Listening time left", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                    Text(buds?.let(BatteryLife::short) ?: "—", style = MaterialTheme.typography.headlineSmallEmphasized.withTabularFigures(),
                        color = if (buds != null && buds < 60) cs.error else cs.onSurface)
                    Text(case?.let { "The case adds ${BatteryLife.short(it)}" } ?: "Case level unknown",
                        style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                }
                HushIcon(Glyph.BACK, Modifier.size(24.dp).graphicsLayer { rotationZ = 180f }, cs.onSurfaceVariant)
            }
        }

    }
}

@Composable
private fun HomeSummaryTile(
    shape: RoundedCornerShape, modifier: Modifier, actionLabel: String, onClick: (() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit,
) {
    val body: @Composable () -> Unit = {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp), content = content)
    }
    if (onClick != null) {
        Surface(onClick = onClick, shape = shape, color = Surfaces.estimate,
            modifier = modifier.semantics { onClick(label = actionLabel, action = null) }, content = body)
    } else {
        Surface(shape = shape, color = Surfaces.estimate, modifier = modifier, content = body)
    }
}

/**
 * With the largest font sizes a one-line label shrinks just enough to fit instead of being cut;
 * at normal sizes it keeps [style]'s size exactly.
 */
private fun fitText(style: androidx.compose.ui.text.TextStyle) =
    androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 7.sp, maxFontSize = style.fontSize, stepSize = 0.5.sp)

/** The page around the rings waits for the intro and comes in last (see [Intro]). */
private fun Modifier.introFade() = graphicsLayer {
    val a = Intro.content
    alpha = a
    translationY = (1 - a) * 16.dp.toPx()
}
