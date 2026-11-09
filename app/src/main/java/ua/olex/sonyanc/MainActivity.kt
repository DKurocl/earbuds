package ua.olex.sonyanc

import android.Manifest
import android.annotation.SuppressLint
import android.appwidget.AppWidgetManager
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
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
    private val shortcutScene = mutableStateOf<String?>(null)

    private fun consumeIntent(intent: Intent?) {
        shortcutMode.value = intent?.getStringExtra("hush.mode")?.let { name -> runCatching { Mode.valueOf(name) }.getOrNull() }
        shortcutScene.value = intent?.getStringExtra(SceneShortcuts.EXTRA)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); consumeIntent(intent) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Headphones.init(this)
        consumeIntent(intent)
        // Picker previews are a binder call per widget; keep them off the first frame.
        Thread { Widgets.publishPreviews(applicationContext) }.start()
        enableEdgeToEdge()
        setContent {
            var dynamic by remember { mutableStateOf(Prefs.dynamicColor(this)) }
            AppTheme(dynamic) {
                App(
                    startTick.intValue, dynamic, shortcutMode.value, { shortcutMode.value = null },
                    shortcutScene.value, { shortcutScene.value = null },
                ) { dynamic = it; Prefs.setDynamicColor(this, it) }
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
fun App(
    startTick: Int, dynamic: Boolean, shortcutMode: Mode? = null, onShortcutConsumed: () -> Unit = {},
    shortcutScene: String? = null, onSceneConsumed: () -> Unit = {}, onDynamic: (Boolean) -> Unit,
) {
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
    /** Google Find Hub, where the earbuds are registered; its web page if the app is missing. */
    fun findHub() {
        val app = ctx.packageManager.getLaunchIntentForPackage("com.google.android.apps.adm")
        ctx.startActivity(app ?: Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com/android/find/")))
    }
    var onConnect by remember { mutableStateOf(Prefs.onConnect(ctx)) }
    var onConnectScene by remember { mutableStateOf(Prefs.onConnectScene(ctx)) }
    // Two-stage haptics: the tap ticks at once (HushScreen); the earbuds' acknowledgement
    // plays the mode's own pulse. Only the newest tap may report, so bursts stay quiet.
    var tapGeneration by remember { mutableIntStateOf(0) }
    fun requestMode(state: NcState) {
        val before = Headphones.state.value.shownNc?.mode
        val receipt = Headphones.request(state)
        val mine = ++tapGeneration
        scope.launch {
            val ok = receipt.await()
            if (!haptics || mine != tapGeneration) return@launch
            if (ok) Haptics.confirm(ctx, if (state.mode != before) state.mode else null) else Haptics.reject(ctx)
        }
    }
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
            requestMode((Headphones.state.value.nc ?: NcState(shortcutMode)).copy(mode = shortcutMode))
            onShortcutConsumed()
        }
    }

    if (renaming) RenameDialog(displayName, onDismiss = { renaming = false }) {
        displayName = it; Prefs.setDisplayName(ctx, it); renaming = false
    }

    // ---- scenes ----
    var scenes by remember { mutableStateOf(Prefs.scenes(ctx)) }
    var editingScene by remember { mutableStateOf<Scene?>(null) }
    var creatingScene by remember { mutableStateOf(false) }
    fun storeScenes(list: List<Scene>) { scenes = list; Prefs.saveScenes(ctx, list); SceneShortcuts.update(ctx, list) }
    LaunchedEffect(Unit) { SceneShortcuts.update(ctx, scenes) }
    fun applyScene(scene: Scene) {
        SceneShortcuts.reportUsed(ctx, scene)
        scope.launch { if (!Headphones.applyScene(scene)) show("Couldn't fully apply ${scene.name}. Check that your earbuds are connected.") }
    }
    LaunchedEffect(shortcutScene, granted, saved) {
        val scene = scenes.firstOrNull { it.id == shortcutScene } ?: return@LaunchedEffect
        if (granted && saved != null) { applyScene(scene); onSceneConsumed() }
    }
    /** What "save current" would capture right now. */
    fun captured(name: String, id: String, includeEq: Boolean) = Scene(
        id, name, device.shownNc ?: NcState(Mode.ANC), if (includeEq) equalizer?.preset else null,
    )
    if (creatingScene || editingScene != null) {
        val editing = editingScene
        SceneDialog(
            editing, current = captured("", "", false).summary, eqName = equalizer?.let { EqualizerProtocol.presets[it.preset] },
            onDismiss = { creatingScene = false; editingScene = null },
            onSave = { name, recapture, includeEq ->
                val updated = when {
                    editing == null -> captured(name, "s${System.currentTimeMillis()}", includeEq)
                    recapture -> captured(name, editing.id, includeEq)
                    else -> editing.copy(name = name)
                }
                storeScenes(if (editing == null) scenes + updated else scenes.map { if (it.id == editing.id) updated else it })
                creatingScene = false; editingScene = null
            },
            onDelete = {
                storeScenes(scenes.filterNot { it.id == editing?.id })
                editingScene = null
            },
        )
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
                        onMode = ::requestMode, onSettings = { open(Page.EARBUDS) },
                        onConnect = { scope.launch { Headphones.connectAndSync() } },
                        onPermission = { permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) },
                        onBluetooth = ::bluetooth, onPick = { open(Page.APP) },
                        onEqualizer = { open(Page.EQUALIZER) }, onEarbuds = { open(Page.EARBUDS) }, onConnection = { open(Page.DEVICE) },
                        haptics = haptics, batteryRefreshing = device.batteryRefreshing, batteryFresh = device.liveBatteryParts.isNotEmpty(),
                        onBattery = ::refreshInfo, codec = device.codec, codecRefreshing = device.codecRefreshing,
                        deviceName = displayName, onRename = { renaming = true }, onDisconnect = { Headphones.disconnect() },
                        onAppSettings = { open(Page.APP) }, batteryParts = device.liveBatteryParts,
                        equalizerPreset = equalizer?.let { EqualizerProtocol.presets[it.preset] },
                        batterySeenAt = device.batterySeenAt, scenes = scenes, equalizer = equalizer,
                        onScene = ::applyScene, onSaveScene = { creatingScene = true }, onEditScene = { editingScene = it },
                        onFind = ::findHub,
                        lastSeen = Prefs.lastSeen(ctx).takeIf { it > 0 && !device.connected && Prefs.linked(ctx) == false },
                    )
                    Page.EQUALIZER -> EqualizerScreen(::back, startTick)
                    Page.EARBUDS -> EarbudSettingsScreen(::back, { open(Page.APP) }, startTick)
                    Page.DEVICE -> {
                        var turningOff by remember { mutableStateOf(false) }
                        val advanced by Headphones.advanced.collectAsStateWithLifecycle()
                        LaunchedEffect(startTick) {
                            Headphones.readFirmware()
                            Headphones.readAdvanced(AdvancedSettings.Key.QUALITY)
                        }
                        // "Stable connection" keeps the earbuds off LDAC; say so only when it matters.
                        val ldacBlocked = device.connected && advanced[AdvancedSettings.Key.QUALITY]?.firstOrNull() == 1 && device.codec?.code != 0x10
                        DeviceInfoContent(
                            displayName, device.status, device.codec, device.firmware,
                            ldacBlocked = ldacBlocked,
                            onPreferQuality = {
                                scope.launch {
                                    val ok = Headphones.writeAdvancedField(AdvancedSettings.Key.QUALITY, 0, 0)
                                    show(if (ok) "Sound quality preferred. LDAC can be used after the earbuds reconnect." else "Change not confirmed. Try again.")
                                }
                            },
                            refreshing = device.codecRefreshing || device.batteryRefreshing || turningOff,
                            onBack = ::back, onRefresh = ::refreshInfo, onBluetooth = ::bluetooth, onDiagnostics = { open(Page.DIAGNOSTICS) },
                            onFind = ::findHub,
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
                        canAddTile = true, canPinWidget = widgetManager.isRequestPinAppWidgetSupported,
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
                            ctx.getSystemService(android.app.StatusBarManager::class.java).requestAddTileService(
                                ComponentName(ctx, NoiseTileService::class.java), "Noise control",
                                android.graphics.drawable.Icon.createWithResource(ctx, R.drawable.w_icon_noise_control_off), ctx.mainExecutor,
                            ) {}
                        },
                        onPinWidget = { widgetManager.requestPinAppWidget(ComponentName(ctx, ModeWidget::class.java), null, null) },
                        onPinBatteryWidget = { widgetManager.requestPinAppWidget(ComponentName(ctx, BatteryWidget::class.java), null, null) },
                        onConnect = onConnect, onOnConnect = { onConnect = it; onConnectScene = null; Prefs.setOnConnect(ctx, it) },
                        scenes = scenes, onConnectScene = onConnectScene,
                        onOnConnectScene = { onConnect = OnConnect.KEEP; onConnectScene = it; Prefs.setOnConnectScene(ctx, it) },
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
                                    // Android confirms clipboard copies itself.
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
