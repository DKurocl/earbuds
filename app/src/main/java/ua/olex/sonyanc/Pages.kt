@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package ua.olex.sonyanc

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
    onFind: () -> Unit = {}, hdAudioOff: Boolean = false,
) {
    var confirmOff by remember { mutableStateOf(false) }
    val connected = status == Headphones.Status.CONNECTED
    if (confirmOff) AlertDialog(
        onDismissRequest = { confirmOff = false },
        title = { Text("Turn off earbuds?") },
        text = { Text("Put them in the case and take them out again to turn them back on.") },
        confirmButton = { TextButton(onClick = { confirmOff = false; onTurnOff() }) { Text("Turn off") } },
        dismissButton = { TextButton(onClick = { confirmOff = false }) { Text("Cancel") } },
    )
    PageScaffold("Device info", onBack, actions = {
        if (refreshing) LoadingIndicator(Modifier.padding(end = 12.dp).size(32.dp))
        else IconButton(onClick = onRefresh, enabled = status != Headphones.Status.CONNECTING, modifier = Modifier.semantics { contentDescription = "Refresh" }) { HushIcon(Glyph.REFRESH) }
    }) {
        DeviceHero(deviceName, statusText(status))
        ListGroup {
            item { InfoRow(it, Glyph.EQ, "Audio codec", if (connected) codec?.label ?: if (refreshing) "Reading…" else "Not reported" else "\u2014") }
            item { InfoRow(it, Glyph.INFO, "Firmware", if (connected) firmware ?: "Reading…" else "\u2014") }
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
                    "Stays connected while the earbuds are out, so changes made on the earbuds show at once and taps are instant",
                    liveLink, onChange = onLiveLink)
            }
            // The sheet can only come up over other apps for a companion app.
            if (liveLink == true) item {
                SwitchRow(it, Glyph.EARBUDS, "Show when connected",
                    "A sheet with the earbuds and their battery comes up when you take them out", connectSheet, onChange = onConnectSheet)
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
            item {
                ChoiceRow(it, Glyph.TIMER, "Ambient for a moment", AmbientTimer.CHOICES.map { m -> m to "$m minutes, then back" }, ambientMinutes) { m ->
                    onAmbientMinutes(m)
                }
            }
            item {
                SwitchRow(it, Glyph.BATTERY, "Case battery reminder",
                    "When the case is at ${CaseReminder.THRESHOLD} % or less as you take the earbuds out or put them away", caseReminder, onChange = onCaseReminder)
            }
            item {
                SwitchRow(it, Glyph.EARBUDS, "Low earbud battery",
                    "A heads-up at ${LowBattery.THRESHOLD} % while you listen, with the time left", budsReminder, onChange = onBudsReminder)
            }
        }
        ListGroup("Appearance") {
            item { SwitchRow(it, Glyph.PALETTE, "Wallpaper colors", "Match the colors of your wallpaper", dynamicColor, onChange = onDynamicColor) }
            item { SwitchRow(it, Glyph.HAPTICS, "Haptics", "A tap when you choose, a pulse when the earbuds confirm", haptics, onChange = onHaptics) }
            if (haptics) item { NavRow(it, Glyph.TUNE, "Vibrations", "How each answer feels: try and choose", onClick = onVibrations) }
        }
        if (canAddTile || canPinWidget) ListGroup("Shortcuts") {
            if (canAddTile) item { NavRow(it, Glyph.QUIET, "Add Quick Settings tile", "Cycle noise control from the notification shade", onClick = onAddTile) }
            item {
                ChoiceRow(it, Glyph.REFRESH, "Quick Settings tile cycles", listOf(0 to "ANC → Ambient → Off", 1 to "ANC ↔ Ambient"),
                    if (tileTwoWay) 1 else 0) { i -> onTileTwoWay(i == 1) }
            }
            if (canPinWidget) item { NavRow(it, Glyph.WIDGET, "Add noise control widget", "ANC, Ambient and Off on your home screen", onClick = onPinWidget) }
            if (canPinWidget) item { NavRow(it, Glyph.BATTERY, "Add battery widget", "Left, case and right at a glance", onClick = onPinBatteryWidget) }
        }
        ListGroup("About") {
            item { NavRow(it, Glyph.BUG, "Diagnostics", "Connection log for troubleshooting", onClick = onDiagnostics) }
            item { InfoRow(it, Glyph.INFO, "Version", version) }
        }
    }
}

// ---- Diagnostics -------------------------------------------------------------------------

@Composable
fun DiagnosticsContent(log: List<String>, onBack: () -> Unit = {}, onCopy: () -> Unit = {}, onClear: () -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    PageScaffold("Diagnostics", onBack, actions = {
        IconButton(onClick = onCopy, enabled = log.isNotEmpty(), modifier = Modifier.semantics { contentDescription = "Copy log" }) { HushIcon(Glyph.COPY) }
        IconButton(onClick = onClear, enabled = log.isNotEmpty(), modifier = Modifier.semantics { contentDescription = "Clear log" }) { HushIcon(Glyph.CLEAR) }
    }) {
        Text(
            "Commands sent to the earbuds (→) and their replies (←).",
            style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp),
        )
        Surface(shape = RoundedCornerShape(24.dp), color = Surfaces.card, modifier = Modifier.fillMaxWidth()) {
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
    Surface(shape = RoundedCornerShape(24.dp), color = cs.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
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
