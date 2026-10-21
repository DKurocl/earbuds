package ua.olex.sonyanc

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.UUID

/** One RFCOMM socket + a reader loop. All callbacks arrive on the reader thread. */
@SuppressLint("MissingPermission")
class SonyConnection(
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
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

    /** Called for every incoming command payload (not ACKs). */
    var onPayload: ((ByteArray) -> Unit)? = null

    val connected get() = socket?.isConnected == true

    suspend fun connect(device: BluetoothDevice): Boolean = withContext(Dispatchers.IO) {
        close()

        // UUIDs the headphones advertise via SDP, Sony ones first.
        val advertised = device.uuids?.map { it.uuid }.orEmpty()
        log("SDP: " + (if (advertised.isEmpty()) "(порожньо)" else advertised.joinToString { it.toString().take(8) }))
        val candidates = (listOf(SONY_V2_UUID, SONY_V1_UUID) + advertised.filter {
            it.toString().startsWith("956c7b26") || it.toString().startsWith("96cc203e")
        } + SPP_UUID).distinct()

        val attempts = buildList<Pair<String, () -> BluetoothSocket>> {
            for (u in candidates) {
                add("secure ${u.toString().take(8)}" to { device.createRfcommSocketToServiceRecord(u) })
                add("insecure ${u.toString().take(8)}" to { device.createInsecureRfcommSocketToServiceRecord(u) })
            }
        }
        for ((name, make) in attempts) {
            try {
                log("→ $name")
                val s = make()
                s.connect()
                socket = s
                seq = 0
                startReader(s)
                log("✓ connected ($name)")
                return@withContext true
            } catch (e: Exception) {
                log("✗ ${e.message?.take(60)}")
                Thread.sleep(300)
            }
        }
        log("Не вдалось. Закрий Sound Connect (примусово зупини) і спробуй ще.")
        false
    }

    suspend fun sendCommand(payload: ByteArray) = withContext(Dispatchers.IO) {
        val s = socket ?: run { log("✗ not connected"); return@withContext }
        val frame = SonyProtocol.frame(SonyProtocol.TYPE_COMMAND, seq, payload)
        try {
            s.outputStream.write(frame); s.outputStream.flush()
            log("→ [seq $seq] ${SonyProtocol.hex(payload)}")
        } catch (e: Exception) {
            log("✗ write: ${e.message}")
        }
    }

    private fun sendAck(incomingSeq: Int) {
        val ack = SonyProtocol.frame(SonyProtocol.TYPE_ACK, 1 - incomingSeq, ByteArray(0))
        runCatching { socket?.outputStream?.write(ack) }
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
            } catch (e: Exception) {
                log("✗ read: ${e.message}")
            }
            log("— disconnected")
        }
    }

    private fun handle(f: SonyProtocol.Frame?) {
        if (f == null) { log("← (broken frame)"); return }
        if (f.type == SonyProtocol.TYPE_ACK.toInt()) {
            // Headphones accepted our message: next one uses the seq they acked with.
            seq = f.seq
            log("← ACK (next seq $seq)")
            return
        }
        log("← type %02X [seq ${f.seq}]%s %s".format(
            f.type, if (f.checksumOk) "" else " BAD-CRC", SonyProtocol.hex(f.payload)))
        sendAck(f.seq)
        onPayload?.invoke(f.payload)
    }

    fun close() {
        reader?.cancel(); reader = null
        runCatching { socket?.close() }
        socket = null
    }
}
