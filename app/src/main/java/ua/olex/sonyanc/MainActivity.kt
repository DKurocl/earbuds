package ua.olex.sonyanc

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { App() } }
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
fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val logLines = remember { mutableStateListOf<String>() }
    val conn = remember {
        SonyConnection(scope) { line -> scope.launch { logLines += line } }
    }
    DisposableEffect(Unit) { onDispose { conn.close() } }

    val permission = if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_CONNECT
                     else Manifest.permission.BLUETOOTH
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }

    val adapter = remember { ctx.getSystemService(BluetoothManager::class.java)?.adapter }
    var devices by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }
    var selected by remember { mutableStateOf<BluetoothDevice?>(null) }
    var isConnected by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf<SonyProtocol.Mode?>(null) }
    var rawHex by remember { mutableStateOf(SonyProtocol.INIT_HEX) }

    // Per-mode payloads: learned ones (saved) override the built-in V1 defaults.
    val prefs = remember { ctx.getSharedPreferences("payloads", 0) }
    val payloads = remember {
        mutableStateMapOf<SonyProtocol.Mode, String>().apply {
            SonyProtocol.Mode.entries.forEach { m -> put(m, prefs.getString(m.name, null) ?: m.payloadHex) }
        }
    }
    val learned = remember {
        mutableStateMapOf<SonyProtocol.Mode, Boolean>().apply {
            SonyProtocol.Mode.entries.forEach { m -> put(m, prefs.contains(m.name)) }
        }
    }
    var learning by remember { mutableStateOf<SonyProtocol.Mode?>(null) }

    LaunchedEffect(Unit) {
        conn.onPayload = { p ->
            scope.launch {
                // 0x69 = headphones reporting the ambient-sound/ANC state; the "set"
                // command is the same layout with 0x68 in front.
                if (p.isNotEmpty() && p[0] == 0x69.toByte()) {
                    val target = learning
                    if (target != null) {
                        val set = p.copyOf().also { it[0] = 0x68 }
                        val hex = SonyProtocol.hex(set)
                        payloads[target] = hex; learned[target] = true
                        prefs.edit().putString(target.name, hex).apply()
                        logLines += "★ «${target.label}» = $hex"
                        mode = target; learning = null
                    } else {
                        logLines += "★ стан ANC: ${SonyProtocol.hex(p)}"
                    }
                }
                // 0x67 = reply to a "get" (0x66 XX) probe
                if (p.isNotEmpty() && p[0] == 0x67.toByte()) {
                    logLines += "◆ GET ${SonyProtocol.hex(p)}"
                }
            }
        }
    }

    LaunchedEffect(granted) {
        if (granted) {
            devices = adapter?.bondedDevices?.sortedByDescending {
                (it.name ?: "").contains(Regex("WH-|WF-|Sony|LinkBuds|ULT", RegexOption.IGNORE_CASE))
            }.orEmpty()
            if (selected == null) selected = devices.firstOrNull()
        }
    }

    Scaffold(
        topBar = {
            LargeTopAppBar(title = { Text("Sony ANC", fontWeight = FontWeight.Black) })
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(horizontal = 16.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!granted) {
                Button(onClick = { launcher.launch(permission) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text("Дати доступ до Bluetooth")
                }
                return@Column
            }

            // Device picker + connect
            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = selected?.name ?: "Немає спарених пристроїв",
                    onValueChange = {}, readOnly = true, label = { Text("Навушники") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    devices.forEach { d ->
                        DropdownMenuItem(text = { Text(d.name ?: d.address) }, onClick = {
                            selected = d; expanded = false
                        })
                    }
                }
            }

            Button(
                enabled = selected != null && !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        if (isConnected) { conn.close(); isConnected = false }
                        else {
                            isConnected = conn.connect(selected!!)
                            if (isConnected) conn.sendCommand(SonyProtocol.parseHex(SonyProtocol.INIT_HEX))
                        }
                        busy = false
                    }
                },
                shape = RoundedCornerShape(if (isConnected) 16.dp else 28.dp),
                colors = if (isConnected) ButtonDefaults.filledTonalButtonColors() else ButtonDefaults.buttonColors(),
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text(when { busy -> "Підключаю…"; isConnected -> "Відключити"; else -> "Підключити" }, fontSize = 16.sp)
            }

            // The three big mode tiles
            Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SonyProtocol.Mode.entries.forEach { m ->
                    ModeTile(
                        label = m.label,
                        sub = when { learning == m -> "тапни навушник…"; learned[m] == true -> "навчено"; else -> null },
                        selected = mode == m || learning == m,
                        enabled = isConnected,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        onLongClick = {
                            learning = if (learning == m) null else m
                            if (learning == m) logLines += "Навчання «${m.label}»: перемкни цей режим дотиком на навушнику"
                        }
                    ) {
                        mode = m
                        scope.launch { conn.sendCommand(SonyProtocol.parseHex(payloads[m] ?: m.payloadHex)) }
                    }
                }
            }

            // Raw command field for experimenting with other models
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = rawHex, onValueChange = { rawHex = it },
                    label = { Text("Сирий payload (hex)") }, singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                    shape = RoundedCornerShape(20.dp), modifier = Modifier.weight(1f)
                )
                FilledTonalButton(
                    enabled = isConnected,
                    onClick = { scope.launch { conn.sendCommand(SonyProtocol.parseHex(rawHex)) } },
                    modifier = Modifier.height(56.dp)
                ) { Text("Send") }
            }

            // Ask the headphones for every ambient-sound/ANC setting type: 66 00 … 66 2F
            var probing by remember { mutableStateOf(false) }
            OutlinedButton(
                enabled = isConnected && !probing,
                onClick = {
                    scope.launch {
                        probing = true
                        logLines.clear()
                        logLines += "Зондую 66 00…66 2F (≈20 с)"
                        for (t in 0x00..0x2F) {
                            conn.sendCommand(byteArrayOf(0x66, t.toByte()))
                            kotlinx.coroutines.delay(400)
                        }
                        val gets = logLines.filter { it.startsWith("◆") || it.startsWith("★") }
                        logLines.clear()
                        logLines += "Готово. Відповіді (${gets.size}):"
                        logLines += gets
                        probing = false
                    }
                },
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) { Text(if (probing) "Зондую…" else "Зондувати") }

            // Log
            val listState = rememberLazyListState()
            LaunchedEffect(logLines.size) { if (logLines.isNotEmpty()) listState.animateScrollToItem(logLines.lastIndex) }
            Surface(
                tonalElevation = 2.dp, shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth().weight(1f).padding(bottom = 16.dp)
            ) {
                LazyColumn(state = listState, contentPadding = PaddingValues(12.dp)) {
                    items(logLines) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp) }
                }
            }
        }
    }
}

/** Expressive-style tile: shape and colour spring-morph when selected. Long-press = learn. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ModeTile(
    label: String, sub: String?, selected: Boolean, enabled: Boolean, modifier: Modifier,
    onLongClick: () -> Unit, onClick: () -> Unit,
) {
    val corner by animateDpAsState(
        if (selected) 40.dp else 16.dp,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow), label = "corner"
    )
    val cs = MaterialTheme.colorScheme
    val bg by animateColorAsState(
        when { !enabled -> cs.surfaceVariant.copy(alpha = 0.5f); selected -> cs.primary; else -> cs.secondaryContainer },
        label = "bg"
    )
    val fg = when { !enabled -> cs.onSurfaceVariant.copy(alpha = 0.5f); selected -> cs.onPrimary; else -> cs.onSecondaryContainer }
    Box(
        modifier.clip(RoundedCornerShape(corner)).background(bg)
            .combinedClickable(enabled = enabled, onLongClick = onLongClick, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(6.dp)) {
            Text(label, color = fg, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                fontSize = 13.sp, maxLines = 2, textAlign = TextAlign.Center)
            if (sub != null) Text(sub, color = fg.copy(alpha = 0.75f), fontSize = 10.sp, textAlign = TextAlign.Center)
        }
    }
}
