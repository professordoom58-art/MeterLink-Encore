package com.utility.dlmsreader.dlms

import java.math.BigDecimal
import java.util.Locale

/**
 * Vendor-neutral net-metering (bi-directional / rooftop solar) support.
 *
 * Every address below is a standard DLMS / COSEM (IEC 62056) object identifier
 * as laid out in BIS IS 15959 Part 2. No vendor-specific object or vendor app
 * name is involved, so the same code works for every compliant meter.
 *
 * Import and export energy are the two sides of the meter's active and
 * apparent energy channels:
 *
 *  - Import: `1.0.1.8.0.255` (kWh) and `1.0.9.8.0.255` (kVAh)
 *  - Export: `1.0.2.8.0.255` (kWh) and `1.0.10.8.0.255` (kVAh)
 *  - Net:    import − export
 */
object NetMetering {

    /** Class 1 metering mode: `0` uni-directional, `1` bi-directional / net. */
    const val OBIS_METERING_MODE = "0.0.94.96.19.255"

    const val OBIS_ACTIVE_ENERGY_IMPORT = "1.0.1.8.0.255"
    const val OBIS_APPARENT_ENERGY_IMPORT = "1.0.9.8.0.255"
    const val OBIS_ACTIVE_ENERGY_EXPORT = "1.0.2.8.0.255"
    const val OBIS_APPARENT_ENERGY_EXPORT = "1.0.10.8.0.255"

    /** Class 4 maximum-demand registers with capture timestamps. */
    const val OBIS_MD_ACTIVE_EXPORT = "1.0.2.6.0.255"
    const val OBIS_MD_APPARENT_EXPORT = "1.0.10.6.0.255"

    /** Class 3 cumulative maximum demand (export side). */
    const val OBIS_CUM_MD_ACTIVE_EXPORT = "1.0.2.2.0.255"
    const val OBIS_CUM_MD_APPARENT_EXPORT = "1.0.10.2.0.255"

    /** Block-load export capture objects (profile columns, not registers). */
    const val OBIS_BLOCK_ACTIVE_EXPORT = "1.0.2.29.0.255"
    const val OBIS_BLOCK_APPARENT_EXPORT = "1.0.10.29.0.255"

    /** Class 1 average billing power factor on the export channel (unit `PF`). */
    const val OBIS_EXPORT_BILLING_PF = "1.0.84.0.0.255"

    const val LABEL_EXPORT_ACTIVE = "Cumulative Active Energy - Export (kWh)"
    const val LABEL_EXPORT_APPARENT = "Cumulative Apparent Energy - Export (kVAh)"
    const val LABEL_EXPORT_MD_ACTIVE = "Maximum Demand - Active Export (kW)"
    const val LABEL_EXPORT_MD_APPARENT = "Maximum Demand - Apparent Export (kVA)"
    const val LABEL_NET_ACTIVE = "Net Active Energy (kWh)"
    const val LABEL_NET_APPARENT = "Net Apparent Energy (kVAh)"

    /** ToD export zones 1..8 on the active-energy channel (kWh). */
    val EXPORT_ACTIVE_TOD_OBIS: List<String> = (1..8).map { "1.0.2.8.$it.255" }

    /** ToD export zones 1..8 on the apparent-energy channel (kVAh). */
    val EXPORT_APPARENT_TOD_OBIS: List<String> = (1..8).map { "1.0.10.8.$it.255" }

    /** ToD export maximum demand zones 1..8 on the active channel (kW). */
    val EXPORT_MD_ACTIVE_TOD_OBIS: List<String> = (1..8).map { "1.0.2.6.$it.255" }

    /** ToD export maximum demand zones 1..8 on the apparent channel (kVA). */
    val EXPORT_MD_APPARENT_TOD_OBIS: List<String> = (1..8).map { "1.0.10.6.$it.255" }

    /**
     * Registers probed (gracefully) after the import registers. A
     * uni-directional meter answers `ObjectUndefined` or `ReadWriteDenied` for
     * these objects, and the reader simply skips them.
     */
    val EXPORT_REGISTER_OBIS: List<String> = listOf(
        OBIS_ACTIVE_ENERGY_EXPORT,
        OBIS_APPARENT_ENERGY_EXPORT,
        OBIS_MD_ACTIVE_EXPORT,
        OBIS_MD_APPARENT_EXPORT,
        OBIS_CUM_MD_ACTIVE_EXPORT,
        OBIS_CUM_MD_APPARENT_EXPORT
    )

    /** Objects that only exist on a bi-directional meter (probed after the flag is set). */
    val EXPORT_TOD_OBIS: List<String> =
        EXPORT_ACTIVE_TOD_OBIS + EXPORT_APPARENT_TOD_OBIS +
            EXPORT_MD_ACTIVE_TOD_OBIS + EXPORT_MD_APPARENT_TOD_OBIS

    /**
     * Demand / maximum-demand registers expose scaler_unit and capture_time,
     * so a probe may need the class 4 (demand) or class 5 (extended) object
     * rather than a plain class 3 register.
     */
    private val DEMAND_REGISTER = Regex("""^1\.0\.(?:1|2|9|10)\.6\.\d\.255$""")

    /** True when [obis] addresses a maximum-demand register (any ToD zone). */
    fun isDemandRegisterObis(obis: String): Boolean = DEMAND_REGISTER.matches(obis.trim())

    /** True when [obis] is an export energy register (cumulative or ToD zone). */
    fun isExportEnergyObis(obis: String): Boolean =
        obis == OBIS_ACTIVE_ENERGY_EXPORT ||
            obis == OBIS_APPARENT_ENERGY_EXPORT ||
            obis in EXPORT_ACTIVE_TOD_OBIS ||
            obis in EXPORT_APPARENT_TOD_OBIS

    /**
     * `isNetMeter` rule: metering mode 1, or any export energy register present
     * in the live read, imported `.csv` / `.dat` / `.OUT` file or saved report.
     */
    fun isNetMeter(meteringMode: Int?, registers: List<RegisterReading>): Boolean =
        meteringMode == 1 ||
            registers.any { isExportEnergyObis(it.obisCode) } ||
            registers.any { it.parameterName.contains("export", ignoreCase = true) && isEnergyUnit(it.unit) }

    /**
     * `import − export` for one channel, with three-decimal precision, or null
     * when either side is missing or not a number.
     */
    fun net(importValue: String?, exportValue: String?): BigDecimal? {
        val importAmount = parseAmount(importValue) ?: return null
        val exportAmount = parseAmount(exportValue) ?: return null
        return importAmount.subtract(exportAmount)
    }

    /** Three-decimal string for a net value, e.g. `5,342.500`. */
    fun formatNet(amount: BigDecimal): String = UnitNormalization.format(amount)

    /**
     * Net value for one channel after normalizing both sides to their kilo unit
     * (so an imported `7443000 WH` and a live `7,443.000 kWh` agree).
     */
    fun net(
        registers: List<RegisterReading>,
        importObis: String,
        exportObis: String
    ): BigDecimal? {
        val importAmount = normalizedAmount(registers.firstOrNull { it.obisCode == importObis })
            ?: return null
        val exportAmount = normalizedAmount(registers.firstOrNull { it.obisCode == exportObis })
            ?: return null
        return importAmount.subtract(exportAmount)
    }

    /**
     * Net value for one channel as reported on the UI cards, PDF and Excel.
     * Returns an empty string when the reading is not a net-meter reading or
     * one of the two sides is missing.
     */
    fun netText(
        registers: List<RegisterReading>,
        importObis: String,
        exportObis: String
    ): String {
        val amount = net(registers, importObis, exportObis) ?: return ""
        return formatNet(amount)
    }

    /**
     * Rows that a net-metering report needs in Section 2 but that the meter may
     * not have returned: the export energy channels, the export maximum demand
     * channels and the computed net energy channels.
     */
    fun derivedRegisters(report: MeterDataReport): List<RegisterReading> {
        val registers = report.cumulativeEnergies
        val existing = registers.map { it.obisCode.ifBlank { it.parameterName } }.toSet()
        val rows = mutableListOf<RegisterReading>()

        fun placeholder(obis: String, label: String, unit: String) {
            if (existing.contains(obis)) return
            if (registers.any { it.parameterName == label }) return
            rows += RegisterReading(label, obis, "N/A", unit)
        }

        placeholder(OBIS_ACTIVE_ENERGY_EXPORT, LABEL_EXPORT_ACTIVE, "kWh")
        placeholder(OBIS_APPARENT_ENERGY_EXPORT, LABEL_EXPORT_APPARENT, "kVAh")
        placeholder(OBIS_MD_ACTIVE_EXPORT, LABEL_EXPORT_MD_ACTIVE, "kW")
        placeholder(OBIS_MD_APPARENT_EXPORT, LABEL_EXPORT_MD_APPARENT, "kVA")

        val netActive = netText(registers, OBIS_ACTIVE_ENERGY_IMPORT, OBIS_ACTIVE_ENERGY_EXPORT)
        if (netActive.isNotEmpty() && registers.none { it.parameterName == LABEL_NET_ACTIVE }) {
            rows += RegisterReading(LABEL_NET_ACTIVE, "", netActive, "kWh")
        }
        val netApparent = netText(registers, OBIS_APPARENT_ENERGY_IMPORT, OBIS_APPARENT_ENERGY_EXPORT)
        if (netApparent.isNotEmpty() && registers.none { it.parameterName == LABEL_NET_APPARENT }) {
            rows += RegisterReading(LABEL_NET_APPARENT, "", netApparent, "kVAh")
        }
        return rows
    }

    /**
     * Appends the net-metering Section 2 rows to [report] when the reading came
     * from a bi-directional meter. Idempotent: running it twice adds nothing.
     */
    fun apply(report: MeterDataReport): MeterDataReport {
        if (!isNetMeter(report.meteringMode, report.cumulativeEnergies)) return report
        val derived = derivedRegisters(report)
        if (derived.isEmpty()) return report
        return report.copy(cumulativeEnergies = report.cumulativeEnergies + derived)
    }

    private fun parseAmount(value: String?): BigDecimal? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty() || text == "-" || text.equals("N/A", ignoreCase = true)) return null
        val match = Regex("""[+-]?\d[\d,]*(?:\.\d+)?""").find(text) ?: return null
        return match.value.replace(",", "").toBigDecimalOrNull()
    }

    /** Reading value converted to its report unit (Wh -> kWh, W -> kW, ...). */
    private fun normalizedAmount(reading: RegisterReading?): BigDecimal? {
        if (reading == null) return null
        val normalized = UnitNormalization.normalize(reading.reading, reading.unit)
        return parseAmount(normalized.value)
    }

    private fun isEnergyUnit(unit: String): Boolean {
        val key = unit.trim().lowercase(Locale.ENGLISH)
        return key in setOf("kwh", "wh", "mwh", "kvah", "vah", "mvah")
    }
}
