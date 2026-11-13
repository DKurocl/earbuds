package ua.olex.sonyanc

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The sheet that comes up when the earbuds connect, as a Pixel does for its own earbuds: the
 * earbuds and the case in their battery rings, over whatever is on screen, for a few seconds.
 * Android lets a companion app ("Stay in sync") open it from the background; without that, or
 * while the app itself is open, the screen is off or locked, nothing comes up.
 */
object ConnectedSheet {
    /** True while the app's own screen is started: the sheet would only repeat it. */
    @Volatile var appVisible = false
    @Volatile private var lastShown = 0L
    private const val QUIET_MS = 90_000L // a link that drops and comes back does not bring it up again

    fun show(ctx: Context) {
        if (!Prefs.connectSheet(ctx) || appVisible || !LiveLink.enabled(ctx)) return
        val now = System.currentTimeMillis()
        if (now - lastShown < QUIET_MS) return
        if (ctx.getSystemService(PowerManager::class.java)?.isInteractive != true) return
        if (ctx.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != false) return
        lastShown = now
        runCatching {
            ctx.startActivity(Intent(ctx, ConnectedSheetActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        }.onFailure { Headphones.addLog("✗ connected sheet: ${it.message}") }
    }
}

class ConnectedSheetActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Headphones.init(this)
        val name = Prefs.displayName(this) ?: Prefs.deviceName(this) ?: "WF-1000XM5"
        val earlier = Prefs.lastBattery(this)?.first
        setContent {
            AppTheme(Prefs.dynamicColor(this)) {
                val device by Headphones.state.collectAsState()
                ConnectedSheetContent(
                    name = name, status = device.status, battery = device.battery ?: earlier ?: SonyProtocol.Battery(),
                    live = device.liveBatteryParts,
                    onOpen = {
                        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        close()
                    },
                    onGone = ::close,
                )
            }
        }
    }

    private fun close() {
        finish()
        if (Build.VERSION.SDK_INT >= 34) overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
    }
}

/** The sheet itself: a floating card at the bottom that springs up and goes after 8 s untouched. */
@Composable
fun ConnectedSheetContent(
    name: String, status: Headphones.Status, battery: SonyProtocol.Battery, live: Set<Int>,
    onOpen: () -> Unit = {}, onGone: () -> Unit = {}, shown: Float? = null,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val enter = remember { Animatable(shown ?: 0f) }
    val drag = remember { Animatable(0f) }
    var touched by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    val travel = with(LocalDensity.current) { 420.dp.toPx() }
    fun dismiss() {
        if (leaving) return
        leaving = true
        scope.launch { enter.animateTo(0f, tween(220, easing = EmphasizedEasing)); onGone() }
    }
    if (shown == null) {
        LaunchedEffect(Unit) { enter.animateTo(1f, spring(dampingRatio = 0.82f, stiffness = 320f)) }
        LaunchedEffect(touched) { if (!touched) { delay(8_000); dismiss() } }
    }
    BackHandler { dismiss() }
    Box(Modifier.fillMaxSize()) {
        // The scrim: tapping it lets the sheet go.
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f * enter.value.coerceIn(0f, 1f)))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { dismiss() },
        )
        Surface(
            shape = RoundedCornerShape(32.dp), color = cs.surfaceContainer, tonalElevation = 2.dp,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 12.dp).widthIn(max = 480.dp).fillMaxWidth()
                .graphicsLayer { translationY = (1 - enter.value) * travel + drag.value }
                .draggable(
                    rememberDraggableState { d -> touched = true; scope.launch { drag.snapTo((drag.value + d).coerceAtLeast(0f)) } },
                    Orientation.Vertical,
                    onDragStopped = { v ->
                        if (drag.value > travel * 0.2f || v > 1500f) dismiss() else drag.animateTo(0f, spring(dampingRatio = 0.7f))
                    },
                )
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { touched = true }
                .semantics { paneTitle = "$name connected" },
        ) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(32.dp, 4.dp).background(cs.onSurfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(2.dp)))
                Spacer(Modifier.height(16.dp))
                Text(name, style = MaterialTheme.typography.headlineSmall, color = cs.onSurface, textAlign = TextAlign.Center)
                Text(
                    if (status == Headphones.Status.CONNECTED) "Connected" else "Connecting…",
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))
                Batteries(battery, live, ready = false, onRefresh = {})
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = ::dismiss, modifier = Modifier.weight(1f).height(48.dp)) { Text("Done") }
                    Button(onClick = onOpen, modifier = Modifier.weight(1f).height(48.dp)) { Text("Open Earbuds") }
                }
            }
        }
    }
}
