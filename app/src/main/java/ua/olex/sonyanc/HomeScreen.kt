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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ua.olex.sonyanc.SonyProtocol.Mode
import ua.olex.sonyanc.SonyProtocol.NcState
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
            IconButton(onClick = onSettings, modifier = Modifier.size(48.dp).introFade().semantics { contentDescription = "Settings" }) {
                HushIcon(Glyph.SETTINGS, color = cs.onSurfaceVariant)
            }
        }
    }) { insets ->
        // The dock floats over the page; the page scrolls under it and ends above it.
        val density = LocalDensity.current
        var dockHeight by remember { mutableStateOf(0.dp) }
        // Scrolling down tucks the dock's card away (the modes stay); scrolling up brings it back.
        var cardShown by remember { mutableStateOf(true) }
        val tuck = remember {
            object : NestedScrollConnection {
                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (consumed.y < -6f) cardShown = false else if (consumed.y > 6f) cardShown = true
                    return Offset.Zero
                }
            }
        }
        Box(Modifier.padding(top = insets.calculateTopPadding()).fillMaxSize().nestedScroll(tuck)) {
        // Pull down to read battery and codec again (also connects if needed).
        val pull = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = batteryRefreshing || codecRefreshing, onRefresh = onBattery, state = pull,
            // The page ends where the dock's fade begins: rows never hide half under the toolbar
            // (unreachable there, and too small a target for the part that shows).
            modifier = Modifier.fillMaxSize().padding(bottom = (dockHeight - DOCK_FADE).coerceAtLeast(0.dp)),
            indicator = {
                PullToRefreshDefaults.LoadingIndicator(state = pull, isRefreshing = batteryRefreshing || codecRefreshing,
                    modifier = Modifier.align(Alignment.TopCenter))
            },
        ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // No banner for a link problem: the status line under the name says it quietly, and
            // Connect is right below. (Setup cards still explain a missing permission or device.)
            Box(Modifier.introFade()) { Header(deviceName, status, codec, batteryRefreshing, lastSeen, problem = error.takeIf { granted && hasDevice }) }

            when {
                !granted -> SetupCard("Allow Bluetooth access", "Earbuds talks to your WF-1000XM5 directly. Nothing leaves your phone.", "Allow", Glyph.BLUETOOTH, onPermission)
                !hasDevice -> SetupCard("Choose your earbuds", "Pair your WF-1000XM5 in Android Bluetooth settings, then pick it here.", "Choose earbuds", Glyph.EARBUDS, onPick)
                else -> {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Batteries(battery ?: SonyProtocol.Battery(), batteryParts, ready, onBattery, confirmed = nc?.mode.takeIf { pending == null })
                        Box(Modifier.introFade()) {
                            // The estimate follows the mode the earbuds are in, not one just tapped: nothing jumps while a
                            // change is on its way (or can't go through, with the earbuds away).
                            ListeningTime(battery, nc?.mode, live = batteryParts.isNotEmpty(), seenAt = batterySeenAt, onClick = onBatteryDetails)
                        }
                    }

                    Row(Modifier.fillMaxWidth().introFade(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        // Only what is used day to day. The control link opens and closes by itself (no
                        // Disconnect); Android's Bluetooth settings are one level down in Device info.
                        BigAction(Glyph.FIND, "Find", onClick = onFind)
                        if (!ready) BigAction(Glyph.REFRESH, if (status == Headphones.Status.CONNECTING) "Connecting…" else "Connect",
                            enabled = status != Headphones.Status.CONNECTING, onClick = onConnect)
                    }

                    Box(Modifier.introFade()) { ListGroup("More controls") {
                        // Battery opens from the listening-time tiles above; no second entry here.
                        item { NavRow(it, Glyph.EQ, "Equalizer", equalizerPreset ?: "Presets, custom bands and Clear Bass", onClick = onEqualizer) }
                        item { NavRow(it, Glyph.TUNE, "Earbud settings", "Touch controls, Speak-to-Chat, voice prompts", onClick = onEarbuds) }
                        item {
                            val codecLine = if (ready) codec?.label ?: if (codecRefreshing) "Reading codec…" else null else null
                            NavRow(it, Glyph.INFO, "Device info", codecLine?.let { c -> "Codec · $c" } ?: "Codec, firmware, power", onClick = onConnection)
                        }
                        item { NavRow(it, Glyph.SETTINGS, "App settings", "Appearance, shortcuts, device", onClick = onAppSettings) }
                    } }
                }
            }
            Spacer(Modifier.height(8.dp + DOCK_FADE))
        }
        }
        // Noise control floats at the bottom: on a 6.8" phone that is where the thumb is.
        if (granted && hasDevice) ModeDock(
            state, pending, ::choose, DockExtras(speakToChat, onSpeakToChat, ambientMinutes, ambientUntil, onAmbientFor, onStayInAmbient),
            cardShown, Modifier.align(Alignment.BottomCenter).introFade().onSizeChanged { dockHeight = with(density) { it.height.toDp() } },
        )
        }
    }
}

@Composable
private fun Header(name: String, status: Headphones.Status, codec: CodecProtocol.State?, busy: Boolean, lastSeen: Long? = null, problem: String? = null) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Renaming is rare: it lives in App settings, not next to the name.
        Text(name, style = MaterialTheme.typography.headlineMediumEmphasized, textAlign = TextAlign.Center, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        Row(Modifier.heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val line = when (status) {
                Headphones.Status.CONNECTED -> "Connected" + (codec?.takeIf { it.isKnown }?.let { " · ${it.label}" } ?: "")
                Headphones.Status.CONNECTING -> statusText(status)
                // Android reported the earbuds gone (case, range or Bluetooth off), or why the link failed.
                else -> lastSeen?.let { "Disconnected · " + DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS) }
                    ?: problem ?: statusText(status)
            }
            Text(line, style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (status == Headphones.Status.CONNECTING || busy) LoadingIndicator(Modifier.size(20.dp))
        }
    }
}

@Composable
internal fun Batteries(b: SonyProtocol.Battery, live: Set<Int>, ready: Boolean, onRefresh: () -> Unit, confirmed: Mode? = null) {
    // The owner's earbuds as Android shows a device in its details: the product render
    // (docs/icon/device_pictures.py).
    val budL = painterResource(R.drawable.device_bud_left)
    val budR = painterResource(R.drawable.device_bud_right)
    // When the earbuds confirm a new mode, a small swell runs through the rings, left to right:
    // the answer comes from the earbuds, not from the button that was tapped.
    val swell = remember { List(3) { Animatable(1f) } }
    val seen = remember { arrayOfNulls<Mode>(1) }
    val grow = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    LaunchedEffect(confirmed) {
        val was = seen[0]; seen[0] = confirmed ?: was
        if (confirmed == null || was == null || was == confirmed) return@LaunchedEffect
        listOf(0, 2, 1).forEachIndexed { order, ring ->
            launch {
                delay(order * 70L)
                swell[ring].animateTo(1.06f, grow)
                swell[ring].animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 380f))
            }
        }
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).clickable(enabled = ready, onClickLabel = "Refresh battery", onClick = onRefresh)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        // Equal thirds: each label has a bounded width to shrink into at the largest font sizes.
        Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) { BatteryRing("Left", b.left, b.leftCharging, 0 in live, { swell[0].value }) { BudArt(budL) } }
        Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
            BatteryRing("Case", b.case, b.caseCharging, 2 in live, { swell[2].value }) {
                Image(painterResource(R.drawable.device_case), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) { BatteryRing("Right", b.right, b.rightCharging, 1 in live, { swell[1].value }) { BudArt(budR) } }
    }
}

/**
 * Pixel "Device details" battery: the product on a filled disc, a thin ring around it with a
 * gap, and a status chip sitting on the ring at the top. The ring starts and ends at the chip's
 * outline, so the chip reads as cut out of the ring rather than stacked on it.
 */
@Composable
private fun BatteryRing(label: String, value: Int?, charging: Boolean, live: Boolean, swell: () -> Float = { 1f }, art: @Composable () -> Unit) {
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
    // The ring fills in from empty when Home opens (as Pixel's battery widget does), then follows
    // each new reading.
    val fill = remember { Animatable(0f) }
    val fillSpec = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    // On a fresh start each ring waits for its piece to land from the intro (Intro).
    val landed = Intro.isLanded(label)
    val shown by animateFloatAsState(if (landed) 1f else 0f, MaterialTheme.motionScheme.fastEffectsSpec(), label = "shown $label")
    LaunchedEffect(value, landed) { if (landed) fill.animateTo((value ?: 0) / 100f, fillSpec) }
    val progress = fill.value.coerceIn(0f, 1f)
    // Runs only while this ring is charging; otherwise no animation frame is ever requested.
    // Read only while drawing: a charging frame redraws the ring and recomposes nothing.
    val phaseState = if (charging && value != null) {
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
                }, Modifier.padding(top = (CHIP - 16.dp) / 2).size(16.dp).graphicsLayer { alpha = shown }, chipContent, filled = true,
            )
        }
        // The number rolls digit by digit when a new reading arrives.
        RollingText(
            "$label: " + (value?.let { "$it%" } ?: "\u2014"), MaterialTheme.typography.titleMedium,
            Modifier.padding(horizontal = 4.dp).graphicsLayer { alpha = shown; translationY = (1 - shown) * 6.dp.toPx() },
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

/** An earbud's picture, centred. */
@Composable
private fun BudArt(image: androidx.compose.ui.graphics.painter.Painter) {
    Image(image, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
}

/** A home action: Material's medium tonal icon button (wide); it squares off while pressed. */
@Composable
private fun BigAction(glyph: Glyph, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalIconButton(
            onClick = onClick, enabled = enabled,
            shapes = IconButtonDefaults.shapes(), interactionSource = press,
            modifier = Modifier.size(IconButtonDefaults.mediumContainerSize(IconButtonDefaults.IconButtonWidthOption.Wide))
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
    val minutes: Int, val until: Long?, val onAmbientFor: () -> Unit, val onStay: () -> Unit,
)

@Composable
private fun ModeDock(state: NcState?, pending: NcState?, onChoose: (NcState) -> Unit, extras: DockExtras, cardShown: Boolean, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    // What the chosen mode offers grows out of the toolbar as a floating card: Ambient's level and
    // voice focus, or Speak-to-Chat and "Ambient for a moment" with noise cancelling.
    val card = state?.mode?.takeIf { it != Mode.OFF }
    var lastCard by remember { mutableStateOf(card) }
    if (card != null) lastCard = card
    // A soft fade of the page behind the floating parts, so the list never shows between them.
    val page = Surfaces.page
    Column(
        modifier.fillMaxWidth()
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(0f to page.copy(alpha = 0f), 0.22f to page.copy(alpha = .94f), 1f to page))
            .navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = DOCK_FADE, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AnimatedVisibility(
            visible = card != null && cardShown,
            enter = fadeIn(motion.fastEffectsSpec()) + scaleIn(motion.defaultSpatialSpec(), initialScale = 0.8f, transformOrigin = TransformOrigin(0.5f, 1f)) +
                expandVertically(motion.defaultSpatialSpec(), expandFrom = Alignment.Bottom),
            exit = fadeOut(motion.fastEffectsSpec()) + scaleOut(motion.fastSpatialSpec(), targetScale = 0.8f, transformOrigin = TransformOrigin(0.5f, 1f)) +
                shrinkVertically(motion.fastSpatialSpec(), shrinkTowards = Alignment.Bottom),
        ) {
            Surface(shape = RoundedCornerShape(28.dp), color = cs.surfaceContainerHigh, shadowElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                AnimatedContent(
                    lastCard, label = "dock card",
                    transitionSpec = { fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()) using SizeTransform(clip = false) { _, _ -> motion.defaultSpatialSpec() } },
                ) { shown ->
                    Box(Modifier.padding(8.dp)) {
                        when (shown) {
                            Mode.AMBIENT -> AmbientControls((state ?: NcState(Mode.AMBIENT)).copy(mode = Mode.AMBIENT), onChoose, extras)
                            else -> Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
        }
        // Material 3 Expressive's floating toolbar: a pill above the page with the three modes. The
        // pressed mode widens and its neighbours give way (animateWidth); the chosen one fills in.
        Surface(shape = CircleShape, color = cs.surfaceContainerHigh, shadowElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
            val interactions = remember { Mode.entries.map { MutableInteractionSource() } }
            ButtonGroup(
                overflowIndicator = {}, // three buttons always fit
                modifier = Modifier.fillMaxWidth().padding(6.dp).selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Mode.entries.forEachIndexed { index, mode ->
                    customItem(
                        buttonGroupContent = {
                            ModeButton(
                                mode, state?.mode == mode, pending?.mode == mode, interactions[index],
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

/** One mode in the floating toolbar: Material's toggle button, filled when chosen. */
@Composable
private fun ModeButton(
    mode: Mode, selected: Boolean, applying: Boolean,
    interaction: MutableInteractionSource, modifier: Modifier, onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val pressed by interaction.collectIsPressedAsState()
    ToggleButton(
        checked = selected,
        onCheckedChange = { onClick() },
        // Toolbar items: no container at rest, a full pill when chosen, squarer under the finger.
        shapes = ToggleButtonShapes(shape = CircleShape, pressedShape = RoundedCornerShape(16.dp), checkedShape = CircleShape),
        colors = ToggleButtonDefaults.colors(containerColor = Color.Transparent, contentColor = cs.onSurface),
        contentPadding = PaddingValues(horizontal = 12.dp),
        interactionSource = interaction,
        modifier = modifier.heightIn(min = ButtonDefaults.MediumContainerHeight)
            // One of three, like radio buttons: announced and tested as selected, not as on/off.
            .semantics { role = Role.RadioButton; this.selected = selected; contentDescription = mode.label },
    ) {
        if (applying) LoadingIndicator(Modifier.size(24.dp), color = LocalContentColor.current)
        // The chosen mode's icon thickens with a small spring pop; a press does the same at once.
        else HushIcon(mode.glyph, Modifier.size(24.dp), LocalContentColor.current, bold = selected || pressed)
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
            RollingText("${level.roundToInt()}", MaterialTheme.typography.titleMedium, color = cs.primary)
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
            Button(onClick = onClick, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(action) }
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
    // Shown whenever the case's level is known, even when it adds little: tiles never come and go.
    val case = BatteryLife.caseMinutes(battery, mode, rate)
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Both tiles have the same parts, so they come out the same height without intrinsics
        // (which the rolling digits, built on AnimatedContent, cannot answer).
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(BatteryLife.short(buds), "Listening left", Modifier.weight(1f), low = buds < 60, onClick = onClick) {
                HushIcon(Glyph.EARBUDS, Modifier.size(22.dp), it, filled = true)
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
    // Pressed, the tile settles in a little and its corners tighten, with Material's springs.
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val corner by animateDpAsState(if (pressed) 20.dp else PageMotion.CARD_CORNER, MaterialTheme.motionScheme.fastSpatialSpec(), label = "corner")
    val squeeze by animateFloatAsState(if (pressed) 0.96f else 1f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "squeeze")
    Surface(onClick = { origins.mark(bounds); onClick() }, shape = RoundedCornerShape(corner), color = Surfaces.card, interactionSource = press,
        modifier = modifier
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .graphicsLayer { scaleX = squeeze; scaleY = squeeze }
            .semantics(mergeDescendants = true) { onClick(label = "Battery history", action = null) }) {
        Column(
            Modifier.padding(vertical = 16.dp, horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Surface(shape = CircleShape, color = accent, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) { icon(onAccent) }
            }
            RollingText(value, MaterialTheme.typography.headlineSmallEmphasized, color = if (low) cs.error else cs.onSurface)
            Text(label, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, maxLines = 1,
                autoSize = fitText(MaterialTheme.typography.labelMedium))
        }
    }
}

/**
 * With the largest font sizes a one-line label shrinks just enough to fit instead of being cut;
 * at normal sizes it keeps [style]'s size exactly.
 */
private fun fitText(style: androidx.compose.ui.text.TextStyle) =
    androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 7.sp, maxFontSize = style.fontSize, stepSize = 0.5.sp)

/** The soft fade above the floating dock, where the page is allowed to run under it. */
private val DOCK_FADE = 28.dp

/** The page around the rings waits for the intro and comes in last (see [Intro]). */
private fun Modifier.introFade() = graphicsLayer {
    val a = Intro.content
    alpha = a
    translationY = (1 - a) * 16.dp.toPx()
}
