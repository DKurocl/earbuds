package ua.olex.sonyanc

import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import ua.olex.sonyanc.Headphones.CMD
import ua.olex.sonyanc.Headphones.Waiter
import ua.olex.sonyanc.Headphones.addLog
import ua.olex.sonyanc.Headphones.app
import ua.olex.sonyanc.Headphones.conn
import ua.olex.sonyanc.Headphones.ensureConnectedLocked
import ua.olex.sonyanc.Headphones.exchange
import ua.olex.sonyanc.Headphones.lock
import ua.olex.sonyanc.Headphones.scope
import ua.olex.sonyanc.Headphones.usage
import ua.olex.sonyanc.Headphones.waiters
import ua.olex.sonyanc.Headphones._state
import ua.olex.sonyanc.ModeControl.awaitModeIdle

/** Codec and battery: read once per screen, then the earbuds' own notifications keep them current. */
internal object DeviceReads {
    // ---- background reads while a screen is open ------------------------------------
    internal var infoJob: Job? = null
    /** Set when the earbuds push changes on their own; polling then becomes a slow fallback. */
    @Volatile internal var codecPushed = false
    @Volatile internal var batteryPushed = false
    private val batteryLock = Mutex()
    private val codecLock = Mutex()

    // ---- codec and battery ----------------------------------------------------------------
    /**
     * While a screen is open: read codec and battery once, then rely on the earbuds' own
     * change notifications. Polling stays only as a slow fallback for firmware that does not
     * push, and backs off when a part (for example a case without a reading) stays unknown.
     */
    @Synchronized internal fun startInfoLoop() {
        if (!usage.foreground || infoJob?.isActive == true) return
        infoJob = scope.launch {
            delay(400)
            var codecDue = 0L
            var batteryDue = 0L
            var batteryRetry = 8_000L
            var equalizerAsked = false
            var chatAsked = false
            var qualityKept = false
            while (usage.foreground && conn.connected) {
                _state.first { it.pending == null || !it.connected }
                if (!usage.foreground || !conn.connected) break
                if (now() >= codecDue) {
                    queryCodec()
                    codecDue = now() + if (codecPushed) 120_000L else 30_000L
                }
                if (!usage.foreground || !conn.connected) break
                // One equalizer read per connection, so Home can name the current preset.
                if (EarbudSettingsLink._equalizer.value == null && !equalizerAsked) { equalizerAsked = true; EarbudSettingsLink.readEqualizer() }
                // Speak-to-Chat once per connection, for its toggle in the dock.
                if (AdvancedSettings.Key.CHAT !in EarbudSettingsLink._advanced.value && !chatAsked) { chatAsked = true; EarbudSettingsLink.readAdvanced(AdvancedSettings.Key.CHAT) }
                if (!qualityKept) { qualityKept = true; EarbudSettingsLink.keepQuality() }
                if (!usage.foreground || !conn.connected) break
                if (now() >= batteryDue) {
                    queryBattery()
                    val complete = _state.value.liveBatteryParts.containsAll(setOf(0, 1, 2))
                    batteryRetry = if (complete) 8_000L else (batteryRetry * 2).coerceAtMost(60_000L)
                    batteryDue = now() + when {
                        batteryPushed -> 180_000L
                        complete -> 60_000L
                        else -> batteryRetry
                    }
                }
                val wait = (minOf(codecDue, batteryDue) - now()).coerceAtLeast(1_000L)
                withTimeoutOrNull(wait) { _state.first { !it.connected } }
            }
        }
    }

    internal fun now() = SystemClock.elapsedRealtime()

    suspend fun queryCodec(): CodecProtocol.State? = codecLock.withLock {
        _state.update { it.copy(codecRefreshing = true) }
        try {
            if (!awaitModeIdle()) return@withLock null
            exchange(900, connect = false, accepts = { type, p -> type == CMD && CodecProtocol.parse(p) != null }) {
                conn.send(CodecProtocol.get)
            }?.let(CodecProtocol::parse)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            addLog("✗ codec: ${e.message}"); null
        } finally {
            _state.update { it.copy(codecRefreshing = false) }
        }
    }

    internal fun batteryFamily(p: ByteArray) =
        if (SonyProtocol.parseBattery(p, SonyProtocol.Battery()) == null) null else if (p[1].toInt() == 0x0A) 2 else 1

    /** When a battery read last got an answer: callers that line up behind it reuse it. */
    @Volatile private var lastBatteryRead = 0L

    suspend fun queryBattery() = batteryLock.withLock {
        // On connect several parts ask at once (the connect sync, Stay in sync, the screen): the
        // ones that waited behind a read that just answered take its result instead of asking again.
        if (System.currentTimeMillis() - lastBatteryRead < 3_000L) return@withLock
        _state.update { it.copy(batteryRefreshing = true) }
        var received = false
        try {
            // 01 and 09 are both documented Sony V2 dual-battery inquiry formats; 0A is the case.
            for (q in SonyProtocol.BATTERY_GETS) {
                val family = if (q[1].toInt() == 0x0A) 2 else 1
                if (family == 1 && received && _state.value.liveBatteryParts.containsAll(setOf(0, 1))) continue
                if (!awaitModeIdle()) continue
                val reply = exchange(900, connect = false, accepts = { type, p -> type == CMD && batteryFamily(p) == family }) {
                    conn.send(q)
                }
                if (reply != null && SonyProtocol.liveBatteryParts(reply).isNotEmpty()) received = true
                yield()
            }
            if (!received) addLog("Battery: no decoded reply; requested 22 01 / 22 09 / 22 0A")
            else lastBatteryRead = System.currentTimeMillis()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            addLog("✗ battery: ${e.message}")
        } finally {
            _state.update { it.copy(batteryRefreshing = false) }
        }
    }
}
