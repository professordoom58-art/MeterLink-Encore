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
    private val packetTimeoutMs: Long = 8000L
) {
    companion object {
        const val RR_FRAME_DELAY_MS = 250L
        const val RR_BLOCK_DELAY_MS = 90L
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

    suspend fun readDataBlock(data: Array<ByteArray>, reply: GXReplyData) {
        for (frame in data) {
            reply.clear()
            readDataBlock(frame, reply)
        }
    }

    suspend fun readDataBlock(data: ByteArray, reply: GXReplyData) {
        if (data.isEmpty()) return

        readDLMSPacket(data, reply)
        while (reply.isMoreData) {
            when {
                reply.isStreaming() || reply.moreData.contains(RequestTypes.FRAME) -> delay(RR_FRAME_DELAY_MS)
                reply.moreData.contains(RequestTypes.DATABLOCK) -> delay(RR_BLOCK_DELAY_MS)
            }
            val nextFrame = client.receiverReady(reply)
            if (nextFrame.isEmpty()) break
            readDLMSPacket(nextFrame, reply, maxRetriesOverride = 1)
        }
    }

    suspend fun readObject(item: GXDLMSObject, attributeIndex: Int): Any? {
        delay(60)
        val data = client.read(item, attributeIndex)
        val reply = GXReplyData()
        readDataBlock(data, reply)
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
        count: Int
    ): Array<Array<Any?>> {
        val data = client.readRowsByEntry(profile, index, count)
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
        end: GXDateTime
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
