package ua.olex.sonyanc

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ua.olex.sonyanc.SonyProtocol.Mode
import ua.olex.sonyanc.SonyProtocol.NcState

class MainActivity : ComponentActivity() {
    /** Bumped on every onStart so the UI re-syncs when you come back to the app. */
    private val startTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Headphones.init(this)
        enableEdgeToEdge()
        setContent { AppTheme { App(startTick.intValue) } }
    }

    override fun onStart() {
        super.onStart()
        Headphones.hold()
        startTick.intValue++
    }

    override fun onStop() {
        // Socket closes a few seconds after the app leaves the screen.
        Headphones.release()
        super.onStop()
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@SuppressLint("MissingPermission")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(startTick: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val status by Headphones.status.collectAsState()
    val nc by Headphones.nc.collectAsState()
    val battery by Headphones.battery.collectAsState()
    val connected = status == Headphones.Status.CONNECTED

    val permission = if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_CONNECT
                     else Manifest.permission.BLUETOOTH
    var granted by remember { mutableStateOf(Headphones.hasPermission(ctx)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }

    var deviceName by remember { mutableStateOf(Prefs.deviceName(ctx)) }
    var picking by remember { mutableStateOf(Prefs.deviceAddress(ctx) == null) }

    // Connect + read current state whenever the app comes to the foreground.
    LaunchedEffect(startTick, granted, deviceName) {
        if (granted && deviceName != null && !picking) Headphones.connectAndSync()
    }

    fun setNc(s: NcState) { scope.launch { Headphones.apply(s) } }

    Scaffold(
        topBar = {
            LargeTopAppBar(title = {
                Column {
                    Text(deviceName ?: "Sony ANC", fontWeight = FontWeight.Black)
                }
            })
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(horizontal = 16.dp).fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!granted) {
                Button(onClick = { launcher.launch(permission) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text("Дати доступ до Bluetooth")
                }
                return@Column
            }

            // ---- device picker ----
            AnimatedVisibility(picking, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                DevicePicker { dev ->
                    Prefs.saveDevice(ctx, dev.address, dev.name)
                    Headphones.disconnect()
                    deviceName = dev.name
                    picking = false
                    ModeWidget.refreshAll(ctx)
                }
            }
            if (picking) return@Column

            // ---- status + battery ----
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val (label, retry) = when (status) {
                    Headphones.Status.CONNECTED -> "● Підключено" to false
                    Headphones.Status.CONNECTING -> "Підключаю…" to false
                    Headphones.Status.FAILED -> "Не вдалось · повторити" to true
                    Headphones.Status.DISCONNECTED -> "Не підключено · підключити" to true
                }
                Pill(label, highlight = connected,
                    onClick = if (retry) ({ scope.launch { Headphones.connectAndSync() }; Unit }) else null)
                Spacer(Modifier.weight(1f))
                battery?.let { b ->
                    b.left?.let { Pill("L $it%" + if (b.leftCharging) "⚡" else "") }
                    b.right?.let { Pill("R $it%" + if (b.rightCharging) "⚡" else "") }
                    b.case?.let { Pill("Кейс $it%" + if (b.caseCharging) "⚡" else "") }
                }
            }

            // ---- the three modes ----
            Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Mode.entries.forEach { m ->
                    ModeTile(
                        label = m.label,
                        selected = nc?.mode == m,
                        enabled = status != Headphones.Status.CONNECTING,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    ) { setNc((nc ?: NcState(m)).copy(mode = m)) }
                }
            }

            // ---- ambient settings (only in transparency mode) ----
            AnimatedVisibility(nc?.mode == Mode.AMBIENT,
                enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                val cur = nc ?: NcState(Mode.AMBIENT)
                var level by remember(cur.level) { mutableFloatStateOf(cur.level.toFloat()) }
                Surface(shape = RoundedCornerShape(28.dp), tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Рівень прозорості", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Text("${level.toInt()}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = level, onValueChange = { level = it },
                            valueRange = 0f..20f, steps = 19,
                            onValueChangeFinished = { setNc(cur.copy(level = level.toInt())) }
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Фокус на голосі", style = MaterialTheme.typography.titleMedium)
                                Text("Глушить фон, пропускає мову", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = cur.voice, onCheckedChange = { setNc(cur.copy(voice = it)) })
                        }
                    }
                }
            }

            // ---- footer ----
            var diag by remember { mutableStateOf(false) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { picking = true }) { Text("Змінити пристрій") }
                TextButton(onClick = { diag = !diag }) { Text(if (diag) "Сховати діагностику" else "Діагностика") }
            }
            if (diag) Diagnostics(connected)

            Spacer(Modifier.height(24.dp))
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun DevicePicker(onPick: (android.bluetooth.BluetoothDevice) -> Unit) {
    val ctx = LocalContext.current
    val devices = remember { Headphones.bondedDevices(ctx) }
    Surface(shape = RoundedCornerShape(28.dp), tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text("Вибери навушники", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            if (devices.isEmpty()) {
                Text("Немає спарених пристроїв. Спочатку спар навушники в налаштуваннях Bluetooth.",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }
            devices.forEach { d ->
                ListItem(
                    headlineContent = { Text(d.name ?: d.address) },
                    supportingContent = { Text(d.address, fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    modifier = Modifier.clickable { onPick(d) }
                )
            }
        }
    }
}

@Composable
private fun Diagnostics(connected: Boolean) {
    val scope = rememberCoroutineScope()
    val log by Headphones.log.collectAsState()
    var rawHex by remember { mutableStateOf(SonyProtocol.INIT_HEX) }
    var probing by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = rawHex, onValueChange = { rawHex = it },
                label = { Text("Payload (hex)") }, singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                shape = RoundedCornerShape(20.dp), modifier = Modifier.weight(1f)
            )
            FilledTonalButton(
                enabled = connected,
                onClick = { scope.launch { Headphones.sendRaw(SonyProtocol.parseHex(rawHex)) } },
                modifier = Modifier.height(56.dp)
            ) { Text("Send") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = connected && !probing,
                onClick = {
                    scope.launch {
                        probing = true
                        Headphones.clearLog()
                        for (t in 0x00..0x2F) Headphones.sendRaw(byteArrayOf(0x66, t.toByte()))
                        val answers = Headphones.log.value.filter { it.startsWith("←") }
                        Headphones.clearLog()
                        Headphones.addLog("Відповіді (${answers.size}):")
                        answers.forEach { Headphones.addLog(it) }
                        probing = false
                    }
                },
                modifier = Modifier.weight(1f)
            ) { Text(if (probing) "Зондую…" else "Зондувати 66 xx") }
            OutlinedButton(onClick = { Headphones.clearLog() }) { Text("Очистити") }
        }
        Surface(tonalElevation = 2.dp, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().height(260.dp)) {
            val s = rememberScrollState()
            LaunchedEffect(log.size) { delay(50); s.animateScrollTo(s.maxValue) }
            Column(Modifier.verticalScroll(s).padding(12.dp)) {
                log.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp) }
            }
        }
    }
}

@Composable
private fun Pill(text: String, highlight: Boolean = false, onClick: (() -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(50),
        color = if (highlight) cs.primaryContainer else cs.surfaceContainerHigh,
        contentColor = if (highlight) cs.onPrimaryContainer else cs.onSurfaceVariant,
        modifier = if (onClick != null) Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick) else Modifier
    ) {
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

/** Expressive-style tile: shape and colour spring-morph when selected. */
@Composable
fun ModeTile(label: String, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val corner by animateDpAsState(
        if (selected) 40.dp else 16.dp,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow), label = "corner"
    )
    val cs = MaterialTheme.colorScheme
    val bg by animateColorAsState(
        when { selected -> cs.primary; else -> cs.secondaryContainer }, label = "bg"
    )
    val fg = if (selected) cs.onPrimary else cs.onSecondaryContainer
    Box(
        modifier.clip(RoundedCornerShape(corner)).background(bg).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = fg, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(8.dp))
    }
}
