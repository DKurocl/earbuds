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
import androidx.activity.viewModels
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
    private val model: AppModel by viewModels()
    private val startTick = mutableIntStateOf(0)
    private val shortcutMode = mutableStateOf<Mode?>(null)

    private fun consumeIntent(intent: Intent?) {
        shortcutMode.value = intent?.getStringExtra("hush.mode")?.let { name -> runCatching { Mode.valueOf(name) }.getOrNull() }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); consumeIntent(intent) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A fresh start opens with the intro (Intro, SplashHandoff); a recreated activity has no splash.
        if (savedInstanceState == null) Intro.prepare()
        SplashHandoff.install(this)
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
            AppTheme(model.dynamicColor) {
                App(model, startTick.intValue, shortcutMode.value) { shortcutMode.value = null }
            }
        }
    }

    // A started screen owns the control link; leaving the app releases it at once.
    override fun onStart() {
        super.onStart()
        ConnectedSheet.appVisible = true
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
    override fun onStop() { ConnectedSheet.appVisible = false; Headphones.release(); super.onStop() }
}

private enum class Page { HOME, EQUALIZER, EARBUDS, DEVICE, APP, DIAGNOSTICS, BATTERY, VIBRATIONS }

@SuppressLint("MissingPermission")
@Composable
fun App(model: AppModel, startTick: Int, shortcutMode: Mode? = null, onShortcutConsumed: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val device by Headphones.state.collectAsStateWithLifecycle()
    val equalizer by Headphones.equalizer.collectAsStateWithLifecycle()
    val advanced by Headphones.advanced.collectAsStateWithLifecycle()
    var renaming by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(model) {
        model.messages.collect { message -> snackbar.currentSnackbarData?.dismiss(); launch { snackbar.showSnackbar(message) } }
    }

    // ---- navigation: a back stack of pages with the system predictive back gesture ----
    var stack by rememberSaveable { mutableStateOf(listOf(Page.HOME.name)) }
    fun open(page: Page) { if (stack.last() != page.name) stack = stack + page.name }
    fun back() { if (stack.size > 1) stack = stack.dropLast(1) }
    // The predictive back gesture lives in PageStack (Navigation.kt).

    // ---- what needs an Activity: permission dialogs and other apps ----
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), model::onPermission)
    val widgetManager = remember { AppWidgetManager.getInstance(ctx) }
    fun bluetooth() = ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    /** Google Find Hub, where the earbuds are registered; its web page if the app is missing. */
    fun findHub() {
        val app = ctx.packageManager.getLaunchIntentForPackage("com.google.android.apps.adm")
        ctx.startActivity(app ?: Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com/android/find/")))
    }
    val backgroundLocationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { model.locationChanged() }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) model.saveHereAsHome() else model.show("Location is off for Earbuds, so home can't be saved.")
    }
    fun setHome() {
        when {
            !Places.hasLocation(ctx) -> locationLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            !Places.hasBackground(ctx) && model.home != null -> {
                // Android shows "Allow all the time" on its own settings page.
                model.show("Choose \"Allow all the time\", so home works when the earbuds connect in the background.")
                backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
            else -> model.saveHereAsHome()
        }
    }
    val budsNotificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), model::onBudsReminderPermission)
    val caseNotificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), model::onCaseReminderPermission)
    // "Stay in sync" (LiveLink): Android's companion consent dialog, then presence events.
    val companionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        LiveLink.confirmed(ctx, result.resultCode == android.app.Activity.RESULT_OK, model::liveLinkResult)
    }
    fun setLiveLink(on: Boolean) {
        if (!on) return model.liveLinkOff()
        LiveLink.enable(ctx, confirm = { sender -> companionLauncher.launch(androidx.activity.result.IntentSenderRequest.Builder(sender).build()) },
            done = model::liveLinkResult)
    }
    // Allowed while a timer runs: show its Live Update at once.
    val timerNotificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) AmbientTimer.until(ctx)?.let { LiveTimer.show(ctx, it, AmbientTimer.returnMode(ctx)) }
    }

    LaunchedEffect(startTick) { model.onStart() }
    // A problem while connected (a mode not confirmed, the earbuds keeping another mode) is a short
    // message, not a banner; away from the earbuds the status line under the name says why.
    LaunchedEffect(device.error, device.connected) {
        val e = device.error ?: return@LaunchedEffect
        if (device.connected) { model.show(e); Headphones.clearError() }
    }
    LaunchedEffect(shortcutMode, model.granted, model.saved) {
        if (shortcutMode != null && model.granted && model.saved != null) {
            model.requestMode(shortcutMode)
            onShortcutConsumed()
        }
    }

    if (renaming) RenameDialog(model.displayName, onDismiss = { renaming = false }) { model.rename(it); renaming = false }

    CompositionLocalProvider(LocalMessages provides model::show) {
        Box(Modifier.fillMaxSize()) {
            PageStack(stack, onPop = ::back) { page ->
                when (Page.valueOf(page)) {
                    Page.HOME -> HushScreen(
                        device.status, device.nc, device.pending, device.ncConfirmed, device.error, device.battery, model.granted, model.saved != null,
                        onMode = model::requestMode, onSettings = { open(Page.EARBUDS) },
                        onConnect = { model.connect() },
                        onPermission = { permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) },
                        onBluetooth = ::bluetooth, onPick = { open(Page.APP) },
                        onEqualizer = { open(Page.EQUALIZER) }, onEarbuds = { open(Page.EARBUDS) }, onConnection = { open(Page.DEVICE) },
                        haptics = model.haptics, batteryRefreshing = device.batteryRefreshing, batteryFresh = device.liveBatteryParts.isNotEmpty(),
                        onBattery = { model.refreshInfo() }, codec = device.codec, codecRefreshing = device.codecRefreshing,
                        deviceName = model.displayName,
                        onAppSettings = { open(Page.APP) }, batteryParts = device.liveBatteryParts,
                        equalizerPreset = equalizer?.let { EqualizerProtocol.presets[it.preset] },
                        batterySeenAt = device.batterySeenAt,
                        onFind = ::findHub, onBatteryDetails = { open(Page.BATTERY) },
                        speakToChat = advanced[AdvancedSettings.Key.CHAT]?.firstOrNull()?.let { it == 0 }?.takeIf { device.connected },
                        onSpeakToChat = { model.setSpeakToChat(it) },
                        ambientMinutes = model.ambientMinutes, ambientUntil = model.ambientUntil,
                        onAmbientFor = {
                            // The countdown can live in the status bar; ask once it is wanted.
                            if (!model.ambientForAMoment()) timerNotificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        },
                        onStayInAmbient = model::stayInAmbient,
                        lastSeen = Prefs.lastSeen(ctx).takeIf { it > 0 && !device.connected && Prefs.linked(ctx) == false },
                    )
                    Page.EQUALIZER -> EqualizerScreen(::back, startTick)
                    Page.BATTERY -> BatteryScreen(::back)
                    Page.EARBUDS -> EarbudSettingsScreen(::back, { open(Page.APP) }, startTick)
                    Page.DEVICE -> {
                        LaunchedEffect(startTick) { model.readDeviceInfo() }
                        val quality = advanced[AdvancedSettings.Key.QUALITY]?.firstOrNull()
                        val ldac = device.codec?.code == 0x10
                        DeviceInfoContent(
                            model.displayName, device.status, device.codec, device.firmware,
                            // "Stable connection" keeps the earbuds off LDAC; say so only when it matters.
                            ldacBlocked = device.connected && quality == 1 && !ldac,
                            hdAudioOff = device.connected && device.codecLive && quality == 0 && !ldac,
                            onPreferQuality = { model.preferQuality() },
                            refreshing = device.codecRefreshing || device.batteryRefreshing || model.turningOff,
                            onBack = ::back, onRefresh = { model.refreshInfo() }, onBluetooth = ::bluetooth,
                            onFind = ::findHub, onTurnOff = { model.turnOff() },
                        )
                    }
                    Page.APP -> AppSettingsContent(
                        model.devices, model.saved, model.displayName, model.dynamicColor, model.haptics,
                        canAddTile = true, canPinWidget = widgetManager.isRequestPinAppWidgetSupported,
                        version = remember { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName.orEmpty() },
                        onBack = ::back,
                        onSelect = { picked -> model.select(picked, ::back) },
                        onRename = { renaming = true }, onDynamicColor = model::updateDynamicColor,
                        onHaptics = model::updateHaptics,
                        onAddTile = {
                            ctx.getSystemService(android.app.StatusBarManager::class.java).requestAddTileService(
                                ComponentName(ctx, NoiseTileService::class.java), "Noise control",
                                android.graphics.drawable.Icon.createWithResource(ctx, R.drawable.w_icon_anc), ctx.mainExecutor,
                            ) {}
                        },
                        onPinWidget = { widgetManager.requestPinAppWidget(ComponentName(ctx, ModeWidget::class.java), null, null) },
                        onPinBatteryWidget = { widgetManager.requestPinAppWidget(ComponentName(ctx, BatteryWidget::class.java), null, null) },
                        onConnect = model.onConnect, onOnConnect = model::updateOnConnect,
                        caseReminder = model.caseReminder, onCaseReminder = { on ->
                            if (!model.setCaseReminder(on)) caseNotificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        },
                        homeMode = model.homeMode, onHomeMode = { m -> model.updateHomeMode(m); if (m != null && model.home == null) setHome() },
                        homeSummary = model.homeSummary,
                        onSetHome = ::setHome,
                        budsReminder = model.budsReminder, onBudsReminder = { on ->
                            if (!model.setBudsReminder(on)) budsNotificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        },
                        tileTwoWay = model.tileTwoWay, onTileTwoWay = model::updateTileTwoWay,
                        ambientMinutes = model.ambientMinutes, onAmbientMinutes = model::updateAmbientMinutes,
                        liveLink = if (LiveLink.supported && model.saved != null) model.liveLink else null, onLiveLink = ::setLiveLink,
                        connectSheet = model.connectSheet, onConnectSheet = model::updateConnectSheet,
                        onBluetooth = ::bluetooth, onDiagnostics = { open(Page.DIAGNOSTICS) },
                        onVibrations = { open(Page.VIBRATIONS) },
                    )
                    Page.VIBRATIONS -> VibrationsContent(model.hapticTakes, remember { Haptics.shaped(ctx) }, onBack = ::back, onTry = model::tryHaptic)
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
