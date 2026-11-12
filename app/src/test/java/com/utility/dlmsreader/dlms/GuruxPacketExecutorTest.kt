package com.utility.dlmsreader.dlms

import gurux.dlms.GXReplyData
import gurux.dlms.enums.Authentication
import gurux.dlms.enums.InterfaceType
import gurux.dlms.objects.GXDLMSData
import gurux.dlms.secure.GXDLMSSecureClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

class GuruxPacketExecutorTest {

    /**
     * Two HDLC segments carrying one GET-RESPONSE for UINT32 value 42.
     *
     * Frame 1 uses format 0xA8 (more segments follow). After the client sends
     * an RR, frame 2 uses 0xA0 and carries the remaining PDU bytes. The reply
     * object must survive the RR boundary; clearing it makes Gurux parse the
     * continuation as a new command and throw "Invalid Command.".
     */
    private val firstSegment = byteArrayOf(
        0x7E,
        0xA8.toByte(), 0x0D, 0x21, 0x03, 0x30,
        0x10, 0xB6.toByte(),
        0xC4.toByte(), 0x01, 0xC1.toByte(), 0x00,
        0x14, 0x37,
        0x7E
    )

    private val secondSegment = byteArrayOf(
        0x7E,
        0xA0.toByte(), 0x0E, 0x21, 0x03, 0x32,
        0xEF.toByte(), 0xEA.toByte(),
        0x06, 0x00, 0x00, 0x00, 0x2A,
        0xB0.toByte(), 0x39,
        0x7E
    )

    @Test
    fun `segmented response keeps reply state across RR continuation`() = runBlocking {
        val client = GXDLMSSecureClient(
            true,
            16,
            1,
            Authentication.NONE,
            null,
            InterfaceType.HDLC
        )
        // Generating the request establishes Gurux's HDLC frame sequence:
        // first response 0x30, response after RR 0x32.
        val request = client.read(GXDLMSData("1.0.1.8.0.255"), 2).first()

        val reply = GXReplyData()
        val chunks = ArrayDeque<ByteArray>().apply {
            add(firstSegment)
            add(secondSegment)
        }

        readDlmsPacket(
            client = client,
            data = request,
            reply = reply,
            maxRetries = 1,
            packetTimeoutMs = 1_000L,
            write = { _, _ -> },
            read = { _, _ -> chunks.pollFirst() ?: ByteArray(0) },
            log = {},
            now = System::currentTimeMillis
        )

        assertTrue("first segment must request an RR continuation", reply.isMoreData)

        while (reply.isMoreData) {
            val receiverReady = client.receiverReady(reply)
            readDlmsPacket(
                client = client,
                data = receiverReady,
                reply = reply,
                maxRetries = 1,
                packetTimeoutMs = 1_000L,
                write = { _, _ -> },
                read = { _, _ -> chunks.pollFirst() ?: ByteArray(0) },
                log = {},
                now = System::currentTimeMillis
            )
        }

        // Gurux decodes UINT32 into GXUInt32, which extends Number.
        assertEquals(42L, (reply.value as Number).toLong())
    }
}
