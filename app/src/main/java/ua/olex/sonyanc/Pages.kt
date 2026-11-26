@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package ua.olex.sonyanc

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---- Device info -------------------------------------------------------------------------

@Composable
fun DeviceInfoContent(
    deviceName: String, status: Headphones.Status, codec: CodecProtocol.State?, firmware: String?,
    refreshing: Boolean, onBack: () -> Unit = {}, onRefresh: () -> Unit = {}, onBluetooth: () -> Unit = {},
    onTurnOff: () -> Unit = {}, ldacBlocked: Boolean = false, onPreferQuality: () -> Unit = {},
    onFind: () -> Unit = {}, hdAudioOff: Boolean = false, functions: Set<Int>? = null,
) {
    var confirmOff by remember { mutableStateOf(false) }
    var showFunctions by remember { mutableStateOf(false) }
    if (showFunctions && functions != null) FunctionsDialog(functions) { showFunctions = false }
    val connected = status == Headphones.Status.CONNECTED
    if (confirmOff) PowerOffDialog({ confirmOff = false }) { confirmOff = false; onTurnOff() }
    PageScaffold("Device info", onBack, actions = {
        HeaderActionButton(HeaderAction(Glyph.REFRESH, "Refresh", {
            if (!refreshing && status != Headphones.Status.CONNECTING) onRefresh()
        }))
    }) {
        DeviceHero(deviceName, statusText(status))
        ListGroup {
            item { InfoRow(it, Glyph.EQ, "Audio codec", codec?.label ?: "Not reported") }
            item { InfoRow(it, Glyph.INFO, "Firmware", firmware ?: "Not reported") }
            if (functions != null) item {
                NavRow(it, Glyph.INSIGHTS, "Earbud functions", "${functions.size} reported by the earbuds", onClick = { showFunctions = true })
            }
        }
        if (ldacBlocked) HintCard(
            "LDAC is turned off by a setting",
            "Bluetooth quality is set to Stable connection, so the earbuds won't use LDAC. Prefer sound quality if your phone stays close.",
            "Prefer sound quality", onPreferQuality,
        )
        // The earbuds allow LDAC but Android picked another codec: Android's own HD audio switch.
        else if (hdAudioOff) HintCard(
            "Android is using ${codec?.label ?: "another codec"}",
            "The earbuds are set for sound quality. Turn on HD audio for $deviceName in Android's Bluetooth settings to use LDAC.",
            "Bluetooth settings", onBluetooth,
        )
        ListGroup {
            item { NavRow(it, Glyph.FIND, "Find earbuds", "Location and ring, in Find Hub", onClick = onFind) }
            item { NavRow(it, Glyph.BLUETOOTH, "Android Bluetooth settings", "Pairing and spatial audio", onClick = onBluetooth) }
        }
        if (connected) ListGroup {
            // Can't be undone from the phone: shown as a destructive action, like Android's own.
            item { NavRow(it, Glyph.POWER, "Turn off earbuds", danger = true, onClick = { confirmOff = true }) }
        }
    }
}

@Composable
internal fun PowerOffDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Turn off earbuds?") },
        text = { Text("Put them in the case and take them out again to turn them back on.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Turn off") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Pixel's device details header: the owner's earbuds and case (the product render), name and state. */
@Composable
private fun DeviceHero(name: String, status: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.device_bud_left), null, Modifier.size(64.dp))
            androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.device_case), null, Modifier.size(width = 124.dp, height = 80.dp))
            androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.device_bud_right), null, Modifier.size(64.dp))
        }
        Text(name, style = MaterialTheme.typography.headlineSmallEmphasized, modifier = Modifier.padding(top = 8.dp))
        Text(status, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

fun statusText(status: Headphones.Status) = when (status) {
    Headphones.Status.CONNECTED -> "Connected"
    Headphones.Status.CONNECTING -> "Connecting…"
    Headphones.Status.FAILED -> "Not connected"
    Headphones.Status.DISCONNECTED -> "Disconnected"
}

// ---- App settings ------------------------------------------------------------------------

data class PairedDevice(val address: String, val name: String)

@Composable
fun AppSettingsContent(
    devices: List<PairedDevice>, selected: String?, displayName: String, dynamicColor: Boolean, haptics: Boolean,
    canAddTile: Boolean, canPinWidget: Boolean, version: String, onConnect: OnConnect = OnConnect.KEEP,
    onBack: () -> Unit = {}, onSelect: (PairedDevice) -> Unit = {}, onRename: () -> Unit = {},
    onDynamicColor: (Boolean) -> Unit = {}, onHaptics: (Boolean) -> Unit = {},
    onAddTile: () -> Unit = {}, onPinWidget: () -> Unit = {}, onBluetooth: () -> Unit = {}, onDiagnostics: () -> Unit = {},
    onPinBatteryWidget: () -> Unit = {}, onOnConnect: (OnConnect) -> Unit = {},
    caseReminder: Boolean = false, onCaseReminder: (Boolean) -> Unit = {},
    budsReminder: Boolean = false, onBudsReminder: (Boolean) -> Unit = {},
    homeMode: OnConnect? = null, onHomeMode: (OnConnect?) -> Unit = {},
    homeSummary: String = "Not set", onSetHome: () -> Unit = {},
    tileTwoWay: Boolean = false, onTileTwoWay: (Boolean) -> Unit = {},
    ambientMinutes: Int = 5, onAmbientMinutes: (Int) -> Unit = {}, onVibrations: () -> Unit = {},
    liveLink: Boolean? = null, onLiveLink: (Boolean) -> Unit = {},
    connectSheet: Boolean = true, onConnectSheet: (Boolean) -> Unit = {},
    qualityByPlace: Boolean = false, onQualityByPlace: (Boolean) -> Unit = {},
    afterSleep: AfterSleep = AfterSleep.POWER_OFF, onAfterSleep: (AfterSleep) -> Unit = {},
    chargeReminder: Boolean = false, onChargeReminder: (Boolean) -> Unit = {},
    activityMode: Boolean = false, onActivityMode: (Boolean) -> Unit = {},
    weekendMode: OnConnect? = null, onWeekendMode: (OnConnect?) -> Unit = {},
    onProfiles: (() -> Unit)? = null, onBackup: (() -> Unit)? = null,
) {
    PageScaffold("App settings", onBack) {
        ListGroup("Earbuds") {
            // One pair of earbuds: the picker only appears when there is something to choose.
            if (devices.size > 1 || devices.none { it.address == selected }) devices.forEach { device ->
                item {
                    NavRow(it, Glyph.EARBUDS, device.name, if (device.address == selected) "Selected" else "Paired",
                        trailing = { if (device.address == selected) HushIcon(Glyph.CHECK, color = MaterialTheme.colorScheme.primary) },
                        onClick = { onSelect(device) })
                }
            }
            if (devices.isEmpty()) item { NavRow(it, Glyph.BLUETOOTH, "Pair your WF-1000XM5", "Open Android Bluetooth settings", onClick = onBluetooth) }
            item { NavRow(it, Glyph.EDIT, "Name", displayName, onClick = onRename) }
            // Android's companion link; absent where presence events are not available.
            if (liveLink != null) item {
                SwitchRow(it, Glyph.LINK, "Stay in sync",
                    "Keeps controls in sync while the earbuds are out",
                    liveLink, onChange = onLiveLink)
            }
            // The sheet can only come up over other apps for a companion app.
            if (liveLink == true) item {
                SwitchRow(it, Glyph.EARBUDS, "Show when connected",
                    "Show battery levels when you take the earbuds out", connectSheet, onChange = onConnectSheet)
            }
            item {
                ChoiceRow(it, Glyph.LINK, "When earbuds connect", OnConnect.entries.map { c -> c.ordinal to c.label }, onConnect.ordinal) { i ->
                    onOnConnect(OnConnect.entries[i])
                }
            }
            item {
                ChoiceRow(it, Glyph.HOME, "At home", listOf(-1 to "Same as everywhere") + OnConnect.entries.map { c -> c.ordinal to c.label },
                    homeMode?.ordinal ?: -1) { i -> onHomeMode(OnConnect.entries.getOrNull(i)) }
            }
            if (homeMode != null) item { NavRow(it, Glyph.LOCATION, "Home", homeSummary, onClick = onSetHome) }
            if (homeMode != null) item {
                SwitchRow(it, Glyph.HQ, "Best sound at home", "LDAC at home, a steadier link when you're out", qualityByPlace, onChange = onQualityByPlace)
            }
        }
        ListGroup("Timers & alerts") {
            item {
                ChoiceRow(it, Glyph.TIMER, "Ambient for a moment", AmbientTimer.CHOICES.map { m -> m to "$m minutes, then back" }, ambientMinutes) { m ->
                    onAmbientMinutes(m)
                }
            }
            item {
                SwitchRow(it, Glyph.BATTERY, "Case battery reminder",
                    "At ${CaseReminder.THRESHOLD}% or less when you open or put away the earbuds", caseReminder, onChange = onCaseReminder)
            }
            item {
                SwitchRow(it, Glyph.EARBUDS, "Low earbud battery",
                    "At ${LowBattery.THRESHOLD}% while listening, with time left", budsReminder, onChange = onBudsReminder)
            }
            item {
                SwitchRow(it, Glyph.UPCOMING, "Charge before you go",
                    "Before the time you usually take the earbuds out, if the case is under ${ChargeReminder.THRESHOLD} %", chargeReminder, onChange = onChargeReminder)
            }
        }
        ListGroup("Automatic") {
            item {
                ChoiceRow(it, Glyph.UPCOMING, "Weekend on connect",
                    listOf(-1 to "Use the usual setting") + OnConnect.entries.map { mode -> mode.ordinal to mode.label },
                    weekendMode?.ordinal ?: -1) { index -> onWeekendMode(OnConnect.entries.getOrNull(index)) }
            }
            item {
                SwitchRow(it, Glyph.WALK, "Ambient while walking",
                    if (liveLink == false) "Requires Stay in sync. Uses steps only while connected."
                    else "Ambient when walking, noise cancelling when stopped", activityMode, onChange = onActivityMode)
            }
            item {
                ChoiceRow(it, Glyph.BEDTIME, "When Music's sleep timer ends", AfterSleep.entries.map { a -> a.ordinal to a.label }, afterSleep.ordinal) { i ->
                    onAfterSleep(AfterSleep.entries[i])
                }
            }
        }
        ListGroup("Appearance") {
            item { SwitchRow(it, Glyph.PALETTE, "Wallpaper colors", "Use your wallpaper palette in the app", dynamicColor, onChange = onDynamicColor) }
            item { SwitchRow(it, Glyph.HAPTICS, "Haptics", "Feedback on tap and confirmation", haptics, onChange = onHaptics) }
            if (haptics) item { NavRow(it, Glyph.TUNE, "Vibrations", "Preview confirmation patterns", onClick = onVibrations) }
        }
        if (canAddTile || canPinWidget) ListGroup("System shortcuts") {
            if (canAddTile) item { NavRow(it, Glyph.QUIET, "Add Quick Settings tile", "Cycle noise control from the notification shade", onClick = onAddTile) }
            item {
                ChoiceRow(it, Glyph.REFRESH, "Quick Settings tile cycles", listOf(0 to "ANC → Ambient → Off", 1 to "ANC ↔ Ambient"),
                    if (tileTwoWay) 1 else 0) { i -> onTileTwoWay(i == 1) }
            }
            if (canPinWidget) item { NavRow(it, Glyph.WIDGET, "Add noise control widget", "ANC, Ambient and Off on your home screen", onClick = onPinWidget) }
            if (canPinWidget) item { NavRow(it, Glyph.BATTERY, "Add battery widget", "Left, right and case charge", onClick = onPinBatteryWidget) }
        }
        if (onProfiles != null || onBackup != null) ListGroup("Personal settings") {
            if (onProfiles != null) item { NavRow(it, Glyph.TUNE, "Personal profiles", "Save and recall noise control and EQ", onClick = onProfiles) }
            if (onBackup != null) item { NavRow(it, Glyph.COPY, "Backup & restore", "Export or import portable settings", onClick = onBackup) }
        }
        ListGroup("About") {
            item { NavRow(it, Glyph.BUG, "Diagnostics", "Connection log for troubleshooting", onClick = onDiagnostics) }
            item { InfoRow(it, Glyph.INFO, "Version", version) }
        }
    }
}

// ---- Diagnostics -------------------------------------------------------------------------

@Composable
internal fun DiagnosticsContent(
    log: List<String>, onBack: () -> Unit = {}, onCopy: () -> Unit = {}, onClear: () -> Unit = {},
    checking: Boolean = false, result: String? = null, onCheck: (() -> Unit)? = null,
    timings: Map<CommandScheduler.Priority, List<CommandScheduler.Timing>> = emptyMap(),
) {
    val cs = MaterialTheme.colorScheme
    PageScaffold("Diagnostics", onBack, actions = {
        HeaderActionPair(
            HeaderAction(Glyph.COPY, "Copy log", onCopy, log.isNotEmpty()),
            HeaderAction(Glyph.CLEAR, "Clear log", onClear, log.isNotEmpty()),
        )
    }) {
        if (onCheck != null) ListGroup("Connection check") {
            item {
                NavRow(it, Glyph.BLUETOOTH, if (checking) "Checking connection…" else "Run connection check",
                    result ?: "Control link, noise control, codec and battery",
                    enabled = !checking, onClick = onCheck)
            }
        }
        if (timings.isNotEmpty()) ListGroup("Bluetooth command timing") {
            for (priority in CommandScheduler.Priority.entries) {
                val samples = timings[priority].orEmpty()
                if (samples.isEmpty()) continue
                val wire = samples.map { it.wireMs }.sorted()
                val queue = samples.map { it.queueMs }.sorted()
                val p95 = (samples.size * 95 + 99) / 100 - 1
                item {
                    InfoRow(it, Glyph.TIMER, priority.label,
                        "Queue p95 ${queue[p95]} ms · Wire p95 ${wire[p95]} ms · ${samples.size} samples")
                }
            }
        }
        Text(
            "Commands sent to the earbuds (→) and their replies (←).",
            style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp),
        )
        Surface(shape = UiTokens.compactCardShape, color = Surfaces.card, modifier = Modifier.fillMaxWidth()) {
            Text(
                log.joinToString("\n").ifEmpty { "No messages yet" },
                fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp,
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(16.dp),
            )
        }
    }
}

/** A quiet suggestion with one action, in the secondary container (Pixel's settings tips). */
@Composable
private fun HintCard(title: String, body: String, action: String, onAction: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = UiTokens.compactCardShape, color = cs.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = cs.onSecondaryContainer)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = cs.onSecondaryContainer)
            TextButton(onClick = onAction, modifier = Modifier.align(androidx.compose.ui.Alignment.End)) { Text(action) }
        }
    }
}

// ---- Vibrations -----------------------------------------------------------------------------

/**
 * The haptics lab: every cue with its three takes. Tapping a take plays it and makes it the one
 * used everywhere. [shaped] says whether this phone plays the shaped (envelope) versions.
 */
@Composable
fun VibrationsContent(chosen: Map<Haptics.Cue, Int>, shaped: Boolean, onBack: () -> Unit = {}, onTry: (Haptics.Cue, Int) -> Unit = { _, _ -> }) {
    val cs = MaterialTheme.colorScheme
    PageScaffold("Vibrations", onBack) {
        Text(
            if (shaped) "Shaped for this phone's vibration motor. Tap a take to feel it; the last one you tap is used."
            else "Tap a take to feel it; the last one you tap is used.",
            style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp),
        )
        Haptics.Cue.entries.forEach { cue ->
            ListGroup(cue.title) {
                item { shape ->
                    Surface(shape = shape.shape, color = Surfaces.card, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(cue.meaning, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                            val takes = Haptics.takes.getValue(cue)
                            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                                takes.forEachIndexed { i, take ->
                                    ToggleButton(
                                        checked = chosen[cue] == i, onCheckedChange = { onTry(cue, i) },
                                        shapes = when (i) {
                                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                            takes.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                                        },
                                        colors = ToggleButtonDefaults.colors(containerColor = cs.surfaceContainerHighest),
                                        contentPadding = PaddingValues(horizontal = 8.dp),
                                        modifier = Modifier.weight(1f).semantics { role = Role.RadioButton; selected = chosen[cue] == i },
                                    ) { Text(take.name, maxLines = 1, style = MaterialTheme.typography.labelLarge) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
