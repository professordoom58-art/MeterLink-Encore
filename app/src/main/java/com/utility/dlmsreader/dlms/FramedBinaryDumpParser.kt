package com.utility.dlmsreader.dlms

import gurux.dlms.enums.Unit as DlmsUnit
import java.io.InputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * Parser for framed binary meter dumps.
 *
 * A framed dump begins each record with the three-byte marker
 * `0x53 0x24 0x53` (`S$S`). Records usually mix an ASCII nameplate block with
 * IEEE-754 double register payloads. The parser is deliberately tolerant of
 * vendor framing: record lengths may be absent, little- or big-endian doubles
 * are both accepted, and OBIS codes may be ASCII hexadecimal or raw six-byte
 * sequences.
 */
object FramedBinaryDumpParser {

    val FRAME_HEADER: ByteArray = byteArrayOf(0x53, 0x24, 0x53)
    const val FRAME_HEADER_TEXT = "S\$S"

    private data class Record(
        val classId: Int,
        val obis: String,
        val attribute: Int,
        val value: Any?
    )

    private data class CaptureObject(
        val classId: Int,
        val obis: String,
        val attribute: Int,
        val dataIndex: Int
    )

    private data class Scaler(val exponent: Int, val unit: String)

    private data class ProfileSource(
        val obis: String,
        val scalerObis: String,
        val title: String
    )

    private val PROFILE_SOURCES = listOf(
        ProfileSource(
            obis = "1.0.94.91.0.255",
            scalerObis = "1.0.94.91.3.255",
            title = "Instantaneous Parameters Snapshot"
        ),
        ProfileSource(
            obis = "1.0.98.1.0.255",
            scalerObis = "1.0.94.91.6.255",
            title = "Billing History Profile"
        ),
        ProfileSource(
            obis = "1.0.99.2.0.255",
            scalerObis = "1.0.94.91.5.255",
            title = "Daily Load Survey Profile"
        ),
        ProfileSource(
            obis = "1.0.99.1.0.255",
            scalerObis = "1.0.94.91.4.255",
            title = "Block Load Survey Profile"
        )
    )

    private val ASCII_RUN = Regex("""[\x20-\x7E]{2,}""")
    private val KEY_VALUE = Regex(
        """(?i)([A-Za-z][A-Za-z0-9 _./()#-]{1,40})\s*[:=]\s*([^\x00-\x1F|;,\r\n]{1,80})"""
    )
    private val HEX_OBIS = Regex("""(?i)([0-9a-f]{12})""")
    /** A numeric value with an optional base unit suffix, e.g. `7443000 WH`. */
    private val NUMERIC_WITH_UNIT =
        Regex("""^([+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?)\s*([A-Za-z%]+)?$""")
    private val ENERGY_UNITS = setOf("kWh", "kVAh", "kVArh", "kW", "kVA", "kVAr")

    private data class NameField(val label: String, val obis: String, val unit: String = "")

    private val NAME_FIELDS: Map<String, NameField> = mapOf(
        "model" to NameField("Meter Model", "0.0.96.1.2.255"),
        "metermodel" to NameField("Meter Model", "0.0.96.1.2.255"),
        "modelname" to NameField("Meter Model", "0.0.96.1.2.255"),
        "serial" to NameField("Meter Serial Number", "0.0.96.1.0.255"),
        "serialno" to NameField("Meter Serial Number", "0.0.96.1.0.255"),
        "serialnumber" to NameField("Meter Serial Number", "0.0.96.1.0.255"),
        "meterserial" to NameField("Meter Serial Number", "0.0.96.1.0.255"),
        "meterserialnumber" to NameField("Meter Serial Number", "0.0.96.1.0.255"),
        "srno" to NameField("Meter Serial Number", "0.0.96.1.0.255"),
        "manufacturer" to NameField("Manufacturer Name", "0.0.96.1.1.255"),
        "make" to NameField("Manufacturer Name", "0.0.96.1.1.255"),
        "brand" to NameField("Manufacturer Name", "0.0.96.1.1.255"),
        "vendor" to NameField("Manufacturer Name", "0.0.96.1.1.255"),
        "phase" to NameField("Phase Type", "0.0.94.91.13.255"),
        "phasetype" to NameField("Phase Type", "0.0.94.91.13.255"),
        "noofphase" to NameField("Phase Type", "0.0.94.91.13.255"),
        "category" to NameField("Meter Category", "0.0.94.91.11.255"),
        "metercategory" to NameField("Meter Category", "0.0.94.91.11.255"),
        "metertype" to NameField("Meter Type", "0.0.94.91.9.255"),
        "currentrating" to NameField("Current Rating", "0.0.94.91.12.255", "A"),
        "ratedcurrent" to NameField("Current Rating", "0.0.94.91.12.255", "A"),
        "rating" to NameField("Current Rating", "0.0.94.91.12.255", "A")
    )

    private val REGISTER_FIELDS: Map<String, NameField> = mapOf(
        "activeenergyimport" to NameField("Cumulative Active Energy - Import", "1.0.1.8.0.255", "kWh"),
        "activeenergyimportkwh" to NameField("Cumulative Active Energy - Import", "1.0.1.8.0.255", "kWh"),
        "kwhimport" to NameField("Cumulative Active Energy - Import", "1.0.1.8.0.255", "kWh"),
        "activeenergyexport" to NameField("Cumulative Active Energy - Export", "1.0.2.8.0.255", "kWh"),
        "activeenergyexportkwh" to NameField("Cumulative Active Energy - Export", "1.0.2.8.0.255", "kWh"),
        "kwh" to NameField("Cumulative Active Energy - Import", "1.0.1.8.0.255", "kWh"),
        "apparentenergyimport" to NameField("Cumulative Apparent Energy - Import", "1.0.9.8.0.255", "kVAh"),
        "apparentenergyimportkvah" to NameField("Cumulative Apparent Energy - Import", "1.0.9.8.0.255", "kVAh"),
        "kvahimport" to NameField("Cumulative Apparent Energy - Import", "1.0.9.8.0.255", "kVAh"),
        "kvah" to NameField("Cumulative Apparent Energy - Import", "1.0.9.8.0.255", "kVAh"),
        "apparentenergyexport" to NameField("Cumulative Apparent Energy - Export", "1.0.10.8.0.255", "kVAh"),
        "apparentenergyexportkvah" to NameField("Cumulative Apparent Energy - Export", "1.0.10.8.0.255", "kVAh"),
        "reactiveenergyq1" to NameField("Cumulative Reactive Energy Q1", "1.0.5.8.0.255", "kVArh"),
        "kvarhq1" to NameField("Cumulative Reactive Energy Q1", "1.0.5.8.0.255", "kVArh"),
        "reactiveenergyq2" to NameField("Cumulative Reactive Energy Q2", "1.0.6.8.0.255", "kVArh"),
        "kvarhq2" to NameField("Cumulative Reactive Energy Q2", "1.0.6.8.0.255", "kVArh"),
        "reactiveenergyq3" to NameField("Cumulative Reactive Energy Q3", "1.0.7.8.0.255", "kVArh"),
        "kvarhq3" to NameField("Cumulative Reactive Energy Q3", "1.0.7.8.0.255", "kVArh"),
        "reactiveenergyq4" to NameField("Cumulative Reactive Energy Q4", "1.0.8.8.0.255", "kVArh"),
        "kvarhq4" to NameField("Cumulative Reactive Energy Q4", "1.0.8.8.0.255", "kVArh"),
        "averagebillingpfexport" to NameField("Average Billing Power Factor - Export", "1.0.84.0.0.255", "PF"),
        "billingpfexport" to NameField("Average Billing Power Factor - Export", "1.0.84.0.0.255", "PF"),
        "powerfactorexport" to NameField("Average Billing Power Factor - Export", "1.0.84.0.0.255", "PF"),
        "activepower" to NameField("Active Power", "1.0.1.7.0.255", "kW"),
        "kw" to NameField("Active Power", "1.0.1.7.0.255", "kW"),
        "apparentpower" to NameField("Apparent Power", "1.0.9.7.0.255", "kVA"),
        "kva" to NameField("Apparent Power", "1.0.9.7.0.255", "kVA"),
        "voltage" to NameField("R-Phase / Phase Voltage", "1.0.32.7.0.255", "V"),
        "voltagev" to NameField("R-Phase / Phase Voltage", "1.0.32.7.0.255", "V"),
        "v" to NameField("R-Phase / Phase Voltage", "1.0.32.7.0.255", "V"),
        "current" to NameField("R-Phase / Phase Current", "1.0.31.7.0.255", "A"),
        "currenta" to NameField("R-Phase / Phase Current", "1.0.31.7.0.255", "A"),
        "frequency" to NameField("Frequency", "1.0.14.7.0.255", "Hz"),
        "hz" to NameField("Frequency", "1.0.14.7.0.255", "Hz"),
        "powerfactor" to NameField("Signed Power Factor", "1.0.13.7.0.255", "PF"),
        "pf" to NameField("Signed Power Factor", "1.0.13.7.0.255", "PF")
    )

    fun containsFrameHeader(bytes: ByteArray): Boolean = indexOf(bytes, FRAME_HEADER, 0) >= 0

    fun parse(input: InputStream): MeterDataReport? = parse(input.readBytes())

    fun parse(bytes: ByteArray): MeterDataReport? {
        parseRecordDump(bytes)?.let { return it }
        if (!containsFrameHeader(bytes)) return null
        val frames = splitFrames(bytes)
        if (frames.isEmpty()) return null

        val identity = linkedMapOf<String, ParameterRow>()
        val registers = linkedMapOf<String, RegisterReading>()
        var decodedCounter = 0

        frames.forEach { frame ->
            val text = String(frame, Charsets.ISO_8859_1)
            val keyValues = readKeyValues(text)

            keyValues.forEach { (rawKey, rawValue) ->
                val key = normalizeKey(rawKey)
                val nameField = NAME_FIELDS[key]
                if (nameField != null) {
                    identity[nameField.obis] = ParameterRow(
                        nameField.label,
                        nameField.obis,
                        cleanText(rawValue),
                        nameField.unit
                    )
                    return@forEach
                }

                val registerField = REGISTER_FIELDS[key]
                val obis = normalizeObis(rawKey) ?: registerField?.obis
                val numeric = NUMERIC_WITH_UNIT.matchEntire(cleanText(rawValue))
                if (obis != null && numeric != null) {
                    val descriptor = DlmsCosemFormatter.getObisLabelAndUnit(obis)
                    // A trailing unit in the export (`7443000 WH`) overrides the
                    // catalogue unit; the exporters normalize base units once.
                    val exportedUnit = numeric.groupValues[2]
                        .takeIf { it.isNotBlank() && UnitNormalization.isKnownUnit(it) }
                    registers[obis] = RegisterReading(
                        parameterName = registerField?.label ?: descriptor.label.ifBlank { "OBIS $obis" },
                        obisCode = obis,
                        reading = formatDouble(numeric.groupValues[1].toDouble()),
                        unit = exportedUnit ?: registerField?.unit ?: descriptor.unit
                    )
                }
            }

            // ASCII OBIS codes immediately followed by an IEEE-754 double.
            HEX_OBIS.findAll(text).forEach { match ->
                val obis = ObisProfileCsvParser.normalizeObis(match.value) ?: return@forEach
                val payloadStart = match.range.last + 1
                val value = findDouble(frame, payloadStart)
                if (value != null) {
                    val descriptor = DlmsCosemFormatter.getObisLabelAndUnit(obis)
                    registers[obis] = RegisterReading(
                        descriptor.label.ifBlank { "OBIS $obis" },
                        obis,
                        formatDouble(value),
                        descriptor.unit
                    )
                }
            }

            // Raw six-byte OBIS codes followed by an IEEE-754 double. Only
            // accept codes already known to the COSEM catalogue, which avoids
            // interpreting unrelated binary payload bytes as registers.
            for (offset in 0..frame.size - 14) {
                val obis = rawObis(frame, offset) ?: continue
                val metadata = DlmsCosemCatalog.metadataFor(obis) ?: continue
                if (metadata.category == ObisCategory.IDENTITY ||
                    metadata.category == ObisCategory.NAMEPLATE
                ) {
                    continue
                }
                val value = decodeDouble(frame, offset + 6) ?: continue
                registers[obis] = RegisterReading(
                    metadata.label,
                    obis,
                    formatDouble(value),
                    metadata.unit
                )
            }

            // Last resort: decode aligned doubles so an unknown vendor record
            // still yields numeric data instead of an empty import. Only the
            // binary region after the ASCII nameplate block is considered, so
            // text bytes are not mistaken for register values.
            if (registers.isEmpty()) {
                alignedDoublesAfterText(frame).take(64).forEach { value ->
                    decodedCounter++
                    val obis = "0.0.0.$decodedCounter.0.255"
                    registers[obis] = RegisterReading(
                        "Decoded IEEE-754 Register $decodedCounter",
                        obis,
                        formatDouble(value),
                        ""
                    )
                }
            }
        }

        val identityRows = identity.values.toList()
        if (identityRows.isEmpty() && registers.isEmpty()) return null
        val nameplate = identityRows.takeIf { it.isNotEmpty() }?.let { rows ->
            ProfileSection(
                title = "Nameplate Profile",
                profileObis = DlmsCosemCatalog.OBIS_NAMEPLATE_PROFILE,
                columns = rows.map { ProfileColumn(it.parameterName, it.obisCode, it.unit) },
                rows = listOf(rows.map { it.formattedValue })
            )
        }
        return MeterDataReport(
            identity = if (identityRows.isEmpty()) null else IdentitySection(identityRows, nameplate),
            cumulativeEnergies = registers.values.toList(),
            sourceLabel = MeterDataReport.SOURCE_IMPORTED
        )
    }

    // --------------------------------------------------------- record dumps

    /**
     * Decodes the proprietary `S$S`-framed record layout:
     * `class(u16) OBIS(6) attribute(u8) 00 2D A-XDR(value)`.
     *
     * The routine is intentionally conservative: a frame is only accepted when
     * the class, OBIS and `00 2D` marker all look valid, so ordinary ASCII
     * `S$S` exports fall through to the legacy heuristic parser below.
     */
    private fun parseRecordDump(bytes: ByteArray): MeterDataReport? {
        val records = splitRecordFrames(bytes).mapNotNull(::parseRecord)
        if (records.isEmpty()) return null

        val identityRows = identityRows(records)
        val profiles = PROFILE_SOURCES.mapNotNull { source -> profileSection(records, source) }
        val instantaneous = profiles.firstOrNull {
            it.profileObis == DlmsCosemCatalog.OBIS_INSTANTANEOUS_PROFILE
        }
        val billing = profiles.firstOrNull {
            it.profileObis == DlmsCosemCatalog.OBIS_BILLING_PROFILE
        }
        val daily = profiles.firstOrNull {
            it.profileObis == DlmsCosemCatalog.OBIS_DAILY_LOAD_PROFILE
        }
        val block = profiles.firstOrNull {
            it.profileObis == DlmsCosemCatalog.OBIS_BLOCK_LOAD_PROFILE
        }

        var cumulative = cumulativeEnergies(instantaneous)
        if (cumulative.isEmpty()) cumulative = cumulativeEnergies(billing, lastRow = true)
        if (identityRows.isEmpty() && cumulative.isEmpty() && profiles.isEmpty()) return null

        val nameplate = identityRows.takeIf { it.isNotEmpty() }?.let { rows ->
            ProfileSection(
                title = "Nameplate Profile",
                profileObis = DlmsCosemCatalog.OBIS_NAMEPLATE_PROFILE,
                columns = rows.map { ProfileColumn(it.parameterName, it.obisCode, it.unit) },
                rows = listOf(rows.map { it.formattedValue })
            )
        }
        return MeterDataReport(
            identity = if (identityRows.isEmpty()) null else IdentitySection(identityRows, nameplate),
            cumulativeEnergies = cumulative,
            instantaneous = instantaneous,
            billing = billing,
            blockLoad = block,
            dailyLoad = daily,
            sourceLabel = MeterDataReport.SOURCE_IMPORTED
        )
    }

    /**
     * Splits `S$S` records and also keeps a leading record when the router has
     * already sliced the file at a binary header (which removes the first
     * marker).
     */
    private fun splitRecordFrames(bytes: ByteArray): List<ByteArray> {
        val marker = indexOf(bytes, FRAME_HEADER, 0)
        if (marker < 0) return if (bytes.size > 11) listOf(bytes) else emptyList()

        val starts = mutableListOf<Int>()
        if (marker > 0) starts += 0
        var position = marker
        while (position >= 0) {
            starts += position + FRAME_HEADER.size
            position = indexOf(bytes, FRAME_HEADER, position + FRAME_HEADER.size)
        }
        val ordered = starts.distinct().filter { it in 0 until bytes.size }
        return ordered.mapIndexed { index, start ->
            val end = ordered.getOrNull(index + 1) ?: bytes.size
            bytes.copyOfRange(start, end)
        }
    }

    private fun parseRecord(frame: ByteArray): Record? {
        if (frame.size < 11) return null
        val classId = uint16(frame, 0)
        if (classId !in 1..40) return null
        val obis = obisAt(frame, 2) ?: return null
        val attribute = frame[8].toInt() and 0xFF
        if ((frame[9].toInt() and 0xFF) != 0x00 ||
            (frame[10].toInt() and 0xFF) != 0x2D
        ) {
            return null
        }
        if (frame.size == 11) return Record(classId, obis, attribute, null)
        val decoded = runCatching { AxdrReader(frame, 11).readValue() }
        if (decoded.isFailure) return null
        return Record(classId, obis, attribute, decoded.getOrNull())
    }

    private fun identityRows(records: List<Record>): List<ParameterRow> {
        val rows = linkedMapOf<String, ParameterRow>()
        records.forEach { record ->
            // Only Data (class 1) and Clock (class 8) objects carry nameplate
            // or identity text. Other classes can reuse the same OBIS and would
            // otherwise add misleading rows.
            if (record.classId != 1 && record.classId != 8) return@forEach
            if (record.attribute != 2) return@forEach
            val metadata = DlmsCosemCatalog.metadataFor(record.obis) ?: return@forEach
            if (metadata.category != ObisCategory.IDENTITY &&
                metadata.category != ObisCategory.NAMEPLATE &&
                metadata.category != ObisCategory.CONFIGURATION
            ) {
                return@forEach
            }
            val descriptor = DlmsCosemFormatter.getObisLabelAndUnit(record.obis)
            val value = formatIdentityValue(record.obis, record.value)
            if (value.isBlank() || value == "-") return@forEach
            rows[record.obis] = ParameterRow(
                parameterName = descriptor.label.ifBlank { metadata.label },
                obisCode = record.obis,
                formattedValue = value,
                unit = descriptor.unit.ifBlank { metadata.unit }
            )
        }
        return rows.values.toList()
    }

    private fun formatIdentityValue(obis: String, value: Any?): String = when (value) {
        null -> ""
        is ByteArray -> when {
            value.isEmpty() -> ""
            isTimestampObis(obis) -> DlmsCosemFormatter.formatCompactDateTime(value)
            value.size == 12 && !isTextBytes(value) ->
                DlmsCosemFormatter.formatCompactDateTime(value)
            isTextBytes(value) -> String(value, Charsets.ISO_8859_1).trim()
            else -> hexString(value)
        }
        is String -> value.trim()
        is Long -> formatLong(value)
        is Int -> formatLong(value.toLong())
        is Double -> formatDouble(value)
        is Boolean -> if (value) "1" else "0"
        is List<*> -> value.joinToString(", ") { formatIdentityValue(obis, it) }
        else -> value.toString()
    }

    private fun profileSection(records: List<Record>, source: ProfileSource): ProfileSection? {
        val buffer = records.firstOrNull {
            it.classId == 7 && it.obis == source.obis && it.attribute == 2
        }?.value as? List<*> ?: return null
        val captureValue = records.firstOrNull {
            it.classId == 7 && it.obis == source.obis && it.attribute == 3
        }?.value
        val captures = parseCaptureObjects(captureValue)
        if (buffer.isEmpty() || captures.isEmpty()) return null

        val scalers = parseScalers(records, source.scalerObis)
        val columns = captures.map { capture ->
            val descriptor = DlmsCosemFormatter.getObisLabelAndUnit(capture.obis)
            val scaler = scalers[capture.obis]
            ProfileColumn(
                parameterName = descriptor.label.ifBlank { "OBIS ${capture.obis}" },
                obisCode = capture.obis,
                unit = descriptor.unit.ifBlank { scaler?.unit.orEmpty() },
                attributeIndex = capture.attribute
            )
        }
        val rows = buffer.mapNotNull { row ->
            val values = row as? List<*> ?: return@mapNotNull null
            columns.mapIndexed { index, column ->
                formatProfileValue(
                    value = values.getOrNull(index),
                    column = column,
                    scaler = scalers[column.obisCode]
                )
            }
        }
        if (rows.isEmpty()) return null
        return ProfileSection(
            title = source.title,
            profileObis = source.obis,
            columns = columns,
            rows = rows,
            totalRows = rows.size
        )
    }

    private fun parseCaptureObjects(value: Any?): List<CaptureObject> =
        (value as? List<*>).orEmpty().mapNotNull { item ->
            val fields = item as? List<*> ?: return@mapNotNull null
            val classId = number(fields.getOrNull(0))?.toInt() ?: return@mapNotNull null
            val obis = (fields.getOrNull(1) as? ByteArray)?.let(::rawObisString)
                ?: return@mapNotNull null
            CaptureObject(
                classId = classId,
                obis = obis,
                attribute = number(fields.getOrNull(2))?.toInt() ?: 2,
                dataIndex = number(fields.getOrNull(3))?.toInt() ?: 0
            )
        }

    private fun parseScalers(records: List<Record>, scalerObis: String): Map<String, Scaler> {
        val captures = parseCaptureObjects(
            records.firstOrNull {
                it.classId == 7 && it.obis == scalerObis && it.attribute == 3
            }?.value
        )
        val buffer = records.firstOrNull {
            it.classId == 7 && it.obis == scalerObis && it.attribute == 2
        }?.value as? List<*> ?: return emptyMap()

        val first = buffer.firstOrNull() as? List<*> ?: return emptyMap()
        val row = if (first.firstOrNull() is List<*>) first else buffer
        val pairs = row.mapNotNull { item ->
            val pair = item as? List<*> ?: return@mapNotNull null
            val exponent = number(pair.getOrNull(0))?.toInt() ?: return@mapNotNull null
            val unitCode = number(pair.getOrNull(1))?.toInt() ?: 0xFF
            Scaler(exponent, unitSymbol(unitCode))
        }
        val result = linkedMapOf<String, Scaler>()
        captures.forEachIndexed { index, capture ->
            pairs.getOrNull(index)?.let { result[capture.obis] = it }
        }
        return result
    }

    private fun formatProfileValue(
        value: Any?,
        column: ProfileColumn,
        scaler: Scaler?
    ): String = when (value) {
        null -> "-"
        is ByteArray -> when {
            value.isEmpty() -> "-"
            column.attributeIndex == 5 || isTimestampObis(column.obisCode) ->
                DlmsCosemFormatter.formatCompactDateTime(value)
            isTextBytes(value) -> String(value, Charsets.ISO_8859_1).trim()
            else -> hexString(value)
        }
        is String -> value.trim()
        is Boolean -> if (value) "1" else "0"
        is Long -> normalizeProfileEnergy(scaleLong(value, scaler), column.unit)
        is Int -> normalizeProfileEnergy(scaleLong(value.toLong(), scaler), column.unit)
        is Double -> normalizeProfileEnergy(scaleDouble(value, scaler), column.unit)
        else -> value.toString()
    }

    /**
     * COSEM transmits energy in Wh/VAh/VArh and power in W/VA/VAr, while the
     * profile columns are labelled kWh/kVAh/kVArh/kW/kVA/kVAr. Live reads
     * already apply this factor in [DlmsCosemFormatter.formatValue]; binary
     * dumps must apply it too, otherwise imported energy is 1000x too large.
     */
    private fun normalizeProfileEnergy(reading: String, unit: String): String {
        if (unit !in ENERGY_UNITS) return reading
        val amount = reading.toBigDecimalOrNull() ?: return reading
        return runCatching {
            clampDecimal(amount.scaleByPowerOfTen(-3))
        }.getOrDefault(reading)
    }

    private fun scaleLong(value: Long, scaler: Scaler?): String {
        if (value == 65535L || value == 4294967295L) return "-"
        val exponent = scaler?.exponent ?: 0
        if (exponent == 0) return value.toString()
        if (value == 0L) return "0"
        return runCatching {
            clampDecimal(BigDecimal.valueOf(value).scaleByPowerOfTen(exponent))
        }.getOrDefault(value.toString())
    }

    private fun scaleDouble(value: Double, scaler: Scaler?): String {
        if (!value.isFinite()) return "-"
        if (value == 0.0) return "0"
        val exponent = scaler?.exponent ?: 0
        return runCatching {
            val decimal = BigDecimal.valueOf(value)
            clampDecimal(if (exponent == 0) decimal else decimal.scaleByPowerOfTen(exponent))
        }.getOrDefault(formatDouble(value))
    }

    private fun clampDecimal(decimal: BigDecimal): String {
        val plain = decimal.stripTrailingZeros().toPlainString()
        val clamped = if (plain.contains('.') && plain.substringAfter('.').length > 4) {
            decimal.setScale(3, RoundingMode.HALF_UP)
        } else {
            decimal
        }
        return clamped.stripTrailingZeros().toPlainString()
    }

    private fun cumulativeEnergies(
        section: ProfileSection?,
        lastRow: Boolean = false
    ): List<RegisterReading> {
        val section = section ?: return emptyList()
        val row = if (lastRow) section.rows.lastOrNull() else section.rows.firstOrNull()
        row ?: return emptyList()
        return section.columns.mapIndexedNotNull { index, column ->
            val metadata = DlmsCosemCatalog.metadataFor(column.obisCode)
            val unit = column.unit.ifBlank { metadata?.unit.orEmpty() }
            val isEnergy = metadata?.category == ObisCategory.BILLING ||
                unit in setOf("kWh", "kVAh", "kVArh", "kW", "kVA", "kVAr")
            if (!isEnergy) return@mapIndexedNotNull null
            RegisterReading(
                parameterName = column.parameterName,
                obisCode = column.obisCode,
                reading = row.getOrNull(index).orEmpty(),
                unit = unit
            )
        }.distinctBy { it.obisCode }
    }

    private fun isTimestampObis(obis: String): Boolean =
        obis == "0.0.1.0.0.255" || obis == "0.0.0.1.2.255" ||
            (obis.startsWith("0.0.1.") && obis.endsWith(".0.255"))

    private fun isTextBytes(value: ByteArray): Boolean =
        value.all { byte ->
            val code = byte.toInt() and 0xFF
            code == 0x09 || code == 0x0A || code == 0x0D || code in 0x20..0x7E
        }

    private fun formatLong(value: Long): String = when (value) {
        65535L, 4294967295L -> "-"
        else -> value.toString()
    }

    private fun number(value: Any?): Long? = when (value) {
        is Long -> value
        is Int -> value.toLong()
        is Double -> value.toLong()
        else -> null
    }

    private fun unitSymbol(code: Int): String = runCatching {
        DlmsCosemFormatter.unitSymbol(DlmsUnit.forValue(code))
    }.getOrDefault("")

    private fun rawObisString(bytes: ByteArray): String? {
        if (bytes.size != 6) return null
        return bytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
    }

    private fun hexString(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    private fun uint16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or
            (bytes[offset + 1].toInt() and 0xFF)

    private fun obisAt(bytes: ByteArray, offset: Int): String? {
        if (offset + 6 > bytes.size) return null
        return (0 until 6).joinToString(".") {
            (bytes[offset + it].toInt() and 0xFF).toString()
        }
    }

    /** Minimal A-XDR decoder for the value payload of one record. */
    private class AxdrReader(
        private val data: ByteArray,
        private var position: Int
    ) {
        fun readValue(depth: Int = 0): Any? {
            if (depth > 32) throw IllegalArgumentException("A-XDR nesting too deep")
            return when (val tag = readByte()) {
                0x00 -> null
                0x01, 0x02 -> {
                    val count = readLength()
                    if (count > 100_000) {
                        throw IllegalArgumentException("A-XDR collection too large")
                    }
                    val values = ArrayList<Any?>(count)
                    repeat(count) { values += readValue(depth + 1) }
                    values
                }
                0x03 -> readByte() != 0
                0x04 -> readBytes(readLength())
                0x05 -> readUInt32().toInt().toLong()
                0x06 -> readUInt32()
                0x09 -> readBytes(readLength())
                0x0A -> String(readBytes(readLength()), Charsets.ISO_8859_1)
                0x0C -> String(readBytes(readLength()), Charsets.UTF_8)
                0x0F -> readByte().toByte().toLong()
                0x10 -> readUInt16().toShort().toInt().toLong()
                0x11 -> readByte().toLong()
                0x12 -> readUInt16()
                0x14, 0x15 -> readUInt64()
                0x16 -> readByte().toLong()
                0x17 -> Float.fromBits(readUInt32().toInt()).toDouble()
                0x18 -> Double.fromBits(readUInt64())
                0x19 -> readBytes(readLength())
                else -> throw IllegalArgumentException(
                    "Unsupported A-XDR tag 0x%02X".format(tag)
                )
            }
        }

        private fun readLength(): Int {
            val first = readByte()
            if (first and 0x80 == 0) return first
            val count = first and 0x7F
            if (count == 0 || count > 4) {
                throw IllegalArgumentException("Invalid A-XDR length prefix")
            }
            var value = 0
            repeat(count) { value = (value shl 8) or readByte() }
            return value
        }

        private fun readByte(): Int {
            if (position >= data.size) throw IndexOutOfBoundsException()
            return data[position++].toInt() and 0xFF
        }

        private fun readBytes(count: Int): ByteArray {
            if (count < 0 || position + count > data.size) {
                throw IndexOutOfBoundsException()
            }
            val result = data.copyOfRange(position, position + count)
            position += count
            return result
        }

        private fun readUInt16(): Long =
            (readByte().toLong() shl 8) or readByte().toLong()

        private fun readUInt32(): Long =
            (readByte().toLong() shl 24) or
                (readByte().toLong() shl 16) or
                (readByte().toLong() shl 8) or
                readByte().toLong()

        private fun readUInt64(): Long {
            var value = 0L
            repeat(8) { value = (value shl 8) or readByte().toLong() }
            return value
        }
    }

    /** Splits a dump into records beginning at each `S$S` marker. */
    fun splitFrames(bytes: ByteArray): List<ByteArray> {
        val starts = mutableListOf<Int>()
        var position = 0
        while (position >= 0) {
            position = indexOf(bytes, FRAME_HEADER, position)
            if (position >= 0) {
                starts += position
                position += FRAME_HEADER.size
            }
        }
        if (starts.isEmpty()) return emptyList()
        return starts.mapIndexed { index, start ->
            val end = starts.getOrNull(index + 1) ?: bytes.size
            bytes.copyOfRange(start, end)
        }
    }

    /**
     * Decodes one IEEE-754 double at [offset]. Big-endian is preferred, then
     * little-endian; both must be finite and plausibly sized.
     */
    fun decodeDouble(bytes: ByteArray, offset: Int, littleEndian: Boolean = false): Double? {
        if (offset < 0 || offset + 8 > bytes.size) return null
        val buffer = ByteBuffer.wrap(bytes, offset, 8)
        buffer.order(if (littleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        val value = buffer.double
        return value.takeIf { it.isFinite() && it != 0.0 && kotlin.math.abs(it) < 1e15 }
    }

    /** Aligned doubles in a frame, skipping the textual header block. */
    fun alignedDoubles(bytes: ByteArray): List<Double> {
        val output = mutableListOf<Double>()
        var offset = 0
        while (offset + 8 <= bytes.size) {
            val big = decodeDouble(bytes, offset, littleEndian = false)
            val little = decodeDouble(bytes, offset, littleEndian = true)
            val value = listOfNotNull(big, little).firstOrNull {
                kotlin.math.abs(it) in 1e-9..1e12
            }
            if (value != null) output += value
            offset += 8
        }
        return output
    }

    private fun alignedDoublesAfterText(bytes: ByteArray): List<Double> {
        // Try every byte offset after the frame marker. The real register start
        // is the first offset whose 8-byte window looks like a meter value
        // rather than text noise: finite, plausible, and with a short decimal
        // representation. Once found, read consecutive 8-byte registers.
        var best = emptyList<Double>()
        var bestLength = Int.MAX_VALUE
        for (start in FRAME_HEADER.size..(bytes.size - 8)) {
            val binaryBytes = (0 until 8).count { index ->
                val byte = bytes[start + index].toInt() and 0xFF
                byte !in 0x20..0x7E && byte != 0x09 && byte != 0x0A && byte != 0x0D
            }
            if (binaryBytes < 3) continue

            val big = decodeDouble(bytes, start, littleEndian = false)
            val little = decodeDouble(bytes, start, littleEndian = true)
            val candidates = listOfNotNull(
                big?.takeIf(::isPlainRegisterValue)?.let { it to false },
                little?.takeIf(::isPlainRegisterValue)?.let { it to true }
            )
            if (candidates.isEmpty()) continue

            val (first, useLittleEndian) = candidates.first()
            val values = mutableListOf(first)
            var offset = start + 8
            while (offset + 8 <= bytes.size) {
                val next = decodeDouble(bytes, offset, littleEndian = useLittleEndian)
                    ?.takeIf(::isPlainRegisterValue)
                    ?: break
                values += next
                offset += 8
            }
            val totalLength = values.sumOf { formatDouble(it).length }
            if (values.size > best.size ||
                (values.size == best.size && totalLength < bestLength)
            ) {
                best = values
                bestLength = totalLength
            }
        }
        return best
    }

    /** A short decimal expansion filters out misaligned binary/text noise. */
    private fun isPlainRegisterValue(value: Double): Boolean {
        if (!value.isFinite() || kotlin.math.abs(value) !in 1e-9..1e12) return false
        val plain = formatDouble(value)
        val decimals = plain.substringAfter('.', "").length
        return plain.length <= 18 && decimals <= 6
    }

    /** Formats a decoded register without scientific notation or float noise. */
    private fun formatDouble(value: Double): String = runCatching {
        val decimal = BigDecimal.valueOf(value)
        val plain = decimal.stripTrailingZeros().toPlainString()
        val clamped = if (plain.contains('.') &&
            plain.substringAfter('.').length > 4
        ) {
            decimal.setScale(3, RoundingMode.HALF_UP)
        } else {
            decimal
        }
        clamped.stripTrailingZeros().toPlainString()
    }.getOrDefault(value.toString())

    private fun readKeyValues(text: String): List<Pair<String, String>> {
        val pairs = mutableListOf<Pair<String, String>>()
        KEY_VALUE.findAll(text).forEach { match ->
            val key = match.groupValues[1].trim()
            val value = match.groupValues[2].trim()
            if (key.isNotBlank() && value.isNotBlank()) pairs += key to value
        }
        if (pairs.isNotEmpty()) return pairs

        // No explicit separators: accept whitespace/tab/pipe separated ASCII
        // tokens such as "SERIAL 123456 MODEL XYZ".
        ASCII_RUN.findAll(text).forEach { run ->
            val tokens = run.value.split('|', ';', '\t')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            tokens.forEachIndexed { index, token ->
                val key = normalizeKey(token)
                if ((NAME_FIELDS.containsKey(key) || REGISTER_FIELDS.containsKey(key)) &&
                    index + 1 < tokens.size
                ) {
                    pairs += token to tokens[index + 1]
                }
            }
        }
        return pairs
    }

    private fun normalizeKey(value: String): String =
        value.trim().trim('"').lowercase(Locale.US)
            .replace(Regex("""[^a-z0-9]"""), "")

    private fun cleanText(value: String): String =
        value.trim().trim('"').trimEnd(',', ';', '|', '\u0000')

    private fun normalizeObis(value: String): String? {
        ObisProfileCsvParser.normalizeObis(value)?.let { return it }
        val key = normalizeKey(value)
        return REGISTER_FIELDS[key]?.obis ?: NAME_FIELDS[key]?.obis
    }

    private fun rawObis(bytes: ByteArray, offset: Int): String? {
        if (offset + 6 > bytes.size) return null
        val parts = (0 until 6).map { bytes[offset + it].toInt() and 0xFF }
        // Reject obvious text and padding bytes.
        if (parts.any { it > 255 }) return null
        return parts.joinToString(".")
    }

    private fun findDouble(frame: ByteArray, textOffset: Int): Double? {
        // The ASCII match offset equals the byte offset because ISO-8859-1 is
        // one char per byte. Look just past the code for padding, then decode.
        val payloadStart = textOffset.coerceAtMost(frame.size)
        for (skip in 0..12) {
            val offset = payloadStart + skip
            decodeDouble(frame, offset, littleEndian = false)?.let { return it }
            decodeDouble(frame, offset, littleEndian = true)?.let { return it }
        }
        return null
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        val last = haystack.size - needle.size
        for (index in from.coerceAtLeast(0)..last) {
            var matched = true
            for (needleIndex in needle.indices) {
                if (haystack[index + needleIndex] != needle[needleIndex]) {
                    matched = false
                    break
                }
            }
            if (matched) return index
        }
        return -1
    }
}
