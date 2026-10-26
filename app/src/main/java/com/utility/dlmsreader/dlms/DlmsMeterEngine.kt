package com.utility.dlmsreader.dlms

import android.os.SystemClock
import com.utility.dlmsreader.transport.OpticalUsbTransport
import com.utility.dlmsreader.transport.TraceDirection
import com.utility.dlmsreader.transport.toHexString
import gurux.dlms.GXByteBuffer
import gurux.dlms.GXDLMSException
import gurux.dlms.GXReplyData
import gurux.dlms.enums.Authentication
import gurux.dlms.enums.InterfaceType
import gurux.dlms.enums.RequestTypes
import gurux.dlms.objects.GXDLMSClock
import gurux.dlms.objects.GXDLMSData
import gurux.dlms.objects.GXDLMSObject
import gurux.dlms.objects.GXDLMSRegister
import gurux.dlms.secure.GXDLMSSecureClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeoutException

data class ConnectionParams(
    val clientAddress: Int = 0x20, // 0x10 = Public (16), 0x20 = Meter Reader (32)
    val serverAddress: Int = 0x01,
    val authentication: Authentication = Authentication.LOW,
    val password: String = "lnt1",
    val baudRate: Int = 9600,
    val useModeE: Boolean = false,
    val responseTimeoutMs: Long = 3000L
)

data class MeterReadSnapshot(
    val logicalDeviceName: String,
    val serialNumber: String,
    val manufacturerName: String,
    val firmwareVersion: String,
    val meterClock: String,
    val activeEnergyKwh: String,
    val instantaneousVoltage: String,
    val instantaneousCurrent: String,
    val powerFactor: String
)

class DlmsMeterEngine(private val transport: OpticalUsbTransport) {

    suspend fun readMeter(params: ConnectionParams): Result<MeterReadSnapshot> =
        withContext(Dispatchers.IO) {
            runCatching {
                transport.openPort(params.baudRate, params.useModeE).getOrThrow()

                val client = GXDLMSSecureClient(
                    true, // IS 15959 uses Logical Name (LN) referencing
                    params.clientAddress,
                    params.serverAddress,
                    params.authentication,
                    if (params.authentication == Authentication.NONE) null else params.password,
                    InterfaceType.HDLC
                )

                val reply = GXReplyData()

                try {
                    // 1. Preemptive DISC to reset any hung optical session on the meter
                    runCatching {
                        val disc = client.disconnectRequest()
                        readDlmsPacket(client, disc, reply, timeoutMs = 600L, maxRetries = 1)
                    }
                    reply.clear()
                    delay(100)

                    // 2. Establish HDLC Link (SNRM -> UA)
                    transport.log(TraceDirection.INFO, "Sending HDLC SNRM (Client=${params.clientAddress})...")
                    val snrm = client.snrmRequest()
                    if (snrm.isNotEmpty()) {
                        readDlmsPacket(client, snrm, reply, params.responseTimeoutMs)
                        client.parseUAResponse(reply.data)
                        transport.log(TraceDirection.INFO, "HDLC UA received. MaxInfoRX=${client.hdlcSettings.maxInfoRX}")
                    }

                    // 3. Establish COSEM Application Association (AARQ -> AARE)
                    transport.log(TraceDirection.INFO, "Sending COSEM AARQ (${params.authentication})...")
                    for (aarqFrame in client.aarqRequest()) {
                        reply.clear()
                        readDlmsPacket(client, aarqFrame, reply, params.responseTimeoutMs)
                    }
                    client.parseAare(reply.data)

                    // 4. Perform HLS Mutual Authentication Pass (if High Authentication is used)
                    if (client.isAuthenticationRequired) {
                        transport.log(TraceDirection.INFO, "Completing HLS Challenge-Response...")
                        for (hlsFrame in client.applicationAssociationRequest) {
                            reply.clear()
                            readDlmsPacket(client, hlsFrame, reply, params.responseTimeoutMs)
                        }
                        client.parseHlsAuthentication(reply.data)
                    }
                    transport.log(TraceDirection.INFO, "DLMS Association Established!")

                    // 5. Read Identity & Clock Registers (Accessible in Public 0x10 and MR 0x20)
                    val ldn = readDataString(client, "0.0.42.0.0.255", params.responseTimeoutMs)
                    val serial = readDataString(client, "0.0.96.1.0.255", params.responseTimeoutMs)
                    val manufacturer = readDataString(client, "0.0.96.1.1.255", params.responseTimeoutMs)
                    val fwVersion = readDataString(client, "1.0.0.2.0.255", params.responseTimeoutMs)
                    val clock = readClock(client, "0.0.1.0.0.255", params.responseTimeoutMs)

                    // 6. Read Metrology Registers (Requires Meter Reader 0x20 on most IS 15959 meters)
                    val kwh = if (params.clientAddress != 0x10) {
                        readScaledRegister(client, "1.0.1.8.0.255", params.responseTimeoutMs)
                    } else "Switch to Client 32 (MR) for Energy"

                    val voltage = if (params.clientAddress != 0x10) {
                        readScaledRegister(client, "1.0.32.7.0.255", params.responseTimeoutMs)
                    } else "Switch to Client 32 (MR) for Voltage"

                    val current = if (params.clientAddress != 0x10) {
                        readScaledRegister(client, "1.0.31.7.0.255", params.responseTimeoutMs)
                    } else "Switch to Client 32 (MR) for Current"

                    val pf = if (params.clientAddress != 0x10) {
                        readScaledRegister(client, "1.0.13.7.0.255", params.responseTimeoutMs)
                    } else "Switch to Client 32 (MR) for PF"

                    MeterReadSnapshot(
                        logicalDeviceName = ldn,
                        serialNumber = serial,
                        manufacturerName = manufacturer,
                        firmwareVersion = fwVersion,
                        meterClock = clock,
                        activeEnergyKwh = kwh,
                        instantaneousVoltage = voltage,
                        instantaneousCurrent = current,
                        powerFactor = pf
                    )
                } finally {
                    // 7. Always send DISC so the meter's optical port does not stay locked
                    runCatching {
                        transport.log(TraceDirection.INFO, "Sending HDLC Disconnect (DISC)...")
                        readDlmsPacket(client, client.disconnectRequest(), GXReplyData(), 1000L, 1)
                    }
                    transport.closePort()
                }
            }
        }

    private suspend fun readDataString(
        client: GXDLMSSecureClient,
        obis: String,
        timeoutMs: Long
    ): String = runCatching {
        val obj = GXDLMSData(obis)
        val raw = readAttribute(client, obj, 2, timeoutMs)
        when (raw) {
            is ByteArray -> String(raw, Charsets.US_ASCII).trim { it <= ' ' }
            null -> "N/A"
            else -> raw.toString()
        }
    }.getOrElse { "Unsupported ($obis)" }

    private suspend fun readClock(
        client: GXDLMSSecureClient,
        obis: String,
        timeoutMs: Long
    ): String = runCatching {
        val clockObj = GXDLMSClock(obis)
        readAttribute(client, clockObj, 2, timeoutMs)?.toString() ?: "N/A"
    }.getOrElse { "Unsupported ($obis)" }

    private suspend fun readScaledRegister(
        client: GXDLMSSecureClient,
        obis: String,
        timeoutMs: Long
    ): String = runCatching {
        val reg = GXDLMSRegister(obis)
        // Read Attribute 3 (Scaler and Unit) first so Gurux knows how to scale Attribute 2
        readAttribute(client, reg, 3, timeoutMs)
        // Read Attribute 2 (Value)
        readAttribute(client, reg, 2, timeoutMs)
        "${reg.value} ${reg.unit}"
    }.getOrElse { e -> "Error: ${e.message}" }

    private suspend fun readAttribute(
        client: GXDLMSSecureClient,
        obj: GXDLMSObject,
        attributeIndex: Int,
        timeoutMs: Long
    ): Any? {
        val reply = GXReplyData()
        val requestFrames = client.read(obj, attributeIndex)
        readDataBlock(client, requestFrames, reply, timeoutMs)
        return client.updateValue(obj, attributeIndex, reply.value)
    }

    /**
     * Handles both HDLC segmented frames (RequestTypes.FRAME -> RR)
     * and COSEM Application Block Transfers (RequestTypes.DATABLOCK).
     */
    private suspend fun readDataBlock(
        client: GXDLMSSecureClient,
        txFrames: Array<ByteArray>,
        reply: GXReplyData,
        timeoutMs: Long
    ) {
        for (frame in txFrames) {
            reply.clear()
            readDlmsPacket(client, frame, reply, timeoutMs)
            while (reply.isMoreData) {
                val nextFrame: ByteArray? = when {
                    reply.moreData.contains(RequestTypes.FRAME) ->
                        client.receiverReady(RequestTypes.FRAME)
                    reply.moreData.contains(RequestTypes.DATABLOCK) ->
                        client.receiverReady(RequestTypes.DATABLOCK)
                    else -> null
                }
                if (nextFrame == null || nextFrame.isEmpty()) break
                readDlmsPacket(client, nextFrame, reply, timeoutMs)
            }
        }
    }

    /**
     * Sends a single HDLC frame and reassembles USB serial byte chunks until
     * Gurux's client.getData() confirms a complete HDLC frame (bounded by 0x7E and valid FCS).
     */
    private suspend fun readDlmsPacket(
        client: GXDLMSSecureClient,
        txFrame: ByteArray,
        reply: GXReplyData,
        timeoutMs: Long,
        maxRetries: Int = 3
    ) {
        if (txFrame.isEmpty()) return
        val rxBuffer = GXByteBuffer()
        val chunkBuf = ByteArray(256)

        var attempt = 0
        while (attempt < maxRetries) {
            attempt++
            rxBuffer.clear()
            reply.error = 0

            transport.write(txFrame)
            val deadline = SystemClock.elapsedRealtime() + timeoutMs

            try {
                while (SystemClock.elapsedRealtime() < deadline) {
                    val bytesRead = transport.readChunk(chunkBuf, 150)
                    if (bytesRead > 0) {
                        transport.log(TraceDirection.RX, chunkBuf.toHexString(bytesRead))
                        rxBuffer.set(chunkBuf, 0, bytesRead)

                        // client.getData returns true ONLY when a complete HDLC frame has arrived
                        if (client.getData(rxBuffer, reply)) {
                            if (reply.error != 0) {
                                throw GXDLMSException(reply.error.toInt())
                            }
                            delay(25) // Inter-frame pacing delay for optical port stability
                            return
                        }
                    }
                }
                throw TimeoutException("Timeout waiting for HDLC response (Attempt $attempt/$maxRetries)")
            } catch (dlmsEx: GXDLMSException) {
                // Meter explicitly rejected the request (e.g., ReadWriteDenied) - do not retry
                transport.log(TraceDirection.ERROR, "DLMS Reject: ${dlmsEx.message}")
                throw dlmsEx
            } catch (ioEx: Exception) {
                transport.log(TraceDirection.ERROR, ioEx.message ?: "Communication error")
                if (attempt >= maxRetries) throw IOException("Failed after $maxRetries attempts: ${ioEx.message}", ioEx)
                delay(150)
            }
        }
    }
}