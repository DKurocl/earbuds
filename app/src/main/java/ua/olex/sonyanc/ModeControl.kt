package ua.olex.sonyanc

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicLong
import ua.olex.sonyanc.CommandScheduler.Priority.CONTROL
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
    private data class ModeRequest(val id: Long, val state: SonyProtocol.NcState, val confirmed: CompletableDeferred<Boolean>)
    private val modeVersion = AtomicLong()
    internal val revision get() = modeVersion.get()
    private val requests = Channel<ModeRequest>(Channel.CONFLATED, onUndeliveredElement = { it.confirmed.complete(false) })
    private var activeRequest: ModeRequest? = null
    @Volatile private var latestReceipt: Deferred<Boolean>? = null

    internal fun isCurrent(receipt: Deferred<Boolean>) = latestReceipt === receipt

    init {
        scope.launch {
            for (requested in requests) {
                var confirmed = false
                try {
                    delay(45) // lets a burst of taps settle on the last one
                    if (modeVersion.get() == requested.id) confirmed = apply(requested.state, requested.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    addLog("✗ mode: ${e.message}")
                    if (modeVersion.get() == requested.id) {
                        _state.update { it.copy(error = "Could not change mode. Try again.") }
                    }
                } finally {
                    synchronized(this@ModeControl) {
                        val latest = modeVersion.get() == requested.id
                        if (latest) {
                            _state.update { it.copy(pending = null) }
                            activeRequest = null
                        }
                        requested.confirmed.complete(latest && confirmed)
                    }
                }
            }
        }
    }

    @Synchronized internal fun cancelModes() {
        modeVersion.incrementAndGet()
        latestReceipt = null
        activeRequest?.confirmed?.complete(false)
        activeRequest = null
        _state.update { it.copy(pending = null) }
        while (true) {
            val old = requests.tryReceive().getOrNull() ?: break
            old.confirmed.complete(false)
        }
    }

    // ---- noise control ------------------------------------------------------------------
    @Synchronized fun request(state: SonyProtocol.NcState): Deferred<Boolean> {
        val current = _state.value
        activeRequest?.takeIf { current.pending == state && it.state == state }?.let { return it.confirmed }
        val previous = activeRequest
        val request = ModeRequest(modeVersion.incrementAndGet(), state, CompletableDeferred())
        latestReceipt = request.confirmed
        _state.update { it.copy(pending = state, error = null) }
        activeRequest = request
        previous?.confirmed?.complete(false)
        requests.trySend(request)
        return request.confirmed
    }

    /** Gate a deferred automation against both intervening choices and a command still in flight. */
    @Synchronized internal fun requestIfUnchanged(
        state: SonyProtocol.NcState,
        revision: Long,
        eligible: () -> Boolean = { true },
    ): Deferred<Boolean>? {
        if (modeVersion.get() != revision || activeRequest != null || !eligible()) return null
        return request(state)
    }

    private fun SonyProtocol.NcState.matches(target: SonyProtocol.NcState): Boolean =
        mode == target.mode && (mode != SonyProtocol.Mode.AMBIENT ||
            voice == target.voice && level == target.level.coerceIn(0, 20))

    private suspend fun apply(state: SonyProtocol.NcState, id: Long): Boolean = commands.run(CONTROL) {
        if (modeVersion.get() != id || !ensureConnectedLocked() || modeVersion.get() != id) return@run false
        val current = _state.value
        if (!current.ncConfirmed || current.nc?.matches(state) != true) {
            var delivered = conn.send(SonyProtocol.ncSet(state))
            // A cached socket can look connected after the earbuds have dropped it.
            if (!delivered && modeVersion.get() == id && ensureConnectedLocked() && modeVersion.get() == id) {
                delivered = conn.send(SonyProtocol.ncSet(state))
            }
            if (modeVersion.get() != id) return@run false
            if (!delivered) {
                _state.update { it.copy(error = "The earbuds did not confirm the command. Try again.") }
                return@run false
            }
            addLog("Mode: asked for ${state.mode.label}")
        }

        val generation = conn.generation
        var reported = readBackLocked()
        if (reported?.matches(state) != true && modeVersion.get() == id && conn.generation == generation) {
            // Firmware can answer before applying the setting. Read again without replaying SET.
            delay(250)
            if (modeVersion.get() == id && conn.connected && conn.generation == generation) reported = readBackLocked()
        }
        if (modeVersion.get() != id || conn.generation != generation) return@run false
        val confirmed = reported?.matches(state) == true
        if (!confirmed) {
            addLog("✗ Mode: read-back did not confirm ${state.mode.label}")
            _state.update { it.copy(error = "Could not verify the requested noise control settings.") }
        }
        confirmed
    }

    /** A push notification updates state but cannot complete an explicit GET. */
    internal suspend fun readBackLocked(): SonyProtocol.NcState? =
        commands.readLocked(900, accepts = { type, payload ->
            type == CMD && payload.firstOrNull() == 0x67.toByte() && SonyProtocol.parseNc(payload) != null
        }) { conn.send(SonyProtocol.NC_GET) }?.let(SonyProtocol::parseNc)

    internal fun setNc(state: SonyProtocol.NcState) {
        val previous = _state.getAndUpdate { it.copy(nc = state, ncConfirmed = true) }
        if (previous.nc != state) Prefs.saveNc(app, state)
        if (previous.nc?.mode != state.mode) Widgets.refreshModes(app)
    }

    internal suspend fun awaitModeIdle(timeoutMs: Long = 2200) =
        withTimeoutOrNull(timeoutMs) { _state.first { it.pending == null } } != null
}
