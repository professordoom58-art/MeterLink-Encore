package com.utility.dlmsreader.dlms

import java.io.InputStream
import java.math.BigDecimal
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

    private val ASCII_RUN = Regex("""[\x20-\x7E]{2,}""")
    private val KEY_VALUE = Regex(
        """(?i)([A-Za-z][A-Za-z0-9 _./()#-]{1,40})\s*[:=]\s*([^\x00-\x1F|;,\r\n]{1,80})"""
    )
    private val HEX_OBIS = Regex("""(?i)([0-9a-f]{12})""")
    private val NUMERIC = Regex("""^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?$""")

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
        "kwh" to NameField("Cumulative Active Energy - Import", "1.0.1.8.0.255", "kWh"),
        "apparentenergyimport" to NameField("Cumulative Apparent Energy - Import", "1.0.9.8.0.255", "kVAh"),
        "apparentenergyimportkvah" to NameField("Cumulative Apparent Energy - Import", "1.0.9.8.0.255", "kVAh"),
        "kvahimport" to NameField("Cumulative Apparent Energy - Import", "1.0.9.8.0.255", "kVAh"),
        "kvah" to NameField("Cumulative Apparent Energy - Import", "1.0.9.8.0.255", "kVAh"),
        "apparentenergyexport" to NameField("Cumulative Apparent Energy - Export", "1.0.10.8.0.255", "kVAh"),
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
                if (obis != null && NUMERIC.matches(cleanText(rawValue))) {
                    val descriptor = DlmsCosemFormatter.getObisLabelAndUnit(obis)
                    registers[obis] = RegisterReading(
                        parameterName = registerField?.label ?: descriptor.label.ifBlank { "OBIS $obis" },
                        obisCode = obis,
                        reading = formatDouble(cleanText(rawValue).toDouble()),
                        unit = registerField?.unit ?: descriptor.unit
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
        BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
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
