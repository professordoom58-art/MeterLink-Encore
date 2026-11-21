package com.utility.dlmsreader.dlms

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/** One energy or demand quantity after vendor-neutral unit normalization. */
data class NormalizedQuantity(
    val value: String,
    val unit: String
)

/**
 * Centralised unit normalization for every report surface.
 *
 * DLMS / COSEM (IEC 62056, BIS IS 15959 Part 2) scaler_units carry the SI
 * prefix outside the value: a register with exponent 0 and unit `WH` means
 * "watts times hours", not kilowatt hours. Imported `.OUT` / `.dat` / `.csv`
 * files and some live scaler profiles therefore hand the reporters base units
 * such as `7443000 WH`, `9077800 VAH`, `1850 W` and `3680 VA`.
 *
 * [normalize] converts those base units to the report unit (kWh, kVAh, kVArh,
 * kW, kVA, kVAr) exactly once. Units that already carry a kilo/mega prefix are
 * never divided again, so the helper is safe to run on live readings, imported
 * files, saved reports and already-normalized data.
 */
object UnitNormalization {

    /** Decimal places used for every normalized energy and demand value. */
    const val PRECISION = 3

    private data class Conversion(val divisor: BigDecimal, val target: String)

    private val THOUSAND = BigDecimal("1000")

    /** Base units that must be rescaled, keyed by lowercase unit symbol. */
    private val BASE_UNITS: Map<String, Conversion> = mapOf(
        "wh" to Conversion(THOUSAND, "kWh"),
        "vah" to Conversion(THOUSAND, "kVAh"),
        "varh" to Conversion(THOUSAND, "kVArh"),
        "w" to Conversion(THOUSAND, "kW"),
        "va" to Conversion(THOUSAND, "kVA"),
        "var" to Conversion(THOUSAND, "kVAr")
    )

    /** Already-prefixed units: their values stay untouched (never double-divided). */
    private val PREFIXED_UNITS: Set<String> = setOf(
        "kwh", "kvah", "kvarh", "kw", "kva", "kvar",
        "mwh", "mvah", "mvarh", "mw", "mva", "mvar",
        "gwh", "gvah", "gvarh", "gw", "gva", "gvar"
    )

    /** Leading decimal value, optionally grouped with commas. */
    private val NUMBER_PREFIX = Regex("""^[+-]?\d[\d,]*(?:\.\d+)?""")

    /** A whole profile cell that is only a value, with an optional unit suffix. */
    private val NUMBER_CELL = Regex("""^[+-]?\d[\d,]*(?:\.\d+)?\s*(?:[A-Za-z%]+)?$""")

    /** A value that is zero but still carries a minus sign. */
    private val NEGATIVE_ZERO = Regex("""^-0(?:\.0+)?$""")

    /**
     * Removes the sign from `-0` / `-0.000`: a rounded-to-zero reading is zero,
     * not a negative quantity. Any other text is returned unchanged.
     */
    fun cleanNegativeZero(value: String): String {
        val text = value.trim()
        if (!NEGATIVE_ZERO.matches(text)) return value
        val decimals = text.substringAfter('.', "")
        return if (decimals.isEmpty()) "0" else "0.$decimals"
    }

    /** True for a unit symbol the normalizer understands. */
    fun isKnownUnit(unit: String): Boolean {
        val key = unitKey(unit)
        return key.isNotEmpty() && (key in BASE_UNITS || key in PREFIXED_UNITS)
    }

    /**
     * Normalizes [valueText] held in [unit].
     *
     * The unit may also be embedded in the value (`7443000 WH`, `1850 W`) as
     * exported .OUT/.dat files do. In that case the embedded symbol wins and
     * the unit column receives the converted symbol.
     */
    fun normalize(valueText: String, unit: String): NormalizedQuantity {
        val declaredUnit = unit.trim()
        val text = valueText.trim()
        if (text.isEmpty() || text == "-" || text.equals("N/A", ignoreCase = true)) {
            return NormalizedQuantity(valueText, declaredUnit)
        }
        val number = NUMBER_PREFIX.find(text) ?: return NormalizedQuantity(cleanNegativeZero(valueText), declaredUnit)
        val embeddedUnit = text.substring(number.range.last + 1)
            .trim()
            .trim('(', ')')
        val sourceUnit = embeddedUnit.ifBlank { declaredUnit }
        val conversion = BASE_UNITS[unitKey(sourceUnit)]
            ?: return NormalizedQuantity(cleanNegativeZero(valueText), declaredUnit)
        val amount = number.value.replace(",", "").toBigDecimalOrNull()
            ?: return NormalizedQuantity(cleanNegativeZero(valueText), declaredUnit)
        val converted = amount.divide(conversion.divisor, PRECISION, RoundingMode.HALF_UP)
        return NormalizedQuantity(format(converted), conversion.target)
    }

    /** Normalizes one cumulative-energy or demand register row. */
    fun normalize(reading: RegisterReading): RegisterReading {
        val normalized = normalize(reading.reading, reading.unit)
        return if (normalized.value == reading.reading && normalized.unit == reading.unit) {
            reading
        } else {
            reading.copy(reading = normalized.value, unit = normalized.unit)
        }
    }

    /** Normalizes one key/value identity row. */
    fun normalize(row: ParameterRow): ParameterRow {
        val normalized = normalize(row.formattedValue, row.unit)
        return if (normalized.value == row.formattedValue && normalized.unit == row.unit) {
            row
        } else {
            row.copy(formattedValue = normalized.value, unit = normalized.unit)
        }
    }

    /**
     * Normalizes every value column of a profile. Columns are converted once
     * from their declared scaler unit, so a report with kWh columns is returned
     * unchanged.
     */
    /** Normalizes every value column of a profile and cleans negative zero. */
    fun normalize(section: ProfileSection): ProfileSection {
        val conversions = section.columns.map { BASE_UNITS[unitKey(it.unit)] }
        val columns = section.columns.mapIndexed { index, column ->
            conversions[index]?.let { column.copy(unit = it.target) } ?: column
        }
        fun convertRows(rows: List<List<String>>): List<List<String>> = rows.map { row ->
            row.mapIndexed { index, cell ->
                val conversion = conversions.getOrNull(index)
                if (conversion != null) normalizeCell(cell, conversion) else cleanNegativeZero(cell)
            }
        }
        return section.copy(
            columns = columns,
            rows = convertRows(section.rows),
            fullRows = convertRows(section.fullRows)
        )
    }

    /**
     * Returns a copy of [report] whose energy and demand values are all in the
     * report units. The stored report itself is never mutated.
     */
    fun normalize(report: MeterDataReport): MeterDataReport = report.copy(
        identity = report.identity?.let { identity ->
            identity.copy(
                rows = identity.rows.map(::normalize),
                nameplate = identity.nameplate?.let(::normalize)
            )
        },
        cumulativeEnergies = report.cumulativeEnergies.map(::normalize),
        instantaneous = report.instantaneous?.let(::normalize),
        billing = report.billing?.let(::normalize),
        blockLoad = report.blockLoad?.let(::normalize),
        dailyLoad = report.dailyLoad?.let(::normalize)
    )

    /** `7,443.000`-style grouped decimal with strict three-decimal precision. */
    fun format(amount: BigDecimal): String {
        val symbols = DecimalFormatSymbols(Locale.ENGLISH)
        val text = DecimalFormat("#,##0.000", symbols).apply {
            roundingMode = RoundingMode.HALF_UP
        }.format(amount)
        return cleanNegativeZero(text)
    }

    private fun normalizeCell(cell: String, conversion: Conversion): String {
        val text = cell.trim()
        if (text.isEmpty() || text == "-") return cell
        // Timestamps and other free text share cells with values; only a plain
        // number (with an optional unit suffix) is converted.
        if (!NUMBER_CELL.matches(text)) return cleanNegativeZero(cell)
        val number = NUMBER_PREFIX.find(text) ?: return cleanNegativeZero(cell)
        val amount = number.value.replace(",", "").toBigDecimalOrNull()
            ?: return cleanNegativeZero(cell)
        return format(amount.divide(conversion.divisor, PRECISION, RoundingMode.HALF_UP))
    }

    private fun unitKey(unit: String): String =
        unit.trim()
            .lowercase(Locale.ENGLISH)
            .replace(" ", "")
            .trim('(', ')')
}
