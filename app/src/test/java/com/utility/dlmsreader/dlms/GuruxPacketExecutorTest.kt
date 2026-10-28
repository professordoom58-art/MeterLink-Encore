package com.utility.dlmsreader.dlms

import com.utility.dlmsreader.transport.TraceDirection
import gurux.dlms.GXReplyData
import gurux.dlms.secure.GXDLMSSecureClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class GuruxPacketExecutorTest {

    private fun create139ByteSegmentedFrame(): ByteArray {
        val frame = ByteArray(139)
        frame[0] = 0x7E.toByte()
        frame[1] = 0xA8.toByte() // Format Type 3, Segmented (S-bit = 1)
        frame[2] = 0x89.toByte() // Payload length 137 -> total frame = 137 + 2 = 139
        for (i in 3 until 138) {
            frame[i] = ((i * 7) and 0xFF).toByte()
        }
        frame[138] = 0x7E.toByte() // Closing flag
        return frame
    }

    @Test
    fun `hdlcFrameAssembler reassembles 139-byte segmented frame arriving in ten 14-byte chunks`() {
        val frame = create139ByteSegmentedFrame()
        assertEquals(139, frame.size)

        // Split into ten chunks: 9 chunks of 14 bytes (126 bytes) + 1 chunk of 13 bytes (13 bytes) = 139 bytes
        val chunks = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < frame.size) {
            val chunkSize = minOf(14, frame.size - offset)
            chunks.add(frame.copyOfRange(offset, offset + chunkSize))
            offset += chunkSize
        }
        assertEquals(10, chunks.size)

        val assembler = HdlcFrameAssembler()

        // Feed first 9 chunks: frame MUST NOT be complete yet
        for (i in 0 until 9) {
            assembler.push(chunks[i])
            assertFalse(
                "Assembler must not report complete at chunk $i (size=${assembler.size}, expected=139)",
                assembler.isComplete()
            )
            assertEquals(139, assembler.expectedTotalBytes())
        }

        // Feed final 10th chunk
        assembler.push(chunks[9])
        assertTrue("Assembler must be complete after all 10 chunks arrive", assembler.isComplete())

        val assembled = assembler.getAssembledFrame()
        assertNotNull(assembled)
        assertEquals(139, assembled!!.size)
        assertArrayEquals(frame, assembled)
    }

    @Test
    fun `hdlcFrameAssembler discards leading noise before opening 7E flag`() {
        val frame = create139ByteSegmentedFrame()
        val noiseAndFrame = byteArrayOf(0x00, 0x55.toByte(), 0xFF.toByte(), 0x12) + frame

        val assembler = HdlcFrameAssembler()
        assembler.push(noiseAndFrame)

        assertTrue(assembler.isComplete())
        assertEquals(139, assembler.expectedTotalBytes())
        assertArrayEquals(frame, assembler.getAssembledFrame())
    }

    @Test
    fun `hdlcFrameAssembler handles consecutive 7E flags`() {
        val frame = create139ByteSegmentedFrame()
        val consecutiveFlagsAndFrame = byteArrayOf(0x7E, 0x7E) + frame

        val assembler = HdlcFrameAssembler()
        assembler.push(consecutiveFlagsAndFrame)

        assertTrue(assembler.isComplete())
        assertEquals(139, assembler.expectedTotalBytes())
        assertArrayEquals(frame, assembler.getAssembledFrame())
    }

    @Test
    fun `executor does not call client getData until entire 139-byte HDLC frame is assembled over 160ms`() = runBlocking {
        val frame = create139ByteSegmentedFrame()

        // 10 chunks: 9 chunks of 14 bytes + 1 chunk of 13 bytes = 139 bytes
        val chunks = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < frame.size) {
            val chunkSize = minOf(14, frame.size - offset)
            chunks.add(frame.copyOfRange(offset, offset + chunkSize))
            offset += chunkSize
        }
        assertEquals(10, chunks.size)

        var chunkIndex = 0
        var getDataCallCount = 0
        val bufferSizesAtGetData = mutableListOf<Int>()

        val dummyClient = GXDLMSSecureClient()

        val executor = GuruxPacketExecutor(
            writeFn = { _, _ -> },
            readBytesRawFn = { _, _ ->
                if (chunkIndex < chunks.size) {
                    val chunk = chunks[chunkIndex++]
                    // Simulate 16ms arrival interval across 10 chunks (~160ms total transmission time at 9600 baud)
                    delay(16)
                    chunk
                } else {
                    ByteArray(0)
                }
            },
            logFn = { _, _ -> },
            getDataFn = { data, _ ->
                getDataCallCount++
                bufferSizesAtGetData.add(data.size())
                true
            },
            client = dummyClient,
            packetTimeoutMs = 5000L
        )

        val reply = GXReplyData()
        executor.readDLMSPacket(byteArrayOf(0x7E, 0xA0.toByte(), 0x07, 0x7E), reply)

        // Verify that getData was NEVER called with partial frames (e.g. 14, 28, 42... bytes)
        assertEquals("client.getData must only be called ONCE after full frame reassembly", 1, getDataCallCount)
        assertEquals("client.getData must receive the full 139-byte frame", 139, bufferSizesAtGetData.first())
    }

    @Test
    fun `executor recovers from getUInt8 underflow when trailing bytes arrive within 500ms`() = runBlocking {
        val frame = create139ByteSegmentedFrame()
        val trailingBytes = byteArrayOf(0x7E)

        var readCall = 0
        var getDataAttempts = 0

        val dummyClient = GXDLMSSecureClient()

        val executor = GuruxPacketExecutor(
            writeFn = { _, _ -> },
            readBytesRawFn = { _, _ ->
                readCall++
                when (readCall) {
                    1 -> frame
                    2 -> {
                        delay(20)
                        trailingBytes
                    }
                    else -> ByteArray(0)
                }
            },
            logFn = { _, _ -> },
            getDataFn = { _, _ ->
                getDataAttempts++
                if (getDataAttempts == 1) {
                    // Simulate Gurux GXByteBuffer.getUInt8 underflow when more bytes are in flight
                    throw IndexOutOfBoundsException("GXByteBuffer.getUInt8 underflow: mid-frame truncation")
                }
                true
            },
            client = dummyClient,
            packetTimeoutMs = 5000L
        )

        val reply = GXReplyData()
        executor.readDLMSPacket(byteArrayOf(0x7E, 0x00), reply)

        assertTrue("getData should have recovered after receiving trailing bytes", getDataAttempts >= 2)
    }

    @Test
    fun `executor wraps malformed or truncated frame underflow into recoverable IOException`() = runBlocking {
        val frame = create139ByteSegmentedFrame()

        val dummyClient = GXDLMSSecureClient()

        var calls = 0
        val executor = GuruxPacketExecutor(
            writeFn = { _, _ -> },
            readBytesRawFn = { _, _ ->
                calls++
                if (calls == 1) frame else ByteArray(0)
            },
            logFn = { _, _ -> },
            getDataFn = { _, _ ->
                throw IndexOutOfBoundsException("GXByteBuffer.getUInt8 underflow")
            },
            client = dummyClient,
            maxRetries = 1,
            packetTimeoutMs = 5000L
        )

        val reply = GXReplyData()
        try {
            executor.readDLMSPacket(byteArrayOf(0x7E, 0x00), reply)
            fail("Expected IOException for truncated frame")
        } catch (e: IOException) {
            assertTrue(
                "Exception should indicate truncated DLMS frame: ${e.message}",
                e.message?.contains("truncated", ignoreCase = true) == true
            )
        }
    }
}
