@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package ua.olex.sonyanc

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---- Device info -------------------------------------------------------------------------

@Composable
fun DeviceInfoContent(
    deviceName: String, status: Headphones.Status, codec: CodecProtocol.State?, firmware: String?,
    refreshing: Boolean, onBack: () -> Unit = {}, onRefresh: () -> Unit = {}, onBluetooth: () -> Unit = {},
    onDiagnostics: () -> Unit = {}, onTurnOff: () -> Unit = {},
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
        ListGroup {
            item { InfoRow(it, Glyph.EARBUDS, deviceName, statusText(status)) }
            item { InfoRow(it, Glyph.EQ, "Audio codec", if (connected) codec?.label ?: if (refreshing) "Reading…" else "Not reported" else "—") }
            item { InfoRow(it, Glyph.INFO, "Firmware", if (connected) firmware ?: "Reading…" else "—") }
        }
        ListGroup {
            item { NavRow(it, Glyph.BLUETOOTH, "Android Bluetooth settings", "Pairing, multipoint, spatial audio, LE Audio", onClick = onBluetooth) }
            item { NavRow(it, Glyph.BUG, "Diagnostics", "Connection log for troubleshooting", onClick = onDiagnostics) }
        }
        if (connected) ListGroup {
            item { NavRow(it, Glyph.OFF, "Turn off earbuds", onClick = { confirmOff = true }) }
        }
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
    canAddTile: Boolean, canPinWidget: Boolean, version: String,
    onBack: () -> Unit = {}, onSelect: (PairedDevice) -> Unit = {}, onRename: () -> Unit = {},
    onDynamicColor: (Boolean) -> Unit = {}, onHaptics: (Boolean) -> Unit = {},
    onAddTile: () -> Unit = {}, onPinWidget: () -> Unit = {}, onBluetooth: () -> Unit = {}, onDiagnostics: () -> Unit = {},
) {
    PageScaffold("App settings", onBack) {
        ListGroup("Earbuds") {
            devices.forEach { device ->
                item {
                    NavRow(it, Glyph.EARBUDS, device.name, if (device.address == selected) "Selected" else "Paired",
                        trailing = { if (device.address == selected) HushIcon(Glyph.CHECK, color = MaterialTheme.colorScheme.primary) },
                        onClick = { onSelect(device) })
                }
            }
            if (devices.isEmpty()) item { NavRow(it, Glyph.BLUETOOTH, "Pair your WF-1000XM5", "Open Android Bluetooth settings", onClick = onBluetooth) }
            item { NavRow(it, Glyph.EDIT, "Name", displayName, onClick = onRename) }
        }
        ListGroup("Appearance") {
            item { SwitchRow(it, Glyph.PALETTE, "Wallpaper colors", "Match the colors of your wallpaper", dynamicColor, onChange = onDynamicColor) }
            item { SwitchRow(it, Glyph.HAPTICS, "Haptics", "Vibrate lightly when you change modes", haptics, onChange = onHaptics) }
        }
        if (canAddTile || canPinWidget) ListGroup("Shortcuts") {
            if (canAddTile) item { NavRow(it, Glyph.QUIET, "Add Quick Settings tile", "Cycle noise control from the notification shade", onClick = onAddTile) }
            if (canPinWidget) item { NavRow(it, Glyph.WIDGET, "Add home screen widget", "Noise control on your home screen", onClick = onPinWidget) }
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
            "Commands sent to the earbuds (→) and their replies (←). Close Sony Sound Connect if it holds the control connection.",
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
