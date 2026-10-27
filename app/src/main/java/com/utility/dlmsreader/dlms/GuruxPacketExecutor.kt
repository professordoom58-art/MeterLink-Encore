package com.utility.dlmsreader.dlms

import gurux.dlms.GXByteBuffer
import gurux.dlms.GXDLMSException
import gurux.dlms.GXReplyData
import gurux.dlms.enums.DataType
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

class GuruxPacketExecutor(
    private val transport: OpticalUsbTransport,
    private val client: GXDLMSSecureClient,
    private val maxRetries: Int = 3,
    private val packetTimeoutMs: Long = 3000L
) {
    suspend fun readDLMSPacket(data: ByteArray, reply: GXReplyData) {
        if (data.isEmpty()) return

        var attempt = 0
        val rd = GXByteBuffer()

        while (attempt < maxRetries) {
            attempt++
            reply.error = 0
            rd.clear()

            try {
                transport.write(data, 1000)
                val deadline = System.currentTimeMillis() + packetTimeoutMs

                while (!client.getData(rd, reply)) {
                    if (System.currentTimeMillis() >= deadline) {
                        throw TimeoutException("Timeout waiting for DLMS packet (Attempt $attempt/$maxRetries)")
                    }

                    val chunk = transport.readBytesRaw(128, 200)
                    if (chunk.isNotEmpty()) {
                        transport.log(TraceDirection.RX, chunk.toHexString())
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
            val nextFrame = client.receiverReady(reply)
            if (nextFrame.isEmpty()) break
            readDLMSPacket(nextFrame, reply)
        }
    }

    suspend fun readObject(item: GXDLMSObject, attributeIndex: Int): Any? {
        val data = client.read(item, attributeIndex)
        val reply = GXReplyData()
        readDataBlock(data, reply)
        if (item.getDataType(attributeIndex) == DataType.NONE) {
            item.setDataType(attributeIndex, reply.valueType)
        }
        return client.updateValue(item, attributeIndex, reply.value)
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
}
