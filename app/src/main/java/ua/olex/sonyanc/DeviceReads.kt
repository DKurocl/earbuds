package ua.olex.sonyanc

import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ua.olex.sonyanc.CommandScheduler.Priority
import ua.olex.sonyanc.Headphones.CMD
import ua.olex.sonyanc.Headphones.addLog
import ua.olex.sonyanc.Headphones.conn
import ua.olex.sonyanc.Headphones.exchange
import ua.olex.sonyanc.Headphones.scope
import ua.olex.sonyanc.Headphones.usage
import ua.olex.sonyanc.Headphones._state
import ua.olex.sonyanc.ModeControl.awaitModeIdle

/** Codec and battery: read once per screen, then the earbuds' own notifications keep them current. */
internal object DeviceReads {
    // ---- background reads while a screen is open ------------------------------------
    @Volatile internal var infoJob: Job? = null
        private set
    /** Set when the earbuds push changes on their own; polling then becomes a slow fallback. */
    @Volatile internal var codecPushed = false
    @Volatile private var pushedBatteryParts = emptySet<Int>()
    internal val batteryPushed get() = pushedBatteryParts.containsAll(buds)
    private val buds = setOf(0, 1)
    private val allParts = setOf(0, 1, 2)

    internal fun batteryNotification(parts: Set<Int>) {
        pushedBatteryParts = pushedBatteryParts + parts
    }

    @Synchronized internal fun stopInfoLoop() {
        infoJob?.cancel()
        infoJob = null
    }

    internal fun invalidate() {
        codecPushed = false
        pushedBatteryParts = emptySet()
        stopInfoLoop()
    }
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
            val generation = conn.generation
            while (usage.foreground && conn.connected && conn.generation == generation) {
                _state.first { it.pending == null || !it.connected }
                if (!usage.foreground || !conn.connected) break
                if (now() >= codecDue) {
                    queryCodec(onlyForeground = true)
                    codecDue = now() + if (codecPushed) 120_000L else 30_000L
                }
                if (!usage.foreground || !conn.connected) break
                // One equalizer read per connection, so Home can name the current preset.
                if (EarbudSettingsLink._equalizer.value == null && !equalizerAsked) { equalizerAsked = true; EarbudSettingsLink.readEqualizer(Priority.BACKGROUND) }
                // Speak-to-Chat once per connection, for its toggle in the dock.
                if (AdvancedSettings.Key.CHAT !in EarbudSettingsLink._advanced.value && !chatAsked) { chatAsked = true; EarbudSettingsLink.readAdvanced(AdvancedSettings.Key.CHAT, Priority.BACKGROUND) }
                if (!qualityKept) { qualityKept = true; EarbudSettingsLink.keepQuality() }
                if (!usage.foreground || !conn.connected) break
                if (now() >= batteryDue) {
                    queryBattery(onlyForeground = true)
                    val complete = _state.value.liveBatteryParts.containsAll(allParts)
                    batteryRetry = if (complete) 8_000L else (batteryRetry * 2).coerceAtMost(60_000L)
                    batteryDue = now() + when {
                        pushedBatteryParts.containsAll(allParts) -> 180_000L
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

    suspend fun queryCodec(onlyForeground: Boolean = false): CodecProtocol.State? = codecLock.withLock {
        _state.update { it.copy(codecRefreshing = true) }
        try {
            if (!awaitModeIdle()) return@withLock null
            val generation = conn.generation
            exchange(900, connect = false, priority = if (onlyForeground) Priority.BACKGROUND else Priority.NORMAL,
                readyToRead = { canRead(generation, onlyForeground) },
                accepts = { type, p -> type == CMD && p.firstOrNull() == 0x13.toByte() && CodecProtocol.parse(p) != null },
            ) { conn.send(CodecProtocol.get) }?.let(CodecProtocol::parse)
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

    /** Coalesce overlapping battery reads on one connection using a monotonic clock. */
    private var lastBatteryRead: Pair<Long, Long>? = null
    private var preferredBudsInquiry = SonyProtocol.BATTERY_GETS.first()
    private var inquiryGeneration = -1L

    private fun canRead(generation: Long, onlyForeground: Boolean): Boolean =
        conn.generation == generation && (!onlyForeground || usage.foreground) && _state.value.pending == null

    suspend fun queryBattery(
        onlyForeground: Boolean = false,
        priority: Priority = if (onlyForeground) Priority.BACKGROUND else Priority.NORMAL,
    ) = batteryLock.withLock {
        val generation = conn.generation
        if (lastBatteryRead?.let { (link, at) -> link == generation && now() - at < 3_000L } == true) return@withLock
        _state.update { it.copy(batteryRefreshing = true) }
        try {
            if (!awaitModeIdle() || !canRead(generation, onlyForeground)) return@withLock
            if (inquiryGeneration != generation) {
                preferredBudsInquiry = SonyProtocol.BATTERY_GETS.first()
                inquiryGeneration = generation
            }
            val caseInquiry = SonyProtocol.BATTERY_GETS.last()
            val alternative = SonyProtocol.BATTERY_GETS.first { it != preferredBudsInquiry && it != caseInquiry }
            var gotBuds = false
            var received = false
            for (inquiry in listOf(preferredBudsInquiry, alternative, caseInquiry)) {
                val isCase = inquiry == caseInquiry
                if (!isCase && gotBuds) continue
                if (!canRead(generation, onlyForeground)) break
                // Notifications still update the cache. Only a reply to this inquiry satisfies the GET.
                val reply = exchange(900, connect = false, priority = priority,
                    readyToRead = { canRead(generation, onlyForeground) },
                    accepts = { type, p ->
                        type == CMD && p.firstOrNull() == 0x23.toByte() && p.getOrNull(1) == inquiry[1] &&
                            batteryFamily(p) != null
                    },
                ) { conn.send(inquiry) }
                if (reply == null || SonyProtocol.liveBatteryParts(reply).isEmpty()) continue
                received = true
                if (!isCase && SonyProtocol.liveBatteryParts(reply).containsAll(buds)) {
                    gotBuds = true
                    preferredBudsInquiry = inquiry
                }
            }
            if (received) {
                lastBatteryRead = generation to now()
            } else if (canRead(generation, onlyForeground)) {
                addLog("Battery: no decoded reply")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            addLog("✗ battery: ${e.message}")
        } finally {
            _state.update { it.copy(batteryRefreshing = false) }
        }
    }
}
