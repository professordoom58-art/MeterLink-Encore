package com.utility.dlmsreader.dlms

import com.utility.dlmsreader.transport.OpticalUsbTransport
import com.utility.dlmsreader.transport.TraceDirection
import gurux.dlms.GXReplyData
import gurux.dlms.enums.Authentication
import gurux.dlms.enums.InterfaceType
import gurux.dlms.enums.Security
import gurux.dlms.objects.enums.SecuritySuite
import gurux.dlms.secure.GXDLMSSecureClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale

data class ConnectionParams(
    val clientAddress: Int = 0x20, // 0x10 = Public (16), 0x20 = Meter Reader (32)
    val serverAddress: Int = 0x01,
    val authentication: Authentication = Authentication.LOW,
    val password: String = "lnt1",
    val passwordCandidates: List<String> = emptyList(),
    val autoTryAlternatePasswords: Boolean = false,
    val vendorName: String = "Unknown",
    val baudRate: Int = 9600,
    val useModeE: Boolean = false,
    val responseTimeoutMs: Long = 3000L,
    val securitySuite0: Boolean = false,
    val secureClientAddress: Int = 32,
    val securitySystemTitle: String = "",
    val authenticationKey: String = "",
    val blockCipherKey: String = "",
    val dedicatedKey: String? = null,
    val loadSurveySelection: LoadSurveySelection = LoadSurveySelection.RECENT
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
        val candidates = buildList {
            add(params.password)
            if (params.autoTryAlternatePasswords) addAll(params.passwordCandidates)
        }.filter { it.isNotEmpty() }.distinct()

        var result: Result<MeterDataReport> = Result.failure(IOException("Meter data read did not start."))
        for ((index, password) in candidates.withIndex()) {
            if (index > 0) {
                transport.log(
                    TraceDirection.INFO,
                    "Explicit alternate-password retry #$index requested by operator."
                )
            }
            result = readMeterModuleOnce(
                params.copy(
                    password = password,
                    passwordCandidates = emptyList(),
                    autoTryAlternatePasswords = false
                ),
                module,
                onProgress
            )
            if (result.isSuccess) return@withContext result
            if (!params.autoTryAlternatePasswords || !isAuthenticationFailure(result.exceptionOrNull())) break
        }
        result
    }

    private suspend fun readMeterModuleOnce(
        params: ConnectionParams,
        module: MeterDataModule,
        onProgress: suspend (String) -> Unit = {}
    ): Result<MeterDataReport> = withContext(Dispatchers.IO) {
        runCatching {
            suspend fun reportProgress(message: String) {
                // Connection/session diagnostics stay in the optical trace log;
                // the user-facing banner only receives section percentages.
                transport.log(TraceDirection.INFO, message)
            }

            reportProgress("Connecting as Client ${params.clientAddress} (Vendor: ${params.vendorName})...")
            transport.openPort(params.baudRate, params.useModeE).getOrThrow()
            // The port is open from here on. This outer finally guarantees it is
            // released even when discovery or pre-session setup throws, so a
            // failed password attempt cannot leak a UsbDeviceConnection.
            try {
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
                    onProgress(ExtractionProgress.format(ExtractionProgress.IDENTITY, 100))
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
                    try {
                        establishSession(client, executor, reply)
                    } catch (e: Exception) {
                        if (isAuthenticationFailure(e)) {
                            val message = authenticationRejectedMessage()
                            transport.log(TraceDirection.ERROR, message)
                            runCatching {
                                executor.readDLMSPacket(client.disconnectRequest(), GXReplyData())
                            }
                            throw IOException(message, e)
                        }
                        throw e
                    }
                    linked = true
                    val reader = Is15959DataReader(
                        transport = transport,
                        client = client,
                        packetTimeoutMs = params.responseTimeoutMs,
                        onProgress = onProgress,
                        meterMake = params.vendorName,
                        loadSurveySelection = params.loadSurveySelection
                    )
                    val identityHeader = if (module != MeterDataModule.IDENTITY && module != MeterDataModule.ALL) {
                        reader.readIdentityHeader()
                    } else null
                    reportProgress(sectionMilestone(module))
                    val moduleReport = try {
                        when (module) {
                        MeterDataModule.IDENTITY -> MeterDataReport(identity = reader.readIdentityAndNameplate())
                        MeterDataModule.ENERGY -> MeterDataReport(cumulativeEnergies = reader.readCumulativeEnergies())
                        MeterDataModule.INSTANTANEOUS -> MeterDataReport(instantaneous = reader.readInstantaneousProfile())
                        MeterDataModule.BILLING -> MeterDataReport(billing = reader.readBillingProfile())
                        MeterDataModule.BLOCK_LOAD -> MeterDataReport(
                            blockLoad = reader.readBlockLoadSurvey(selection = params.loadSurveySelection)
                        )
                        MeterDataModule.DAILY_LOAD -> MeterDataReport(
                            dailyLoad = reader.readDailyLoadSurvey(selection = params.loadSurveySelection)
                        )
                        MeterDataModule.TAMPER -> MeterDataReport(
                            tamperEvents = reader.readTamperEventLogs(),
                            eventLogStatus = reader.eventLogStatus
                        )
                        MeterDataModule.ALL -> reader.readAllMeterData()
                        }
                    } catch (e: Exception) {
                        transport.log(
                            TraceDirection.ERROR,
                            "Section ${module.name.replace('_', ' ')} failed: ${e.message ?: "unknown error"}"
                        )
                        throw e
                    }
                    moduleReport.copy(identity = identityHeader ?: moduleReport.identity)
                } finally {
                    if (linked) {
                        runCatching {
                            reportProgress("Closing Meter Reader session...")
                            executor.readDLMSPacket(client.disconnectRequest(), GXReplyData())
                        }
                    }
                }
            } finally {
                transport.closePort(preserveStatus = true)
            }
        }
    }

    private fun sectionMilestone(module: MeterDataModule): String = when (module) {
        MeterDataModule.IDENTITY -> "Reading Section: Identity (OBIS: 0.0.42.0.0.255)..."
        MeterDataModule.ENERGY -> "Reading Section: Energy & MD (OBIS: 1.0.1.8.0.255)..."
        MeterDataModule.INSTANTANEOUS -> "Reading Section: Instantaneous (OBIS: 1.0.94.91.0.255)..."
        MeterDataModule.BILLING -> "Reading Section: Billing (OBIS: 1.0.98.1.0.255)..."
        MeterDataModule.BLOCK_LOAD -> "Reading Section: Block Load (OBIS: 1.0.99.1.0.255)..."
        MeterDataModule.DAILY_LOAD -> "Reading Section: Daily Load (OBIS: 1.0.99.2.0.255)..."
        MeterDataModule.TAMPER -> "Reading Section: Tamper Logs (OBIS: 0.0.99.98.0.255)..."
        MeterDataModule.ALL -> "Reading Section: Full Meter Dump..."
    }

    private fun authenticationRejectedMessage(): String =
        "Authentication rejected by meter. Verify vendor password (if wrong password was tried multiple times, wait 60-120s for meter security lockout to clear)."

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
