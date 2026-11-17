package com.utility.dlmsreader.dlms

import java.io.InputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/**
 * Parser for tagged DLMS / COSEM (IEC 62056) CSV profiles.
 *
 * Extracted meter files often contain one tagged section per object, for
 * example:
 *
 * ```
 * Instant data OBIS
 * 01001F0700FF,0100200700FF
 * END
 * Instant data value
 * 123456,23045
 * END
 * Instant scalar value
 * -3|kWh,2|V
 * END
 * ```
 *
 * Section terminators are accepted as `,END` or a standalone `END` cell. OBIS
 * codes may be written as twelve hexadecimal characters or as dotted decimal.
 * Scalar cells use the `<exponent>|<unit>` form and are applied as
 * `rawValue * 10^exponent`; timestamp strings keep their original text.
 */
object ObisProfileCsvParser {

    private enum class SectionKind {
        NAME_PLATE,
        PROGRAMMABLE,
        INSTANT_OBIS,
        INSTANT_VALUE,
        INSTANT_SCALAR_OBIS,
        INSTANT_SCALAR_VALUE,
        BILLING_OBIS,
        BILLING_VALUE,
        BILLING_SCALAR_OBIS,
        BILLING_SCALAR_VALUE,
        DAILY_OBIS,
        DAILY_VALUE,
        DAILY_SCALAR_OBIS,
        DAILY_SCALAR_VALUE,
        BLOCK_OBIS,
        BLOCK_VALUE,
        BLOCK_SCALAR_OBIS,
        BLOCK_SCALAR_VALUE,
        EVENT_LOG
    }

    private val SECTION_ALIASES: Map<String, SectionKind> = buildMap {
        fun add(kind: SectionKind, vararg names: String) {
            names.forEach { put(normalizeHeader(it), kind) }
        }
        add(SectionKind.NAME_PLATE, "Name plate details", "Nameplate details", "Name plate", "Nameplate")
        add(
            SectionKind.PROGRAMMABLE,
            "Programmable datas",
            "Programmable data",
            "Programmable parameters",
            "Programmable parameter"
        )
        add(SectionKind.INSTANT_OBIS, "Instant data OBIS", "Instantaneous data OBIS", "Instant OBIS")
        add(
            SectionKind.INSTANT_VALUE,
            "Instant data value",
            "Instant data values",
            "Instantaneous data value",
            "Instantaneous data values",
            "Instant values"
        )
        add(SectionKind.INSTANT_SCALAR_OBIS, "Instant scalar OBIS", "Instantaneous scalar OBIS")
        add(SectionKind.INSTANT_SCALAR_VALUE, "Instant scalar value", "Instantaneous scalar value")
        add(SectionKind.BILLING_OBIS, "Billing data OBIS", "Billing OBIS")
        add(SectionKind.BILLING_VALUE, "Billing data value", "Billing data values", "Billing values")
        add(SectionKind.BILLING_SCALAR_OBIS, "Billing scalar OBIS")
        add(SectionKind.BILLING_SCALAR_VALUE, "Billing scalar value")
        add(SectionKind.DAILY_OBIS, "Daily load data OBIS", "Daily load OBIS")
        add(SectionKind.DAILY_VALUE, "Daily load data value", "Daily load data values", "Daily load values")
        add(SectionKind.DAILY_SCALAR_OBIS, "Daily load scalar OBIS")
        add(SectionKind.DAILY_SCALAR_VALUE, "Daily load scalar value")
        add(SectionKind.BLOCK_OBIS, "Block load data OBIS", "Block load OBIS")
        add(SectionKind.BLOCK_VALUE, "Block load data value", "Block load data values", "Block load values")
        add(SectionKind.BLOCK_SCALAR_OBIS, "Block load scalar OBIS")
        add(SectionKind.BLOCK_SCALAR_VALUE, "Block load scalar value")
        add(
            SectionKind.EVENT_LOG,
            "Event logs",
            "Event log",
            "Tamper logs",
            "Tamper log",
            "Event data",
            "Tamper data",
            "Event data OBIS",
            "Event data value"
        )
    }

    private val SECTION_NAMES: Map<SectionKind, List<String>> =
        SECTION_ALIASES.entries.groupBy({ it.value }, { it.key })

    /** Continuous token stream plus line-preserving rows for one section. */
    private class SectionData {
        val tokens: MutableList<String> = mutableListOf()
        val rows: MutableList<List<String>> = mutableListOf()
    }

    private data class SectionHeader(
        val original: String,
        val lookup: String,
        val index: Int
    )

    private val NUMERIC = Regex("""^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?$""")
    private val TIMESTAMP = Regex(
        """^\d{1,4}(?:[-/.]\d{1,2}|[-/.]?[A-Za-z]{3})(?:[-/.]\d{1,4})?(?:[ T]\d{1,2}:\d{2}(?::\d{2})?)?$""",
        RegexOption.IGNORE_CASE
    )
    private val DOTTED_OBIS = Regex("""^\d{1,3}(?:\.\d{1,3}){5}$""")
    private val HEX_OBIS = Regex("""^(?:[0-9A-Fa-f]{2}){6}$""")

    /**
     * Logical device names such as `LNTB2XXXXXXXC5`, `AMEERIS106_10`,
     * `GOESPHWCN-S`, and `LGZ-LGC110E150`. Vendors use underscores and hyphens
     * inside the LDN, so separators are accepted as long as the value starts
     * with letters and is at least eight characters long.
     */
    private val POSITIONAL_LDN = Regex("""^(?=.{8,}$)[A-Za-z]{2,}[A-Za-z0-9._-]*$""")

    /** `28/12/2023 23:25:47`-style meter clock written without a label. */
    private val PROGRAMMABLE_TIMESTAMP = Regex(
        """^(\d{1,2})[/.-](\d{1,2})[/.-](\d{4})\s+\d{1,2}:\d{2}(?::\d{2})?$"""
    )

    private const val POSITIONAL_NAMEPLATE_MIN_CELLS = 10

    /**
     * Columns of the unlabelled L&T nameplate record, in file order:
     * LDN, serial, manufacturer, firmware, …, current rating, year.
     */
    private val POSITIONAL_NAMEPLATE_FIELDS = listOf(
        0 to "0.0.42.0.0.255",
        1 to "0.0.96.1.0.255",
        2 to "0.0.96.1.1.255",
        3 to "1.0.0.2.0.255",
        8 to "0.0.94.91.12.255",
        9 to "0.0.96.1.4.255"
    )

    private val UNIT_SYMBOLS = setOf(
        "kWh", "kVAh", "kVArh", "kW", "kVA", "kVAr", "Wh", "VAh", "VArh",
        "V", "A", "Hz", "%", "PF", "deg", "min", "s", "Wh", "var"
    )

    /** Header labels that map key/value nameplate rows onto standard OBIS codes. */
    private val LABEL_OBIS: Map<String, String> = mapOf(
        "locicaldevicename" to "0.0.42.0.0.255",
        "logicaldevicename" to "0.0.42.0.0.255",
        "ldn" to "0.0.42.0.0.255",
        "meterserialnumber" to "0.0.96.1.0.255",
        "serialnumber" to "0.0.96.1.0.255",
        "serialno" to "0.0.96.1.0.255",
        "srno" to "0.0.96.1.0.255",
        "metersrno" to "0.0.96.1.0.255",
        "manufacturername" to "0.0.96.1.1.255",
        "manufacturer" to "0.0.96.1.1.255",
        "make" to "0.0.96.1.1.255",
        "model" to "0.0.96.1.2.255",
        "metermodel" to "0.0.96.1.2.255",
        "yearofmanufacture" to "0.0.96.1.4.255",
        "firmwareversion" to "1.0.0.2.0.255",
        "firmware" to "1.0.0.2.0.255",
        "metertype" to "0.0.94.91.9.255",
        "metercategory" to "0.0.94.91.11.255",
        "category" to "0.0.94.91.11.255",
        "currentrating" to "0.0.94.91.12.255",
        "ratedcurrent" to "0.0.94.91.12.255",
        "phase" to "0.0.94.91.13.255",
        "phasetype" to "0.0.94.91.13.255",
        "realtimeclock" to "0.0.1.0.0.255",
        "meterrtc" to "0.0.1.0.0.255",
        "dateandtime" to "0.0.1.0.0.255"
    )

    /** Returns the catalogue label for a canonical OBIS code. */
    private fun descriptor(obis: String): ObisDescriptor =
        DlmsCosemFormatter.getObisLabelAndUnit(obis)

    fun looksLikeTaggedCsv(text: String): Boolean {
        if (!text.contains(",END", ignoreCase = true) &&
            !text.contains("\nEND", ignoreCase = true)
        ) {
            return false
        }
        val probe = text.take(131_072)
        return SECTION_ALIASES.keys.any { probe.contains(it, ignoreCase = true) }
    }

    fun parse(input: InputStream): MeterDataReport? =
        parse(input.readBytes().toString(Charsets.UTF_8))

    fun parse(bytes: ByteArray): MeterDataReport? =
        parse(bytes.toString(Charsets.UTF_8))

    fun parse(text: String): MeterDataReport? {
        if (text.isBlank()) return null
        val sections = readSections(text)
        if (sections.isEmpty()) return null

        val rawNameplateRows = sections["name plate details"]?.rows
            ?: sections["nameplate details"]?.rows
            ?: rowsOf(sections, SectionKind.NAME_PLATE)
        val nameplateRows = parseNameplateRows(rawNameplateRows)
        val programmableRows = parseProgrammableRows(rowsOf(sections, SectionKind.PROGRAMMABLE))
        val identityRows = mergeRows(nameplateRows + programmableRows)

        val instantaneous = buildProfile(
            title = "Instantaneous Parameters Snapshot",
            profileObis = DlmsCosemCatalog.OBIS_INSTANTANEOUS_PROFILE,
            obisTokens = tokensOf(sections, SectionKind.INSTANT_OBIS),
            valueTokens = tokensOf(sections, SectionKind.INSTANT_VALUE),
            scalarObisTokens = tokensOf(sections, SectionKind.INSTANT_SCALAR_OBIS),
            scalarValueTokens = tokensOf(sections, SectionKind.INSTANT_SCALAR_VALUE)
        )
        val billing = buildProfile(
            title = "Billing History Profile",
            profileObis = DlmsCosemCatalog.OBIS_BILLING_PROFILE,
            obisTokens = tokensOf(sections, SectionKind.BILLING_OBIS),
            valueTokens = tokensOf(sections, SectionKind.BILLING_VALUE),
            scalarObisTokens = tokensOf(sections, SectionKind.BILLING_SCALAR_OBIS),
            scalarValueTokens = tokensOf(sections, SectionKind.BILLING_SCALAR_VALUE)
        )
        val dailyLoad = buildProfile(
            title = "Daily Load Survey Profile",
            profileObis = DlmsCosemCatalog.OBIS_DAILY_LOAD_PROFILE,
            obisTokens = tokensOf(sections, SectionKind.DAILY_OBIS),
            valueTokens = tokensOf(sections, SectionKind.DAILY_VALUE),
            scalarObisTokens = tokensOf(sections, SectionKind.DAILY_SCALAR_OBIS),
            scalarValueTokens = tokensOf(sections, SectionKind.DAILY_SCALAR_VALUE)
        )
        val blockLoad = buildProfile(
            title = "Block Load Survey Profile",
            profileObis = DlmsCosemCatalog.OBIS_BLOCK_LOAD_PROFILE,
            obisTokens = tokensOf(sections, SectionKind.BLOCK_OBIS),
            valueTokens = tokensOf(sections, SectionKind.BLOCK_VALUE),
            scalarObisTokens = tokensOf(sections, SectionKind.BLOCK_SCALAR_OBIS),
            scalarValueTokens = tokensOf(sections, SectionKind.BLOCK_SCALAR_VALUE)
        )

        val cumulativeEnergies = registersFrom(instantaneous)
            .ifEmpty { registersFrom(billing) }
            .distinctBy { it.obisCode }

        val eventLogs = parseEventLogs(rowsOf(sections, SectionKind.EVENT_LOG))
        val nameplateProfile = nameplateRows.takeIf { it.isNotEmpty() }?.let { rows ->
            ProfileSection(
                title = "Nameplate Profile",
                profileObis = DlmsCosemCatalog.OBIS_NAMEPLATE_PROFILE,
                columns = rows.map {
                    ProfileColumn(it.parameterName, it.obisCode, it.unit)
                },
                rows = listOf(rows.map { it.formattedValue })
            )
        }

        val report = MeterDataReport(
            identity = if (identityRows.isEmpty() && nameplateProfile == null) {
                null
            } else {
                IdentitySection(identityRows, nameplateProfile)
            },
            cumulativeEnergies = cumulativeEnergies,
            instantaneous = instantaneous,
            billing = billing,
            blockLoad = blockLoad,
            dailyLoad = dailyLoad,
            tamperEvents = eventLogs,
            sourceLabel = MeterDataReport.SOURCE_IMPORTED
        )
        return report.takeIf { it.hasData || it.cumulativeEnergies.isNotEmpty() }
    }

    // ------------------------------------------------------------- tokenising

    /**
     * Reads tagged sections as a continuous token stream.
     *
     * Line breaks are deliberately not profile row boundaries: long profile
     * pages are stitched back together and only an explicit `END` token closes
     * the current section. A parallel line-preserving row list is kept for
     * nameplate and event sections, where one record may span a physical line.
     */
    private fun readSections(text: String): Map<String, SectionData> {
        val sections = linkedMapOf<String, SectionData>()
        var current: SectionData? = null
        val pending = mutableListOf<String>()
        val lineRow = mutableListOf<String>()

        fun commitLine() {
            val target = current
            if (target != null && lineRow.any { it.isNotBlank() }) {
                target.rows += lineRow.toList()
            }
            lineRow.clear()
        }

        fun commitSection() {
            val target = current
            if (target != null && pending.any { it.isNotBlank() }) {
                target.tokens += pending.toList()
            }
            pending.clear()
            commitLine()
        }

        fun append(cells: List<String>) {
            val data = cells.dropLastWhile { it.isBlank() }
            if (data.isEmpty()) return
            pending += data
            lineRow += data
        }

        text.removePrefix("\uFEFF")
            .lineSequence()
            .forEach { rawLine ->
                val cells = parseCsvLine(rawLine)
                if (cells.isEmpty() || cells.all { it.isBlank() }) return@forEach

                val header = sectionHeaderFor(cells)
                if (header != null) {
                    commitSection()
                    // Reuse the existing buffer for mixed-case variants of the
                    // same header, then expose both the exported spelling and
                    // a lowercase lookup alias.
                    val target = sections[header.lookup] ?: SectionData()
                    sections[header.original] = target
                    sections[header.lookup] = target
                    current = target
                    val remainder = cells.drop(header.index + 1)
                    val end = remainder.indexOfFirst(::isEnd)
                    append(if (end >= 0) remainder.take(end) else remainder)
                    if (end >= 0) {
                        commitSection()
                        current = null
                    } else {
                        commitLine()
                    }
                    return@forEach
                }

                if (current == null) return@forEach
                val end = cells.indexOfFirst(::isEnd)
                append(if (end >= 0) cells.take(end) else cells)
                if (end >= 0) {
                    commitSection()
                    current = null
                } else {
                    commitLine()
                }
            }
        commitSection()
        return sections
    }

    /**
     * Locates a section header while keeping the exported spelling. The
     * lowercase form remains available for alias lookups.
     */
    private fun sectionHeaderFor(cells: List<String>): SectionHeader? {
        val firstLookup = normalizeHeader(cells.first())
        if (SECTION_ALIASES.containsKey(firstLookup)) {
            return SectionHeader(
                original = originalHeader(cells.first()),
                lookup = firstLookup,
                index = 0
            )
        }
        // A few exporters prefix the section name with a row number.
        if (cells.size > 1 && cells.first().trim().toIntOrNull() != null) {
            val secondLookup = normalizeHeader(cells[1])
            if (SECTION_ALIASES.containsKey(secondLookup)) {
                return SectionHeader(
                    original = originalHeader(cells[1]),
                    lookup = secondLookup,
                    index = 1
                )
            }
        }
        return null
    }

    /** Header text with the exported casing retained. */
    private fun originalHeader(text: String): String =
        text.trim().trim('"').trimEnd(':', ';', ',').removePrefix("\uFEFF")
            .replace(Regex("""\s+"""), " ")

    private fun rowsOf(
        sections: Map<String, SectionData>,
        kind: SectionKind
    ): List<List<String>> =
        SECTION_NAMES[kind].orEmpty().flatMap { sections[it]?.rows.orEmpty() }

    private fun tokensOf(
        sections: Map<String, SectionData>,
        kind: SectionKind
    ): List<String> =
        SECTION_NAMES[kind].orEmpty().flatMap { sections[it]?.tokens.orEmpty() }

    private fun isEnd(cell: String): Boolean =
        cell.trim().trim('"').equals("END", ignoreCase = true)

    private fun normalizeHeader(text: String): String =
        text.trim().trim('"').trimEnd(':', ';', ',').removePrefix("\uFEFF")
            .lowercase(Locale.US)
            .replace(Regex("""\s+"""), " ")

    /** A minimal RFC 4180-style splitter: quoted commas and doubled quotes. */
    internal fun parseCsvLine(line: String): List<String> {
        val cells = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val ch = line[index]
            when {
                ch == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
                    cell.append('"')
                    index++
                }
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> {
                    cells += cell.toString().trim()
                    cell.setLength(0)
                }
                else -> cell.append(ch)
            }
            index++
        }
        cells += cell.toString().trim()
        return cells
    }

    // --------------------------------------------------------------- profiles

    private data class ScalarValue(val exponent: Int, val unit: String)

    private fun buildProfile(
        title: String,
        profileObis: String,
        obisTokens: List<String>,
        valueTokens: List<String>,
        scalarObisTokens: List<String>,
        scalarValueTokens: List<String>
    ): ProfileSection? {
        val obisList = parseObisTokens(obisTokens)
        val values = valueTokens
        if (obisList.isEmpty() || values.isEmpty()) return null

        val scalarObisList = parseObisTokens(scalarObisTokens)
        val scalars = scalarValueTokens.map(::parseScalar)
        val scalarByObis = scalarObisList.zip(scalars)
            .mapNotNull { (obis, scalar) -> scalar?.let { obis to it } }
            .toMap()

        val columns = obisList.mapIndexed { index, obis ->
            val descriptor = descriptor(obis)
            val scalar = scalarByObis[obis] ?: scalars.getOrNull(index)
            ProfileColumn(
                parameterName = descriptor.label.ifBlank { "OBIS $obis" },
                obisCode = obis,
                unit = scalar?.unit?.takeIf { it.isNotBlank() } ?: descriptor.unit,
                attributeIndex = if (isTimestampObis(obis)) 5 else 2
            )
        }

        val rows = parseMultiRowProfile(values, obisList, columns, scalars, scalarByObis)

        return ProfileSection(
            title = title,
            profileObis = profileObis,
            columns = columns,
            rows = rows,
            totalRows = rows.size
        )
    }

    /**
     * Rebuilds profile rows from the continuous token stream. A short trailing
     * chunk is usually a comma artefact; dropping it keeps columns aligned.
     */
    private fun parseMultiRowProfile(
        values: List<String>,
        obisList: List<String>,
        columns: List<ProfileColumn>,
        scalars: List<ScalarValue?>,
        scalarByObis: Map<String, ScalarValue>
    ): List<List<String>> =
        values.chunked(obisList.size)
            .filter { it.size == obisList.size }
            .map { chunk ->
                columns.indices.map { index ->
                    val raw = chunk[index].trim()
                    val scalar = scalarByObis[columns[index].obisCode]
                        ?: scalars.getOrNull(index)
                    applyScalar(raw, scalar)
                }
            }

    /** Converts a 12-character hex or dotted decimal OBIS cell to canonical form. */
    fun normalizeObis(value: String): String? {
        val cleaned = value.trim().trim('"').replace(" ", "").replace("-", "").replace(":", ".")
        if (DOTTED_OBIS.matches(cleaned)) {
            return cleaned.split('.').joinToString(".") { part ->
                part.toIntOrNull()?.toString() ?: part
            }
        }
        val hex = cleaned.removePrefix("0x")
        if (HEX_OBIS.matches(hex)) {
            return hex.chunked(2).joinToString(".") { byte ->
                byte.toInt(16).toString()
            }
        }
        // Some files place several codes in one cell, separated by spaces or
        // semicolons. Take the first complete code.
        val match = Regex("""(?:[0-9A-Fa-f]{2}){6}""").find(cleaned)
        if (match != null) {
            return match.value.chunked(2).joinToString(".") { it.toInt(16).toString() }
        }
        return null
    }

    private fun parseObisTokens(tokens: List<String>): List<String> =
        tokens.flatMap { cell ->
            cell.split(';', '|', ' ', '\t').ifEmpty { listOf(cell) }
        }.mapNotNull(::normalizeObis)

    private fun parseScalar(cell: String): ScalarValue? {
        val trimmed = cell.trim().trim('"')
        if (trimmed.isEmpty()) return null
        val exponentPart = trimmed.substringBefore('|').trim()
        val unit = trimmed.substringAfter('|', "").trim()
        val exponent = exponentPart.toIntOrNull() ?: return null
        return ScalarValue(exponent, unit)
    }

    private fun applyScalar(raw: String, scalar: ScalarValue?): String {
        if (raw.isBlank()) return ""
        if (TIMESTAMP.matches(raw)) return raw
        // DLMS uses all-ones integers for null/error registers.
        if (raw == "65535" || raw == "4294967295") return "-"
        if (scalar == null) return raw
        if (scalar.exponent == 0) return raw
        if (!NUMERIC.matches(raw)) return raw
        val numeric = raw.toDoubleOrNull() ?: return raw
        if (numeric == 0.0) return "0"
        return runCatching {
            val scaled = BigDecimal(raw).scaleByPowerOfTen(scalar.exponent)
            val plain = scaled.stripTrailingZeros().toPlainString()
            val clamped = if (plain.contains('.') &&
                plain.substringAfter('.').length > 4
            ) {
                scaled.setScale(3, RoundingMode.HALF_UP)
            } else {
                scaled
            }
            clamped.stripTrailingZeros().toPlainString()
        }.getOrDefault(raw)
    }

    private fun isTimestampObis(obis: String): Boolean =
        obis == "0.0.1.0.0.255" || obis == "0.0.0.1.2.255" ||
            obis.endsWith(".0.255") && obis.startsWith("0.0.1.")

    // -------------------------------------------------------------- nameplate

    /**
     * Parses the nameplate section, accepting both labelled rows and the
     * unlabelled positional record written by L&T `.OUT` exports.
     *
     * Labelled rows keep priority; the positional record only contributes the
     * fields that would otherwise be lost.
     */
    private fun parseNameplateRows(rows: List<List<String>>): List<ParameterRow> {
        val labelled = parseParameterRows(rows)
        val positional = positionalNameplateRows(rows)
        if (positional.isEmpty()) return labelled
        // parseParameterRows read the positional record's first value as a
        // label, so only its resolved OBIS rows are worth keeping.
        return mergeRows(positional + labelled.filter { it.obisCode.isNotBlank() })
    }

    /**
     * Reads the L&T positional nameplate record:
     *
     * ```
     * Name plate details,<LDN>,<serial>,<manufacturer>,<firmware>,5,0,0,C3,
     * <current rating>,<year>,0,END
     * ```
     *
     * The first cell is the LDN rather than a label, which is why the generic
     * row parser cannot resolve any identity OBIS codes for this layout.
     */
    private fun positionalNameplateRows(rows: List<List<String>>): List<ParameterRow> {
        val record = rows.firstOrNull(::isPositionalNameplate)?.map { it.trim() }
            ?: return emptyList()
        return POSITIONAL_NAMEPLATE_FIELDS.mapNotNull { (index, obis) ->
            val value = record.getOrNull(index).orEmpty()
            if (value.isEmpty()) return@mapNotNull null
            val descriptor = descriptor(obis)
            parameterRow(
                label = descriptor.label,
                obis = obis,
                value = value,
                unit = descriptor.unit
            )
        }
    }

    private fun isPositionalNameplate(row: List<String>): Boolean {
        val cells = row.map { it.trim() }
        return cells.size >= POSITIONAL_NAMEPLATE_MIN_CELLS &&
            cells.none { normalizeObis(it) != null } &&
            POSITIONAL_LDN.matches(cells[0]) &&
            cells[2].any(Char::isLetter)
    }

    /**
     * Parses programmable parameters, mapping the unlabelled meter clock that
     * L&T exports write in the first cell (`28/12/2023 23:25:47,1800,1800`).
     */
    private fun parseProgrammableRows(rows: List<List<String>>): List<ParameterRow> {
        val labelled = parseParameterRows(rows)
        val timestamp = rows.firstOrNull()?.firstOrNull()?.trim().orEmpty()
        val clock = programmableClockRow(timestamp) ?: return labelled
        return mergeRows(
            listOf(clock) + labelled.filterNot { it.parameterName == timestamp }
        )
    }

    private fun programmableClockRow(timestamp: String): ParameterRow? {
        val match = PROGRAMMABLE_TIMESTAMP.matchEntire(timestamp) ?: return null
        val (day, month, year) = match.destructured
        val dayNumber = day.toIntOrNull() ?: return null
        val monthNumber = month.toIntOrNull() ?: return null
        val yearNumber = year.toIntOrNull() ?: return null
        // All-zero placeholders mean the clock was never set.
        if (dayNumber !in 1..31 || monthNumber !in 1..12 || yearNumber <= 0) {
            return null
        }
        val descriptor = descriptor("0.0.1.0.0.255")
        return parameterRow(
            label = descriptor.label,
            obis = "0.0.1.0.0.255",
            value = timestamp,
            unit = descriptor.unit
        )
    }

    private fun parseParameterRows(rows: List<List<String>>): List<ParameterRow> {
        val parsed = mutableListOf<ParameterRow>()
        rows.forEach { rawRow ->
            val cells = rawRow.map { it.trim() }.filter { it.isNotBlank() }
            if (cells.isEmpty() || isHeaderRow(cells)) return@forEach

            // Support a single "Label=Value" or "Label: Value" cell.
            if (cells.size == 1 && (cells[0].contains('=') || cells[0].contains(':'))) {
                val separator = if (cells[0].contains('=')) '=' else ':'
                val label = cells[0].substringBefore(separator).trim()
                val value = cells[0].substringAfter(separator).trim()
                if (label.isNotBlank() && value.isNotBlank()) {
                    parsed += parameterRow(label = label, obis = null, value = value, unit = "")
                }
                return@forEach
            }

            val obisIndex = cells.indexOfFirst { normalizeObis(it) != null }
            val obis = if (obisIndex >= 0) normalizeObis(cells[obisIndex]) else null
            val remaining = if (obisIndex >= 0) {
                cells.filterIndexed { index, _ -> index != obisIndex }
            } else {
                cells
            }
            if (remaining.isEmpty()) return@forEach

            val unit = remaining.lastOrNull()
                ?.trim()
                ?.trimEnd('.')
                ?.takeIf { it in UNIT_SYMBOLS && remaining.size >= 3 }
                .orEmpty()
            val withoutUnit = if (unit.isNotEmpty()) remaining.dropLast(1) else remaining
            if (withoutUnit.isEmpty()) return@forEach

            val label: String
            val value: String
            if (obis != null) {
                if (withoutUnit.size == 1) {
                    label = descriptor(obis).label.ifBlank { "OBIS $obis" }
                    value = withoutUnit[0]
                } else {
                    label = withoutUnit.first()
                    value = withoutUnit.drop(1).joinToString(", ").ifBlank { withoutUnit.first() }
                }
            } else {
                label = withoutUnit.first()
                value = withoutUnit.drop(1).joinToString(", ").ifBlank { "N/A" }
            }
            parsed += parameterRow(label, obis, value, unit)
        }
        return mergeRows(parsed)
    }

    private fun parameterRow(
        label: String,
        obis: String?,
        value: String,
        unit: String
    ): ParameterRow {
        val resolvedObis = obis ?: LABEL_OBIS[normalizeHeader(label).replace(Regex("[^a-z0-9]"), "")]
        val resolvedLabel = when {
            label.isBlank() -> resolvedObis?.let { descriptor(it).label }.orEmpty()
            else -> label
        }.ifBlank { resolvedObis?.let { descriptor(it).label } ?: "Parameter" }
        return ParameterRow(
            parameterName = resolvedLabel,
            obisCode = resolvedObis.orEmpty(),
            formattedValue = value,
            unit = unit.ifBlank { resolvedObis?.let { descriptor(it).unit }.orEmpty() }
        )
    }

    private fun mergeRows(rows: List<ParameterRow>): List<ParameterRow> {
        val seen = mutableSetOf<String>()
        return rows.filter { row ->
            val key = row.obisCode.ifBlank { row.parameterName.lowercase(Locale.US) }
            seen.add(key)
        }
    }

    private fun isHeaderRow(cells: List<String>): Boolean {
        val first = normalizeHeader(cells.first()).replace(Regex("[^a-z]"), "")
        val second = cells.getOrNull(1)?.let { normalizeHeader(it).replace(Regex("[^a-z]"), "") }.orEmpty()
        val firstLooksLikeHeader = first in setOf("parameter", "name", "field", "particulars", "description")
        val secondLooksLikeHeader = second in setOf("value", "values", "details", "data", "reading")
        return firstLooksLikeHeader && (secondLooksLikeHeader || cells.size == 1)
    }

    // ------------------------------------------------------------ cumulative

    private fun registersFrom(section: ProfileSection?): List<RegisterReading> {
        val section = section ?: return emptyList()
        val firstRow = section.rows.firstOrNull() ?: return emptyList()
        return section.columns.mapIndexedNotNull { index, column ->
            val metadata = DlmsCosemCatalog.metadataFor(column.obisCode)
            val unit = column.unit.ifBlank { metadata?.unit.orEmpty() }
            val isEnergy = metadata?.category == ObisCategory.BILLING ||
                unit in setOf("kWh", "kVAh", "kVArh", "kW", "kVA", "kVAr")
            if (!isEnergy) return@mapIndexedNotNull null
            RegisterReading(
                parameterName = column.parameterName,
                obisCode = column.obisCode,
                reading = firstRow.getOrNull(index).orEmpty(),
                unit = unit
            )
        }
    }

    // ------------------------------------------------------------- event logs

    private fun parseEventLogs(rows: List<List<String>>): List<TamperEventRow> {
        if (rows.isEmpty()) return emptyList()
        val headerIndex = rows.indexOfFirst { row -> isEventHeader(row) }
        val header = if (headerIndex >= 0) rows[headerIndex] else null
        val start = if (headerIndex >= 0) headerIndex + 1 else 0
        val columns = header?.let(::eventColumnMap)
        return rows.drop(start).mapNotNull { row ->
            val values = row.map { it.trim() }
            if (values.all { it.isBlank() }) return@mapNotNull null
            if (columns != null) {
                fun value(vararg names: String): String {
                    val index = names.firstNotNullOfOrNull { name -> columns[name] }
                    return index?.let { values.getOrNull(it) }.orEmpty()
                }
                val code = value("code", "eventcode")
                TamperEventRow(
                    compartment = "Event Log",
                    dateTime = value("datetime", "date", "time").ifBlank { "N/A" },
                    eventCode = code.ifBlank { "N/A" },
                    eventDescription = value("description", "eventdescription")
                        .ifBlank { code.toIntOrNull()?.let(DlmsCosemFormatter::decodeTamperEvent) ?: "N/A" },
                    voltage = value("voltage", "v").ifBlank { "N/A" },
                    current = value("current", "i").ifBlank { "N/A" },
                    powerFactor = value("powerfactor", "pf").ifBlank { "N/A" },
                    energy = value("energy", "kwh").ifBlank { "N/A" },
                    kvah = value("kvah").ifBlank { "N/A" },
                    neutralCurrent = value("neutralcurrent", "neutrali").ifBlank { "N/A" }
                )
            } else {
                val dateTime = values.getOrNull(0).orEmpty().ifBlank { "N/A" }
                val code = values.getOrNull(1).orEmpty().ifBlank { "N/A" }
                TamperEventRow(
                    compartment = "Event Log",
                    dateTime = dateTime,
                    eventCode = code,
                    eventDescription = code.toIntOrNull()
                        ?.let(DlmsCosemFormatter::decodeTamperEvent)
                        ?: "N/A",
                    voltage = values.getOrNull(3).orEmpty().ifBlank { "N/A" },
                    current = values.getOrNull(4).orEmpty().ifBlank { "N/A" },
                    powerFactor = values.getOrNull(5).orEmpty().ifBlank { "N/A" },
                    energy = values.getOrNull(6).orEmpty().ifBlank { "N/A" },
                    kvah = values.getOrNull(7).orEmpty().ifBlank { "N/A" },
                    neutralCurrent = values.getOrNull(8).orEmpty().ifBlank { "N/A" }
                )
            }
        }
    }

    private fun isEventHeader(row: List<String>): Boolean {
        val normalized = row.map { normalizeHeader(it).replace(Regex("[^a-z]"), "") }
        val hits = normalized.count {
            it in setOf("datetime", "date", "time", "code", "eventcode", "description", "voltage", "current", "pf")
        }
        return hits >= 2
    }

    private fun eventColumnMap(row: List<String>): Map<String, Int> =
        row.mapIndexedNotNull { index, cell ->
            val key = normalizeHeader(cell).replace(Regex("[^a-z]"), "")
            key.takeIf { it.isNotBlank() }?.let { it to index }
        }.toMap()
}
