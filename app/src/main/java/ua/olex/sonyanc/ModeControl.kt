package ua.olex.sonyanc

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicLong
import ua.olex.sonyanc.Headphones.CMD
import ua.olex.sonyanc.Headphones.addLog
import ua.olex.sonyanc.Headphones.app
import ua.olex.sonyanc.Headphones.commands
import ua.olex.sonyanc.Headphones.conn
import ua.olex.sonyanc.Headphones.ensureConnectedLocked
import ua.olex.sonyanc.Headphones.scope
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

    private fun SonyProtocol.NcState.matches(target: SonyProtocol.NcState): Boolean =
        mode == target.mode && (mode != SonyProtocol.Mode.AMBIENT ||
            voice == target.voice && level == target.level.coerceIn(0, 20))

    private suspend fun apply(state: SonyProtocol.NcState, id: Long, onDelivered: (Boolean) -> Unit) = commands.run {
        if (modeVersion.get() != id || !ensureConnectedLocked() || modeVersion.get() != id) {
            onDelivered(false)
            return@run
        }
        val current = _state.value
        if (current.ncConfirmed && current.nc?.matches(state) == true) {
            onDelivered(true)
            return@run
        }

        var ok = conn.send(SonyProtocol.ncSet(state))
        // A cached socket can look connected after the earbuds have dropped it.
        if (!ok && modeVersion.get() == id && ensureConnectedLocked() && modeVersion.get() == id) {
            ok = conn.send(SonyProtocol.ncSet(state))
        }
        onDelivered(ok)
        if (modeVersion.get() != id) return@run
        if (!ok) {
            _state.update { it.copy(error = "The earbuds did not confirm the command. Try again.") }
            return@run
        }
        addLog("Mode: asked for ${state.mode.label}")

        var reported = readBackLocked()
        if (reported != null && !reported.matches(state) && modeVersion.get() == id) {
            // Some firmware answers before it applies a setting. Read again without replaying SET.
            delay(250)
            if (modeVersion.get() == id && conn.connected) reported = readBackLocked()
        }
        if (modeVersion.get() != id) return@run
        if (reported?.matches(state) != true && !(_state.value.ncConfirmed && _state.value.nc?.matches(state) == true)) {
            addLog("✗ Mode: read-back did not confirm ${state.mode.label}")
            _state.update { it.copy(error = "Could not verify the requested noise control settings.") }
        }
    }

    /** A push notification updates state but cannot complete an explicit GET. */
    internal suspend fun readBackLocked(): SonyProtocol.NcState? =
        commands.readLocked(900, accepts = { type, payload ->
            type == CMD && payload.firstOrNull() == 0x67.toByte() && SonyProtocol.parseNc(payload) != null
        }) { conn.send(SonyProtocol.NC_GET) }?.let(SonyProtocol::parseNc)

    internal fun setNc(state: SonyProtocol.NcState) {
        _state.update { it.copy(nc = state, ncConfirmed = true) }
        Prefs.saveNc(app, state)
        ModeWidget.refreshAll(app)
    }

    internal suspend fun awaitModeIdle(timeoutMs: Long = 2200) =
        withTimeoutOrNull(timeoutMs) { _state.first { it.pending == null } } != null
}
