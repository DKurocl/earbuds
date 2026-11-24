package ua.olex.sonyanc

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class CommandSchedulerTest {
    private val accepts = { type: Int, payload: ByteArray -> type == 0x0C && payload[0] == 0x67.toByte() }

    @Test fun queuedReadCannotConsumeTheActiveReply() = runBlocking {
        withTimeout(2_000) {
            val scheduler = CommandScheduler()
            val firstSent = CompletableDeferred<Unit>()
            val firstReply = byteArrayOf(0x67, 1)
            val secondReply = byteArrayOf(0x67, 2)
            val first = async {
                scheduler.run {
                    scheduler.readLocked(1_500, accepts) {
                        firstSent.complete(Unit)
                        true
                    }
                }
            }
            firstSent.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                scheduler.run {
                    scheduler.readLocked(1_500, accepts) {
                        scheduler.received(0x0C, secondReply) // arrives before the send returns its ACK
                        true
                    }
                }
            }

            assertFalse(second.isCompleted)
            scheduler.received(0x0C, byteArrayOf(0x69, 3))
            assertFalse(first.isCompleted)
            scheduler.received(0x0C, firstReply)
            assertArrayEquals(firstReply, first.await())
            assertArrayEquals(secondReply, second.await())
        }
    }

    @Test fun disconnectWakesReadAndTheNextConnectionGetsItsOwnReply() = runBlocking {
        withTimeout(2_000) {
            val scheduler = CommandScheduler()
            val firstSent = CompletableDeferred<Unit>()
            val first = async {
                scheduler.run {
                    scheduler.readLocked(1_500, accepts) { firstSent.complete(Unit); true }
                }
            }
            firstSent.await()
            scheduler.disconnected()
            assertNull(first.await())
            val nextReply = byteArrayOf(0x67, 2)
            val next = scheduler.run {
                scheduler.readLocked(1_500, accepts) {
                    scheduler.received(0x0C, nextReply)
                    true
                }
            }
            assertArrayEquals(nextReply, next)
        }
    }
}
