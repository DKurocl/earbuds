package ua.olex.sonyanc

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * One RFCOMM socket + a reader loop. Not thread-safe for concurrent sends:
 * callers serialize (see [Headphones]). The protocol is stop-and-wait —
 * each command must be ACKed before the next one, so [send] waits for it.
 */
@SuppressLint("MissingPermission")
class SonyConnection(
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
    private val onPayload: (ByteArray) -> Unit,
    private val onClosed: () -> Unit,
) {
    companion object {
        /** Newer Sony devices (XM5 generation, LinkBuds…) — protocol V2. */
        val SONY_V2_UUID: UUID = UUID.fromString("956C7B26-D49A-4BA8-B03F-B17D393CB6E2")
        /** Older Sony devices (XM3/XM4) — protocol V1. */
        val SONY_V1_UUID: UUID = UUID.fromString("96CC203E-5068-46AD-B32D-E316F5E069BA")
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private var socket: BluetoothSocket? = null
    private var reader: Job? = null
    private var seq = 0
    @Volatile private var pendingAck: CompletableDeferred<Unit>? = null

    val connected get() = socket?.isConnected == true

    suspend fun connect(device: BluetoothDevice): Boolean = withContext(Dispatchers.IO) {
        close(notify = false)
        val candidates = listOf(SONY_V2_UUID, SONY_V1_UUID, SPP_UUID)
        val attempts = buildList<Pair<String, () -> BluetoothSocket>> {
            for (u in candidates) {
                add("secure ${u.toString().take(8)}" to { device.createRfcommSocketToServiceRecord(u) })
                add("insecure ${u.toString().take(8)}" to { device.createInsecureRfcommSocketToServiceRecord(u) })
            }
        }
        for ((name, make) in attempts) {
            try {
                val s = make()
                s.connect()
                socket = s
                seq = 0
                startReader(s)
                log("✓ connected ($name)")
                return@withContext true
            } catch (e: Exception) {
                log("✗ $name: ${e.message?.take(50)}")
                delay(200)
            }
        }
        false
    }

    /** Sends one command and waits (up to 1.5 s) for the ACK. Returns true if ACKed. */
    suspend fun send(payload: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val s = socket ?: return@withContext false
        val ack = CompletableDeferred<Unit>()
        pendingAck = ack
        try {
            s.outputStream.write(SonyProtocol.frame(SonyProtocol.TYPE_COMMAND, seq, payload))
            s.outputStream.flush()
            log("→ [seq $seq] ${SonyProtocol.hex(payload)}")
        } catch (e: Exception) {
            log("✗ write: ${e.message}")
            close()
            return@withContext false
        }
        val ok = withTimeoutOrNull(1500) { ack.await() } != null
        if (!ok) log("… no ACK")
        ok
    }

    private fun sendAck(incomingSeq: Int) {
        val frame = SonyProtocol.frame(SonyProtocol.TYPE_ACK, 1 - incomingSeq, ByteArray(0))
        runCatching { socket?.outputStream?.write(frame) }
    }

    private fun startReader(s: BluetoothSocket) {
        reader = scope.launch(Dispatchers.IO) {
            val input = s.inputStream
            val buf = ByteArrayOutputStream()
            var inFrame = false
            val chunk = ByteArray(512)
            try {
                while (isActive) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    for (i in 0 until n) {
                        val b = chunk[i]
                        when {
                            b == SonyProtocol.START -> { buf.reset(); inFrame = true }
                            b == SonyProtocol.END && inFrame -> {
                                inFrame = false
                                handle(SonyProtocol.parse(buf.toByteArray()))
                            }
                            inFrame -> buf.write(b.toInt())
                        }
                    }
                }
            } catch (e: Exception) { }
            if (socket === s) close()
        }
    }

    private fun handle(f: SonyProtocol.Frame?) {
        if (f == null) { log("← (broken frame)"); return }
        if (f.type == SonyProtocol.TYPE_ACK.toInt()) {
            seq = f.seq            // next message uses the seq the device acked with
            pendingAck?.complete(Unit)
            return
        }
        log("← %02X [seq ${f.seq}] %s".format(f.type, SonyProtocol.hex(f.payload)))
        sendAck(f.seq)
        onPayload(f.payload)
    }

    fun close(notify: Boolean = true) {
        val had = socket != null
        reader?.cancel(); reader = null
        runCatching { socket?.close() }
        socket = null
        pendingAck?.cancel(); pendingAck = null
        if (had && notify) { log("— disconnected"); onClosed() }
    }
}
