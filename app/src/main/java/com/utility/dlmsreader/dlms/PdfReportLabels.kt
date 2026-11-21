package com.utility.dlmsreader.dlms

import java.util.Locale

/**
 * PDF-only presentation labels for register and profile columns.
 *
 * Vendor imports can carry capture objects that have no entry in
 * [DlmsCosemCatalog]. The importers keep those columns addressable by falling
 * back to a name such as `OBIS 1.0.2.6.2.255`, but that protocol address means
 * nothing to somebody reading the report. Every label is therefore resolved
 * here before it reaches a PDF table; the stored report data is left unchanged.
 */
internal object PdfReportLabels {

    /** A full address wrapped in the importer's `OBIS ...` fallback. */
    private val OBIS_TEXT = Regex("""(?i)\bOBIS\s*\d{1,3}(?:\.\d{1,3}){4,5}\b""")

    /** A bare six-part IEC 62056 address. */
    private val RAW_OBIS = Regex("""(?<![\d.])\d{1,3}(?:\.\d{1,3}){5}(?![\d.])""")

    private val OBIS_PREFIX = Regex("""(?i)^OBIS\s*""")
    private val MD_ACTIVE_EXPORT = Regex("""^1\.0\.2\.6\.([1-8])\.255$""")
    private val MD_APPARENT_EXPORT = Regex("""^1\.0\.10\.6\.([1-8])\.255$""")

    /**
     * Per-phase active power. L&T-style meters expose R/Y/B power at the
     * current objects +5 (31/51/71 A, 36/56/76 kW).
     */
    private val PHASE_POWER = mapOf(
        "1.0.36.7.0.255" to "R-Phase Active Power",
        "1.0.56.7.0.255" to "Y-Phase Active Power",
        "1.0.76.7.0.255" to "B-Phase Active Power"
    )

    /** Vendor registers whose meaning is known but that are not in the catalogue. */
    private val EXPLICIT_LABELS = mapOf(
        "1.0.2.2.0.255" to "Cum. MD Active Export (kW)",
        "1.0.10.2.0.255" to "Cum. MD Apparent Export (kVA)",
        "1.0.128.8.62.255" to "Cum. MD Apparent Export (kVA)",
        "1.0.128.7.26.255" to "Meter Program Identifier"
    )

    /**
     * @param text the label to print.
     * @param generic true when the label was derived from the unit alone, so
     *   repeated columns can be numbered to stay distinguishable.
     */
    data class Label(val text: String, val generic: Boolean)

    /**
     * Returns the label a PDF table should show. A readable catalogue label is
     * preserved; an unmapped address becomes a quantity name derived from the
     * unit. The returned text never contains an OBIS address.
     */
    fun resolve(parameterName: String, obisCode: String, unit: String): Label {
        val name = parameterName.trim()
        if (name.isNotEmpty() && !containsObis(name)) return Label(name, false)
        val obis = obisCode.trim().ifEmpty { firstObisIn(name) }
        if (obis.isNotEmpty()) {
            catalogLabel(obis)?.let { return Label(it, false) }
            explicitLabel(obis)?.let { return Label(it, false) }
        }
        return Label(quantityLabel(unit), true)
    }

    fun containsObis(text: String): Boolean =
        OBIS_TEXT.containsMatchIn(text) || RAW_OBIS.containsMatchIn(text)

    /**
     * Final safety net for free text (diagnostic messages): every address is
     * replaced by its readable label, so no code can reach the rendered page.
     */
    fun scrub(text: String): String {
        if (!containsObis(text)) return text
        val withoutWrapped = OBIS_TEXT.replace(text) { match ->
            labelFor(OBIS_PREFIX.replace(match.value, "").trim())
        }
        return RAW_OBIS.replace(withoutWrapped) { match -> labelFor(match.value) }
    }

    /** Numbers repeated unit-derived labels so two columns never share a name. */
    class Allocator {
        private val counts = mutableMapOf<String, Int>()

        fun allocate(label: Label): String {
            if (!label.generic) return label.text
            val next = (counts[label.text] ?: 0) + 1
            counts[label.text] = next
            return if (next == 1) label.text else "${label.text} $next"
        }
    }

    fun allocate(labels: List<Label>): List<String> {
        val allocator = Allocator()
        return labels.map(allocator::allocate)
    }

    private fun labelFor(code: String): String =
        catalogLabel(code) ?: explicitLabel(code) ?: quantityLabel("")

    /** Recovers the address from a fallback name when the column has no code. */
    private fun firstObisIn(text: String): String {
        OBIS_TEXT.find(text)?.let { return OBIS_PREFIX.replace(it.value, "").trim() }
        return RAW_OBIS.find(text)?.value.orEmpty()
    }

    private fun catalogLabel(obis: String): String? =
        DlmsCosemCatalog.metadataFor(obis)?.label?.takeIf { it.isNotBlank() }

    private fun explicitLabel(obis: String): String? {
        MD_ACTIVE_EXPORT.matchEntire(obis)?.let {
            return "MD Active Export - ToD Zone ${it.groupValues[1]} (kW)"
        }
        MD_APPARENT_EXPORT.matchEntire(obis)?.let {
            return "MD Apparent Export - ToD Zone ${it.groupValues[1]} (kVA)"
        }
        PHASE_POWER[obis]?.let { return "$it (kW)" }
        return EXPLICIT_LABELS[obis]
    }

    private fun quantityLabel(unit: String): String {
        val symbol = unit.trim()
        val key = symbol.lowercase(Locale.ENGLISH)
        val base = when {
            key.startsWith("kwh") -> "Cumulative Active Energy"
            key.startsWith("kvah") -> "Cumulative Apparent Energy"
            key.startsWith("kvarh") -> "Cumulative Reactive Energy"
            key == "kw" -> "Active Power"
            key == "kva" -> "Apparent Power"
            key == "kvar" -> "Reactive Power"
            key == "v" -> "Voltage"
            key == "a" -> "Current"
            key == "pf" -> "Power Factor"
            key == "hz" -> "Frequency"
            key == "min" -> "Cumulative Duration"
            key == "%" -> "Percentage"
            else -> "Meter Reading"
        }
        return if (symbol.isEmpty()) base else "$base ($symbol)"
    }
}
