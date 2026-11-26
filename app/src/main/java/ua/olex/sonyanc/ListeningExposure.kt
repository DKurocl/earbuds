package ua.olex.sonyanc

import android.media.AudioManager
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlin.math.pow

/** The WF-1000XM5 exposes a level per period over MDR v2, command type 2. */
internal object ListeningPressure {
    val variants = listOf(0x01, 0x03)

    fun isReply(payload: ByteArray, variant: Int): Boolean =
        payload.size == 4 && payload[0] == 0x5B.toByte() && payload[1].toInt() == variant

    fun level(payload: ByteArray): Int? = payload.getOrNull(2)?.let { it.toInt() and 0xFF }?.takeIf { it in 1..140 }

    fun unavailable(payload: ByteArray): String? = when (payload.getOrNull(3)?.toInt()) {
        0 -> "Play audio to measure"
        1 -> "Paused during calls"
        2 -> "Put in your earbuds"
        else -> null
    }

    suspend fun read(variant: Int): ByteArray? {
        val bus = SonyProtocol.TYPE_COMMAND_2
        return Headphones.exchange(700, connect = false, priority = CommandScheduler.Priority.BACKGROUND,
            readyToRead = { Headphones.usage.foreground },
            accepts = { type, payload -> type == bus.toInt() && isReply(payload, variant) },
        ) { Headphones.conn.send(byteArrayOf(0x5A, variant.toByte()), bus) }
    }
}

data class ListeningExposure(val level: Int?, val fraction: Double, val unavailable: String? = null) {
    internal val percent: Int get() = (fraction * 100).toInt()
}

internal object ExposureLog {
    const val HOUR_MS = 3_600_000L
    private const val HOURS = 7 * 24
    private const val REFERENCE_SECONDS = 40 * 3600.0

    // WHO–ITU adult reference: equivalent acoustic energy of 80 dB for 40 hours.
    fun fraction(first: Int, last: Int, seconds: Double): Double =
        (10.0.pow((first - 80) / 10.0) + 10.0.pow((last - 80) / 10.0)) * .5 * seconds / REFERENCE_SECONDS

    fun decode(raw: String?, now: Long): MutableMap<Long, Double> {
        val cutoff = now / HOUR_MS - HOURS + 1
        return raw.orEmpty().split(';').mapNotNull { entry ->
            val hour = entry.substringBefore(':').toLongOrNull()?.takeIf { it in cutoff..(now / HOUR_MS) }
            val amount = entry.substringAfter(':').toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
            if (hour != null && amount != null) hour to amount else null
        }.toMap().toMutableMap()
    }

    fun add(values: MutableMap<Long, Double>, now: Long, amount: Double) {
        val hour = now / HOUR_MS
        values.keys.removeAll { it !in (hour - HOURS + 1)..hour }
        values[hour] = (values[hour] ?: 0.0) + amount
    }

    fun encode(values: Map<Long, Double>): String = values.entries.joinToString(";") { "${it.key}:${it.value}" }
}

@Composable
internal fun rememberListeningExposure(enabled: Boolean, address: String?): ListeningExposure? {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var shown by remember(address) { mutableStateOf<ListeningExposure?>(null) }
    LaunchedEffect(enabled, address, lifecycle) {
        if (!enabled || address == null) {
            shown = shown?.copy(level = null)
            return@LaunchedEffect
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val audio = context.getSystemService(AudioManager::class.java)
            val prefs = Prefs.p(context)
            val values = ExposureLog.decode(prefs.getString("listening_exposure", null), System.currentTimeMillis())
            shown = values.values.sum().takeIf { it > 0 }?.let { ListeningExposure(null, it) }
            var dirty = false
            var lastSaved = SystemClock.elapsedRealtime()
            var generation: Long? = null
            var variant: Int? = null
            var nextProbe = 0L
            var previous: Pair<Int, Long>? = null
            fun save() {
                if (dirty && Prefs.deviceAddress(context) == address) {
                    prefs.edit().putString("listening_exposure", ExposureLog.encode(values)).apply()
                    dirty = false
                }
            }
            try {
                while (true) {
                    val connection = Headphones.conn.generation
                    if (generation != connection) {
                        generation = connection
                        variant = null
                        nextProbe = 0L
                        previous = null
                    }
                    val payload = if (SystemClock.elapsedRealtime() >= nextProbe && Headphones.state.value.connected &&
                        Headphones.state.value.pending == null) {
                        val known = variant
                        if (known != null) ListeningPressure.read(known)
                        else {
                            var reply: ByteArray? = null
                            for (candidate in ListeningPressure.variants) {
                                reply = ListeningPressure.read(candidate)
                                if (reply != null) { variant = candidate; break }
                            }
                            if (reply == null) nextProbe = SystemClock.elapsedRealtime() + 60_000L
                            reply
                        }
                    } else null
                    val level = payload?.let(ListeningPressure::level)
                    val elapsed = SystemClock.elapsedRealtime()
                    val now = System.currentTimeMillis()
                    if (level != null && generation == Headphones.conn.generation) {
                        previous?.let { (prior, at) ->
                            val duration = elapsed - at
                            if (duration in 1..21_000L) {
                                ExposureLog.add(values, now, ExposureLog.fraction(prior, level, duration / 1000.0))
                                dirty = true
                            }
                        }
                        previous = level to elapsed
                        shown = ListeningExposure(level, values.values.sum())
                    } else {
                        previous = null
                        shown = ListeningExposure(null, values.values.sum(), payload?.let(ListeningPressure::unavailable) ?: "No current reading")
                    }
                    if (elapsed - lastSaved >= 60_000L) { save(); lastSaved = elapsed }
                    delay(if (level != null || audio?.isMusicActive == true) 15_000 else 30_000)
                }
            } finally {
                save()
                shown = shown?.copy(level = null)
            }
        }
    }
    return shown
}
