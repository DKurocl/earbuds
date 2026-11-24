package ua.olex.sonyanc

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Only one request owns the wire, including the time spent waiting for its read-back. */
internal class CommandScheduler {
    private val wire = Mutex()

    private class Reply(val accepts: (Int, ByteArray) -> Boolean) {
        val result = CompletableDeferred<ByteArray?>()
    }

    @Volatile private var reply: Reply? = null

    suspend fun <T> run(command: suspend () -> T): T = wire.withLock { command() }

    /** Within [run], arm the matcher before sending the GET so fast replies can arrive before ACK. */
    suspend fun readLocked(
        timeoutMs: Long,
        accepts: (Int, ByteArray) -> Boolean,
        send: suspend () -> Boolean,
    ): ByteArray? {
        check(reply == null)
        val current = Reply(accepts)
        reply = current
        try {
            return if (send()) withTimeoutOrNull(timeoutMs) { current.result.await() } else null
        } finally {
            reply = null
        }
    }

    fun received(type: Int, payload: ByteArray) {
        val current = reply ?: return
        if (current.accepts(type, payload)) current.result.complete(payload)
    }

    fun disconnected() {
        reply?.result?.complete(null)
    }
}
