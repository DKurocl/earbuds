package ua.olex.sonyanc

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/** Only one request owns the wire, including the time spent waiting for its read-back. */
internal class CommandScheduler {
    enum class Priority(val label: String) { CONTROL("Controls"), NORMAL("Reads"), BACKGROUND("Background") }
    data class Timing(val queueMs: Long, val wireMs: Long)

    private val _timings = MutableStateFlow<Map<Priority, List<Timing>>>(emptyMap())
    val timings = _timings.asStateFlow()

    fun clearTimings() { _timings.value = emptyMap() }

    private class Turn(val priority: Priority) {
        val ready = CompletableDeferred<Unit>()
    }

    private val queue = ArrayDeque<Turn>()
    private var active: Turn? = null

    private class Reply(val accepts: (Int, ByteArray) -> Boolean) {
        val result = CompletableDeferred<ByteArray?>()
    }

    @Volatile private var reply: Reply? = null

    // A command already on the wire holds its turn through read-back. Priority only reorders waiting turns.
    suspend fun <T> run(priority: Priority = Priority.NORMAL, command: suspend () -> T): T {
        val turn = Turn(priority)
        val queuedAt = System.nanoTime()
        synchronized(queue) {
            if (active == null) {
                active = turn
                turn.ready.complete(Unit)
            } else {
                queue.addLast(turn)
            }
        }
        try {
            turn.ready.await()
            currentCoroutineContext().ensureActive()
            val startedAt = System.nanoTime()
            val result = command()
            val sample = Timing((startedAt - queuedAt) / 1_000_000L, (System.nanoTime() - startedAt) / 1_000_000L)
            _timings.update { it + (priority to (it[priority].orEmpty().takeLast(63) + sample)) }
            return result
        } finally {
            synchronized(queue) {
                if (active == turn) {
                    val next = queue.minByOrNull { it.priority }
                    active = next
                    if (next != null) {
                        queue.remove(next)
                        next.ready.complete(Unit)
                    }
                } else {
                    queue.remove(turn)
                }
            }
        }
    }

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
