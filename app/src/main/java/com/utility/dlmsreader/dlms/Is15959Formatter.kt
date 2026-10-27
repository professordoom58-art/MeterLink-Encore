package com.utility.dlmsreader.dlms

import gurux.dlms.GXDLMSClient
import gurux.dlms.GXDateTime
import gurux.dlms.enums.DataType
import gurux.dlms.enums.Unit as DlmsUnit
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object MeterTime {
    fun wallClock(dt: GXDateTime): Calendar =
        (dt.getMeterCalendar().clone() as Calendar)

    fun format(dt: GXDateTime, compact: Boolean = false): String {
        val calendar = wallClock(dt)
        val year = calendar.get(Calendar.YEAR)
        if (year < 2000 || year > 2200) return "N/A"
        val formatter = SimpleDateFormat(
            if (compact) "dd-MM-yy HH:mm" else "dd-MMM-yyyy HH:mm:ss",
            Locale.ENGLISH
        )
        formatter.timeZone = calendar.timeZone
        return formatter.format(calendar.time)
    }
}

data class ObisDescriptor(
    val label: String,
    val unit: String = "",
    val category: ObisCategory = ObisCategory.UNKNOWN
)

object Is15959Formatter {
    private val numberFormat = DecimalFormat(
        "#,##0.###",
        DecimalFormatSymbols(Locale.ENGLISH)
    )
    private val dateFormat = SimpleDateFormat("dd-MMM-yyyy HH:mm:ss", Locale.ENGLISH)
    private val compactDateFormat = SimpleDateFormat("dd-MM-yy HH:mm", Locale.ENGLISH)
    private val yearFormat = DecimalFormat("0", DecimalFormatSymbols(Locale.ENGLISH))
    private val twoDecimalFormat = DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.ENGLISH))
    private val threeDecimalFormat = DecimalFormat("#,##0.000", DecimalFormatSymbols(Locale.ENGLISH))

    fun formatValue(
        raw: Any?,
        scaler: Double = 1.0,
        unit: DlmsUnit? = null,
        obis: String? = null,
        includeUnit: Boolean = true,
        runtimeMetadata: Map<String, ObisMetadata> = emptyMap(),
        meterMake: String? = null
    ): String = when (raw) {
        null -> "N/A"
        is GXDateTime -> formatDateTime(raw)
        is Date -> formatDate(raw)
        is ByteArray -> formatBytes(raw)
        is Number -> {
            val displayUnit = displayUnit(unit, obis, runtimeMetadata, meterMake)
            val scaledValue = raw.toDouble() * scaler
            val value = scaledValue * if (isEnergyOrPower(unit, obis)) 0.001 else 1.0
            val formatted = if (isYearOfManufacture(obis)) {
                yearFormat.format(value)
            } else if (isMeterType(obis)) {
                meterTypeLabel(scaledValue.toInt())
            } else {
                formatNumber(value, unit, obis, runtimeMetadata, meterMake)
            }
            if (includeUnit && displayUnit.isNotEmpty()) "$formatted $displayUnit" else formatted
        }
        is Array<*> -> raw.joinToString(", ") {
            formatValue(
                it,
                includeUnit = includeUnit,
                runtimeMetadata = runtimeMetadata,
                meterMake = meterMake
            )
        }
        is Iterable<*> -> raw.joinToString(", ") {
            formatValue(
                it,
                includeUnit = includeUnit,
                runtimeMetadata = runtimeMetadata,
                meterMake = meterMake
            )
        }
        else -> raw.toString().trim().ifEmpty { "N/A" }
    }

    fun formatDateTime(raw: GXDateTime): String = runCatching {
        MeterTime.format(raw)
    }.getOrDefault("N/A")

    fun formatCompactDateTime(raw: Any?): String = runCatching {
        val date = when (raw) {
            is GXDateTime -> return@runCatching MeterTime.format(raw, compact = true)
            is Date -> raw
            is ByteArray -> {
                if (raw.size != 12) return@runCatching formatValue(raw)
                (GXDLMSClient.changeType(raw, DataType.DATETIME, false) as? GXDateTime)
                    ?.let { return@runCatching MeterTime.format(it, compact = true) }
            }
            else -> null
        } ?: return@runCatching formatValue(raw)
        val year = Calendar.getInstance().apply { time = date }.get(Calendar.YEAR)
        if (year < 2000 || year > 2200) "N/A" else compactDateFormat.format(date)
    }.getOrDefault("N/A")

    fun formatDate(raw: Date?): String = raw?.let { dateFormat.format(it) } ?: "N/A"

    fun unitSymbol(unit: DlmsUnit?): String = when (unit) {
        DlmsUnit.ACTIVE_ENERGY -> "kWh"
        DlmsUnit.APPARENT_ENERGY -> "kVAh"
        DlmsUnit.REACTIVE_ENERGY -> "kVArh"
        DlmsUnit.ACTIVE_POWER -> "kW"
        DlmsUnit.APPARENT_POWER -> "kVA"
        DlmsUnit.REACTIVE_POWER -> "kVAr"
        DlmsUnit.VOLTAGE -> "V"
        DlmsUnit.CURRENT -> "A"
        DlmsUnit.FREQUENCY -> "Hz"
        DlmsUnit.PERCENTAGE -> "%"
        DlmsUnit.MINUTE -> "min"
        DlmsUnit.SECOND -> "s"
        DlmsUnit.PHASE_ANGLE_DEGREE -> "deg"
        else -> ""
    }

    fun getObisLabelAndUnit(
        obis: String,
        runtimeMetadata: Map<String, ObisMetadata> = emptyMap(),
        meterMake: String? = null
    ): ObisDescriptor {
        Is15959Catalog.metadataFor(obis, runtimeMetadata, meterMake)?.let { metadata ->
            return ObisDescriptor(metadata.label, metadata.unit, metadata.category)
        }
        return when {
        obis == "0.0.42.0.0.255" -> ObisDescriptor("Logical Device Name")
        obis == "0.0.96.1.0.255" -> ObisDescriptor("Meter Serial Number")
        obis == "0.0.96.1.1.255" -> ObisDescriptor("Manufacturer Name")
        obis == "0.0.96.1.4.255" -> ObisDescriptor("Year of Manufacture")
        obis == "1.0.0.2.0.255" -> ObisDescriptor("Firmware Version")
        obis == "0.0.94.91.9.255" -> ObisDescriptor("Meter Type (5=1P-2W, 1=3P-3W, 2=3P-4W)")
        obis == "0.0.94.91.11.255" -> ObisDescriptor("Meter Category")
        obis == "0.0.94.91.12.255" -> ObisDescriptor("Current Rating")
        obis == "0.0.94.91.14.255" -> ObisDescriptor("Cumulative Power-On Duration (min)", "min")
        obis == "0.0.96.2.0.255" -> ObisDescriptor("Cumulative Programming Count")
        obis == "0.128.169.128.128.255" -> ObisDescriptor("L&T Config ID")
        obis == "0.128.176.128.128.255" -> ObisDescriptor("Meter Constant (imp/kWh)")
        obis == "0.0.0.1.2.255" -> ObisDescriptor("Billing Date")
        obis == "1.0.13.0.0.255" -> ObisDescriptor("Average Billing Power Factor", "PF")
        isTodEnergyObis(obis, 1) -> ObisDescriptor("kWh ToD Zone ${todZone(obis)}", "kWh")
        isTodEnergyObis(obis, 9) -> ObisDescriptor("kVAh ToD Zone ${todZone(obis)}", "kVAh")
        obis == "0.0.1.0.0.255" -> ObisDescriptor("Real-Time Clock")
        obis == "1.0.12.7.0.255" || obis == "1.0.32.7.0.255" -> ObisDescriptor("R-Phase / Phase Voltage (V)", "V")
        obis == "1.0.52.7.0.255" -> ObisDescriptor("Y-Phase Voltage (V)", "V")
        obis == "1.0.72.7.0.255" -> ObisDescriptor("B-Phase Voltage (V)", "V")
        obis == "1.0.11.7.0.255" || obis == "1.0.31.7.0.255" -> ObisDescriptor("R-Phase / Phase Current (A)", "A")
        obis == "1.0.51.7.0.255" -> ObisDescriptor("Y-Phase Current (A)", "A")
        obis == "1.0.71.7.0.255" -> ObisDescriptor("B-Phase Current (A)", "A")
        obis == "1.0.91.7.0.255" -> ObisDescriptor("Neutral Current (A)", "A")
        obis == "1.0.13.7.0.255" -> ObisDescriptor("Signed Power Factor", "PF")
        obis == "1.0.33.7.0.255" || obis == "1.0.53.7.0.255" || obis == "1.0.73.7.0.255" -> ObisDescriptor("R / Y / B Phase PF", "PF")
        obis == "1.0.14.7.0.255" -> ObisDescriptor("Frequency (Hz)", "Hz")
        obis == "1.0.1.7.0.255" -> ObisDescriptor("Active Power (kW)", "kW")
        obis == "1.0.9.7.0.255" -> ObisDescriptor("Apparent Power (kVA)", "kVA")
        obis == "1.0.3.7.0.255" -> ObisDescriptor("Reactive Power (kVAr)", "kVAr")
        obis == "1.0.1.8.0.255" -> ObisDescriptor("Cumulative Active Energy - Import (kWh)", "kWh")
        obis == "1.0.2.8.0.255" -> ObisDescriptor("Cumulative Active Energy - Export (kWh)", "kWh")
        obis == "1.0.9.8.0.255" -> ObisDescriptor("Cumulative Apparent Energy - Import (kVAh)", "kVAh")
        obis == "1.0.10.8.0.255" -> ObisDescriptor("Cumulative Apparent Energy - Export (kVAh)", "kVAh")
        obis == "1.0.5.8.0.255" || obis == "1.0.3.8.0.255" -> ObisDescriptor("Reactive Energy - Lag (kVArh)", "kVArh")
        obis == "1.0.8.8.0.255" || obis == "1.0.4.8.0.255" -> ObisDescriptor("Reactive Energy - Lead (kVArh)", "kVArh")
        obis == "1.0.1.6.0.255" -> ObisDescriptor("Maximum Demand - Active (kW)", "kW")
        obis == "1.0.9.6.0.255" -> ObisDescriptor("Maximum Demand - Apparent (kVA)", "kVA")
        obis == "1.0.1.2.0.255" -> ObisDescriptor("Cum. MD Active (kW)", "kW")
        obis == "1.0.9.2.0.255" -> ObisDescriptor("Cum. MD Apparent (kVA)", "kVA")
        obis == "1.0.128.8.38.255" -> ObisDescriptor("Billing Reset Type / Status")
        obis.startsWith("0.0.96.11.") && obis.endsWith(".255") -> ObisDescriptor("Tamper Event Code")
        obis == "0.0.94.91.0.255" -> ObisDescriptor("Cumulative Tamper Count")
        obis == "0.0.0.1.0.255" -> ObisDescriptor("Billing Reset Count")
        obis == "0.0.94.91.13.255" -> ObisDescriptor("Cumulative Power-On Duration (Min)", "min")
        else -> ObisDescriptor("OBIS $obis")
    }
    }

    fun decodeTamperEvent(code: Int): String = when (code) {
        1 -> "R-Phase VT Link Missing (Missing Potential) - Occurrence"
        2 -> "R-Phase VT Link Missing (Missing Potential) - Restoration"
        3 -> "Y-Phase VT Link Missing (Missing Potential) - Occurrence"
        4 -> "Y-Phase VT Link Missing (Missing Potential) - Restoration"
        5 -> "B-Phase VT Link Missing (Missing Potential) - Occurrence"
        6 -> "B-Phase VT Link Missing (Missing Potential) - Restoration"
        7 -> "Over Voltage in Any Phase - Occurrence"
        8 -> "Over Voltage in Any Phase - Restoration"
        9 -> "Low Voltage in Any Phase - Occurrence"
        10 -> "Low Voltage in Any Phase - Restoration"
        11 -> "Voltage Unbalance - Occurrence"
        12 -> "Voltage Unbalance - Restoration"
        51 -> "R-Phase CT Reverse - Occurrence"
        52 -> "R-Phase CT Reverse - Restoration"
        53 -> "Y-Phase CT Reverse - Occurrence"
        54 -> "Y-Phase CT Reverse - Restoration"
        55 -> "B-Phase CT Reverse - Occurrence"
        56 -> "B-Phase CT Reverse - Restoration"
        57 -> "R-Phase CT Open - Occurrence"
        58 -> "R-Phase CT Open - Restoration"
        59 -> "Y-Phase CT Open - Occurrence"
        60 -> "Y-Phase CT Open - Restoration"
        61 -> "B-Phase CT Open - Occurrence"
        62 -> "B-Phase CT Open - Restoration"
        63 -> "Current Unbalance - Occurrence"
        64 -> "Current Unbalance - Restoration"
        65 -> "CT Bypass - Occurrence"
        66 -> "CT Bypass - Restoration"
        67 -> "Over Current in Any Phase - Occurrence"
        68 -> "Over Current in Any Phase - Restoration"
        69 -> "Earth Loading - Occurrence"
        70 -> "Earth Loading - Restoration"
        101 -> "Power Failure - Occurrence"
        102 -> "Power Failure - Restoration"
        151 -> "Real Time Clock (Date & Time) Changed"
        152 -> "Demand Integration Period Changed"
        153 -> "Profile Capture Period Changed"
        154 -> "Single-Action Schedule for Billing Dates Changed"
        155 -> "Activity Calendar for Time Zones Changed"
        201 -> "Influence of Permanent/Electromagnet - Occurrence"
        202 -> "Influence of Permanent/Electromagnet - Restoration"
        203 -> "Neutral Disturbance (HF & DC) - Occurrence"
        204 -> "Neutral Disturbance (HF & DC) - Restoration"
        205 -> "Very Low PF - Occurrence"
        206 -> "Very Low PF - Restoration"
        251 -> "Meter Cover Opening - Occurrence"
        else -> "Event Code $code"
    }

    private fun formatBytes(raw: ByteArray): String {
        if (raw.all { it.toInt() in 0x20..0x7E }) {
            return String(raw, Charsets.US_ASCII).trim()
        }
        if (raw.size == 12) {
            val dateTime = runCatching {
                GXDLMSClient.changeType(raw, DataType.DATETIME, false) as? GXDateTime
            }.getOrNull()
            if (dateTime != null) return formatDateTime(dateTime)
        }
        return raw.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
    }

    private fun formatNumber(
        value: Double,
        unit: DlmsUnit?,
        obis: String?,
        runtimeMetadata: Map<String, ObisMetadata>,
        meterMake: String?
    ): String {
        val metadataUnit = obis?.let {
            Is15959Catalog.metadataFor(it, runtimeMetadata, meterMake)?.unit
        }.orEmpty()
        return when {
        isEnergyOrPower(unit, obis) -> threeDecimalFormat.format(value)
        unit == DlmsUnit.VOLTAGE || unit == DlmsUnit.CURRENT ||
            unit == DlmsUnit.FREQUENCY || unit == DlmsUnit.PERCENTAGE ||
            unit == DlmsUnit.PHASE_ANGLE_DEGREE ||
            metadataUnit in setOf("V", "A", "Hz", "%", "PF", "deg") -> twoDecimalFormat.format(value)
        else -> numberFormat.format(value)
    }
    }

    /** Returns the meter scaler only. Display-unit conversion is done by formatValue. */
    fun normalizedScaler(scaler: Double, unit: DlmsUnit?): Double = scaler

    private fun displayUnit(
        unit: DlmsUnit?,
        obis: String?,
        runtimeMetadata: Map<String, ObisMetadata>,
        meterMake: String?
    ): String = unitSymbol(unit).ifEmpty {
        Is15959Catalog.metadataFor(obis.orEmpty(), runtimeMetadata, meterMake)?.unit
            ?.takeIf { it.isNotEmpty() }
            ?: obisUnitSymbol(obis)
    }

    private fun isEnergyOrPower(unit: DlmsUnit?, obis: String?): Boolean =
        unit in setOf(
            DlmsUnit.ACTIVE_ENERGY,
            DlmsUnit.APPARENT_ENERGY,
            DlmsUnit.REACTIVE_ENERGY,
            DlmsUnit.ACTIVE_POWER,
            DlmsUnit.APPARENT_POWER,
            DlmsUnit.REACTIVE_POWER
        ) || isEnergyOrPowerObis(obis)

    private fun isEnergyOrPowerObis(obis: String?): Boolean {
        val parts = obis?.split('.')?.mapNotNull { it.toIntOrNull() } ?: return false
        if (parts.size != 6 || parts[0] != 1 || parts[1] != 0 || parts[5] != 255) return false
        return (parts[3] == 8 && parts[2] in setOf(1, 2, 3, 4, 5, 8, 9, 10)) ||
            (parts[3] == 7 && parts[2] in setOf(1, 3, 9)) ||
            (parts[3] == 6 && parts[2] in setOf(1, 9))
    }

    private fun obisUnitSymbol(obis: String?): String {
        val parts = obis?.split('.')?.mapNotNull { it.toIntOrNull() } ?: return ""
        if (parts.size != 6 || parts[0] != 1 || parts[1] != 0 || parts[5] != 255) return ""
        return when {
            parts[3] == 8 && parts[2] in setOf(9, 10) -> "kVAh"
            parts[3] == 8 && parts[2] in setOf(3, 4, 5, 8) -> "kVArh"
            parts[3] == 8 && parts[2] in setOf(1, 2) -> "kWh"
            parts[3] == 7 && parts[2] == 9 -> "kVA"
            parts[3] == 7 && parts[2] == 3 -> "kVAr"
            parts[3] == 7 && parts[2] == 1 -> "kW"
            parts[3] == 6 && parts[2] == 9 -> "kVA"
            parts[3] == 6 && parts[2] == 1 -> "kW"
            else -> ""
        }
    }

    private fun isYearOfManufacture(obis: String?): Boolean = obis == "0.0.96.1.4.255"

    private fun isMeterType(obis: String?): Boolean = obis == "0.0.94.91.9.255"

    private fun meterTypeLabel(value: Int): String = when (value) {
        5 -> "5 (1P-2W)"
        1 -> "1 (3P-3W)"
        2 -> "2 (3P-4W)"
        3 -> "3 (3P-4W CT)"
        4 -> "4 (3P-4W HT)"
        else -> value.toString()
    }

    private fun isTodEnergyObis(obis: String, channel: Int): Boolean {
        val parts = obis.split('.').mapNotNull { it.toIntOrNull() }
        return parts.size == 6 && parts[0] == 1 && parts[1] == 0 &&
            parts[2] == channel && parts[3] == 8 && parts[4] in 1..8 && parts[5] == 255
    }

    private fun todZone(obis: String): Int = obis.split('.')[4].toInt()
}
