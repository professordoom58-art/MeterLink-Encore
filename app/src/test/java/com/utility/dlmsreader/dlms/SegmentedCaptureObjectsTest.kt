package com.utility.dlmsreader.dlms

import gurux.dlms.GXReplyData
import gurux.dlms.enums.Authentication
import gurux.dlms.enums.InterfaceType
import gurux.dlms.objects.GXDLMSProfileGeneric
import gurux.dlms.secure.GXDLMSSecureClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque

/**
 * Regression for field log MeterLink_HexLog_92717295_20261112_192522 (L&T 1P,
 * build before commit 8ac2b11).
 *
 * The meter answers GET capture_objects (attribute 3) of the billing profile
 * 1.0.98.1.0.255 with one GET-RESPONSE-NORMAL (not block transfer) carrying an
 * array of 20 capture definitions, about 370 bytes. Its HDLC frames hold
 * 0x89 bytes, so the reply spans three segments: 0xA8, 0xA8, 0xA0. That build
 * threw "Invalid Command." on the second segment. Instantaneous, billing,
 * block load and most event logs all failed that way, while short (one- or
 * two-segment) capture lists worked.
 *
 * The 20 definitions below are the billing capture list from that log:
 * billing date, average PF, kWh total and TOD 1–4, kVAh total and TOD 1–4,
 * MD kW and kVA (value + capture time), and the remaining registers.
 */
class SegmentedCaptureObjectsTest {

    /** {class, OBIS, attribute} as they appear in the log. */
    private val captureList: List<Triple<Int, String, Int>> = listOf(
        Triple(3, "0.0.0.1.2.255", 2),
        Triple(3, "1.0.13.0.0.255", 2),
        Triple(3, "1.0.1.8.0.255", 2),
        Triple(3, "1.0.1.8.1.255", 2),
        Triple(3, "1.0.1.8.2.255", 2),
        Triple(3, "1.0.1.8.3.255", 2),
        Triple(3, "1.0.1.8.4.255", 2),
        Triple(3, "1.0.9.8.0.255", 2),
        Triple(3, "1.0.9.8.1.255", 2),
        Triple(3, "1.0.9.8.2.255", 2),
        Triple(3, "1.0.9.8.3.255", 2),
        Triple(3, "1.0.9.8.4.255", 2),
        Triple(4, "1.0.1.6.0.255", 2),
        Triple(4, "1.0.1.6.0.255", 5),
        Triple(4, "1.0.9.6.0.255", 2),
        Triple(4, "1.0.9.6.0.255", 5),
        Triple(3, "0.0.94.91.13.255", 2),
        Triple(3, "1.0.1.29.0.255", 2),
        Triple(3, "1.0.9.29.0.255", 2),
        Triple(1, "0.128.171.128.128.255", 2)
    )

    private fun obisBytes(obis: String): ByteArray =
        obis.split('.').map { it.toInt().toByte() }.toByteArray()

    /** LLC header + GET-RESPONSE-NORMAL + array(20) of capture_object_definition. */
    private fun responsePdu(): ByteArray = ByteArrayOutputStream().apply {
        write(byteArrayOf(0xE6.toByte(), 0xE7.toByte(), 0x00))
        write(byteArrayOf(0xC4.toByte(), 0x01, 0xC1.toByte(), 0x00))
        write(byteArrayOf(0x01, captureList.size.toByte()))
        captureList.forEach { (classId, obis, attribute) ->
            write(byteArrayOf(0x02, 0x04, 0x12, 0x00, classId.toByte(), 0x09, 0x06))
            write(obisBytes(obis))
            write(byteArrayOf(0x0F, attribute.toByte(), 0x12, 0x00, 0x00))
        }
    }.toByteArray()

    /** One HDLC I-frame from server 1 to client 16 (dest 0x21, src 0x03). */
    private fun hdlcFrame(info: ByteArray, control: Int, moreSegments: Boolean): ByteArray {
        val length = 2 + 1 + 1 + 1 + 2 + info.size + 2
        val frame = ByteArray(length + 2)
        frame[0] = 0x7E
        frame[1] = ((if (moreSegments) 0xA8 else 0xA0) or ((length shr 8) and 0x07)).toByte()
        frame[2] = length.toByte()
        frame[3] = 0x21
        frame[4] = 0x03
        frame[5] = control.toByte()
        val hcs = hdlcFcs16(frame, 1, 5)
        frame[6] = (hcs and 0xFF).toByte()
        frame[7] = ((hcs ushr 8) and 0xFF).toByte()
        info.copyInto(frame, 8)
        frame[frame.size - 1] = 0x7E
        updateHdlcFcs(frame)
        return frame
    }

    @Test
    fun `three segment capture_objects reply decodes all 20 columns`() = runBlocking {
        val client = GXDLMSSecureClient(true, 16, 1, Authentication.NONE, null, InterfaceType.HDLC)
        val profile = GXDLMSProfileGeneric("1.0.98.1.0.255")
        val request = client.read(profile, 3).first()

        // 0x89-byte frames, as in the log: 128 info bytes per segment.
        val segments = responsePdu().toList().chunked(128).map { it.toByteArray() }
        assertEquals("fixture must need three segments", 3, segments.size)
        // Gurux expects 0x30, 0x32, 0x34 for this fresh client (see GuruxPacketExecutorTest).
        val frames = segments.mapIndexed { index, info ->
            hdlcFrame(info, control = 0x30 + 2 * index, moreSegments = index < segments.lastIndex)
        }
        // Deliver each frame in two USB chunks, as the phone did.
        val chunks = ArrayDeque<ByteArray>()
        frames.forEach { frame ->
            chunks.add(frame.copyOfRange(0, 9))
            chunks.add(frame.copyOfRange(9, frame.size))
        }

        val reply = GXReplyData()
        val read: (Int, Int) -> ByteArray = { _, _ -> chunks.pollFirst() ?: ByteArray(0) }
        readDlmsPacket(client, request, reply, 1, 1_000L, { _, _ -> }, read, {})
        var receiverReadyCount = 0
        while (reply.isMoreData) {
            receiverReadyCount++
            readDlmsPacket(client, client.receiverReady(reply), reply, 1, 1_000L, { _, _ -> }, read, {})
        }

        assertEquals("one RR per continuation segment", 2, receiverReadyCount)
        client.updateValue(profile, 3, reply.value)
        assertEquals(captureList.size, profile.captureObjects.size)
        assertEquals(
            captureList.map { it.second to it.third },
            profile.captureObjects.map { it.key.logicalName to it.value.attributeIndex }
        )
    }
}
