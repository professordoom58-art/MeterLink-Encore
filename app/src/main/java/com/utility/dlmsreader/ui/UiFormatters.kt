package com.utility.dlmsreader.ui

import com.utility.dlmsreader.dlms.MeterDataReport
import com.utility.dlmsreader.dlms.NetMetering
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/** Four or more digits are worth grouping: `7443` becomes `7,443`. */
private val GROUPABLE_INTEGER = Regex("""^[+-]?\d{4,}$""")

/**
 * Groups the integer part with commas so every summary tile reads the same
 * way. Only plain integers are touched, so timestamps, `N/A` and already
 * grouped values pass through unchanged.
 */
private fun String.groupWholeThousands(): String {
    val trimmed = trim()
    val sign = if (trimmed.startsWith('-') || trimmed.startsWith('+')) trimmed.substring(0, 1) else ""
    val body = trimmed.removePrefix(sign)
    if (!GROUPABLE_INTEGER.matches(sign + body)) return this
    return sign + String.format(Locale.US, "%,d", java.math.BigInteger(body))
}

/** Groups the integer part of `1234.5` while keeping any fraction intact. */
private fun groupIntegerPart(text: String): String {
    val dot = text.indexOf('.')
    if (dot < 0) return text.groupWholeThousands()
    return text.substring(0, dot).groupWholeThousands() + text.substring(dot)
}

/**
 * UI-only preview formatter: drops the decimal fraction so `7,443.000` becomes
 * `7,443` and groups thousands so `5342` becomes `5,342`. Raw report data,
 * JSON, PDF and Excel keep full precision.
 */
internal fun String.toPreviewWholeUnits(): String {
    val trimmed = trim()
    if (trimmed.isBlank() || trimmed == "—") return trimmed
    return trimmed.substringBefore('.').ifBlank { trimmed }.groupWholeThousands()
}

private val ENERGY_NUMBER = Regex("""^[+-]?\d[\d,]*(?:\.\d+)?""")

/**
 * Multiplier to the base unit of the matching family: Wh for active energy,
 * VAh for apparent energy, VArh for reactive energy.
 */
private val ENERGY_UNIT_FACTOR: Map<String, Pair<BigDecimal, String>> = mapOf(
    "wh" to (BigDecimal.ONE to "wh"),
    "kwh" to (BigDecimal("1000") to "wh"),
    "mwh" to (BigDecimal("1000000") to "wh"),
    "gwh" to (BigDecimal("1000000000") to "wh"),
    "vah" to (BigDecimal.ONE to "vah"),
    "kvah" to (BigDecimal("1000") to "vah"),
    "mvah" to (BigDecimal("1000000") to "vah"),
    "varh" to (BigDecimal.ONE to "varh"),
    "kvarh" to (BigDecimal("1000") to "varh"),
    "mvarh" to (BigDecimal("1000000") to "varh")
)

/**
 * Scales a stored energy reading such as `21239700 WH` to the unit the heading
 * displays, e.g. `21239.7` for the `kWh` heading. Stored values are untouched;
 * only the preview string changes.
 */
internal fun energyAmount(reading: String, unit: String): String {
    val trimmed = reading.trim()
    if (trimmed.isBlank() || trimmed == "—") return "—"
    val match = ENERGY_NUMBER.find(trimmed) ?: return trimmed.toPreviewWholeUnits()
    val amount = match.value.replace(",", "").toBigDecimalOrNull()
        ?: return trimmed.toPreviewWholeUnits()
    val displayedNumber = trimmed.substring(0, match.range.last + 1).toPreviewWholeUnits()

    val sourceUnit = trimmed.substring(match.range.last + 1).trim().trim('(', ')')
    val targetUnit = unit.trim()
    val source = ENERGY_UNIT_FACTOR[sourceUnit.lowercase(Locale.US)]
    val target = ENERGY_UNIT_FACTOR[targetUnit.lowercase(Locale.US)]
    if (source == null || target == null || source.second != target.second ||
        sourceUnit.equals(targetUnit, ignoreCase = true)
    ) {
        return displayedNumber.ifBlank { "—" }
    }

    val converted = amount
        .multiply(source.first)
        .divide(target.first, 6, RoundingMode.HALF_UP)
        .stripTrailingZeros()
    return if (converted.scale() > 0) {
        groupIntegerPart(converted.toPlainString())
    } else {
        converted.toBigInteger().toString().groupWholeThousands()
    }
}

internal fun MeterDataReport.energyReading(obis: String, unit: String): String {
    val register = cumulativeEnergies.firstOrNull { it.obisCode == obis }
    val reading = register?.reading.orEmpty()
    val sourceUnit = register?.unit.orEmpty().trim()
    // A base scaler unit lives in the register's unit column, so attach it
    // before previewing; the card always shows whole report units.
    val value = if (reading.isNotBlank() && sourceUnit.isNotEmpty() && reading.none { it.isLetter() }) {
        "$reading $sourceUnit"
    } else {
        reading
    }
    return energyAmount(value, unit).toPreviewWholeUnits()
}

/**
 * `import − export` active energy for a net-metering summary card, already in
 * whole-unit preview form. Empty when either side of the channel is missing.
 */
internal fun MeterDataReport.netActiveEnergyReading(): String =
    energyAmount(
        NetMetering.netText(
            cumulativeEnergies,
            NetMetering.OBIS_ACTIVE_ENERGY_IMPORT,
            NetMetering.OBIS_ACTIVE_ENERGY_EXPORT
        ),
        "kWh"
    ).toPreviewWholeUnits().ifBlank { "—" }

/** `import − export` apparent energy for a net-metering summary card. */
internal fun MeterDataReport.netApparentEnergyReading(): String =
    energyAmount(
        NetMetering.netText(
            cumulativeEnergies,
            NetMetering.OBIS_APPARENT_ENERGY_IMPORT,
            NetMetering.OBIS_APPARENT_ENERGY_EXPORT
        ),
        "kVAh"
    ).toPreviewWholeUnits().ifBlank { "—" }

internal fun manufacturerTag(manufacturer: String): String {
    val upper = manufacturer.uppercase(Locale.US)
    return when {
        "LARSEN" in upper || "L&T" in upper || "LNT" in upper -> "L&T"
        manufacturer.isBlank() -> "METER"
        else -> manufacturer.trim().substringBefore(' ').take(10).uppercase(Locale.US)
    }
}