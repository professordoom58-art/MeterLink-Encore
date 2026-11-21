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
    const val CAPTURE_TIME_OBIS = "0.0.1.0.0.255"

    /** OBIS addresses of the primary timestamp capture object in a profile. */
    private val TIMESTAMP_OBIS = setOf(BILLING_DATE_OBIS, CAPTURE_TIME_OBIS)

    /** Header spellings vendors use for the primary timestamp column. */
    private val TIMESTAMP_LABELS = listOf(
        "billing date",
        "real-time clock",
        "real time clock",
        "timestamp",
        "date"
    )

    private val NEGATIVE_ZERO = Regex("""^-0(?:\.0+)?$""")

    private val PDF_NUMERIC = Regex("""^[+-]?\d[\d,]*(?:\.\d+)?$""")

    /**
     * Splits a profile table into parts of at most [maxColumnsPerPart] columns.
     *
     * Every part keeps the promoted timestamp column (`indexes[0]`, usually
     * `0`) as its first, sticky column, and the timestamp is never repeated
     * inside a part. Indexes are returned as column references, so the caller
     * renders headers and row cells from the same permutation.
     */
    fun splitProfileParts(
        columns: List<ProfileColumn>,
        indexes: List<Int>,
        maxColumnsPerPart: Int = 6
    ): List<List<Int>> {
        if (indexes.isEmpty() || columns.isEmpty()) return emptyList()
        // Group a value column with the capture timestamp that follows it, so
        // a pair is never split across two parts.
        val units = mutableListOf<List<Int>>()
        var position = 0
        while (position < indexes.size) {
            val index = indexes[position]
            val nextIndex = indexes.getOrNull(position + 1)
            if (nextIndex != null &&
                isCapturePair(columns[index], columns[nextIndex])
            ) {
                units += listOf(index, nextIndex)
                position += 2
            } else {
                units += listOf(index)
                position++
            }
        }

        val chunks = mutableListOf<MutableList<Int>>()
        var current = mutableListOf<Int>()
        units.forEach { unit ->
            if (current.isNotEmpty() && current.size + unit.size > maxColumnsPerPart) {
                chunks += current
                current = mutableListOf()
            }
            current.addAll(unit)
        }
        if (current.isNotEmpty()) chunks += current

        // Avoid a last part with a single column: move the trailing unit of the
        // previous part forward instead of leaving an almost empty page.
        if (chunks.size > 1 && chunks.last().size == 1) {
            val previous = chunks[chunks.lastIndex - 1].toMutableList()
            val moveCount = if (previous.size >= 2 &&
                isCapturePair(
                    columns[previous[previous.lastIndex - 1]],
                    columns[previous.last()]
                )
            ) {
                2
            } else {
                1
            }
            if (previous.size > moveCount) {
                val moved = previous.takeLast(moveCount)
                repeat(moveCount) { previous.removeAt(previous.lastIndex) }
                chunks[chunks.lastIndex - 1] = previous
                chunks[chunks.lastIndex].addAll(0, moved)
            }
        }

        // Pin the promoted timestamp column as the first, sticky column of
        // every part. Only a real timestamp column is pinned; a profile without
        // one keeps its natural capture-object order.
        val pinIndex = indexes.first()
        if (isTimestampColumn(columns[pinIndex])) {
            chunks.forEachIndexed { chunkIndex, chunk ->
                if (!chunk.contains(pinIndex)) {
                    chunk.add(0, pinIndex)
                } else if (chunk.first() != pinIndex) {
                    // removeAt, not remove: on a MutableList<Int> remove(Int)
                    // deletes the element equal to that value, which shifts
                    // every column.
                    chunk.removeAt(chunk.indexOf(pinIndex))
                    chunk.add(0, pinIndex)
                }
                chunks[chunkIndex] = chunk
            }
        }
        return chunks
    }

    /**
     * True when [capture] is the capture-time column that belongs to the value
     * column [value] directly before it.
     *
     * Live profile capture objects express the pair as attribute 2 followed by
     * attribute 5 on the same address; tagged CSV exports repeat the same
     * attribute-2 address twice (value, then timestamp), so both shapes count.
     */
    private fun isCapturePair(value: ProfileColumn, capture: ProfileColumn?): Boolean {
        if (capture == null) return false
        if (value.obisCode.isBlank() || value.obisCode != capture.obisCode) return false
        return (value.attributeIndex == 2 && capture.attributeIndex == 5) ||
            value.attributeIndex == capture.attributeIndex
    }

    /** Date/time cell as written by meters and vendor exports. */
    private val TIMESTAMP_VALUE = Regex(
        """^(?:\d{1,2}[-/.]\d{1,2}[-/.]\d{2,4}|\d{4}-\d{2}-\d{2})""" +
            """(?:[ T]\d{1,2}:\d{2}(?::\d{2})?(?:\.\d+)?)?$"""
    )

    /** True when [value] is a date/time cell rather than a quantity. */
    fun isTimestampValue(value: String): Boolean = TIMESTAMP_VALUE.matches(value.trim())

    /**
     * Renames the trailing capture-time column of a repeated capture object to
     * `Timestamp`, so two identical headers never sit side by side. [labels]
     * and [values] are in display order; already-unique labels are untouched.
     */
    fun markDuplicateTimestamps(
        labels: List<String>,
        values: List<String>,
        capturePositions: Set<Int> = emptySet()
    ): List<String> {
        val used = mutableSetOf<String>()
        var timestamps = 0
        return labels.mapIndexed { index, label ->
            if (used.add(label)) return@mapIndexed label
            if (index !in capturePositions &&
                !isTimestampValue(values.getOrNull(index).orEmpty())
            ) {
                return@mapIndexed label
            }
            timestamps += 1
            var name = if (timestamps == 1) "Timestamp" else "Timestamp $timestamps"
            // A profile may already carry a literal "Timestamp" header, so walk
            // forward until the generated name is free instead of assuming the
            // first candidate is unused.
            while (!used.add(name)) {
                timestamps += 1
                name = "Timestamp $timestamps"
            }
            name
        }
    }

    /**
     * Index of the primary timestamp capture object, or `-1` when the profile
     * has none. The address is matched before the header text, so a vendor
     * that writes `Billing Date` (`0.0.0.1.2.255`) at the end of its capture
     * object list is still recognised.
     */
    fun timestampColumnIndex(columns: List<ProfileColumn>): Int {
        listOf(BILLING_DATE_OBIS, CAPTURE_TIME_OBIS).forEach { obis ->
            val index = columns.indexOfFirst { it.obisCode.trim() == obis }
            if (index >= 0) return index
        }
        val labelled = columns.indexOfFirst { column ->
            val name = column.parameterName.lowercase(Locale.ENGLISH)
            TIMESTAMP_LABELS.any { name.contains(it) }
        }
        if (labelled >= 0) return labelled
        return columns.indexOfFirst { it.attributeIndex == 5 }
    }

    /** True when [column] is the primary timestamp of a profile table. */
    fun isTimestampColumn(column: ProfileColumn): Boolean =
        column.obisCode.trim() == BILLING_DATE_OBIS ||
            column.obisCode.trim() == CAPTURE_TIME_OBIS ||
            TIMESTAMP_LABELS.any { column.parameterName.lowercase(Locale.ENGLISH).contains(it) }

    /**
     * Moves the primary timestamp column to index `0` and drops it from its
     * original position, permuting the header list and every row's cells with
     * the same index order so headers and values can never drift apart.
     *
     * Tables that already start with their timestamp are returned unchanged.
     */
    fun promoteTimestampColumn(section: ProfileSection): ProfileSection {
        val index = timestampColumnIndex(section.columns)
        if (index <= 0) return section
        val order = listOf(index) + section.columns.indices.filter { it != index }
        fun permute(rows: List<List<String>>): List<List<String>> = rows.map { row ->
            order.map { row.getOrNull(it) ?: "N/A" }
        }
        return section.copy(
            columns = order.map { section.columns[it] },
            rows = permute(section.rows),
            fullRows = permute(section.fullRows)
        )
    }

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
        if (NEGATIVE_ZERO.matches(text)) {
            return text.removePrefix("-")
        }
        val fraction = text.substringAfter('.', "")
        if (fraction.length <= 3) return value
        return runCatching {
            val negative = text.startsWith('-')
            val body = text.removePrefix("-").removePrefix("+").replace(",", "")
            val rounded = BigDecimal(body).setScale(3, RoundingMode.HALF_UP)
            val formatted = if (text.contains(',')) {
                val symbols = DecimalFormatSymbols.getInstance(Locale.ENGLISH)
                val formatter = DecimalFormat("#,##0.###", symbols)
                (if (negative) "-" else "") + formatter.format(rounded.abs())
            } else {
                (if (negative) "-" else "") +
                    rounded.abs().stripTrailingZeros().toPlainString()
            }
            // A value that rounds to zero must never keep its sign.
            if (NEGATIVE_ZERO.matches(formatted)) formatted.removePrefix("-") else formatted
        }.getOrDefault(value)
    }

    private fun billingDateIndex(section: ProfileSection): Int {
        val index = timestampColumnIndex(section.columns)
        return if (index >= 0) index else 0
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
