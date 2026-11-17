package com.utility.dlmsreader.dlms

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pure ordering and display-precision helpers shared by the PDF exporter.
 *
 * Keeping these functions out of [MeterLinkPdfExporter] makes the billing
 * order and phase grouping testable without an Android runtime.
 */
internal object PdfReportOrdering {

    const val BILLING_DATE_OBIS = "0.0.0.1.2.255"

    private val PDF_NUMERIC = Regex("""^[+-]?\d[\d,]*(?:\.\d+)?$""")

    /** Common billing-date spellings. */
    private val BILL_DATE_PATTERNS = listOf(
        "dd-MM-yyyy HH:mm:ss.SSS",
        "dd-MM-yyyy HH:mm:ss",
        "dd-MM-yyyy HH:mm",
        "dd-MM-yy HH:mm:ss.SSS",
        "dd-MM-yy HH:mm:ss",
        "dd-MM-yy HH:mm",
        "dd-MMM-yyyy HH:mm:ss",
        "dd-MMM-yyyy HH:mm",
        "dd-MMM-yy HH:mm:ss",
        "dd-MMM-yy HH:mm",
        "yyyy-MM-dd HH:mm:ss.SSS",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm",
        "dd-MM-yyyy",
        "dd-MM-yy",
        "dd-MMM-yyyy",
        "dd-MMM-yy",
        "yyyy-MM-dd"
    )

    private data class BillingRow(
        val originalIndex: Int,
        val values: List<String>,
        val date: Date?
    )

    /**
     * Newest bill first. The date is read from `0.0.0.1.2.255` when present,
     * otherwise from the first date/time column. Rows with an unparseable
     * date stay at the bottom in their original order.
     */
    fun sortBillingRowsDescending(section: ProfileSection): List<List<String>> {
        if (section.rows.size < 2 || section.columns.isEmpty()) return section.rows
        val dateIndex = billingDateIndex(section)
        val parsed = section.rows.mapIndexed { index, row ->
            BillingRow(
                originalIndex = index,
                values = row,
                date = parseBillingDate(row.getOrNull(dateIndex).orEmpty())
            )
        }
        return parsed.sortedWith(
            compareByDescending<BillingRow> { it.date?.time ?: Long.MIN_VALUE }
                .thenBy { it.originalIndex }
        ).map { it.values }
    }

    /**
     * Keeps the timestamp first, then groups voltage, current and power-factor
     * rows so the phase quantities read as one block instead of following the
     * meter's capture-object order.
     */
    fun groupPhaseColumns(
        columns: List<IndexedValue<ProfileColumn>>
    ): List<IndexedValue<ProfileColumn>> = columns.sortedWith(
        compareBy({ phaseRank(it.value) }, { it.index })
    )

    /**
     * Keeps every numeric table cell within the project's 3-decimal limit.
     * Non-numeric text and timestamps are returned unchanged.
     */
    fun clampNumericText(value: String): String {
        val text = value.trim()
        if (text.isEmpty() || !PDF_NUMERIC.matches(text)) return value
        val fraction = text.substringAfter('.', "")
        if (fraction.length <= 3) return value
        return runCatching {
            val negative = text.startsWith('-')
            val body = text.removePrefix("-").removePrefix("+").replace(",", "")
            val rounded = BigDecimal(body).setScale(3, RoundingMode.HALF_UP)
            if (text.contains(',')) {
                val symbols = DecimalFormatSymbols.getInstance(Locale.ENGLISH)
                val formatter = DecimalFormat("#,##0.###", symbols)
                (if (negative) "-" else "") + formatter.format(rounded.abs())
            } else {
                (if (negative) "-" else "") +
                    rounded.abs().stripTrailingZeros().toPlainString()
            }
        }.getOrDefault(value)
    }

    private fun billingDateIndex(section: ProfileSection): Int {
        section.columns.indexOfFirst { it.obisCode == BILLING_DATE_OBIS }
            .takeIf { it >= 0 }
            ?.let { return it }
        return section.columns.indexOfFirst { column ->
            column.attributeIndex == 5 ||
                column.parameterName.contains("date", ignoreCase = true) ||
                column.parameterName.contains("time", ignoreCase = true)
        }.takeIf { it >= 0 } ?: 0
    }

    private fun phaseRank(column: ProfileColumn): Int {
        val name = column.parameterName.lowercase(Locale.ENGLISH)
        val unit = column.unit.lowercase(Locale.ENGLISH)
        return when {
            column.attributeIndex == 5 || column.obisCode == "0.0.1.0.0.255" -> 0
            unit == "v" || name.contains("voltage") -> 10
            unit == "a" || name.contains("current") -> 20
            unit == "pf" || name.contains("power factor") -> 30
            else -> 40
        }
    }

    private fun parseBillingDate(raw: String): Date? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        // Normalise date separators only; keep the time (and any fractional
        // seconds after the dot) untouched.
        val normalized = text.replace('T', ' ').let { value ->
            val split = value.indexOf(' ')
            if (split < 0) {
                value.replace('/', '-').replace('.', '-')
            } else {
                value.substring(0, split).replace('/', '-').replace('.', '-') +
                    value.substring(split)
            }
        }
        BILL_DATE_PATTERNS.forEach { pattern ->
            val formatter = SimpleDateFormat(pattern, Locale.ENGLISH).apply {
                isLenient = false
            }
            val position = ParsePosition(0)
            val date = formatter.parse(normalized, position)
            if (date != null && position.index == normalized.length) return date
        }
        return null
    }
}
