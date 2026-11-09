package ua.olex.sonyanc

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** Byte transport boundary, also exercised with real streams in JVM tests. */
internal interface MdrStream {
    val connected:Boolean
    val input:InputStream
    val output:OutputStream
    fun close()
}
private class BluetoothStream(private val socket:BluetoothSocket):MdrStream {
    override val connected get()=socket.isConnected
    override val input get()=socket.inputStream
    override val output get()=socket.outputStream
    override fun close()=socket.close()
}

/** WF-1000XM5 transport. Headphones serializes commands; the reader only sends ACKs. */
@SuppressLint("MissingPermission")
class SonyConnection(
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
    private val onPayload: (Int, ByteArray) -> Unit,
    private val ackTimeoutMs:Long = 1200,
    private val onClosed: () -> Unit,
) {
    companion object {
        val SONY_V2_UUID: UUID = UUID.fromString("956C7B26-D49A-4BA8-B03F-B17D393CB6E2")
        private const val CONNECT_TIMEOUT_MS = 2400L
    }

    @Volatile private var socket: MdrStream? = null
    private var reader: Job? = null
    @Volatile private var connecting:BluetoothSocket?=null
    @Volatile private var connectionEpoch=0L
    private val outputLock = Any()
    @Volatile private var seq = 0
    @Volatile private var pendingAck: CompletableDeferred<Boolean>? = null
    val connected get() = socket?.connected == true
    private val sendLock=Mutex()

    suspend fun connect(device: BluetoothDevice,stillNeeded:()->Boolean={true}): Boolean = withContext(Dispatchers.IO) {
        if(!stillNeeded()) return@withContext false
        close(notify = false)
        val expectedEpoch=connectionEpoch
        // The secure V2 service is verified on the owner's WF-1000XM5. The insecure retry was
        // removed: it never helped there and doubled the wait whenever the earbuds were away.
        // No slow attempts at unrelated XM3/SPP services.
        for (secure in listOf(true)) {
            currentCoroutineContext().ensureActive()
            if(connectionEpoch!=expectedEpoch || !stillNeeded()) return@withContext false
            val candidate = if (secure) device.createRfcommSocketToServiceRecord(SONY_V2_UUID)
                else device.createInsecureRfcommSocketToServiceRecord(SONY_V2_UUID)
            val registered=synchronized(this@SonyConnection) {
                if(connectionEpoch!=expectedEpoch || !stillNeeded()) false else {connecting=candidate;true}
            }
            if(!registered) {runCatching{candidate.close()};return@withContext false}
            // BluetoothSocket.connect blocks and ignores coroutine cancellation, so it runs
            // in the independent scope and the caller waits with a deadline. A timeout or a
            // cancelled caller closes the socket, which makes the blocked connect throw.
            val attempt = scope.async(Dispatchers.IO) { runCatching { candidate.connect() } }
            try {
                val result = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { attempt.await() }
                if (result == null) {
                    runCatching { candidate.close() }
                    log("✗ connect: no answer in ${CONNECT_TIMEOUT_MS} ms")
                    continue
                }
                result.exceptionOrNull()?.let { throw it }
                currentCoroutineContext().ensureActive()
                val installed=synchronized(this@SonyConnection) {
                    if(connectionEpoch!=expectedEpoch || !stillNeeded()) false
                    else {connecting=null;attach(BluetoothStream(candidate));true}
                }
                if(!installed) {runCatching{candidate.close()};return@withContext false}
                log("✓ WF-1000XM5 · ${if (secure) "secure" else "insecure"} V2")
                return@withContext true
            } catch (e: CancellationException) {
                runCatching { candidate.close() }
                throw e
            } catch (e: Exception) {
                runCatching { candidate.close() }
                log("✗ connect: ${e.message?.take(80)}")
            } finally {if(connecting===candidate) connecting=null}
        }
        false
    }

    @Synchronized internal fun attach(stream:MdrStream) {
        close(notify=false)
        socket=stream;seq=0
        startReader(stream)
    }

    suspend fun send(payload: ByteArray, type:Byte=SonyProtocol.TYPE_COMMAND): Boolean = sendLock.withLock {
        withContext(Dispatchers.IO) {
            val s=socket ?: return@withContext false
            val ack=CompletableDeferred<Boolean>()
            pendingAck=ack
            // One timer bounds both a blocked write and a missing ACK: closing the stream
            // unblocks the write and completes the ACK with false.
            val watchdog=scope.launch {
                delay(ackTimeoutMs)
                if(socket===s && pendingAck===ack && !ack.isCompleted) {log("✗ ACK timeout; closing link");close()}
            }
            try {
                synchronized(outputLock) {
                    s.output.write(SonyProtocol.frame(type,seq,payload))
                    s.output.flush()
                }
                log("→ [%02X] %s".format(type,SonyProtocol.hex(payload)))
                ack.await()
            } catch(e:CancellationException) {
                if(socket===s) close()
                throw e
            } catch(e:Exception) {
                log("✗ send: ${e.message?.take(80)}")
                if(socket===s) close()
                false
            } finally {
                watchdog.cancel()
                if(pendingAck===ack) pendingAck=null
            }
        }
    }

    private fun startReader(s: MdrStream) {
        reader = scope.launch(Dispatchers.IO) {
            val buf = ByteArrayOutputStream()
            var inFrame = false
            val chunk = ByteArray(512)
            try {
                while (isActive && socket === s) {
                    val n = s.input.read(chunk)
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

    private fun handle(s: MdrStream, f: SonyProtocol.Frame?) {
        if (socket !== s || f == null || !f.checksumOk || f.seq !in 0..1) return
        if (f.type == SonyProtocol.TYPE_ACK.toInt()) {
            if (pendingAck!=null && f.payload.isEmpty() && f.seq == 1 - seq) {
                seq = f.seq
                pendingAck?.complete(true)
            }
            return
        }
        if (f.type !in listOf(SonyProtocol.TYPE_COMMAND.toInt(), SonyProtocol.TYPE_COMMAND_2.toInt())) return
        synchronized(outputLock) {
            s.output.write(SonyProtocol.frame(SonyProtocol.TYPE_ACK, 1 - f.seq, byteArrayOf()))
            s.output.flush()
        }
        log("← [%02X] %s".format(f.type, SonyProtocol.hex(f.payload)))
        if(socket===s) onPayload(f.type,f.payload)
    }

    @Synchronized fun close(notify: Boolean = true) {
        connectionEpoch++
        val attempt=connecting;connecting=null
        runCatching{attempt?.close()}
        val old = socket
        socket = null
        pendingAck?.complete(false)
        pendingAck = null
        reader?.cancel(); reader = null
        runCatching { old?.close() }
        if ((old != null || attempt!=null) && notify) onClosed()
    }
}
