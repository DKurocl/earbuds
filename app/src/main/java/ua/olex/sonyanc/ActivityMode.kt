package ua.olex.sonyanc

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ua.olex.sonyanc.SonyProtocol.Mode

/**
 * "Ambient while walking": Ambient when the owner starts walking (hear the street), noise
 * cancelling once they have stood or sat still for a minute. It listens to the phone's step
 * detector, a low-power hardware sensor, and only while the earbuds are connected (Stay in sync
 * keeps the app present then). Without GPS, Play services or polling.
 *
 * It never fights the owner: Off is left alone, and a mode chosen by hand (app, widget, tile or
 * a touch on the earbuds) holds for [HOLD_MS] before walking may change it again.
 */
object ActivityMode {
    private const val WALK_STEPS = 8
    private const val WALK_WINDOW_MS = 10_000L
    private const val STILL_MS = 60_000L
    private const val HOLD_MS = 10 * 60_000L

    private var listener: SensorEventListener? = null
    private var watch: Job? = null
    private val steps = ArrayDeque<Long>()
    @Volatile private var walking = false
    @Volatile private var lastStep = 0L
    @Volatile private var lastAuto: Mode? = null
    @Volatile private var heldUntil = 0L

    fun allowed(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    /** Pure, unit-tested: what walking or stillness asks for, given the mode now; null for no change. */
    fun decide(now: Mode?, walking: Boolean): Mode? = when {
        now == null || now == Mode.OFF -> null
        walking && now == Mode.ANC -> Mode.AMBIENT
        !walking && now == Mode.AMBIENT -> Mode.ANC
        else -> null
    }

    /** Starts or stops listening: on only with the option, the permission and the earbuds present. */
    @Synchronized fun update(ctx: Context) {
        val want = Prefs.activityMode(ctx) && allowed(ctx) && Headphones.staying
        if (want == (listener != null)) return
        val sensors = ctx.getSystemService(SensorManager::class.java) ?: return
        if (!want) {
            listener?.let(sensors::unregisterListener); listener = null
            watch?.cancel(); watch = null
            steps.clear(); walking = false
            return
        }
        val detector = sensors.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) ?: return Headphones.addLog("✗ Ambient while walking: no step sensor")
        val l = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) = step()
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        // Batched by the sensor hub for up to 5 s: the phone's processor sleeps between steps.
        sensors.registerListener(l, detector, SensorManager.SENSOR_DELAY_NORMAL, 5_000_000)
        listener = l
        watch = Headphones.scope.launch {
            var seen = Headphones.state.value.nc?.mode
            while (isActive) {
                delay(5_000)
                val now = SystemClock.elapsedRealtime()
                // A change nobody here asked for was made by hand: hold off for a while.
                val mode = Headphones.state.value.nc?.mode
                if (mode != seen && mode != lastAuto) heldUntil = now + HOLD_MS
                seen = mode
                if (walking && now - lastStep > STILL_MS) walking = false
                if (now < heldUntil || Headphones.state.value.pending != null) continue
                val target = decide(mode, walking) ?: continue
                Headphones.addLog("· Ambient while walking: ${if (walking) "walking" else "still"}, ${target.short}")
                lastAuto = target
                val base = Headphones.state.value.nc ?: SonyProtocol.NcState(target)
                ModeControl.request(base.copy(mode = target))
            }
        }
        Headphones.addLog("· Ambient while walking: listening for steps")
    }

    @Synchronized private fun step() {
        val now = SystemClock.elapsedRealtime()
        lastStep = now
        steps.addLast(now)
        while (steps.isNotEmpty() && now - steps.first() > WALK_WINDOW_MS) steps.removeFirst()
        if (steps.size >= WALK_STEPS) walking = true
    }
}
