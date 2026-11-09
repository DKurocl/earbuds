package ua.olex.sonyanc

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/** Official Material icons; the release shrinker retains only referenced vectors. */
enum class Glyph { QUIET, AMBIENT, OFF, TUNE, BACK, REFRESH, CHECK, BLUETOOTH, WIDGET, COPY, EARBUDS, PALETTE, HAPTICS, INFO, EQ, SETTINGS, EDIT, BUG, CLEAR, ADD, FIND, BATTERY, LINK }

@Composable
fun HushIcon(glyph: Glyph, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    val vector = when (glyph) {
        Glyph.QUIET -> Icons.Rounded.NoiseControlOff
        Glyph.AMBIENT -> Icons.Rounded.Hearing
        Glyph.OFF -> Icons.Rounded.PowerSettingsNew
        Glyph.TUNE -> Icons.Rounded.Tune
        Glyph.BACK -> Icons.AutoMirrored.Rounded.ArrowBack
        Glyph.REFRESH -> Icons.Rounded.Refresh
        Glyph.CHECK -> Icons.Rounded.Check
        Glyph.BLUETOOTH -> Icons.Rounded.Bluetooth
        Glyph.WIDGET -> Icons.Rounded.Widgets
        Glyph.COPY -> Icons.Rounded.ContentCopy
        Glyph.EARBUDS -> Icons.Rounded.Earbuds
        Glyph.PALETTE -> Icons.Rounded.Palette
        Glyph.HAPTICS -> Icons.Rounded.Vibration
        Glyph.INFO -> Icons.Rounded.Info
        Glyph.EQ -> Icons.Rounded.GraphicEq
        Glyph.SETTINGS -> Icons.Rounded.Settings
        Glyph.EDIT -> Icons.Rounded.Edit
        Glyph.BUG -> Icons.Rounded.BugReport
        Glyph.CLEAR -> Icons.Rounded.DeleteSweep
        Glyph.ADD -> Icons.Rounded.Add
        Glyph.FIND -> Icons.Rounded.LocationOn
        Glyph.BATTERY -> Icons.Rounded.BatteryFull
        Glyph.LINK -> Icons.Rounded.BluetoothConnected
    }
    Icon(vector,contentDescription=null,modifier=modifier.size(24.dp),tint=color)
}

