package ua.olex.sonyanc

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

/**
 * Noise-control requests: a burst of taps settles on the newest choice, each choice is sent once
 * under the wire lock, and the earbuds' own report is what finally decides the mode shown.
 */
internal object ModeControl {
    // ---- mode requests ----------------------------------------------------------------
    private data class ModeRequest(val id: Long, val state: SonyProtocol.NcState, val delivered: CompletableDeferred<Boolean>)
    private val modeVersion = AtomicLong()
    private val requests = Channel<ModeRequest>(Channel.CONFLATED, onUndeliveredElement = { it.delivered.complete(false) })
    private var activeRequest: ModeRequest? = null

    init {
        scope.launch {
            for (requested in requests) {
                try {
                    delay(45) // lets a burst of taps settle on the last one
                    if (modeVersion.get() == requested.id) apply(requested.state, requested.id) { requested.delivered.complete(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    addLog("✗ mode: ${e.message}")
                    _state.update { it.copy(error = "Could not change mode. Try again.") }
                } finally {
                    requested.delivered.complete(false)
                    synchronized(this@ModeControl) {
                        if (modeVersion.get() == requested.id) {
                            _state.update { it.copy(pending = null) }
                            activeRequest = null
                        }
                    }
                }
            }
        }
    }

    @Synchronized internal fun cancelModes() {
        modeVersion.incrementAndGet()
        activeRequest?.delivered?.complete(false)
        activeRequest = null
        _state.update { it.copy(pending = null) }
        while (true) {
            val old = requests.tryReceive().getOrNull() ?: break
            old.delivered.complete(false)
        }
    }

    // ---- noise control ------------------------------------------------------------------
    @Synchronized fun request(state: SonyProtocol.NcState): Deferred<Boolean> {
        val current = _state.value
        activeRequest?.takeIf { current.pending == state && it.state == state }?.let { return it.delivered }
        if (current.pending == null && current.ncConfirmed && current.nc == state && conn.connected) return CompletableDeferred(true)
        val request = ModeRequest(modeVersion.incrementAndGet(), state, CompletableDeferred())
        _state.update { it.copy(pending = state, error = null) }
        activeRequest = request
        requests.trySend(request)
        return request.delivered
    }

    private suspend fun apply(state: SonyProtocol.NcState, id: Long, onDelivered: (Boolean) -> Unit): Boolean = lock.withLock {
        if (!ensureConnectedLocked() || modeVersion.get() != id) { onDelivered(false); return@withLock false }
        _state.value.let { if (it.ncConfirmed && it.nc == state) { onDelivered(true); return@withLock true } }
        var ok = conn.send(SonyProtocol.ncSet(state))
        // A cached socket can look connected after the earbuds have dropped it.
        // Reconnect once for the latest choice; never replay a superseded choice.
        if (!ok && modeVersion.get() == id && ensureConnectedLocked() && modeVersion.get() == id) ok = conn.send(SonyProtocol.ncSet(state))
        if (!ok) {
            onDelivered(false)
            _state.update { it.copy(error = "The earbuds did not confirm the command. Try again.") }
            return@withLock false
        }
        addLog("Mode: asked for ${state.mode.label}")
        setNc(state, confirmed = false)
        onDelivered(true) // Widget lifetime depends on delivery, not on the optional read-back.
        if (modeVersion.get() != id) return@withLock true
        // Read back while still holding the wire, so the next command waits for the device.
        var reported = readBack()
        if (reported != null && reported.mode != state.mode && modeVersion.get() == id) {
            // The earbuds answered with another mode: a stale reply or a refusal. Ask once more,
            // then believe them (their report already updated the screen through decode).
            addLog("Mode: earbuds answered ${reported.mode.label}; asking again")
            delay(250)
            if (modeVersion.get() == id && conn.send(SonyProtocol.ncSet(state))) reported = readBack()
            if (reported != null && reported.mode != state.mode && modeVersion.get() == id) {
                addLog("✗ Mode: earbuds stayed in ${reported.mode.label}")
                _state.update { it.copy(error = "The earbuds stayed in ${reported.mode.label}. Another app may be controlling them.") }
            }
        }
        true
    }

    /** Asks the earbuds for their noise control and waits briefly for the answer. */
    private suspend fun readBack(): SonyProtocol.NcState? {
        val waiter = Waiter { type, p -> type == CMD && SonyProtocol.parseNc(p) != null }
        waiters += waiter
        try {
            return if (conn.send(SonyProtocol.NC_GET)) withTimeoutOrNull(900) { waiter.reply.await() }?.let(SonyProtocol::parseNc) else null
        } finally {
            waiters -= waiter
        }
    }

    internal fun setNc(state: SonyProtocol.NcState, confirmed: Boolean) {
        _state.update { it.copy(nc = state, ncConfirmed = confirmed) }
        Prefs.saveNc(app, state)
        ModeWidget.refreshAll(app)
    }

    internal suspend fun awaitModeIdle(timeoutMs: Long = 2200) =
        withTimeoutOrNull(timeoutMs) { _state.first { it.pending == null } } != null
}
