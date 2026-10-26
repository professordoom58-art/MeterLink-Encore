package com.utility.dlmsreader.dlms

import gurux.dlms.GXReplyData
import gurux.dlms.enums.DataType
import gurux.dlms.objects.GXDLMSData
import gurux.dlms.objects.GXDLMSClock
import gurux.dlms.secure.GXDLMSSecureClient
import com.utility.dlmsreader.transport.OpticalUsbTransport

object PublicClientDiscovery {

    data class MeterIdentity(
        val logicalDeviceName: String,
        val serialNumber: String,
        val clockTime: String
    )

    suspend fun discover(
        transport: OpticalUsbTransport,
        client: GXDLMSSecureClient
    ): MeterIdentity {
        val executor = GuruxPacketExecutor(transport, client)
        val reply = GXReplyData()

        // 1. Send HDLC SNRM request
        val snrm = client.snrmRequest()
        require(snrm.isNotEmpty()) { "Public client SNRM request was empty." }
        executor.readDLMSPacket(snrm, reply)
        client.parseUAResponse(reply.data)

        // 2. Send AARQ (Association Request) for Public Client (Client 16 / 0x10)
        executor.readDataBlock(client.aarqRequest(), reply)
        client.parseAareResponse(reply.data)

        // 3. Read Logical Device Name (OBIS: 0.0.42.0.0.255)
        val ldnObj = GXDLMSData("0.0.42.0.0.255")
        ldnObj.setUIDataType(2, DataType.STRING)
        executor.readObject(ldnObj, 2)
        val ldn = formatDlmsValue(ldnObj.value)

        // 4. Read Meter Serial Number (OBIS: 0.0.96.1.0.255)
        val serialObj = GXDLMSData("0.0.96.1.0.255")
        serialObj.setUIDataType(2, DataType.STRING)
        executor.readObject(serialObj, 2)
        val serial = formatDlmsValue(serialObj.value)

        // 5. Read Real-Time Clock (OBIS: 0.0.1.0.0.255)
        val clockObj = GXDLMSClock("0.0.1.0.0.255")
        val clockTime = formatDlmsValue(executor.readObject(clockObj, 2))

        // 6. Graceful Disconnect
        val disc = client.disconnectRequest()
        if (disc.isNotEmpty()) {
            executor.readDLMSPacket(disc, GXReplyData())
        }

        return MeterIdentity(ldn, serial, clockTime)
    }

    private fun formatDlmsValue(value: Any?): String = when (value) {
        is ByteArray -> {
            val ascii = String(value, Charsets.US_ASCII).trim { it <= ' ' || it == '\u0000' }
            ascii.ifEmpty {
                value.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
            }
        }
        else -> value?.toString() ?: "Unknown"
    }

}
