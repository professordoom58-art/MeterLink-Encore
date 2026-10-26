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
import gurux.dlms.enums.Security
import gurux.dlms.objects.enums.SecuritySuite
import gurux.dlms.objects.GXDLMSClock
import gurux.dlms.objects.GXDLMSData
import gurux.dlms.objects.GXDLMSObject
import gurux.dlms.objects.GXDLMSRegister
import gurux.dlms.secure.GXDLMSSecureClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeoutException

data class ConnectionParams(
    val clientAddress: Int = 0x20, // 0x10 = Public (16), 0x20 = Meter Reader (32)
    val serverAddress: Int = 0x01,
    val authentication: Authentication = Authentication.LOW,
    val password: String = "lnt1",
    val passwordCandidates: List<String> = emptyList(),
    val baudRate: Int = 9600,
    val useModeE: Boolean = false,
    val responseTimeoutMs: Long = 3000L,
    val securitySuite0: Boolean = false,
    val secureClientAddress: Int = 32,
    val securitySystemTitle: String = "",
    val authenticationKey: String = "",
    val blockCipherKey: String = "",
    val dedicatedKey: String? = null
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

enum class MeterDataModule {
    IDENTITY,
    ENERGY,
    INSTANTANEOUS,
    BILLING,
    BLOCK_LOAD,
    DAILY_LOAD,
    TAMPER,
    ALL
}

class DlmsMeterEngine(private val transport: OpticalUsbTransport) {

    suspend fun readMeterModule(
        params: ConnectionParams,
        module: MeterDataModule,
        onProgress: suspend (String) -> Unit = {}
    ): Result<MeterDataReport> = withContext(Dispatchers.IO) {
        runCatching {
            suspend fun reportProgress(message: String) {
                onProgress(message)
                transport.log(TraceDirection.INFO, message)
            }

            transport.openPort(params.baudRate, params.useModeE).getOrThrow()
            reportProgress("Starting Public Client (0x10) discovery...")
            val publicClient = GXDLMSSecureClient().apply {
                clientAddress = 16
                serverAddress = 1
                useLogicalNameReferencing = true
                interfaceType = InterfaceType.HDLC
            }
            val publicIdentity = PublicClientDiscovery.discover(transport, publicClient)

            if (module == MeterDataModule.IDENTITY && params.clientAddress == 0x10) {
                transport.markMeterLinkEstablished()
                transport.closePort(preserveStatus = true)
                return@runCatching MeterDataReport(identity = publicIdentity.toIdentitySection())
            }
            if (params.clientAddress == 0x10) {
                throw IOException("Client 32 authentication is required for ${module.name.lowercase(Locale.US)} data.")
            }

            delay(400)
            transport.purgeBuffers()
            reportProgress("Preparing authenticated Meter Reader (Client=32) session...")

            val client = createMeterReaderClient(params, publicIdentity.invocationCounter)
            val executor = GuruxPacketExecutor(transport, client, packetTimeoutMs = params.responseTimeoutMs)
            val reply = GXReplyData()
            var linked = false
            try {
                establishSession(client, executor, reply)
                linked = true
                val reader = Is15959DataReader(transport, client, params.responseTimeoutMs, onProgress)
                when (module) {
                    MeterDataModule.IDENTITY -> MeterDataReport(identity = reader.readIdentityAndNameplate())
                    MeterDataModule.ENERGY -> MeterDataReport(cumulativeEnergies = reader.readCumulativeEnergies())
                    MeterDataModule.INSTANTANEOUS -> MeterDataReport(instantaneous = reader.readInstantaneousProfile())
                    MeterDataModule.BILLING -> MeterDataReport(billing = reader.readBillingProfile())
                    MeterDataModule.BLOCK_LOAD -> MeterDataReport(blockLoad = reader.readBlockLoadSurvey())
                    MeterDataModule.DAILY_LOAD -> MeterDataReport(dailyLoad = reader.readDailyLoadSurvey())
                    MeterDataModule.TAMPER -> MeterDataReport(tamperEvents = reader.readTamperEventLogs())
                    MeterDataModule.ALL -> reader.readAllMeterData()
                }
            } finally {
                if (linked) {
                    runCatching {
                        reportProgress("Closing Meter Reader session...")
                        executor.readDLMSPacket(client.disconnectRequest(), GXReplyData())
                    }
                }
                transport.closePort(preserveStatus = true)
            }
        }
    }

    private suspend fun establishSession(
        client: GXDLMSSecureClient,
        executor: GuruxPacketExecutor,
        reply: GXReplyData
    ) {
        val snrm = client.snrmRequest()
        if (snrm.isEmpty()) throw IOException("Meter Reader SNRM request was empty.")
        executor.readDLMSPacket(snrm, reply)
        client.parseUAResponse(reply.data)
        reply.clear()

        executor.readDataBlock(client.aarqRequest(), reply)
        client.parseAareResponse(reply.data)

        if (client.isAuthenticationRequired) {
            reply.clear()
            executor.readDataBlock(client.applicationAssociationRequest, reply)
            client.parseApplicationAssociationResponse(reply.data)
        }
    }

    private fun createMeterReaderClient(
        params: ConnectionParams,
        invocationCounter: Long?
    ): GXDLMSSecureClient {
        if (params.securitySuite0) {
            require(params.secureClientAddress == 32 || params.secureClientAddress == 48) {
                "IS 15959 Suite 0 client address must be 32 or 48."
            }
        }
        val clientAddress = if (params.securitySuite0) params.secureClientAddress else params.clientAddress
        val authentication = if (params.securitySuite0) Authentication.HIGH_GMAC else params.authentication
        val client = GXDLMSSecureClient(
            true,
            clientAddress,
            params.serverAddress,
            authentication,
            if (authentication == Authentication.NONE || authentication == Authentication.HIGH_GMAC) null else params.password,
            InterfaceType.HDLC
        )
        if (authentication != Authentication.NONE && authentication != Authentication.HIGH_GMAC) {
            client.password = params.password.toByteArray(Charsets.US_ASCII)
        }

        if (params.securitySuite0) {
            val counter = requireNotNull(invocationCounter) {
                "Public Client 16 did not return ${Is15959Catalog.OBIS_INVOCATION_COUNTER_MR}."
            }
            require(params.securitySystemTitle.isNotBlank()) {
                "Security Suite 0 requires an 8-byte system title."
            }
            val ciphering = client.ciphering
            ciphering.security = Security.AUTHENTICATION_ENCRYPTION
            ciphering.securitySuite = SecuritySuite.SUITE_0
            ciphering.invocationCounter = counter
            ciphering.systemTitle = Is15959Catalog.requireSystemTitle(params.securitySystemTitle)
            ciphering.authenticationKey = Is15959Catalog.requireKey(params.authenticationKey, "Authentication key")
            ciphering.blockCipherKey = Is15959Catalog.requireKey(params.blockCipherKey, "Block cipher key")
            ciphering.dedicatedKey = params.dedicatedKey
                ?.let { Is15959Catalog.requireKey(it, "Dedicated key") }
                ?: Is15959Catalog.DEFAULT_DEDICATED_KEY.copyOf()
            transport.log(
                TraceDirection.INFO,
                "Configured IS 15959 Security Suite 0 AES-GCM-128 for Client=$clientAddress, invocation counter=$counter."
            )
        }
        return client
    }

    suspend fun readMeter(params: ConnectionParams): Result<MeterReadSnapshot> = withContext(Dispatchers.IO) {
        val candidates = buildList {
            add(params.password)
            addAll(params.passwordCandidates)
        }.filter { it.isNotEmpty() }.distinct()

        var result: Result<MeterReadSnapshot> = Result.failure(IOException("Meter read did not start."))
        for ((index, password) in candidates.withIndex()) {
            if (index > 0) {
                transport.log(
                    TraceDirection.INFO,
                    "Primary LLS authentication failed; retrying with vendor alternate password #$index."
                )
            }
            result = readMeterOnce(params.copy(password = password, passwordCandidates = emptyList()))
            if (result.isSuccess) return@withContext result

            val failure = result.exceptionOrNull()
            if (index == candidates.lastIndex || !isAuthenticationFailure(failure)) break
        }
        result
    }

    private fun isAuthenticationFailure(error: Throwable?): Boolean {
        var current = error
        while (current != null) {
            val message = current.message?.lowercase(Locale.US).orEmpty()
            if ("auth" in message || "aarq" in message || "aare" in message ||
                "password" in message || "challenge" in message || "access denied" in message
            ) return true
            current = current.cause
        }
        return false
    }

    private suspend fun readMeterOnce(params: ConnectionParams): Result<MeterReadSnapshot> =
        withContext(Dispatchers.IO) {
            runCatching {
                transport.openPort(params.baudRate, params.useModeE).getOrThrow()

                val identity = try {
                    transport.log(TraceDirection.INFO, "Starting Public Client (0x10) Discovery...")

                    val secureClient = GXDLMSSecureClient().apply {
                        clientAddress = 16
                        serverAddress = 1
                        useLogicalNameReferencing = true
                        interfaceType = InterfaceType.HDLC
                    }

                    PublicClientDiscovery.discover(transport, secureClient)
                } catch (e: Exception) {
                    val error = "Discovery failed: ${e.message ?: "unknown error"}"
                    transport.log(TraceDirection.ERROR, error)
                    transport.markNoMeterResponse(error)
                    transport.closePort(preserveStatus = true)
                    throw e
                }

                transport.log(TraceDirection.INFO, "=== METER IDENTITY DISCOVERED ===")
                transport.log(TraceDirection.INFO, "Logical Device Name: ${identity.logicalDeviceName}")
                transport.log(TraceDirection.INFO, "Serial Number: ${identity.serialNumber}")
                transport.log(TraceDirection.INFO, "Real-Time Clock: ${identity.clockTime}")
                transport.log(TraceDirection.INFO, "=================================")
                transport.markMeterLinkEstablished()
                transport.log(
                    TraceDirection.INFO,
                    "✅ Meter Link Established Successfully! Serial: ${identity.serialNumber}"
                )

                if (params.clientAddress == 0x10) {
                    transport.log(
                        TraceDirection.INFO,
                        "Public Client (0x10) discovery complete; skipping a second SNRM session."
                    )
                    transport.closePort(preserveStatus = true)
                    return@runCatching MeterReadSnapshot(
                        logicalDeviceName = identity.logicalDeviceName,
                        serialNumber = identity.serialNumber,
                        manufacturerName = "Public Client Discovery",
                        firmwareVersion = "Available via Meter Reader (0x20)",
                        meterClock = identity.clockTime,
                        activeEnergyKwh = "Switch to Client 32 (MR) for Energy",
                        instantaneousVoltage = "Switch to Client 32 (MR) for Voltage",
                        instantaneousCurrent = "Switch to Client 32 (MR) for Current",
                        powerFactor = "Switch to Client 32 (MR) for PF"
                    )
                }

                delay(400)
                transport.purgeBuffers()
                transport.log(
                    TraceDirection.INFO,
                    "Public discovery session closed; preparing Meter Reader (Client=32) session."
                )

                val client = createMeterReaderClient(params, identity.invocationCounter)

                val reply = GXReplyData()
                var hdlcLinkEstablished = false

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
                    val snrmResponse = ByteArrayOutputStream()
                    var snrmRequest: ByteArray? = null
                    val snrm = try {
                        client.snrmRequest().also { snrmRequest = it }
                    } catch (e: Exception) {
                        val error = "No physical meter response detected (HDLC SNRM failed): ${e.message ?: "request error"}"
                        transport.markNoMeterResponse(error)
                        transport.logConnectionDiagnosticReport(
                            modeEUsed = params.useModeE,
                            sentBytes = snrmRequest,
                            receivedBytes = snrmResponse.toByteArray(),
                            errorMessage = error
                        )
                        throw e
                    }
                    if (snrm.isEmpty()) {
                        val error = "No physical meter response detected (HDLC SNRM was empty)."
                        transport.markNoMeterResponse(error)
                        transport.logConnectionDiagnosticReport(
                            modeEUsed = params.useModeE,
                            sentBytes = snrm.takeIf { it.isNotEmpty() },
                            receivedBytes = snrmResponse.toByteArray(),
                            errorMessage = error
                        )
                        throw IOException("HDLC SNRM request was empty.")
                    }
                    try {
                        readDlmsPacket(
                            client,
                            snrm,
                            reply,
                            params.responseTimeoutMs,
                            rawResponse = snrmResponse
                        )
                        client.parseUAResponse(reply.data)
                        transport.log(TraceDirection.INFO, "HDLC UA received. MaxInfoRX=${client.hdlcSettings.maxInfoRX}")
                        transport.markMeterLinkEstablished()
                        hdlcLinkEstablished = true
                    } catch (e: Exception) {
                        val error = "No physical meter response detected (HDLC SNRM failed): ${e.message ?: "timeout"}"
                        transport.markNoMeterResponse(error)
                        transport.logConnectionDiagnosticReport(
                            modeEUsed = params.useModeE,
                            sentBytes = snrm,
                            receivedBytes = snrmResponse.toByteArray(),
                            errorMessage = error
                        )
                        throw e
                    }

// 3. Establish COSEM Application Association (AARQ -> AARE)
                    transport.log(TraceDirection.INFO, "Sending COSEM AARQ (${params.authentication})...")
                    for (aarqFrame in client.aarqRequest()) {
                        reply.clear()
                        readDlmsPacket(client, aarqFrame, reply, params.responseTimeoutMs)
                    }

                    // 4. Perform HLS Mutual Authentication Pass (if High Authentication is used)
                    if (client.isAuthenticationRequired) {
                        transport.log(TraceDirection.INFO, "Completing HLS Challenge-Response...")
                        for (hlsFrame in client.applicationAssociationRequest) {
                            reply.clear()
                            readDlmsPacket(client, hlsFrame, reply, params.responseTimeoutMs)
                        }
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
                    if (hdlcLinkEstablished) {
                        runCatching {
                            transport.log(TraceDirection.INFO, "Sending HDLC Disconnect (DISC)...")
                            readDlmsPacket(client, client.disconnectRequest(), GXReplyData(), 1000L, 1)
                        }
                    }
                    transport.closePort(preserveStatus = true)
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
        Is15959Formatter.formatValue(raw)
    }.getOrElse { "Unsupported ($obis)" }

    private suspend fun readClock(
        client: GXDLMSSecureClient,
        obis: String,
        timeoutMs: Long
    ): String = runCatching {
        val clockObj = GXDLMSClock(obis)
        Is15959Formatter.formatValue(readAttribute(client, clockObj, 2, timeoutMs))
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
        Is15959Formatter.formatValue(
            reg.value,
            Is15959Formatter.normalizedScaler(reg.scaler, reg.unit),
            reg.unit
        )
    }.getOrElse { e -> "Error: ${e.message}" }

    private suspend fun readAttribute(
        client: GXDLMSSecureClient,
        obj: GXDLMSObject,
        attributeIndex: Int,
        timeoutMs: Long
    ): Any? {
        val reply = GXReplyData()
        val requestFrames = client.read(obj, attributeIndex)
        GuruxPacketExecutor(
            transport = transport,
            client = client,
            packetTimeoutMs = timeoutMs
        ).readDataBlock(requestFrames, reply)
        return client.updateValue(obj, attributeIndex, reply.value)
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
        maxRetries: Int = 3,
        rawResponse: ByteArrayOutputStream? = null
    ) {
        if (txFrame.isEmpty()) return
        val rxBuffer = GXByteBuffer()
        val chunkBuf = ByteArray(256)

        var attempt = 0
        while (attempt < maxRetries) {
            attempt++
            reply.clear()
            rxBuffer.clear()

            transport.write(txFrame)
            val deadline = SystemClock.elapsedRealtime() + timeoutMs

            try {
                while (SystemClock.elapsedRealtime() < deadline) {
                    val bytesRead = transport.readChunk(chunkBuf, 150)
                    if (bytesRead > 0) {
                        rawResponse?.write(chunkBuf, 0, bytesRead)
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

private fun PublicClientDiscovery.MeterIdentity.toIdentitySection(): IdentitySection =
    IdentitySection(
        rows = listOf(
            ParameterRow(
                parameterName = Is15959Formatter.getObisLabelAndUnit("0.0.42.0.0.255").label,
                obisCode = "0.0.42.0.0.255",
                formattedValue = logicalDeviceName
            ),
            ParameterRow(
                parameterName = Is15959Formatter.getObisLabelAndUnit("0.0.96.1.0.255").label,
                obisCode = "0.0.96.1.0.255",
                formattedValue = serialNumber
            ),
            ParameterRow(
                parameterName = Is15959Formatter.getObisLabelAndUnit("0.0.1.0.0.255").label,
                obisCode = "0.0.1.0.0.255",
                formattedValue = clockTime
            )
        )
    )
