package com.utility.dlmsreader.dlms

import com.utility.dlmsreader.transport.OpticalUsbTransport
import gurux.dlms.GXDateTime
import gurux.dlms.enums.Unit as DlmsUnit
import gurux.dlms.objects.GXDLMSClock
import gurux.dlms.objects.GXDLMSData
import gurux.dlms.objects.GXDLMSDemandRegister
import gurux.dlms.objects.GXDLMSExtendedRegister
import gurux.dlms.objects.GXDLMSObject
import gurux.dlms.objects.GXDLMSProfileGeneric
import gurux.dlms.objects.GXDLMSRegister
import java.util.Calendar
import java.util.Date
import kotlin.math.pow

data class ParameterRow(
    val parameterName: String,
    val obisCode: String,
    val formattedValue: String,
    val unit: String = ""
)

data class RegisterReading(
    val parameterName: String,
    val obisCode: String,
    val reading: String,
    val unit: String,
    val captureTimestamp: String = ""
)

data class ProfileColumn(
    val parameterName: String,
    val obisCode: String,
    val unit: String = ""
)

data class ProfileSection(
    val title: String,
    val profileObis: String,
    val columns: List<ProfileColumn>,
    val rows: List<List<String>>
)

private data class ProfileScaler(
    val multiplier: Double,
    val unit: DlmsUnit?
)

data class IdentitySection(
    val rows: List<ParameterRow>,
    val nameplate: ProfileSection? = null
) {
    fun merge(other: IdentitySection): IdentitySection {
        val existingObis = rows.map { it.obisCode }.toSet()
        return IdentitySection(
            rows = rows + other.rows.filter { it.obisCode !in existingObis },
            nameplate = other.nameplate ?: nameplate
        )
    }
}

data class TamperEventRow(
    val compartment: String,
    val dateTime: String,
    val eventCode: String,
    val eventDescription: String,
    val voltage: String,
    val current: String,
    val powerFactor: String,
    val energy: String
)

data class MeterDataReport(
    val identity: IdentitySection? = null,
    val cumulativeEnergies: List<RegisterReading> = emptyList(),
    val instantaneous: ProfileSection? = null,
    val billing: ProfileSection? = null,
    val blockLoad: ProfileSection? = null,
    val dailyLoad: ProfileSection? = null,
    val tamperEvents: List<TamperEventRow> = emptyList(),
    val errors: List<String> = emptyList()
) {
    val hasData: Boolean
        get() = identity != null || cumulativeEnergies.isNotEmpty() ||
            instantaneous != null || billing != null || blockLoad != null ||
            dailyLoad != null || tamperEvents.isNotEmpty() || errors.isNotEmpty()

    fun merge(other: MeterDataReport): MeterDataReport = MeterDataReport(
        identity = when {
            identity == null -> other.identity
            other.identity == null -> identity
            else -> identity.merge(other.identity)
        },
        cumulativeEnergies = if (other.cumulativeEnergies.isNotEmpty()) other.cumulativeEnergies else cumulativeEnergies,
        instantaneous = other.instantaneous ?: instantaneous,
        billing = other.billing ?: billing,
        blockLoad = other.blockLoad ?: blockLoad,
        dailyLoad = other.dailyLoad ?: dailyLoad,
        tamperEvents = if (other.tamperEvents.isNotEmpty()) other.tamperEvents else tamperEvents,
        errors = errors + other.errors
    )
}

class Is15959DataReader(
    private val transport: OpticalUsbTransport,
    private val client: gurux.dlms.secure.GXDLMSSecureClient,
    packetTimeoutMs: Long = 3000L,
    private val onProgress: suspend (String) -> Unit = {}
) {
    private val executor = GuruxPacketExecutor(transport, client, packetTimeoutMs = packetTimeoutMs)

    suspend fun readIdentityAndNameplate(): IdentitySection {
        progress("Reading identity and nameplate...")
        val rows = mutableListOf<ParameterRow>()
        readTextObject("0.0.42.0.0.255", rows)
        readTextObject("0.0.96.1.0.255", rows)
        readTextObject("0.0.96.1.1.255", rows)
        readTextObject("0.0.96.1.4.255", rows)
        readTextObject("1.0.0.2.0.255", rows)
        runCatching {
            val clock = GXDLMSClock("0.0.1.0.0.255")
            val value = executor.readObject(clock, 2)
            addRow(rows, "0.0.1.0.0.255", value)
        }
        val nameplate = runCatching {
            val profile = Is15959Catalog.profileByName("Nameplate Profile")
            loadProfile(profile.dataObis, profile.name)
        }.getOrNull()
        return IdentitySection(rows, nameplate)
    }

    suspend fun readIdentityHeader(): IdentitySection {
        progress("Reading identity header...")
        val rows = mutableListOf<ParameterRow>()
        readTextObject("0.0.42.0.0.255", rows)
        readTextObject("0.0.96.1.0.255", rows)
        readTextObject("0.0.96.1.1.255", rows)
        return IdentitySection(rows)
    }

    suspend fun readCumulativeEnergies(): List<RegisterReading> {
        progress("Reading cumulative energy and maximum demand registers...")
        val definitions = listOf(
            "1.0.1.8.0.255",
            "1.0.9.8.0.255",
            "1.0.3.8.0.255",
            "1.0.5.8.0.255",
            "1.0.4.8.0.255",
            "1.0.8.8.0.255",
            "1.0.1.6.0.255",
            "1.0.9.6.0.255"
        )
        return definitions.mapNotNull { obis ->
            runCatching { readRegister(obis, obis.endsWith(".6.0.255")) }.getOrNull()
        }
    }

    suspend fun readInstantaneousProfile(): ProfileSection {
        progress("Reading instantaneous profile...")
        val profile = Is15959Catalog.profileByName("Instantaneous Profile")
        return loadProfile(profile.dataObis, "Instantaneous Parameters Snapshot", profile.scalerObis)
    }

    suspend fun readBillingProfile(): ProfileSection {
        progress("Reading billing history profile...")
        val profile = Is15959Catalog.profileByName("Billing Profile")
        return loadProfile(profile.dataObis, "Billing History Profile", profile.scalerObis)
    }

    suspend fun readBlockLoadSurvey(maxEntries: Int = 48): ProfileSection {
        progress("Reading block load survey...")
        val profile = Is15959Catalog.profileByName("Block Load Profile")
        return loadRecentProfile(profile.dataObis, "Block Load Survey", maxEntries, profile.scalerObis)
    }

    suspend fun readDailyLoadSurvey(maxEntries: Int = 35): ProfileSection {
        progress("Reading daily load survey...")
        val profile = Is15959Catalog.profileByName("Daily Load Profile")
        return loadRecentProfile(profile.dataObis, "Daily Load Survey", maxEntries, profile.scalerObis)
    }

    suspend fun readTamperEventLogs(): List<TamperEventRow> {
        progress("Reading tamper and event logs...")
        return Is15959Catalog.PROFILES
            .filter { it.dataObis.startsWith("0.0.99.98.") }
            .flatMap { profile ->
                runCatching { readTamperProfile(profile.name, profile.dataObis) }.getOrDefault(emptyList())
        }
    }

    suspend fun readAllMeterData(): MeterDataReport {
        var report = MeterDataReport()
        val errors = mutableListOf<String>()

        suspend fun section(name: String, action: suspend () -> MeterDataReport) {
            runCatching { action() }
                .onSuccess { report = report.merge(it) }
                .onFailure {
                    val message = "$name unavailable: ${it.message ?: "unsupported or unreadable"}"
                    errors += message
                    progress(message)
                }
        }

        section("Identity") {
            MeterDataReport(identity = readIdentityAndNameplate())
        }
        section("Cumulative energy") {
            MeterDataReport(cumulativeEnergies = readCumulativeEnergies())
        }
        section("Instantaneous profile") {
            MeterDataReport(instantaneous = readInstantaneousProfile())
        }
        section("Billing profile") {
            MeterDataReport(billing = readBillingProfile())
        }
        section("Block load survey") {
            MeterDataReport(blockLoad = readBlockLoadSurvey())
        }
        section("Daily load survey") {
            MeterDataReport(dailyLoad = readDailyLoadSurvey())
        }
        section("Tamper logs") {
            MeterDataReport(tamperEvents = readTamperEventLogs())
        }
        progress("Full meter extraction complete.")
        return report.copy(errors = report.errors + errors)
    }

    private suspend fun readTextObject(obis: String, rows: MutableList<ParameterRow>) {
        runCatching {
            val obj = GXDLMSData(obis)
            obj.setUIDataType(2, gurux.dlms.enums.DataType.STRING)
            addRow(rows, obis, executor.readObject(obj, 2))
        }
    }

    private suspend fun readRegister(obis: String, extended: Boolean): RegisterReading {
        val register = if (extended) GXDLMSExtendedRegister(obis) else GXDLMSRegister(obis)
        executor.readObject(register, 3)
        val raw = executor.readObject(register, 2)
        if (register is GXDLMSExtendedRegister) {
            runCatching { executor.readObject(register, 5) }
        }
        val descriptor = Is15959Formatter.getObisLabelAndUnit(obis)
        val formatted = Is15959Formatter.formatValue(
            raw,
            scaler = 1.0,
            unit = register.unit,
            obis = obis,
            includeUnit = false
        )
        val capture = if (register is GXDLMSExtendedRegister) {
            Is15959Formatter.formatValue(register.captureTime)
        } else ""
        return RegisterReading(
            parameterName = descriptor.label,
            obisCode = obis,
            reading = formatted,
            unit = descriptor.unit.ifEmpty { Is15959Formatter.unitSymbol(register.unit) },
            captureTimestamp = capture
        )
    }

    private suspend fun loadProfile(obis: String, title: String, scalerObis: String? = null): ProfileSection {
        val profile = GXDLMSProfileGeneric(obis)
        executor.readObject(profile, 3)
        val scalers = loadCompanionScalers(scalerObis ?: Is15959Catalog.profileByDataObis(obis).scalerObis)
        if (scalers.isEmpty()) loadCaptureScalers(profile)
        executor.readObject(profile, 2)
        return formatProfile(profile, title, scalers)
    }

    private suspend fun loadRecentProfile(
        obis: String,
        title: String,
        maxEntries: Int,
        scalerObis: String?
    ): ProfileSection {
        val profile = GXDLMSProfileGeneric(obis)
        executor.readObject(profile, 3)
        val scalers = loadCompanionScalers(scalerObis)
        if (scalers.isEmpty()) loadCaptureScalers(profile)

        try {
            val endCalendar = Calendar.getInstance()
            val startCalendar = endCalendar.clone() as Calendar
            startCalendar.add(Calendar.DAY_OF_YEAR, -7)
            executor.readRowsByRange(profile, startCalendar.time, endCalendar.time)
            trimProfileBuffer(profile, maxEntries)
        } catch (_: Exception) {
            // Some L&T firmware rejects selective access for load surveys. The
            // complete buffer is reliable, and is trimmed locally for the PDF.
            executor.readObject(profile, 2)
            trimProfileBuffer(profile, maxEntries)
        }
        return formatProfile(profile, title, scalers)
    }

    private fun trimProfileBuffer(profile: GXDLMSProfileGeneric, maxEntries: Int) {
        if (profile.buffer.size <= maxEntries) return
        val latestRows = profile.buffer.takeLast(maxEntries.coerceAtLeast(1))
        profile.setBuffer(latestRows.map { rowValues(it).toTypedArray() }.toTypedArray())
    }

    private suspend fun loadCaptureScalers(profile: GXDLMSProfileGeneric) {
        for (entry in profile.captureObjects) {
            val obj = entry.key
            if (obj is GXDLMSRegister) {
                runCatching { executor.readObject(obj, 3) }
            } else if (obj is GXDLMSDemandRegister) {
                runCatching { executor.readObject(obj, 4) }
            }
        }
    }

    private suspend fun loadCompanionScalers(obis: String?): Map<String, ProfileScaler> {
        if (obis == null) return emptyMap()
        return runCatching {
            val scalerProfile = GXDLMSProfileGeneric(obis)
            executor.readObject(scalerProfile, 3)
            executor.readObject(scalerProfile, 2)
            val row = scalerProfile.buffer.lastOrNull()?.let { rowValues(it) }.orEmpty()
            scalerProfile.captureObjects.mapIndexedNotNull { index, entry ->
                val structure = row.getOrNull(index)?.let { rowValues(it) } ?: return@mapIndexedNotNull null
                val exponent = (structure.getOrNull(0) as? Number)?.toInt() ?: return@mapIndexedNotNull null
                val unitValue = (structure.getOrNull(1) as? Number)?.toInt()
                val unit = unitValue?.let { DlmsUnit.forValue(it) }
                entry.key.logicalName to ProfileScaler(10.0.pow(exponent), unit)
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun formatProfile(
        profile: GXDLMSProfileGeneric,
        title: String,
        scalers: Map<String, ProfileScaler> = emptyMap()
    ): ProfileSection {
        val objects = profile.captureObjects.map { it.key }
        val columns = objects.map { obj ->
            val obis = obj.logicalName
            val descriptor = Is15959Formatter.getObisLabelAndUnit(obis)
            val profileScaler = scalers[obis]
            ProfileColumn(
                parameterName = descriptor.label,
                obisCode = obis,
                unit = descriptor.unit.ifEmpty {
                    profileScaler?.unit?.let { Is15959Formatter.unitSymbol(it) }
                        ?: (obj as? GXDLMSRegister)?.let { Is15959Formatter.unitSymbol(it.unit) }
                        ?: (obj as? GXDLMSDemandRegister)?.let { Is15959Formatter.unitSymbol(it.unit) }
                        ?: ""
                }
            )
        }
        val rows = profile.buffer.map { row ->
            val values = rowValues(row)
            objects.indices.map { index ->
                formatProfileValue(
                    objects[index],
                    values.getOrNull(index),
                    scalers[objects[index].logicalName]
                )
            }
        }
        return ProfileSection(title, profile.logicalName, columns, rows)
    }

    private suspend fun readTamperProfile(compartment: String, obis: String): List<TamperEventRow> {
        val profile = GXDLMSProfileGeneric(obis)
        executor.readObject(profile, 3)
        val scalers = loadCompanionScalers(Is15959Catalog.profileByDataObis(obis).scalerObis)
        if (scalers.isEmpty()) loadCaptureScalers(profile)
        executor.readObject(profile, 2)
        val formatted = formatProfile(profile, "$compartment Tamper Events", scalers)
        val objects = profile.captureObjects.map { it.key }
        val dateIndex = objects.indexOfFirst { it is GXDLMSClock || Is15959Formatter.getObisLabelAndUnit(it.logicalName).label.contains("time", true) }
        val eventIndex = objects.indexOfFirst { Is15959Formatter.getObisLabelAndUnit(it.logicalName).label.contains("event", true) || Is15959Formatter.getObisLabelAndUnit(it.logicalName).label.contains("code", true) }
        val voltageIndex = objects.indexOfFirst { Is15959Formatter.getObisLabelAndUnit(it.logicalName).unit == "V" }
        val currentIndex = objects.indexOfFirst { Is15959Formatter.getObisLabelAndUnit(it.logicalName).unit == "A" }
        val pfIndex = objects.indexOfFirst { Is15959Formatter.getObisLabelAndUnit(it.logicalName).label.contains("PF", true) }
        val energyIndex = objects.indexOfFirst { Is15959Formatter.getObisLabelAndUnit(it.logicalName).unit.contains("Wh") }

        return profile.buffer.mapIndexed { rowIndex, rawRow ->
            val raw = rowValues(rawRow)
            val values = formatted.rows.getOrNull(rowIndex).orEmpty()
            val code = raw.getOrNull(eventIndex).toIntValue()
            TamperEventRow(
                compartment = compartment,
                dateTime = values.valueAt(dateIndex),
                eventCode = code?.toString() ?: values.valueAt(eventIndex),
                eventDescription = code?.let { Is15959Formatter.decodeTamperEvent(it) } ?: "Unknown Event",
                voltage = values.valueAt(voltageIndex),
                current = values.valueAt(currentIndex),
                powerFactor = values.valueAt(pfIndex),
                energy = values.valueAt(energyIndex)
            )
        }
    }

    private fun formatProfileValue(
        obj: GXDLMSObject,
        raw: Any?,
        profileScaler: ProfileScaler?
    ): String {
        if (raw is GXDateTime || raw is Date || (raw is ByteArray && raw.size == 12)) {
            return Is15959Formatter.formatCompactDateTime(raw)
        }
        val register = obj as? GXDLMSRegister
        val demandRegister = obj as? GXDLMSDemandRegister
        val unit = profileScaler?.unit ?: register?.unit ?: demandRegister?.unit
        val value = Is15959Formatter.formatValue(
            raw,
            scaler = profileScaler?.multiplier ?: 1.0,
            unit = unit,
            obis = obj.logicalName,
            includeUnit = false
        )
        return value
    }

    private fun addRow(rows: MutableList<ParameterRow>, obis: String, raw: Any?) {
        val descriptor = Is15959Formatter.getObisLabelAndUnit(obis)
        rows += ParameterRow(
            descriptor.label,
            obis,
            Is15959Formatter.formatValue(raw, obis = obis, includeUnit = false),
            descriptor.unit
        )
    }

    private suspend fun progress(message: String) {
        onProgress(message)
        transport.log(com.utility.dlmsreader.transport.TraceDirection.INFO, message)
    }

    private fun rowValues(row: Any?): List<Any?> = when (row) {
        is Array<*> -> row.toList()
        is Iterable<*> -> row.toList()
        null -> emptyList()
        else -> listOf(row)
    }

    private fun Any?.toIntValue(): Int? = when (this) {
        is Number -> toInt()
        is ByteArray -> String(this, Charsets.US_ASCII).trim().toIntOrNull()
        else -> this?.toString()?.trim()?.toIntOrNull()
    }

    private fun List<String>.valueAt(index: Int): String = if (index >= 0) getOrNull(index) ?: "N/A" else "N/A"
}
