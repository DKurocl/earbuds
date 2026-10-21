package ua.olex.sonyanc

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.util.UUID

/** WF-1000XM5 transport. Headphones serializes commands; the reader only sends ACKs. */
@SuppressLint("MissingPermission")
class SonyConnection(
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
    private val onPayload: (ByteArray) -> Unit,
    private val onClosed: () -> Unit,
) {
    companion object {
        val SONY_V2_UUID: UUID = UUID.fromString("956C7B26-D49A-4BA8-B03F-B17D393CB6E2")
    }

    @Volatile private var socket: BluetoothSocket? = null
    private var reader: Job? = null
    private val outputLock = Any()
    @Volatile private var seq = 0
    @Volatile private var pendingAck: CompletableDeferred<Boolean>? = null
    val connected get() = socket?.isConnected == true

    suspend fun connect(device: BluetoothDevice): Boolean = withContext(Dispatchers.IO) {
        close(notify = false)
        // The secure V2 service is verified on the owner's WF-1000XM5.
        // No slow attempts at unrelated XM3/SPP services.
        for (secure in listOf(true, false)) {
            currentCoroutineContext().ensureActive()
            val candidate = if (secure) device.createRfcommSocketToServiceRecord(SONY_V2_UUID)
                else device.createInsecureRfcommSocketToServiceRecord(SONY_V2_UUID)
            // BluetoothSocket.connect is blocking: cancellation MUST close the socket.
            // A child of an independent scope also covers a cancelled caller/widget timeout.
            val caller = currentCoroutineContext().job
            val watchdog = scope.launch {
                var elapsed = 0
                while (caller.isActive && elapsed < 2400) { delay(50); elapsed += 50 }
                runCatching { candidate.close() }
            }
            try {
                candidate.connect()
                currentCoroutineContext().ensureActive()
                watchdog.cancel()
                socket = candidate
                seq = 0
                startReader(candidate)
                log("✓ WF-1000XM5 · ${if (secure) "secure" else "insecure"} V2")
                return@withContext true
            } catch (e: CancellationException) {
                runCatching { candidate.close() }
                throw e
            } catch (e: Exception) {
                runCatching { candidate.close() }
                log("✗ connect: ${e.message?.take(80)}")
            } finally { watchdog.cancel() }
        }
        false
    }

    suspend fun send(payload: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val s = socket ?: return@withContext false
        val ack = CompletableDeferred<Boolean>()
        pendingAck = ack
        try {
            synchronized(outputLock) {
                s.outputStream.write(SonyProtocol.frame(SonyProtocol.TYPE_COMMAND, seq, payload))
                s.outputStream.flush()
            }
            log("→ ${SonyProtocol.hex(payload)}")
            val ok = withTimeoutOrNull(1200) { ack.await() } == true
            if (!ok) {
                // A late ACK must never satisfy the next command or corrupt its sequence.
                log("✗ ACK timeout; closing link")
                close()
            }
            ok
        } catch (e: CancellationException) {
            close()
            throw e
        } catch (e: Exception) {
            log("✗ send: ${e.message?.take(80)}")
            close()
            false
        } finally { if (pendingAck === ack) pendingAck = null }
    }

    private fun startReader(s: BluetoothSocket) {
        reader = scope.launch(Dispatchers.IO) {
            val buf = ByteArrayOutputStream()
            var inFrame = false
            val chunk = ByteArray(512)
            try {
                while (isActive && socket === s) {
                    val n = s.inputStream.read(chunk)
                    if (n < 0) break
                    for (i in 0 until n) {
                        when (val b = chunk[i]) {
                            SonyProtocol.START -> { buf.reset(); inFrame = true }
                            SonyProtocol.END -> if (inFrame) {
                                inFrame = false
                                handle(s, SonyProtocol.parse(buf.toByteArray()))
                            }
                            else -> if (inFrame) {
                                if (buf.size() >= 8192) { inFrame = false; buf.reset() }
                                else buf.write(b.toInt())
                            }
                        }
                    }
                }
            } catch (_: Exception) { /* close below wakes a pending send */ }
            finally { if (socket === s) close() }
        }
    }

    private fun handle(s: BluetoothSocket, f: SonyProtocol.Frame?) {
        if (socket !== s || f == null || !f.checksumOk || f.seq !in 0..1) return
        if (f.type == SonyProtocol.TYPE_ACK.toInt()) {
            if (f.payload.isEmpty() && f.seq == 1 - seq) {
                seq = f.seq
                pendingAck?.complete(true)
            }
            return
        }
        if (f.type != SonyProtocol.TYPE_COMMAND.toInt()) return
        synchronized(outputLock) {
            s.outputStream.write(SonyProtocol.frame(SonyProtocol.TYPE_ACK, 1 - f.seq, byteArrayOf()))
            s.outputStream.flush()
        }
        log("← ${SonyProtocol.hex(f.payload)}")
        onPayload(f.payload)
    }

    @Synchronized fun close(notify: Boolean = true) {
        val old = socket
        socket = null
        pendingAck?.complete(false)
        pendingAck = null
        reader?.cancel(); reader = null
        runCatching { old?.close() }
        if (old != null && notify) onClosed()
    }
}
