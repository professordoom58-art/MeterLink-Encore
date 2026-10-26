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

    fun formatValue(raw: Any?, scaler: Double = 1.0, unit: DlmsUnit? = null): String = when (raw) {
        null -> "N/A"
        is GXDateTime -> formatDateTime(raw)
        is ByteArray -> formatBytes(raw)
        is Number -> {
            val value = raw.toDouble() * scaler
            val suffix = unitSymbol(unit)
            numberFormat.format(value) + if (suffix.isEmpty()) "" else " $suffix"
        }
        is Array<*> -> raw.joinToString(", ") { formatValue(it) }
        is Iterable<*> -> raw.joinToString(", ") { formatValue(it) }
        else -> raw.toString().trim().ifEmpty { "N/A" }
    }

    fun formatDateTime(raw: GXDateTime): String = runCatching {
        val calendar = raw.getLocalCalendar()
        val year = calendar.get(Calendar.YEAR)
        if (year < 2000 || year > 2200) "N/A" else dateFormat.format(calendar.time)
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
        obis == "0.0.96.1.4.255" -> ObisDescriptor("Firmware Version")
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
        7 -> "Voltage Unbalance - Occurrence"
        8 -> "Voltage Unbalance - Restoration"
        51 -> "R-Phase Current Reversal - Occurrence"
        52 -> "R-Phase Current Reversal - Restoration"
        69 -> "Current Bypass/Short - Occurrence"
        101 -> "Power Failure - Occurrence"
        102 -> "Power Failure - Restoration"
        201 -> "Magnetic Influence - Occurrence"
        202 -> "Magnetic Influence - Restoration"
        207 -> "Neutral Disturbance - Occurrence"
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

    fun normalizedScaler(scaler: Double, unit: DlmsUnit?): Double = when (unit) {
        DlmsUnit.ACTIVE_ENERGY,
        DlmsUnit.APPARENT_ENERGY,
        DlmsUnit.REACTIVE_ENERGY,
        DlmsUnit.ACTIVE_POWER,
        DlmsUnit.APPARENT_POWER,
        DlmsUnit.REACTIVE_POWER -> scaler / 1000.0
        else -> scaler
    }
}
