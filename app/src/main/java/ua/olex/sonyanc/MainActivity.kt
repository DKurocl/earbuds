package ua.olex.sonyanc

import android.Manifest
import android.annotation.SuppressLint
import android.appwidget.AppWidgetManager
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
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
        // Picker previews are a binder call per widget; keep them off the first frame.
        Thread {
            Widgets.publishPreviews(applicationContext)
            // Scenes are gone; drop the launcher shortcuts earlier versions published for them.
            runCatching { getSystemService(android.content.pm.ShortcutManager::class.java)?.removeAllDynamicShortcuts() }
        }.start()
        enableEdgeToEdge()
        setContent {
            var dynamic by remember { mutableStateOf(Prefs.dynamicColor(this)) }
            AppTheme(dynamic) {
                App(
                    startTick.intValue, dynamic, shortcutMode.value, { shortcutMode.value = null },
                ) { dynamic = it; Prefs.setDynamicColor(this, it) }
            }
        }
    }

    // A started screen owns the control link; leaving the app releases it at once.
    override fun onStart() {
        super.onStart()
        Headphones.hold()
        startTick.intValue++
        // Open the control link before the first frame is drawn: the first tap on a mode then
        // finds it ready instead of waiting for the UI to start connecting.
        if (Headphones.hasPermission(this) && Prefs.deviceAddress(this) != null) {
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                Headphones.connectAndSync()
                // Re-arms "Stay in sync" after an update; a no-op when it is off.
                LiveLink.ensureObserving(this@MainActivity)
            }
        }
    }
    override fun onStop() { Headphones.release(); super.onStop() }
}

private enum class Page { HOME, EQUALIZER, EARBUDS, DEVICE, APP, DIAGNOSTICS, BATTERY }

@SuppressLint("MissingPermission")
@Composable
fun App(
    startTick: Int, dynamic: Boolean, shortcutMode: Mode? = null, onShortcutConsumed: () -> Unit = {},
    onDynamic: (Boolean) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val device by Headphones.state.collectAsStateWithLifecycle()
    val equalizer by Headphones.equalizer.collectAsStateWithLifecycle()
    val advancedHome by Headphones.advanced.collectAsStateWithLifecycle()
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
    // The predictive back gesture lives in PageStack (Navigation.kt).

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
    var caseReminder by remember { mutableStateOf(Prefs.caseReminder(ctx)) }
    // "At home" (Places): a mode for home, the saved point, and the location permissions it needs.
    var homeMode by remember { mutableStateOf(Prefs.homeMode(ctx)) }
    var home by remember { mutableStateOf(Prefs.home(ctx)) }
    var placesTick by remember { mutableIntStateOf(0) }
    fun saveHereAsHome() = Places.currentLocation(ctx) { here ->
        if (here == null) show("Couldn't find where you are. Turn on location and try again.")
        else { Prefs.setHome(ctx, here.latitude, here.longitude); home = here.latitude to here.longitude; show("Home saved") }
        placesTick++
    }
    val backgroundLocationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { placesTick++ }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) saveHereAsHome() else show("Location is off for Earbuds, so home can't be saved.")
    }
    fun setHome() {
        when {
            !Places.hasLocation(ctx) -> locationLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            !Places.hasBackground(ctx) && home != null -> {
                // Android shows "Allow all the time" on its own settings page.
                show("Choose \"Allow all the time\", so home works when the earbuds connect in the background.")
                backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
            else -> saveHereAsHome()
        }
    }
    val notificationsAllowed = { ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED }
    var budsReminder by remember { mutableStateOf(Prefs.budsReminder(ctx) && notificationsAllowed()) }
    val budsNotificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        budsReminder = granted; Prefs.setBudsReminder(ctx, granted)
        if (!granted) show("Notifications are off for Earbuds, so the heads-up can't be shown.")
    }
    var tileTwoWay by remember { mutableStateOf(Prefs.tileTwoWay(ctx)) }
    // "Stay in sync" (LiveLink): Android's companion consent dialog, then presence events.
    var liveLink by remember { mutableStateOf(LiveLink.enabled(ctx)) }
    val companionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        LiveLink.confirmed(ctx, result.resultCode == android.app.Activity.RESULT_OK) { liveLink = it }
    }
    fun setLiveLink(on: Boolean) {
        if (!on) { LiveLink.disable(ctx); liveLink = false; return }
        LiveLink.enable(ctx, confirm = { sender -> companionLauncher.launch(androidx.activity.result.IntentSenderRequest.Builder(sender).build()) }) { ok ->
            liveLink = ok
            if (ok && Prefs.linked(ctx) == true) Headphones.stay(true)
            else show("Couldn't link to the earbuds. Make sure they're connected and try again.")
        }
    }
    // The reminder is the only notification; ask for the permission only when it is turned on.
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        caseReminder = granted; Prefs.setCaseReminder(ctx, granted)
        if (!granted) show("Notifications are off for Earbuds, so the reminder can't be shown.")
    }
    // Allowed while a timer runs: show its Live Update at once.
    val timerNotificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) AmbientTimer.until(ctx)?.let { LiveTimer.show(ctx, it, AmbientTimer.returnMode(ctx)) }
    }
    // Two-stage haptics: the tap ticks at once (HushScreen); the earbuds' acknowledgement
    // plays the mode's own pulse. Only the newest tap may report, so bursts stay quiet.
    var tapGeneration by remember { mutableIntStateOf(0) }
    // "Ambient for a moment": the end time drives the dock's countdown.
    var ambientUntil by remember { mutableStateOf(AmbientTimer.until(ctx)) }
    var ambientMinutes by remember { mutableIntStateOf(Prefs.ambientMinutes(ctx)) }
    LaunchedEffect(startTick, ambientUntil) {
        // Picks up a timer that ended (or was cancelled from the widget) while away.
        ambientUntil = AmbientTimer.until(ctx)
        ambientUntil?.let { kotlinx.coroutines.delay(it - System.currentTimeMillis() + 300); ambientUntil = AmbientTimer.until(ctx) }
    }
    fun requestMode(state: NcState) {
        // Choosing a mode yourself always wins over a running timer.
        if (ambientUntil != null) { AmbientTimer.cancel(ctx); ambientUntil = null }
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

    CompositionLocalProvider(LocalMessages provides show) {
        Box(Modifier.fillMaxSize()) {
            PageStack(stack, onPop = ::back) { page ->
                when (Page.valueOf(page)) {
                    Page.HOME -> HushScreen(
                        device.status, device.nc, device.pending, device.ncConfirmed, device.error, device.battery, granted, saved != null,
                        onMode = ::requestMode, onSettings = { open(Page.EARBUDS) },
                        onConnect = { scope.launch { Headphones.connectAndSync() } },
                        onPermission = { permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) },
                        onBluetooth = ::bluetooth, onPick = { open(Page.APP) },
                        onEqualizer = { open(Page.EQUALIZER) }, onEarbuds = { open(Page.EARBUDS) }, onConnection = { open(Page.DEVICE) },
                        haptics = haptics, batteryRefreshing = device.batteryRefreshing, batteryFresh = device.liveBatteryParts.isNotEmpty(),
                        onBattery = ::refreshInfo, codec = device.codec, codecRefreshing = device.codecRefreshing,
                        deviceName = displayName,
                        onAppSettings = { open(Page.APP) }, batteryParts = device.liveBatteryParts,
                        equalizerPreset = equalizer?.let { EqualizerProtocol.presets[it.preset] },
                        batterySeenAt = device.batterySeenAt,
                        onFind = ::findHub, onBatteryDetails = { open(Page.BATTERY) },
                        speakToChat = advancedHome[AdvancedSettings.Key.CHAT]?.firstOrNull()?.let { it == 0 }?.takeIf { device.connected },
                        onSpeakToChat = { on ->
                            scope.launch {
                                val ok = Headphones.writeAdvancedField(AdvancedSettings.Key.CHAT, 0, if (on) 0 else 1)
                                if (haptics) if (ok) Haptics.confirm(ctx, null) else Haptics.reject(ctx)
                                if (!ok) show("Speak-to-Chat change not confirmed. Try again.")
                            }
                        },
                        ambientMinutes = ambientMinutes, ambientUntil = ambientUntil,
                        onAmbientFor = {
                            val from = Headphones.state.value.shownNc ?: NcState(Mode.ANC)
                            AmbientTimer.start(ctx, ambientMinutes, from)
                            ambientUntil = AmbientTimer.until(ctx)
                            // The countdown can live in the status bar; ask once it is wanted.
                            if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                                timerNotificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        },
                        onStayInAmbient = { AmbientTimer.cancel(ctx); ambientUntil = null },
                        lastSeen = Prefs.lastSeen(ctx).takeIf { it > 0 && !device.connected && Prefs.linked(ctx) == false },
                    )
                    Page.EQUALIZER -> EqualizerScreen(::back, startTick)
                    Page.BATTERY -> BatteryScreen(::back)
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
                            onBack = ::back, onRefresh = ::refreshInfo, onBluetooth = ::bluetooth,
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
                                LiveLink.forget(ctx); liveLink = false
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
                                android.graphics.drawable.Icon.createWithResource(ctx, R.drawable.w_icon_anc), ctx.mainExecutor,
                            ) {}
                        },
                        onPinWidget = { widgetManager.requestPinAppWidget(ComponentName(ctx, ModeWidget::class.java), null, null) },
                        onPinBatteryWidget = { widgetManager.requestPinAppWidget(ComponentName(ctx, BatteryWidget::class.java), null, null) },
                        onConnect = onConnect, onOnConnect = { onConnect = it; Prefs.setOnConnect(ctx, it) },
                        caseReminder = caseReminder, onCaseReminder = { on ->
                            if (!on) { caseReminder = false; Prefs.setCaseReminder(ctx, false) }
                            else if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                caseReminder = true; Prefs.setCaseReminder(ctx, true)
                            } else notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        },
                        homeMode = homeMode, onHomeMode = { m -> homeMode = m; Prefs.setHomeMode(ctx, m); if (m != null && home == null) setHome() },
                        homeSummary = run {
                            placesTick // recompose after permission or location changes
                            when {
                                home == null -> "Set to where you are now"
                                !Places.hasLocation(ctx) || !Places.hasBackground(ctx) -> "Needs location \"Allow all the time\""
                                else -> "Saved · tap to set to where you are now"
                            }
                        },
                        onSetHome = ::setHome,
                        budsReminder = budsReminder, onBudsReminder = { on ->
                            if (!on) { budsReminder = false; Prefs.setBudsReminder(ctx, false); LowBattery.onAway(ctx) }
                            else if (notificationsAllowed()) { budsReminder = true; Prefs.setBudsReminder(ctx, true) }
                            else budsNotificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        },
                        tileTwoWay = tileTwoWay, onTileTwoWay = { tileTwoWay = it; Prefs.setTileTwoWay(ctx, it) },
                        ambientMinutes = ambientMinutes, onAmbientMinutes = { ambientMinutes = it; Prefs.setAmbientMinutes(ctx, it) },
                        liveLink = if (LiveLink.supported && saved != null) liveLink else null, onLiveLink = ::setLiveLink,
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
