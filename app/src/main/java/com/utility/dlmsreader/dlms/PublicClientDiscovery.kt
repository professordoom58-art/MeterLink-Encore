package com.utility.dlmsreader.dlms

import gurux.dlms.GXReplyData
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
        executor.readDlmsPacket(snrm, reply)
        client.parseUAResponse(reply.data)

        // 2. Send AARQ (Association Request) for Public Client (Client 16 / 0x10)
        executeFrames(executor, client.aarqRequest(), reply)
        client.parseAareResponse(reply.data)

        // 3. Read Logical Device Name (OBIS: 0.0.42.0.0.255)
        val ldnObj = GXDLMSData("0.0.42.0.0.255")
        val readLdn = client.read(ldnObj, 2)
        executeFrames(executor, readLdn, reply)
        client.updateValue(ldnObj, 2, reply.value)
        val ldn = ldnObj.value?.toString() ?: "Unknown"

        // 4. Read Meter Serial Number (OBIS: 0.0.96.1.0.255)
        val serialObj = GXDLMSData("0.0.96.1.0.255")
        val readSerial = client.read(serialObj, 2)
        executeFrames(executor, readSerial, reply)
        client.updateValue(serialObj, 2, reply.value)
        val serial = serialObj.value?.toString() ?: "Unknown"

        // 5. Read Real-Time Clock (OBIS: 0.0.1.0.0.255)
        val clockObj = GXDLMSClock("0.0.1.0.0.255")
        val readClock = client.read(clockObj, 2)
        executeFrames(executor, readClock, reply)
        val clockTime = client.updateValue(clockObj, 2, reply.value)?.toString() ?: "Unknown"

        // 6. Graceful Disconnect
        val disc = client.disconnectRequest()
        if (disc.isNotEmpty()) {
            reply.clear()
            executor.readDlmsPacket(disc, reply)
        }

        return MeterIdentity(ldn, serial, clockTime)
    }

    private suspend fun executeFrames(
        executor: GuruxPacketExecutor,
        frames: Array<ByteArray>,
        reply: GXReplyData
    ) {
        for (frame in frames) {
            reply.clear()
            executor.readDlmsPacket(frame, reply)
        }
    }
}
