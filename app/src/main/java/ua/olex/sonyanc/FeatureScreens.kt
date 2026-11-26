@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package ua.olex.sonyanc

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

// ---- Listening level ----------------------------------------------------------------------

internal fun minutesText(minutes: Double): String {
    val m = minutes.roundToInt()
    return when {
        m < 1 -> "No listening yet"
        m < 60 -> "$m min"
        m % 60 == 0 -> "${m / 60} h"
        else -> "${m / 60} h ${m % 60} min"
    }
}

/** The Home row's summary: the week's share of the allowance, or why there is none yet. */
internal fun listeningSummary(state: SafeListening.State, reference: SafeListening.Reference, on: Boolean): String = when {
    !on -> "Off"
    state.summary == null -> "Measured by your earbuds"
    else -> "${(state.summary.dose(reference) * 100).roundToInt()}% of this week's limit"
}

private fun liveText(live: SafeListening.Live?, connected: Boolean): String = when {
    !connected -> "Connect the earbuds to measure"
    live == null -> "Measuring"
    live.db != null -> "${live.db} dB"
    live.reason == 1 -> "Paused during calls"
    live.reason == 2 -> "Put in your earbuds"
    else -> "Play something to measure"
}

@Composable
fun ListeningLevelScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val state by SafeListening.state.collectAsStateWithLifecycle()
    val device by Headphones.state.collectAsStateWithLifecycle()
    var on by remember { mutableStateOf(SafeListening.enabled(ctx)) }
    var reference by remember { mutableStateOf(SafeListening.reference(ctx)) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val connected = device.status == Headphones.Status.CONNECTED
    // The live level only while this page is on screen, at the pace the earbuds ask for.
    LaunchedEffect(on, connected, state.type) {
        if (!on || !connected || state.type == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            SafeListening.sync(ctx)
            while (true) {
                SafeListening.readLive()
                delay(SafeListening.state.value.intervalSeconds * 1000L)
            }
        }
    }
    ListeningLevelContent(state, connected, on, reference, onBack,
        onRefresh = { scope.launch { SafeListening.sync(ctx, force = true) } },
        onEnabled = { value ->
            on = value
            SafeListening.setEnabled(ctx, value)
            if (value) scope.launch { SafeListening.sync(ctx, force = true) }
        },
        onReference = { reference = it; SafeListening.setReference(ctx, it) })
}

@Composable
internal fun ListeningLevelContent(
    state: SafeListening.State, connected: Boolean, on: Boolean, reference: SafeListening.Reference,
    onBack: () -> Unit = {}, onRefresh: () -> Unit = {}, onEnabled: (Boolean) -> Unit = {}, onReference: (SafeListening.Reference) -> Unit = {},
) {
    PageScaffold("Listening level", onBack, actions = {
        HeaderActionButton(HeaderAction(Glyph.REFRESH, "Refresh", onRefresh, enabled = on && connected && !state.syncing))
    }) {
        val summary = state.summary
        MainSwitchBar("Measure listening level", on, onChange = onEnabled)
        WeekCard(summary, reference, on, state.syncing)
        if (on) ListGroup("Now") {
            item {
                InfoRow(it, Glyph.EAR_SOUND, "Sound at your ears",
                    if (state.available == false) "Not available right now" else liveText(state.live, connected))
            }
        }
        if (on && summary != null) DaysCard(summary, reference)
        if (on) ListGroup("Limit") {
            item {
                ChoiceRow(it, Glyph.INSIGHTS, "Weekly limit", SafeListening.Reference.entries.map { r -> r.ordinal to "${r.label}: ${r.detail}" }, reference.ordinal) { i ->
                    onReference(SafeListening.Reference.entries[i])
                }
            }
        }
        Text(
            "Your earbuds measure the sound that reaches your ears. The limit follows the WHO and ITU safe listening standard (H.870); left and right are averaged.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}

/** The week at a glance: the share of the limit, big, with a wavy bar, as Google's wellbeing cards do. */
@Composable
private fun WeekCard(summary: SafeListening.Summary?, reference: SafeListening.Reference, on: Boolean, syncing: Boolean) {
    val cs = MaterialTheme.colorScheme
    val dose = summary?.dose(reference)
    val over = (dose ?: 0.0) >= 1.0
    Surface(shape = RoundedCornerShape(UiTokens.cardRadius), color = if (over) cs.errorContainer else cs.primaryContainer,
        contentColor = if (over) cs.onErrorContainer else cs.onPrimaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("This week", style = MaterialTheme.typography.titleMedium)
            Text(
                when { !on -> "Off"; dose == null -> if (syncing) "Reading" else "No data yet"; else -> "${(dose * 100).roundToInt()}%" },
                style = MaterialTheme.typography.displayMedium,
                modifier = Modifier.semantics { contentDescription = if (dose == null) "No data" else "${(dose * 100).roundToInt()} percent of the weekly limit" },
            )
            if (on && dose != null) {
                Text(if (over) "Above this week's limit. Lower the volume for the next days." else "of this week's limit",
                    style = MaterialTheme.typography.bodyLarge)
                LinearWavyProgressIndicator(
                    progress = { dose.toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(),
                    color = if (over) cs.error else cs.primary, trackColor = (if (over) cs.onErrorContainer else cs.onPrimaryContainer).copy(alpha = .18f),
                )
                Text(
                    listOfNotNull(summary.averageDb?.let { "Average ${it.roundToInt()} dB" }, minutesText(summary.minutes), reference.label).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else if (on && syncing) {
                LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth(), color = cs.primary)
            } else if (on) {
                Text("Listen for a while with the earbuds in. The log fills in hour by hour.", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Seven days as rounded bars: the share of a day's part of the limit (1/7 of the week). */
@Composable
private fun DaysCard(summary: SafeListening.Summary, reference: SafeListening.Reference) {
    val cs = MaterialTheme.colorScheme
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val day = remember(locale) { SimpleDateFormat("EEE", locale) }
    val shares = summary.days.map { SafeListening.dose(it.pressure, reference, days = 1) }
    val top = maxOf(1.0, shares.maxOrNull() ?: 0.0)
    Surface(shape = RoundedCornerShape(UiTokens.cardRadius), color = Surfaces.card, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Last 7 days", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth().height(140.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                summary.days.forEachIndexed { i, d ->
                    val share = shares[i]
                    val today = i == summary.days.lastIndex
                    Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                        val barColor = when { share >= 1.0 -> cs.error; today -> cs.primary; else -> cs.primary.copy(alpha = .55f) }
                        val track = cs.surfaceContainerHighest
                        Canvas(Modifier.weight(1f).width(20.dp).semantics {
                            contentDescription = "${day.format(d.start)}: ${(share * 100).roundToInt()} percent of a day's limit"
                        }) {
                            val r = CornerRadius(size.width / 2, size.width / 2)
                            drawRoundRect(track, cornerRadius = r)
                            val h = (size.height * (share / top)).toFloat().coerceIn(if (share > 0) size.width else 0f, size.height)
                            if (h > 0) drawRoundRect(barColor, topLeft = Offset(0f, size.height - h), size = Size(size.width, h), cornerRadius = r)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(day.format(d.start), style = MaterialTheme.typography.labelMedium,
                            color = if (today) cs.primary else cs.onSurfaceVariant)
                    }
                }
            }
            Text("A full bar is a day's share of the weekly limit", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}

// ---- Ear tip fit ----------------------------------------------------------------------------

@Composable
fun FitTestScreen(onBack: () -> Unit) {
    val state by FitTest.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) { FitTest.reset(); onDispose { Headphones.scope.launch { FitTest.stop() } } }
    FitTestContent(state, onBack, onStart = { scope.launch { FitTest.run() } }, onCancel = { scope.launch { FitTest.stop() } })
}

@Composable
internal fun FitTestContent(state: FitTest.State, onBack: () -> Unit = {}, onStart: () -> Unit = {}, onCancel: () -> Unit = {}) {
    val busy = state.phase == FitTest.Phase.PREPARING || state.phase == FitTest.Phase.MEASURING
    PageScaffold("Ear tip fit", onBack) {
        Text(
            when (state.phase) {
                FitTest.Phase.IDLE -> "Check the seal"
                FitTest.Phase.PREPARING, FitTest.Phase.MEASURING -> "Checking seal"
                FitTest.Phase.DONE -> if (state.left == true && state.right == true) "Good seal" else "Try another tip size"
                FitTest.Phase.FAILED -> "Couldn't check"
            },
            style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 4.dp),
        )
        Text(
            when (state.phase) {
                FitTest.Phase.IDLE -> "Put in both earbuds and stay still and quiet. They'll play a short sound and check how well each tip seals."
                FitTest.Phase.PREPARING, FitTest.Phase.MEASURING -> "This takes a few seconds. Try to stay still."
                FitTest.Phase.DONE -> if (state.left == true && state.right == true) "Both tips seal well, so noise cancelling and bass work as intended."
                    else "A tip that doesn't seal lets noise in and loses bass. Try the next size up, or reseat the earbud, then check again."
                FitTest.Phase.FAILED -> state.error ?: "The test didn't finish"
            },
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FitSide("Left", R.drawable.device_bud_left, state.left, busy, Modifier.weight(1f))
            FitSide("Right", R.drawable.device_bud_right, state.right, busy, Modifier.weight(1f))
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (busy) OutlinedButton(onClick = onCancel, modifier = Modifier.heightIn(min = 56.dp)) { Text("Cancel") }
            else Button(onClick = onStart, modifier = Modifier.heightIn(min = 56.dp)) {
                Text(if (state.phase == FitTest.Phase.IDLE) "Start" else "Check again")
            }
        }
    }
}

@Composable
private fun FitSide(label: String, picture: Int, good: Boolean?, busy: Boolean, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val (container, content) = when (good) {
        true -> cs.primaryContainer to cs.onPrimaryContainer
        false -> cs.errorContainer to cs.onErrorContainer
        null -> Surfaces.card to cs.onSurface
    }
    Surface(shape = RoundedCornerShape(UiTokens.cardRadius), color = container, contentColor = content, modifier = modifier) {
        Column(Modifier.padding(vertical = 24.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // The earbud itself, as Pixel Buds shows it; a wavy ring while measuring, the verdict as a badge after.
            Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
                if (busy) CircularWavyProgressIndicator(Modifier.fillMaxSize())
                androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(picture), null, Modifier.size(64.dp))
                if (!busy && good != null) Surface(shape = androidx.compose.foundation.shape.CircleShape, color = content, contentColor = container,
                    modifier = Modifier.align(Alignment.BottomEnd).size(32.dp)) {
                    Box(contentAlignment = Alignment.Center) { HushIcon(if (good) Glyph.CHECK else Glyph.PRIORITY, Modifier.size(20.dp), container) }
                }
            }
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(when (good) { true -> "Good seal"; false -> "Doesn't seal"; null -> if (busy) "Checking" else "Not checked" },
                style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
    }
}

// ---- Connected devices ----------------------------------------------------------------------

@SuppressLint("MissingPermission")
private fun phoneName(ctx: android.content.Context): String? = runCatching {
    ctx.getSystemService(BluetoothManager::class.java)?.adapter?.name
}.getOrNull()

private fun deviceGlyph(name: String): Glyph {
    val n = name.lowercase()
    return when {
        listOf("book", "laptop", "pc", "desktop", "mac", "windows", "thinkpad").any { it in n } -> Glyph.COMPUTER
        listOf("tab", "ipad").any { it in n } -> Glyph.TABLET
        listOf("pixel", "phone", "galaxy", "iphone", "xiaomi", "oneplus", "redmi").any { it in n } -> Glyph.PHONE
        else -> Glyph.DEVICE_OTHER
    }
}

@Composable
fun DevicesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val state by Multipoint.state.collectAsStateWithLifecycle()
    val codes by SupportedFunctions.codes.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val say = LocalMessages.current
    val me = remember { phoneName(ctx) }
    val canSwitch = Multipoint.canSwitch(codes)
    LaunchedEffect(Unit) { launch { Multipoint.follow() }; Multipoint.read() }
    LaunchedEffect(state.error) { state.error?.let(say) }
    DevicesContent(state, me, canSwitch, onBack, onRefresh = { scope.launch { Multipoint.read() } }) { d, action, switch ->
        scope.launch { Multipoint.perform(d, action, switch) }
    }
}

@Composable
internal fun DevicesContent(
    state: Multipoint.State, me: String?, canSwitch: Boolean, onBack: () -> Unit = {}, onRefresh: () -> Unit = {},
    onAction: (Multipoint.Device, Int?, Boolean) -> Unit = { _, _, _ -> },
) {
    var confirm by remember { mutableStateOf<Multipoint.Device?>(null) }
    confirm?.let { d ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Disconnect ${d.name}?") },
            text = { Text("The earbuds stay connected to your other devices.") },
            confirmButton = { TextButton(onClick = { confirm = null; onAction(d, 0, false) }) { Text("Disconnect") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
    PageScaffold("Connected devices", onBack, actions = {
        HeaderActionButton(HeaderAction(Glyph.REFRESH, "Refresh", onRefresh, enabled = !state.reading))
    }) {
        val list = state.list
        if (list == null) {
            Text(if (state.reading) "Reading the earbuds' device list" else "The earbuds didn't share their device list",
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp))
            return@PageScaffold
        }
        val connected = list.devices.filter { it.connected }
        val others = list.devices.filterNot { it.connected }
        if (connected.isNotEmpty()) ListGroup("Connected") {
            connected.forEach { d ->
                item { shape ->
                    val isMe = me != null && d.name == me
                    val playing = d.slot == list.playing
                    NavRow(shape, if (isMe) Glyph.PHONE else deviceGlyph(d.name), d.name,
                        listOfNotNull(if (isMe) "This phone" else null, if (playing) "Playing" else "Connected").joinToString(" · "),
                        enabled = state.busy == null,
                        trailing = {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (state.busy == d.address) CircularWavyProgressIndicator(Modifier.size(24.dp))
                                else {
                                    if (canSwitch && !playing) FilledTonalButton(onClick = { onAction(d, null, true) }) { Text("Play here") }
                                    if (!isMe) FilledTonalIconButton(onClick = { confirm = d }, modifier = Modifier.semantics { contentDescription = "Disconnect ${d.name}" }) {
                                        HushIcon(Glyph.LINK_OFF, Modifier.size(20.dp))
                                    }
                                }
                            }
                        },
                        onClick = { if (!isMe) confirm = d })
                }
            }
        }
        if (others.isNotEmpty()) ListGroup("Paired before") {
            others.forEach { d ->
                item { shape ->
                    NavRow(shape, deviceGlyph(d.name), d.name, "Not connected", enabled = state.busy == null,
                        trailing = {
                            if (state.busy == d.address) CircularWavyProgressIndicator(Modifier.size(24.dp))
                            else TextButton(onClick = { onAction(d, 1, false) }) { Text("Connect") }
                        },
                        onClick = { onAction(d, 1, false) })
                }
            }
        }
        Text("The earbuds connect to two devices at once. Play here moves the sound without disconnecting.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp))
    }
}

// ---- What the earbuds report ----------------------------------------------------------------

@Composable
internal fun FunctionsDialog(codes: Set<Int>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Earbud functions") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${codes.size} functions, as the earbuds report them", color = MaterialTheme.colorScheme.onSurfaceVariant)
                SupportedFunctions.names(codes).forEach { Text(it) }
                val unknown = codes.count { it !in SONY_FUNCTION_NAMES }
                if (unknown > 0) Text("$unknown more without a name yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
