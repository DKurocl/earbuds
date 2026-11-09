package ua.olex.sonyanc

import java.io.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SonyConnectionTest {
    private class Stream(val autoAck:Boolean=true,val blockWrite:Boolean=false):MdrStream {
        private val closed=AtomicBoolean(false)
        private val bytes=LinkedBlockingQueue<Int>()
        private val released=CountDownLatch(1)
        val commands=CopyOnWriteArrayList<SonyProtocol.Frame>()
        override val connected get()=!closed.get()
        fun feed(data:ByteArray) {data.forEach{bytes.put(it.toInt() and 255)}}
        override val input=object:InputStream() {
            override fun read():Int=bytes.take()
            override fun read(b:ByteArray,off:Int,len:Int):Int {
                val first=read();if(first<0) return -1
                b[off]=first.toByte();var n=1
                while(n<minOf(len,7)) {val next=bytes.poll() ?: break;if(next<0) {bytes.offer(-1);break};b[off+n++]=next.toByte()}
                return n
            }
        }
        override val output=object:OutputStream() {
            override fun write(b:Int) {throw UnsupportedOperationException()}
            override fun write(b:ByteArray,off:Int,len:Int) {
                if(blockWrite) {released.await();throw IOException("closed blocked write")}
                val f=SonyProtocol.parse(b.copyOfRange(off+1,off+len-1))!!
                if(f.type in listOf(0x0C,0x0E)) {
                    commands.add(f)
                    if(autoAck) feed(SonyProtocol.frame(SonyProtocol.TYPE_ACK,1-f.seq,byteArrayOf()))
                }
            }
        }
        override fun close() {closed.set(true);released.countDown();bytes.offer(-1)}
    }
    private fun session(scope:CoroutineScope,onPayload:(Int,ByteArray)->Unit={_,_->})=SonyConnection(scope,{},onPayload,ackTimeoutMs=180,onClosed={})
    @Test fun concurrentCommandsAreSerializedAndAlternateSequence()= runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO);val stream=Stream();val c=session(scope)
        try {
            c.attach(stream)
            val results=(0..9).map {i->async {c.send(byteArrayOf(0x66,i.toByte()))}}.awaitAll()
            assertTrue(results.all{it});assertEquals(10,stream.commands.size)
            assertEquals((0..9).map{it%2},stream.commands.map{it.seq})
        } finally {c.close();scope.cancel()}
    }
    @Test fun wrongAckDoesNotCompleteCommand()= runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO);val stream=Stream(false);val c=session(scope)
        try {
            c.attach(stream);val result=async {c.send(byteArrayOf(0x66,0x17))}
            withTimeout(1000){while(stream.commands.isEmpty()) delay(1)}
            stream.feed(SonyProtocol.frame(SonyProtocol.TYPE_ACK,0,byteArrayOf()))
            delay(20);assertFalse(result.isCompleted)
            stream.feed(SonyProtocol.frame(SonyProtocol.TYPE_ACK,1,byteArrayOf()))
            assertTrue(result.await())
        } finally {c.close();scope.cancel()}
    }
    @Test fun ackTimeoutClosesLinkAndDoesNotPoisonReplacement()= runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO);val c=session(scope)
        try {
            val first=Stream(false);c.attach(first)
            assertFalse(withTimeout(2000){c.send(byteArrayOf(0x66,0x17))});assertFalse(c.connected)
            first.feed(SonyProtocol.frame(SonyProtocol.TYPE_ACK,1,byteArrayOf()))
            val next=Stream();c.attach(next)
            assertTrue(c.send(byteArrayOf(0x12,2)));assertEquals(0,next.commands.single().seq)
        } finally {c.close();scope.cancel()}
    }
    @Test fun blockingWriteAndCancellationHaveABoundedExit()= runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO);val c=session(scope)
        try {
            c.attach(Stream(blockWrite=true))
            assertFalse(withTimeout(2000){c.send(byteArrayOf(0x66,0x17))})
            assertFalse(c.connected)
            val next=Stream(false);c.attach(next)
            val job=launch {c.send(byteArrayOf(0x66,0x17))}
            withTimeout(1000){while(next.commands.isEmpty()) delay(1)}
            withTimeout(2000){job.cancelAndJoin()};assertFalse(c.connected)
        } finally {c.close();scope.cancel()}
    }
    @Test fun fragmentedCommandTablesAndEscapesReachTheDecoder()= runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val seen=CopyOnWriteArrayList<Pair<Int,String>>()
        val c=session(scope){type,p->seen.add(type to SonyProtocol.hex(p))}
        try {
            val stream=Stream();c.attach(stream)
            stream.feed(byteArrayOf(0x7F,0x20))
            val bad=SonyProtocol.frame(SonyProtocol.TYPE_COMMAND,0,byteArrayOf(1,2));bad[bad.lastIndex-1]=0
            stream.feed(bad)
            stream.feed(SonyProtocol.frame(SonyProtocol.TYPE_COMMAND_2,0,byteArrayOf(0x47,1,0,1)))
            stream.feed(SonyProtocol.frame(SonyProtocol.TYPE_COMMAND,1,byteArrayOf(0x3C,0x3D,0x3E)))
            withTimeout(1000){while(seen.size<2) delay(1)}
            assertEquals(listOf(0x0E to "47 01 00 01",0x0C to "3C 3D 3E"),seen.toList())
        } finally {c.close();scope.cancel()}
    }
}
