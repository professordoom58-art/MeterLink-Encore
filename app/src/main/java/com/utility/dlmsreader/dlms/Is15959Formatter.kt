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

data class ObisDescriptor(
    val label: String,
    val unit: String = ""
)

object Is15959Formatter {
    private val numberFormat = DecimalFormat(
        "#,##0.###",
        DecimalFormatSymbols(Locale.ENGLISH)
    )
    private val dateFormat = SimpleDateFormat("dd-MMM-yyyy HH:mm:ss", Locale.ENGLISH)
    private val compactDateFormat = SimpleDateFormat("dd-MM-yy HH:mm", Locale.ENGLISH)
    private val yearFormat = DecimalFormat("0", DecimalFormatSymbols(Locale.ENGLISH))

    fun formatValue(
        raw: Any?,
        scaler: Double = 1.0,
        unit: DlmsUnit? = null,
        obis: String? = null,
        includeUnit: Boolean = true
    ): String = when (raw) {
        null -> "N/A"
        is GXDateTime -> formatDateTime(raw)
        is Date -> formatDate(raw)
        is ByteArray -> formatBytes(raw)
        is Number -> {
            val displayUnit = displayUnit(unit, obis)
            val value = raw.toDouble() * scaler * if (isEnergyOrPower(unit, obis)) 0.001 else 1.0
            val formatted = if (isYearOfManufacture(obis)) {
                yearFormat.format(value)
            } else if (isMeterType(obis)) {
                meterTypeLabel(raw.toInt())
            } else {
                numberFormat.format(value)
            }
            if (includeUnit && displayUnit.isNotEmpty()) "$formatted $displayUnit" else formatted
        }
        is Array<*> -> raw.joinToString(", ") { formatValue(it, includeUnit = includeUnit) }
        is Iterable<*> -> raw.joinToString(", ") { formatValue(it, includeUnit = includeUnit) }
        else -> raw.toString().trim().ifEmpty { "N/A" }
    }

    fun formatDateTime(raw: GXDateTime): String = runCatching {
        val calendar = raw.getLocalCalendar()
        val year = calendar.get(Calendar.YEAR)
        if (year < 2000 || year > 2200) "N/A" else dateFormat.format(calendar.time)
    }.getOrDefault("N/A")

    fun formatCompactDateTime(raw: Any?): String = runCatching {
        val date = when (raw) {
            is GXDateTime -> raw.getLocalCalendar().time
            is Date -> raw
            is ByteArray -> {
                if (raw.size != 12) return@runCatching formatValue(raw)
                (GXDLMSClient.changeType(raw, DataType.DATETIME, false) as? GXDateTime)
                    ?.getLocalCalendar()?.time
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

    fun getObisLabelAndUnit(obis: String): ObisDescriptor = when {
        obis == "0.0.42.0.0.255" -> ObisDescriptor("Logical Device Name")
        obis == "0.0.96.1.0.255" -> ObisDescriptor("Meter Serial Number")
        obis == "0.0.96.1.1.255" -> ObisDescriptor("Manufacturer Name")
        obis == "0.0.96.1.4.255" -> ObisDescriptor("Year of Manufacture")
        obis == "1.0.0.2.0.255" -> ObisDescriptor("Firmware Version")
        obis == "0.0.94.91.9.255" -> ObisDescriptor("Meter Type")
        obis == "0.0.94.91.11.255" -> ObisDescriptor("Meter Category")
        obis == "0.0.94.91.12.255" -> ObisDescriptor("Current Rating")
        obis == "0.0.94.91.14.255" -> ObisDescriptor("Cumulative Power-On Duration (min)", "min")
        obis == "0.0.96.2.0.255" -> ObisDescriptor("Cumulative Programming Count")
        obis == "0.128.169.128.128.255" -> ObisDescriptor("L&T Internal Config ID")
        obis == "0.128.176.128.128.255" -> ObisDescriptor("Meter Constant (imp/kWh)")
        obis == "0.0.0.1.2.255" -> ObisDescriptor("Billing Date")
        obis == "1.0.13.0.0.255" -> ObisDescriptor("Average Billing PF", "PF")
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
        obis.startsWith("0.0.96.11.") && obis.endsWith(".255") -> ObisDescriptor("Tamper Event Code")
        obis == "0.0.94.91.0.255" -> ObisDescriptor("Cumulative Tamper Count")
        obis == "0.0.0.1.0.255" -> ObisDescriptor("Billing Reset Count")
        obis == "0.0.94.91.13.255" -> ObisDescriptor("Cumulative Power-On Duration (Min)", "min")
        else -> ObisDescriptor("OBIS $obis")
    }

    fun decodeTamperEvent(code: Int): String = when (code) {
        1 -> "R-Phase Voltage Missing - Occurrence"
        2 -> "R-Phase Voltage Missing - Restoration"
        3 -> "Y-Phase Voltage Missing - Occurrence"
        4 -> "Y-Phase Voltage Missing - Restoration"
        5 -> "B-Phase Voltage Missing - Occurrence"
        6 -> "B-Phase Voltage Missing - Restoration"
        7 -> "Over Voltage - Occurrence"
        8 -> "Over Voltage - Restoration"
        9 -> "Low Voltage - Occurrence"
        10 -> "Low Voltage - Restoration"
        11 -> "Voltage Unbalance - Occurrence"
        12 -> "Voltage Unbalance - Restoration"
        51 -> "R-Phase Current Reversal - Occurrence"
        52 -> "R-Phase Current Reversal - Restoration"
        53 -> "Y-Phase Current Reversal - Occurrence"
        54 -> "Y-Phase Current Reversal - Restoration"
        55 -> "B-Phase Current Reversal - Occurrence"
        56 -> "B-Phase Current Reversal - Restoration"
        57 -> "R-Phase Current Open - Occurrence"
        58 -> "R-Phase Current Open - Restoration"
        59 -> "Y-Phase Current Open - Occurrence"
        60 -> "Y-Phase Current Open - Restoration"
        61 -> "B-Phase Current Open - Occurrence"
        62 -> "B-Phase Current Open - Restoration"
        63 -> "Current Unbalance - Occurrence"
        64 -> "Current Unbalance - Restoration"
        67 -> "Over Current - Occurrence"
        68 -> "Over Current - Restoration"
        69 -> "Current Bypass / Short - Occurrence"
        70 -> "Current Bypass / Short - Restoration"
        101 -> "Power Failure - Occurrence"
        102 -> "Power Failure - Restoration"
        151 -> "Real Time Clock Changed"
        152 -> "Demand Integration Period Changed"
        153 -> "Profile Capture Period Changed"
        154 -> "Single-Action Billing Schedule Changed"
        155 -> "Activity Calendar (ToD) Changed"
        201 -> "Magnetic Influence - Occurrence"
        202 -> "Magnetic Influence - Restoration"
        203 -> "Neutral Disturbance - Occurrence"
        204 -> "Neutral Disturbance - Restoration"
        205 -> "Low Power Factor - Occurrence"
        206 -> "Low Power Factor - Restoration"
        207 -> "Single Wire / Earth Load - Occurrence"
        208 -> "Single Wire / Earth Load - Restoration"
        251 -> "Meter Cover Open - Occurrence"
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

    /** Returns the meter scaler only. Display-unit conversion is done by formatValue. */
    fun normalizedScaler(scaler: Double, unit: DlmsUnit?): Double = scaler

    private fun displayUnit(unit: DlmsUnit?, obis: String?): String =
        unitSymbol(unit).ifEmpty { obisUnitSymbol(obis) }

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
