package com.utility.dlmsreader.dlms

import com.utility.dlmsreader.transport.OpticalUsbTransport
import com.utility.dlmsreader.transport.TraceDirection
import com.utility.dlmsreader.transport.toHexString
import gurux.dlms.GXByteBuffer
import gurux.dlms.GXDLMSException
import gurux.dlms.GXDateTime
import gurux.dlms.GXReplyData
import gurux.dlms.enums.DataType
import gurux.dlms.enums.RequestTypes
import gurux.dlms.objects.GXDLMSObject
import gurux.dlms.objects.GXDLMSProfileGeneric
import gurux.dlms.secure.GXDLMSSecureClient
import kotlinx.coroutines.delay
import java.io.IOException
import java.nio.BufferUnderflowException
import java.util.Date
import java.util.concurrent.TimeoutException

data class GuruxReadResult(
    val raw: Any?,
    val value: Any?
)

class GuruxPacketExecutor internal constructor(
    private val writeFn: suspend (ByteArray, Int) -> Unit,
    private val readBytesRawFn: suspend (Int, Int) -> ByteArray,
    private val logFn: (TraceDirection, String) -> Unit,
    private val getDataFn: (GXByteBuffer, GXReplyData) -> Boolean,
    private val client: GXDLMSSecureClient,
    private val maxRetries: Int = 3,
    private val packetTimeoutMs: Long = 8000L
) {
    constructor(
        transport: OpticalUsbTransport,
        client: GXDLMSSecureClient,
        maxRetries: Int = 3,
        packetTimeoutMs: Long = 8000L
    ) : this(
        writeFn = { data, timeout -> transport.write(data, timeout) },
        readBytesRawFn = { maxBytes, timeout -> transport.readBytesRaw(maxBytes, timeout) },
        logFn = { dir, msg -> transport.log(dir, msg) },
        getDataFn = { buf, reply -> client.getData(buf, reply) },
        client = client,
        maxRetries = maxRetries,
        packetTimeoutMs = packetTimeoutMs
    )

    companion object {
        const val RR_FRAME_DELAY_MS = 100L
        const val RR_BLOCK_DELAY_MS = 100L
        const val INTER_CHUNK_GAP_TIMEOUT_MS = 400
        const val MIN_FRAME_READ_TIMEOUT_MS = 5000L
    }

    suspend fun readDLMSPacket(
        data: ByteArray,
        reply: GXReplyData,
        maxRetriesOverride: Int? = null
    ) {
        if (data.isEmpty()) return

        var attempt = 0
        val retries = maxRetriesOverride ?: maxRetries
        val effectiveTimeoutMs = packetTimeoutMs.coerceAtLeast(MIN_FRAME_READ_TIMEOUT_MS)

        while (attempt < retries) {
            attempt++
            reply.error = 0
            val assembler = HdlcFrameAssembler()

            try {
                writeFn(data, 1000)
                val deadline = System.currentTimeMillis() + effectiveTimeoutMs

                while (System.currentTimeMillis() < deadline) {
                    val chunk = readBytesRawFn(128, INTER_CHUNK_GAP_TIMEOUT_MS)
                    if (chunk.isNotEmpty()) {
                        assembler.push(chunk)
                        if (assembler.isComplete()) {
                            break
                        }
                    }
                }

                if (!assembler.isComplete()) {
                    if (assembler.size > 0) {
                        logFn(TraceDirection.RX, assembler.bytes.toHexString())
                    }
                    throw TimeoutException(
                        "Timeout waiting for DLMS packet (Attempt $attempt/$retries, received ${assembler.size} bytes, expected ${assembler.expectedTotalBytes() ?: "unknown"})"
                    )
                }

                val frameBytes = assembler.getAssembledFrame() ?: assembler.bytes
                logFn(TraceDirection.RX, frameBytes.toHexString())

                val rd = GXByteBuffer(frameBytes)
                var success = false

                try {
                    success = getDataFn(rd, reply)
                } catch (e: Exception) {
                    if (e is IndexOutOfBoundsException || e is BufferUnderflowException || e is IllegalArgumentException) {
                        val recoveryDeadline = System.currentTimeMillis() + 500L
                        while (System.currentTimeMillis() < recoveryDeadline && !success) {
                            val extraChunk = readBytesRawFn(128, 100)
                            if (extraChunk.isNotEmpty()) {
                                assembler.push(extraChunk)
                                val updatedFrame = assembler.getAssembledFrame() ?: assembler.bytes
                                rd.clear()
                                rd.set(updatedFrame, 0, updatedFrame.size)
                                try {
                                    success = getDataFn(rd, reply)
                                } catch (_: Exception) {
                                }
                            }
                        }
                        if (!success) {
                            val rxBytes = assembler.bytes
                            logFn(
                                TraceDirection.ERROR,
                                "[WARN] Malformed/truncated DLMS frame (${rxBytes.size} bytes): ${e.message}"
                            )
                            throw IOException("Truncated DLMS frame: ${e.message}", e)
                        }
                    } else {
                        throw e
                    }
                }

                if (!success) {
                    val recoveryDeadline = System.currentTimeMillis() + 500L
                    while (System.currentTimeMillis() < recoveryDeadline && !success) {
                        val extraChunk = readBytesRawFn(128, 100)
                        if (extraChunk.isNotEmpty()) {
                            assembler.push(extraChunk)
                            val updatedFrame = assembler.getAssembledFrame() ?: assembler.bytes
                            rd.clear()
                            rd.set(updatedFrame, 0, updatedFrame.size)
                            try {
                                success = getDataFn(rd, reply)
                            } catch (_: Exception) {
                            }
                        }
                    }
                }

                if (reply.error != 0) {
                    throw GXDLMSException(reply.error.toInt())
                }
                return
            } catch (e: GXDLMSException) {
                throw e
            } catch (e: Exception) {
                if (attempt >= retries) {
                    throw if (e is IOException) e else IOException("Failed after $retries attempts: ${e.message}", e)
                }
                delay(250)
            }
        }
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
            try {
                readDLMSPacket(nextFrame, reply, maxRetriesOverride = 1)
            } catch (error: Exception) {
                throw IOException("Incomplete HDLC frame: ${error.message}", error)
            }
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
        return try {
            client.updateValue(item, attributeIndex, reply.value)
        } catch (e: Exception) {
            if (e is IndexOutOfBoundsException || e is BufferUnderflowException || e is IllegalArgumentException || e is IllegalStateException) {
                throw IOException("Failed to parse object ${item.logicalName} attribute $attributeIndex: ${e.message}", e)
            }
            throw e
        }
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
        val value = try {
            client.updateValue(item, attributeIndex, raw)
        } catch (e: Exception) {
            if (e is IndexOutOfBoundsException || e is BufferUnderflowException || e is IllegalArgumentException || e is IllegalStateException) {
                throw IOException("Failed to parse object ${item.logicalName} attribute $attributeIndex: ${e.message}", e)
            }
            throw e
        }
        return GuruxReadResult(raw, value)
    }

    suspend fun readRowsByEntry(
        profile: GXDLMSProfileGeneric,
        index: Int,
        count: Int
    ): Array<Array<Any?>> {
        val data = client.readRowsByEntry(profile, index, count)
        val reply = GXReplyData()
        readDataBlock(data, reply)
        try {
            client.updateValue(profile, 2, reply.value)
        } catch (e: Exception) {
            if (e is IndexOutOfBoundsException || e is BufferUnderflowException || e is IllegalArgumentException || e is IllegalStateException) {
                throw IOException("Failed to parse profile rows: ${e.message}", e)
            }
            throw e
        }
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
        try {
            client.updateValue(profile, 2, reply.value)
        } catch (e: Exception) {
            if (e is IndexOutOfBoundsException || e is BufferUnderflowException || e is IllegalArgumentException || e is IllegalStateException) {
                throw IOException("Failed to parse profile rows: ${e.message}", e)
            }
            throw e
        }
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
        try {
            client.updateValue(profile, 2, reply.value)
        } catch (e: Exception) {
            if (e is IndexOutOfBoundsException || e is BufferUnderflowException || e is IllegalArgumentException || e is IllegalStateException) {
                throw IOException("Failed to parse profile rows: ${e.message}", e)
            }
            throw e
        }
        return profile.buffer.map { row ->
            when (row) {
                is Array<*> -> row.map { it }.toTypedArray()
                is Iterable<*> -> row.toList().toTypedArray()
                else -> arrayOf(row)
            }
        }.toTypedArray()
    }
}
