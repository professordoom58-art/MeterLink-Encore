package com.utility.dlmsreader.dlms

import gurux.dlms.GXReplyData
import gurux.dlms.enums.Authentication
import gurux.dlms.enums.InterfaceType
import gurux.dlms.objects.GXDLMSData
import gurux.dlms.secure.GXDLMSSecureClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class CancellationTest {

    @Test
    fun `runCatchingCancellable captures failures but rethrows cancellation`() {
        assertTrue(runCatchingCancellable { throw IOException("x") }.isFailure)
        try {
            runCatchingCancellable { throw CancellationException("stop") }
            fail("cancellation must propagate")
        } catch (_: CancellationException) {
        }
    }

    @Test
    fun `rethrowIfCancelled finds wrapped cancellation`() {
        try {
            rethrowIfCancelled(IOException("wrapped", CancellationException("stop")))
            fail("wrapped cancellation must propagate")
        } catch (_: CancellationException) {
        }
        rethrowIfCancelled(IOException("plain")) // no throw
    }

    @Test
    fun `cancelled packet read stops promptly instead of retrying to timeout`() = runBlocking {
        val client = GXDLMSSecureClient(true, 16, 1, Authentication.NONE, null, InterfaceType.HDLC)
        val request = client.read(GXDLMSData("1.0.1.8.0.255"), 2).first()
        val writes = AtomicInteger()

        // A silent meter: every read blocks 50 ms and returns nothing. With a
        // 10 s timeout and 3 retries, an uncancellable read would run ~30 s.
        val read = async(Dispatchers.IO) {
            readDlmsPacket(
                client = client,
                data = request,
                reply = GXReplyData(),
                maxRetries = 3,
                packetTimeoutMs = 10_000L,
                write = { _, _ -> writes.incrementAndGet() },
                read = { _, _ -> Thread.sleep(50); ByteArray(0) },
                log = {}
            )
        }
        delay(300)
        val started = System.currentTimeMillis()
        read.cancel()
        try {
            read.await()
            fail("read must be cancelled")
        } catch (_: CancellationException) {
        }
        val stopMs = System.currentTimeMillis() - started
        assertTrue("stopped in ${stopMs} ms", stopMs < 1_000)
        assertEquals("no retry after cancel", 1, writes.get())
    }
}
