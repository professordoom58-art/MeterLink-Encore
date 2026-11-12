package com.utility.dlmsreader.dlms

import gurux.dlms.GXByteBuffer
import gurux.dlms.GXDLMSClient
import gurux.dlms.GXDLMSException
import gurux.dlms.GXDateTime
import gurux.dlms.GXReplyData
import gurux.dlms.enums.DataType
import gurux.dlms.enums.RequestTypes
import gurux.dlms.objects.GXDLMSObject
import gurux.dlms.objects.GXDLMSProfileGeneric
import gurux.dlms.secure.GXDLMSSecureClient
import com.utility.dlmsreader.transport.OpticalUsbTransport
import com.utility.dlmsreader.transport.TraceDirection
import com.utility.dlmsreader.transport.toHexString
import kotlinx.coroutines.delay
import java.io.IOException
import java.util.Date
import java.util.concurrent.TimeoutException

data class GuruxReadResult(
    val raw: Any?,
    val value: Any?
)

class GuruxPacketExecutor(
    private val transport: OpticalUsbTransport,
    private val client: GXDLMSSecureClient,
    private val maxRetries: Int = 3,
    private val packetTimeoutMs: Long = 8000L,
    private val onProgress: suspend (String) -> Unit = {}
) {
    companion object {
        const val RR_FRAME_DELAY_MS = 250L
        const val RR_BLOCK_DELAY_MS = 90L
        private const val ROWS_PER_BLOCK_ESTIMATE = 16
    }

    suspend fun readDLMSPacket(
        data: ByteArray,
        reply: GXReplyData,
        maxRetriesOverride: Int? = null
    ) {
        readDlmsPacket(
            client = client,
            data = data,
            reply = reply,
            maxRetries = maxRetriesOverride ?: maxRetries,
            packetTimeoutMs = packetTimeoutMs,
            write = { frame, timeoutMs -> transport.write(frame, timeoutMs) },
            read = { maxBytes, timeoutMs -> transport.readBytesRaw(maxBytes, timeoutMs) },
            log = { hex -> transport.log(TraceDirection.RX, hex) }
        )
    }

    suspend fun readDataBlock(
        data: Array<ByteArray>,
        reply: GXReplyData,
        progressLabel: String? = null,
        expectedEntries: Int? = null
    ) {
        for (frame in data) {
            reply.clear()
            readDataBlock(frame, reply, progressLabel, expectedEntries)
        }
    }

    suspend fun readDataBlock(
        data: ByteArray,
        reply: GXReplyData,
        progressLabel: String? = null,
        expectedEntries: Int? = null
    ) {
        if (data.isEmpty()) return

        var blocksReceived = 0
        var lastReportAt = 0L
        val totalBlocks = expectedEntries
            ?.takeIf { it > 0 }
            ?.let { (it + ROWS_PER_BLOCK_ESTIMATE - 1) / ROWS_PER_BLOCK_ESTIMATE }
            ?.takeIf { it > 0 }

        suspend fun reportBlockProgress() {
            blocksReceived++
            if (progressLabel == null) return
            val now = System.currentTimeMillis()
            val dueByCount = blocksReceived == 1 || blocksReceived % 5 == 0
            val dueByTime = now - lastReportAt >= 1000L
            if (!dueByCount && !dueByTime) return
            lastReportAt = now
            val kb = reply.data.size() / 1024.0
            val estimatedEntries = (blocksReceived * ROWS_PER_BLOCK_ESTIMATE)
            val expected = expectedEntries ?: 0
            val detail = if (totalBlocks != null) {
                val percent = (blocksReceived * 100 / totalBlocks).coerceIn(0, 100)
                "Block $blocksReceived of ~$totalBlocks (~$percent% of $expected entries, " +
                    "~${estimatedEntries.coerceAtMost(expected)} received, " +
                    String.format(java.util.Locale.US, "%.1f", kb) + " KB)"
            } else {
                "Block $blocksReceived (" +
                    String.format(java.util.Locale.US, "%.1f", kb) + " KB received)"
            }
            onProgress("$progressLabel: $detail")
        }

        readDLMSPacket(data, reply)
        reportBlockProgress()
        while (reply.isMoreData) {
            when {
                reply.isStreaming() || reply.moreData.contains(RequestTypes.FRAME) -> delay(RR_FRAME_DELAY_MS)
                reply.moreData.contains(RequestTypes.DATABLOCK) -> delay(RR_BLOCK_DELAY_MS)
            }
            val nextFrame = client.receiverReady(reply)
            if (nextFrame.isEmpty()) break
            readDLMSPacket(nextFrame, reply, maxRetriesOverride = 1)
            reportBlockProgress()
        }
    }

    suspend fun readObject(
        item: GXDLMSObject,
        attributeIndex: Int,
        progressLabel: String? = null,
        expectedEntries: Int? = null
    ): Any? {
        delay(60)
        val data = client.read(item, attributeIndex)
        val reply = GXReplyData()
        readDataBlock(data, reply, progressLabel, expectedEntries)
        if (item.getDataType(attributeIndex) == DataType.NONE) {
            item.setDataType(attributeIndex, reply.valueType)
        }
        return client.updateValue(item, attributeIndex, reply.value)
    }

    suspend fun readObjectWithRaw(item: GXDLMSObject, attributeIndex: Int): GuruxReadResult {
        delay(60)
        val data = client.read(item, attributeIndex)
        val reply = GXReplyData()
        readDataBlock(data, reply)
        if (item.getDataType(attributeIndex) == DataType.NONE) {
            item.setDataType(attributeIndex, reply.valueType)
        }
        val raw = reply.value
        return GuruxReadResult(raw, client.updateValue(item, attributeIndex, raw))
    }

    suspend fun readRowsByEntry(
        profile: GXDLMSProfileGeneric,
        index: Int,
        count: Int,
        progressLabel: String? = null,
        expectedEntries: Int? = null
    ): Array<Array<Any?>> {
        val data = client.readRowsByEntry(profile, index, count)
        val reply = GXReplyData()
        readDataBlock(data, reply, progressLabel, expectedEntries)
        client.updateValue(profile, 2, reply.value)
        return profile.buffer.map { row ->
            when (row) {
                is Array<*> -> row.map { it }.toTypedArray()
                is Iterable<*> -> row.toList().toTypedArray()
                else -> arrayOf(row)
            }
        }.toTypedArray()
    }

    /** Selector 2 with explicit column bounds (`from=colIndex`, `to=colIndex+colCount-1`). */
    suspend fun readRowsByEntry(
        profile: GXDLMSProfileGeneric,
        index: Int,
        count: Int,
        colIndex: Int,
        colCount: Int,
        progressLabel: String? = null,
        expectedEntries: Int? = null
    ): Array<Array<Any?>> {
        val data = client.readRowsByEntry(profile, index, count, colIndex, colCount)
        val reply = GXReplyData()
        readDataBlock(data, reply, progressLabel, expectedEntries)
        client.updateValue(profile, 2, reply.value)
        return profile.buffer.map { row ->
            when (row) {
                is Array<*> -> row.map { it }.toTypedArray()
                is Iterable<*> -> row.toList().toTypedArray()
                else -> arrayOf(row)
            }
        }.toTypedArray()
    }

    suspend fun readRowsByRange(
        profile: GXDLMSProfileGeneric,
        start: Date,
        end: Date
    ): Array<Array<Any?>> {
        val data = client.readRowsByRange(profile, start, end)
        val reply = GXReplyData()
        readDataBlock(data, reply)
        client.updateValue(profile, 2, reply.value)
        return profile.buffer.map { row ->
            when (row) {
                is Array<*> -> row.map { it }.toTypedArray()
                is Iterable<*> -> row.toList().toTypedArray()
                else -> arrayOf(row)
            }
        }.toTypedArray()
    }

    suspend fun readRowsByRange(
        profile: GXDLMSProfileGeneric,
        start: GXDateTime,
        end: GXDateTime,
        nativeDeviationMinutes: Int? = null,
        progressLabel: String? = null,
        expectedEntries: Int? = null
    ): Array<Array<Any?>> {
        val generated = client.readRowsByRange(profile, start, end)
        // Some IS 15959 meters require the profile timestamp format they
        // actually store: wildcard day-of-week/hundredths and their native
        // positive IST deviation (0x014A), not Gurux's inverted 0xFEB6.
        val data = if (nativeDeviationMinutes == null) {
            generated
        } else {
            patchProfileRangeDatetimes(generated, nativeDeviationMinutes)
        }
        val reply = GXReplyData()
        readDataBlock(data, reply, progressLabel, expectedEntries)
        client.updateValue(profile, 2, reply.value)
        return profile.buffer.map { row ->
            when (row) {
                is Array<*> -> row.map { it }.toTypedArray()
                is Iterable<*> -> row.toList().toTypedArray()
                else -> arrayOf(row)
            }
        }.toTypedArray()
    }
}

/**
 * Reads one DLMS response packet while preserving Gurux's multi-frame state.
 *
 * [GXReplyData] is not just a result holder: during a segmented HDLC response
 * it carries the parsed command, block/more-data flags and the partial PDU.
 * Clearing it before an RR continuation makes Gurux parse the continuation
 * bytes as a brand-new APDU and throw "Invalid Command.".
 */
internal suspend fun readDlmsPacket(
    client: GXDLMSClient,
    data: ByteArray,
    reply: GXReplyData,
    maxRetries: Int,
    packetTimeoutMs: Long,
    write: (ByteArray, Int) -> Unit,
    read: (Int, Int) -> ByteArray,
    log: (String) -> Unit,
    now: () -> Long = System::currentTimeMillis,
    pause: suspend (Long) -> Unit = { delay(it) }
) {
    if (data.isEmpty()) return

    var attempt = 0
    val rd = GXByteBuffer()

    while (attempt < maxRetries) {
        attempt++
        // Clear only when starting a new request. An in-flight segmented reply
        // must keep its command, more-data flags and accumulated PDU bytes so
        // the next RR continuation can be appended instead of re-parsed.
        if (!reply.isMoreData) {
            reply.clear()
        }
        reply.error = 0
        rd.clear()

        try {
            write(data, 1000)
            val deadline = now() + packetTimeoutMs

            while (!client.getData(rd, reply)) {
                if (now() >= deadline) {
                    throw TimeoutException("Timeout waiting for DLMS packet (Attempt $attempt/$maxRetries)")
                }

                val chunk = read(128, 200)
                if (chunk.isNotEmpty()) {
                    log(chunk.toHexString())
                    rd.set(chunk, 0, chunk.size)
                }
            }

            if (reply.error != 0) {
                throw GXDLMSException(reply.error.toInt())
            }
            return
        } catch (e: GXDLMSException) {
            throw e
        } catch (e: Exception) {
            if (attempt >= maxRetries) {
                throw IOException("Failed after $maxRetries attempts: ${e.message}", e)
            }
            pause(250)
        }
    }
}

/**
 * Patches the two Selector 1 COSEM datetime octet strings in a generated
 * `readRowsByRange` request before transmission.
 *
 * Indian IS 15959 meters commonly store profile timestamps with:
 *  - day-of-week `0xFF` (wildcard),
 *  - hundredths `0xFF` (wildcard),
 *  - their native RTC deviation (`0x014A` for +330), and
 *  - clock status `0x00`.
 *
 * Gurux may serialize the same range with a calculated day-of-week,
 * hundredths `0x00` and an inverted deviation (`0xFEB6` for -330), which the
 * meter rejects with `denied(3)`. HDLC FCS is recalculated after patching so
 * the frame remains valid on the wire.
 */
internal fun patchProfileRangeDatetimes(
    frames: Array<ByteArray>,
    deviationMinutes: Int?,
    wildcardDayOfWeek: Boolean = true,
    wildcardHundredths: Boolean = true,
    clockStatus: Int = 0
): Array<ByteArray> = frames.map { original ->
    val frame = original.copyOf()
    var patched = false
    var offset = 0
    while (offset + 14 <= frame.size) {
        val isOctetString = (frame[offset].toInt() and 0xFF) == 0x09 &&
            (frame[offset + 1].toInt() and 0xFF) == 0x0C
        if (!isOctetString) {
            offset++
            continue
        }
        val dateTime = offset + 2
        if (!looksLikeCosemDateTime(frame, dateTime)) {
            offset++
            continue
        }
        if (wildcardDayOfWeek) frame[dateTime + 4] = 0xFF.toByte()
        if (wildcardHundredths) frame[dateTime + 8] = 0xFF.toByte()
        if (deviationMinutes != null) {
            val raw = encodeDeviationMinutes(deviationMinutes)
            frame[dateTime + 9] = raw[0]
            frame[dateTime + 10] = raw[1]
        }
        frame[dateTime + 11] = clockStatus.toByte()
        patched = true
        offset += 14
    }
    if (patched) updateHdlcFcs(frame)
    frame
}.toTypedArray()

internal fun encodeDeviationMinutes(minutes: Int): ByteArray {
    val value = minutes and 0xFFFF
    return byteArrayOf((value ushr 8).toByte(), value.toByte())
}

private fun looksLikeCosemDateTime(frame: ByteArray, offset: Int): Boolean {
    if (offset + 12 > frame.size) return false
    val year = ((frame[offset].toInt() and 0xFF) shl 8) or
        (frame[offset + 1].toInt() and 0xFF)
    val month = frame[offset + 2].toInt() and 0xFF
    val day = frame[offset + 3].toInt() and 0xFF
    if (year != 0xFFFF && year !in 1900..2200) return false
    if (month != 0xFF && month !in 1..12) return false
    if (day != 0xFF && day != 0xFE && day !in 1..31) return false
    return true
}

/** Recomputes the HDLC FCS when [frame] is a full `7E ... FCS 7E` frame. */
internal fun updateHdlcFcs(frame: ByteArray) {
    if (frame.size < 6) return
    if ((frame.first().toInt() and 0xFF) != 0x7E ||
        (frame.last().toInt() and 0xFF) != 0x7E
    ) return
    val fcs = hdlcFcs16(frame, 1, frame.size - 4)
    frame[frame.size - 3] = (fcs and 0xFF).toByte()
    frame[frame.size - 2] = ((fcs ushr 8) and 0xFF).toByte()
}

/** CRC-16/X-25 used by IEC 62056-46 HDLC framing. */
internal fun hdlcFcs16(data: ByteArray, offset: Int, length: Int): Int {
    var crc = 0xFFFF
    val end = (offset + length).coerceAtMost(data.size)
    for (index in offset until end) {
        crc = crc xor (data[index].toInt() and 0xFF)
        repeat(8) {
            crc = if ((crc and 1) != 0) {
                (crc ushr 1) xor 0x8408
            } else {
                crc ushr 1
            }
        }
    }
    return crc xor 0xFFFF
}
