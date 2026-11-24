package ua.olex.sonyanc

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

@Composable
internal fun PersonalProfilesContent(
    profiles: Map<Int, PersonalProfile?>,
    device: DeviceState,
    busy: Boolean,
    onBack: () -> Unit,
    onSave: (Int) -> Unit,
    onApply: (Int, PersonalProfile) -> Unit,
) {
    var replacing by remember { mutableIntStateOf(0) }
    if (replacing != 0) AlertDialog(
        onDismissRequest = { replacing = 0 },
        title = { Text("Replace profile $replacing?") },
        text = { Text("Save the earbuds' current settings in this slot.") },
        confirmButton = { TextButton(onClick = { onSave(replacing); replacing = 0 }) { Text("Replace") } },
        dismissButton = { TextButton(onClick = { replacing = 0 }) { Text("Cancel") } },
    )
    PageScaffold("Personal profiles", onBack) {
        (1..2).forEach { slot ->
            val profile = profiles[slot]
            ListGroup("Profile $slot") {
                if (profile != null) {
                    val parts = listOfNotNull(profile.noise.mode.label, profile.equalizer?.let { EqualizerProtocol.presets[it.preset] }) +
                        profile.features.map { (feature, enabled) -> "${feature.title} ${if (enabled) "on" else "off"}" }
                    item {
                        NavRow(it, Glyph.TUNE, if (busy) "Applying…" else "Apply profile",
                            parts.joinToString(" · "), enabled = !busy, onClick = { onApply(slot, profile) })
                    }
                }
                item {
                    NavRow(it, Glyph.COPY, if (profile == null) "Save current settings" else "Replace with current settings",
                        enabled = !busy && device.ncConfirmed && device.pending == null,
                        onClick = { if (profile == null) onSave(slot) else replacing = slot })
                }
            }
        }
    }
}

@Composable
internal fun BackupContent(onBack: () -> Unit, export: () -> Unit, restore: () -> Unit) {
    PageScaffold("Backup & restore", onBack) {
        ListGroup {
            item { NavRow(it, Glyph.COPY, "Export settings", "Save profiles, routines, and vibration choices to a JSON file", onClick = export) }
            item { NavRow(it, Glyph.REFRESH, "Restore settings", "Choose an Earbuds backup file", onClick = restore) }
        }
    }
}
