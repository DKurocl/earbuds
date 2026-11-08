package ua.olex.sonyanc

import android.Manifest
import android.annotation.SuppressLint
import android.appwidget.AppWidgetManager
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import ua.olex.sonyanc.SonyProtocol.Mode
import ua.olex.sonyanc.SonyProtocol.NcState

class MainActivity : ComponentActivity() {
    private val startTick = mutableIntStateOf(0)
    private val shortcutMode = mutableStateOf<Mode?>(null)

    private fun consumeIntent(intent: Intent?) {
        shortcutMode.value = intent?.getStringExtra("hush.mode")?.let { name -> runCatching { Mode.valueOf(name) }.getOrNull() }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); consumeIntent(intent) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Headphones.init(this)
        consumeIntent(intent)
        enableEdgeToEdge()
        setContent {
            var dynamic by remember { mutableStateOf(Prefs.dynamicColor(this)) }
            AppTheme(dynamic) {
                App(startTick.intValue, dynamic, shortcutMode.value, { shortcutMode.value = null }) { dynamic = it; Prefs.setDynamicColor(this, it) }
            }
        }
    }

    // A started screen owns the control link; leaving the app releases it at once.
    override fun onStart() { super.onStart(); Headphones.hold(); startTick.intValue++ }
    override fun onStop() { Headphones.release(); super.onStop() }
}

private enum class Page { HOME, EQUALIZER, EARBUDS, DEVICE, APP, DIAGNOSTICS }

@SuppressLint("MissingPermission")
@Composable
fun App(startTick: Int, dynamic: Boolean, shortcutMode: Mode? = null, onShortcutConsumed: () -> Unit = {}, onDynamic: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val device by Headphones.state.collectAsStateWithLifecycle()
    val equalizer by Headphones.equalizer.collectAsStateWithLifecycle()
    var granted by remember { mutableStateOf(Headphones.hasPermission(ctx)) }
    var saved by remember { mutableStateOf(Prefs.deviceAddress(ctx)) }
    var devices by remember { mutableStateOf(emptyList<PairedDevice>()) }
    var haptics by remember { mutableStateOf(Prefs.haptics(ctx)) }
    var displayName by rememberSaveable { mutableStateOf(Prefs.displayName(ctx) ?: Prefs.deviceName(ctx) ?: "WF-1000XM5") }
    var renaming by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val show: (String) -> Unit = { message -> scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(message) } }

    // ---- navigation: a back stack of pages with the system predictive back gesture ----
    var stack by rememberSaveable { mutableStateOf(listOf(Page.HOME.name)) }
    fun open(page: Page) { if (stack.last() != page.name) stack = stack + page.name }
    fun back() { if (stack.size > 1) stack = stack.dropLast(1) }
    var backProgress by remember { mutableFloatStateOf(0f) }
    PredictiveBackHandler(enabled = stack.size > 1) { events ->
        try {
            events.collect { backProgress = it.progress }
            back()
        } catch (e: CancellationException) {
            backProgress = 0f
            throw e
        }
        backProgress = 0f
    }

    // ---- data and actions ----
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val widgetManager = remember { AppWidgetManager.getInstance(ctx) }
    fun bluetooth() = ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    fun refreshInfo() { scope.launch { Headphones.refreshInfo() } }
    LaunchedEffect(startTick, granted, saved) {
        granted = Headphones.hasPermission(ctx)
        if (!granted) return@LaunchedEffect
        val bonded = Headphones.bondedDevices(ctx)
        devices = bonded.map { PairedDevice(it.address, it.name ?: "WF-1000XM5") }
        if (saved == null && bonded.size == 1) {
            Headphones.selectDevice(bonded.single().address, bonded.single().name)
            saved = bonded.single().address
        } else if (saved != null) Headphones.connectAndSync()
    }
    LaunchedEffect(shortcutMode, granted, saved) {
        if (shortcutMode != null && granted && saved != null) {
            Headphones.request((Headphones.state.value.nc ?: NcState(shortcutMode)).copy(mode = shortcutMode))
            onShortcutConsumed()
        }
    }

    if (renaming) RenameDialog(displayName, onDismiss = { renaming = false }) {
        displayName = it; Prefs.setDisplayName(ctx, it); renaming = false
    }

    CompositionLocalProvider(LocalMessages provides show) {
        Box(Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = stack,
                contentKey = { it.last() },
                transitionSpec = {
                    val forward = targetState.size >= initialState.size
                    val spec = tween<IntOffset>(350, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                    if (forward) (slideInHorizontally(spec) { it / 5 } + fadeIn(tween(250))) togetherWith (slideOutHorizontally(spec) { -it / 10 } + fadeOut(tween(150)))
                    else (slideInHorizontally(spec) { -it / 10 } + fadeIn(tween(250))) togetherWith (slideOutHorizontally(spec) { it / 5 } + fadeOut(tween(150)))
                },
                label = "pages",
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    // Predictive back: the page shrinks and follows the finger before it is popped.
                    // Read in the draw phase only, so the gesture never recomposes the page.
                    val p = backProgress
                    scaleX = 1f - .08f * p; scaleY = 1f - .08f * p
                    translationX = 24.dp.toPx() * p
                    shape = RoundedCornerShape(32.dp * p)
                    clip = p > 0f
                },
            ) { pages ->
                when (Page.valueOf(pages.last())) {
                    Page.HOME -> HushScreen(
                        device.status, device.nc, device.pending, device.ncConfirmed, device.error, device.battery, granted, saved != null,
                        onMode = { Headphones.request(it) }, onSettings = { open(Page.EARBUDS) },
                        onConnect = { scope.launch { Headphones.connectAndSync() } },
                        onPermission = { if (Build.VERSION.SDK_INT >= 31) permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) },
                        onBluetooth = ::bluetooth, onPick = { open(Page.APP) },
                        onEqualizer = { open(Page.EQUALIZER) }, onEarbuds = { open(Page.EARBUDS) }, onConnection = { open(Page.DEVICE) },
                        haptics = haptics, batteryRefreshing = device.batteryRefreshing, batteryFresh = device.liveBatteryParts.isNotEmpty(),
                        onBattery = ::refreshInfo, codec = device.codec, codecRefreshing = device.codecRefreshing,
                        deviceName = displayName, onRename = { renaming = true }, onDisconnect = { Headphones.disconnect() },
                        onAppSettings = { open(Page.APP) }, batteryParts = device.liveBatteryParts,
                        equalizerPreset = equalizer?.let { EqualizerProtocol.presets[it.preset] },
                    )
                    Page.EQUALIZER -> EqualizerScreen(::back, startTick)
                    Page.EARBUDS -> EarbudSettingsScreen(::back, { open(Page.APP) }, startTick)
                    Page.DEVICE -> {
                        var turningOff by remember { mutableStateOf(false) }
                        LaunchedEffect(startTick) { Headphones.readFirmware() }
                        DeviceInfoContent(
                            displayName, device.status, device.codec, device.firmware,
                            refreshing = device.codecRefreshing || device.batteryRefreshing || turningOff,
                            onBack = ::back, onRefresh = ::refreshInfo, onBluetooth = ::bluetooth, onDiagnostics = { open(Page.DIAGNOSTICS) },
                            onTurnOff = {
                                scope.launch {
                                    turningOff = true
                                    try { if (!Headphones.powerOff()) show("The earbuds didn't confirm. They may still be on.") }
                                    finally { turningOff = false }
                                }
                            },
                        )
                    }
                    Page.APP -> AppSettingsContent(
                        devices, saved, displayName, dynamic, haptics,
                        canAddTile = Build.VERSION.SDK_INT >= 33, canPinWidget = widgetManager.isRequestPinAppWidgetSupported,
                        version = remember { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName.orEmpty() },
                        onBack = ::back,
                        onSelect = { picked ->
                            scope.launch {
                                Headphones.selectDevice(picked.address, picked.name)
                                saved = picked.address
                                back()
                                Headphones.connectAndSync()
                            }
                        },
                        onRename = { renaming = true }, onDynamicColor = onDynamic,
                        onHaptics = { haptics = it; Prefs.setHaptics(ctx, it) },
                        onAddTile = {
                            if (Build.VERSION.SDK_INT >= 33) ctx.getSystemService(android.app.StatusBarManager::class.java).requestAddTileService(
                                ComponentName(ctx, NoiseTileService::class.java), "Noise control",
                                android.graphics.drawable.Icon.createWithResource(ctx, R.drawable.w_icon_noise_control_off), ctx.mainExecutor,
                            ) {}
                        },
                        onPinWidget = { widgetManager.requestPinAppWidget(ComponentName(ctx, ModeWidget::class.java), null, null) },
                        onBluetooth = ::bluetooth, onDiagnostics = { open(Page.DIAGNOSTICS) },
                    )
                    Page.DIAGNOSTICS -> {
                        val log by Headphones.log.collectAsStateWithLifecycle()
                        val clipboard = LocalClipboard.current
                        DiagnosticsContent(
                            log, onBack = ::back,
                            onCopy = {
                                scope.launch {
                                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Earbuds log", log.joinToString("\n"))))
                                    // Android 13+ confirms clipboard copies itself.
                                    if (Build.VERSION.SDK_INT < 33) show("Log copied")
                                }
                            },
                            onClear = { Headphones.clearLog() },
                        )
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp))
        }
    }
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var draft by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Name") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Shown in this app only. The Bluetooth name doesn't change.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(value = draft, onValueChange = { draft = it.take(40) }, singleLine = true, label = { Text("Name") })
            }
        },
        confirmButton = { TextButton(enabled = draft.isNotBlank(), onClick = { onSave(draft.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
