package com.utility.dlmsreader.dlms

enum class ObisCategory {
    IDENTITY,
    NAMEPLATE,
    CONFIGURATION,
    INSTANTANEOUS,
    BILLING,
    LOAD_SURVEY,
    EVENT_CODE,
    EVENT_PROFILE,
    TAMPER,
    VENDOR_SPECIFIC,
    UNKNOWN
}

data class ObisMetadata(
    val label: String,
    val unit: String = "",
    val category: ObisCategory = ObisCategory.UNKNOWN
)

data class ProfilePair(
    val name: String,
    val dataObis: String,
    val scalerObis: String? = null,
    val category: ObisCategory = ObisCategory.UNKNOWN
)

object Is15959Catalog {
    const val OBIS_INVOCATION_COUNTER_MR = "0.0.43.1.2.255"
    const val OBIS_INVOCATION_COUNTER_US = "0.0.43.1.3.255"

    const val OBIS_NAMEPLATE_PROFILE = "0.0.94.91.10.255"
    const val OBIS_INSTANTANEOUS_PROFILE = "1.0.94.91.0.255"
    const val OBIS_INSTANTANEOUS_SCALER_PROFILE = "1.0.94.91.3.255"
    const val OBIS_BILLING_PROFILE = "1.0.98.1.0.255"
    const val OBIS_BILLING_SCALER_PROFILE = "1.0.94.91.6.255"
    const val OBIS_BLOCK_LOAD_PROFILE = "1.0.99.1.0.255"
    const val OBIS_BLOCK_LOAD_SCALER_PROFILE = "1.0.94.91.4.255"
    const val OBIS_DAILY_LOAD_PROFILE = "1.0.99.2.0.255"
    const val OBIS_DAILY_LOAD_SCALER_PROFILE = "1.0.94.91.5.255"
    const val OBIS_EVENT_SCALER_PROFILE = "1.0.94.91.7.255"

    const val OBIS_AME_VENDOR_EVENT_PROFILE = "0.0.99.98.7.255"
    const val OBIS_BILLING_RESET_TYPE = "0.128.171.128.128.255"
    const val OBIS_RESET_TYPE = "0.128.153.128.128.255"
    const val OBIS_MDC_UNI_BID = "0.128.161.128.128.255"

    /**
     * Labels that are stable enough to use before a meter RES payload is loaded.
     * Runtime RES metadata must take precedence over this map.
    */
    val STATIC_OBIS_METADATA: Map<String, ObisMetadata> = mapOf(
        "0.0.42.0.0.255" to ObisMetadata("Logical Device Name", category = ObisCategory.IDENTITY),
        "0.0.96.1.0.255" to ObisMetadata("Meter Serial Number", category = ObisCategory.NAMEPLATE),
        "0.0.96.1.1.255" to ObisMetadata("Manufacturer Name", category = ObisCategory.NAMEPLATE),
        "0.0.96.1.4.255" to ObisMetadata("Year of Manufacture", category = ObisCategory.NAMEPLATE),
        "1.0.0.2.0.255" to ObisMetadata("Firmware Version", category = ObisCategory.NAMEPLATE),
        "0.0.94.91.9.255" to ObisMetadata(
            "Meter Type (5=1P-2W, 1=3P-3W, 2=3P-4W)",
            category = ObisCategory.NAMEPLATE
        ),
        "0.0.94.91.11.255" to ObisMetadata("Meter Category", category = ObisCategory.NAMEPLATE),
        "0.0.94.91.12.255" to ObisMetadata("Current Rating", category = ObisCategory.NAMEPLATE),
        "0.0.94.91.14.255" to ObisMetadata(
            "Cumulative Power-On Duration (min)",
            "min",
            ObisCategory.NAMEPLATE
        ),
        "0.0.96.2.0.255" to ObisMetadata("Cumulative Programming Count", category = ObisCategory.CONFIGURATION),
        "0.128.169.128.128.255" to ObisMetadata("L&T Config ID", category = ObisCategory.VENDOR_SPECIFIC),
        "0.128.176.128.128.255" to ObisMetadata(
            "Meter Constant (imp/kWh)",
            category = ObisCategory.VENDOR_SPECIFIC
        ),
        "0.0.0.1.2.255" to ObisMetadata("Billing Date", category = ObisCategory.BILLING),
        "1.0.13.0.0.255" to ObisMetadata("Average Billing Power Factor", "PF", ObisCategory.BILLING),
        "0.0.1.0.0.255" to ObisMetadata("Real-Time Clock", category = ObisCategory.IDENTITY),

        "1.0.12.7.0.255" to ObisMetadata("R-Phase / Phase Voltage (V)", "V", ObisCategory.INSTANTANEOUS),
        "1.0.32.7.0.255" to ObisMetadata("R-Phase / Phase Voltage (V)", "V", ObisCategory.INSTANTANEOUS),
        "1.0.52.7.0.255" to ObisMetadata("Y-Phase Voltage (V)", "V", ObisCategory.INSTANTANEOUS),
        "1.0.72.7.0.255" to ObisMetadata("B-Phase Voltage (V)", "V", ObisCategory.INSTANTANEOUS),
        "1.0.11.7.0.255" to ObisMetadata("R-Phase / Phase Current (A)", "A", ObisCategory.INSTANTANEOUS),
        "1.0.31.7.0.255" to ObisMetadata("R-Phase / Phase Current (A)", "A", ObisCategory.INSTANTANEOUS),
        "1.0.51.7.0.255" to ObisMetadata("Y-Phase Current (A)", "A", ObisCategory.INSTANTANEOUS),
        "1.0.71.7.0.255" to ObisMetadata("B-Phase Current (A)", "A", ObisCategory.INSTANTANEOUS),
        "1.0.91.7.0.255" to ObisMetadata("Neutral Current (A)", "A", ObisCategory.INSTANTANEOUS),
        "1.0.94.91.14.255" to ObisMetadata("Current (A)", "A", ObisCategory.INSTANTANEOUS),
        "1.0.13.7.0.255" to ObisMetadata("Signed Power Factor", "PF", ObisCategory.INSTANTANEOUS),
        "1.0.33.7.0.255" to ObisMetadata("R / Y / B Phase PF", "PF", ObisCategory.INSTANTANEOUS),
        "1.0.53.7.0.255" to ObisMetadata("R / Y / B Phase PF", "PF", ObisCategory.INSTANTANEOUS),
        "1.0.73.7.0.255" to ObisMetadata("R / Y / B Phase PF", "PF", ObisCategory.INSTANTANEOUS),
        "1.0.14.7.0.255" to ObisMetadata("Frequency (Hz)", "Hz", ObisCategory.INSTANTANEOUS),
        "1.0.1.7.0.255" to ObisMetadata("Active Power (kW)", "kW", ObisCategory.INSTANTANEOUS),
        "1.0.9.7.0.255" to ObisMetadata("Apparent Power (kVA)", "kVA", ObisCategory.INSTANTANEOUS),
        "1.0.3.7.0.255" to ObisMetadata("Reactive Power (kVAr)", "kVAr", ObisCategory.INSTANTANEOUS),

        "1.0.1.8.0.255" to ObisMetadata(
            "Cumulative Active Energy - Import (kWh)",
            "kWh",
            ObisCategory.BILLING
        ),
        "1.0.2.8.0.255" to ObisMetadata(
            "Cumulative Active Energy - Export (kWh)",
            "kWh",
            ObisCategory.BILLING
        ),
        "1.0.9.8.0.255" to ObisMetadata(
            "Cumulative Apparent Energy - Import (kVAh)",
            "kVAh",
            ObisCategory.BILLING
        ),
        "1.0.10.8.0.255" to ObisMetadata(
            "Cumulative Apparent Energy - Export (kVAh)",
            "kVAh",
            ObisCategory.BILLING
        ),
        "1.0.5.8.0.255" to ObisMetadata("Reactive Energy - Lag (kVArh)", "kVArh", ObisCategory.BILLING),
        "1.0.3.8.0.255" to ObisMetadata("Reactive Energy - Lag (kVArh)", "kVArh", ObisCategory.BILLING),
        "1.0.8.8.0.255" to ObisMetadata("Reactive Energy - Lead (kVArh)", "kVArh", ObisCategory.BILLING),
        "1.0.4.8.0.255" to ObisMetadata("Reactive Energy - Lead (kVArh)", "kVArh", ObisCategory.BILLING),
        "1.0.1.6.0.255" to ObisMetadata("Maximum Demand - Active (kW)", "kW", ObisCategory.BILLING),
        "1.0.9.6.0.255" to ObisMetadata("Maximum Demand - Apparent (kVA)", "kVA", ObisCategory.BILLING),
        "1.0.1.2.0.255" to ObisMetadata("Cum. MD Active (kW)", "kW", ObisCategory.BILLING),
        "1.0.9.2.0.255" to ObisMetadata("Cum. MD Apparent (kVA)", "kVA", ObisCategory.BILLING),
        "1.0.128.8.38.255" to ObisMetadata("Billing Reset Type / Status", category = ObisCategory.BILLING),
        "0.0.94.91.0.255" to ObisMetadata("Cumulative Tamper Count", category = ObisCategory.TAMPER),
        "0.0.0.1.0.255" to ObisMetadata("Billing Reset Count", category = ObisCategory.BILLING),
        "0.0.94.91.13.255" to ObisMetadata(
            "Cumulative Power-On Duration (Min)",
            "min",
            ObisCategory.NAMEPLATE
        ),

        OBIS_NAMEPLATE_PROFILE to ObisMetadata("Nameplate Profile", category = ObisCategory.NAMEPLATE),
        OBIS_INSTANTANEOUS_PROFILE to ObisMetadata("Instantaneous Profile", category = ObisCategory.INSTANTANEOUS),
        OBIS_INSTANTANEOUS_SCALER_PROFILE to ObisMetadata(
            "Instantaneous Scaler Profile",
            category = ObisCategory.INSTANTANEOUS
        ),
        OBIS_BILLING_PROFILE to ObisMetadata("Billing Profile", category = ObisCategory.BILLING),
        OBIS_BILLING_SCALER_PROFILE to ObisMetadata("Billing Scaler Profile", category = ObisCategory.BILLING),
        OBIS_BLOCK_LOAD_PROFILE to ObisMetadata("Block Load Profile", category = ObisCategory.LOAD_SURVEY),
        OBIS_BLOCK_LOAD_SCALER_PROFILE to ObisMetadata(
            "Block Load Scaler Profile",
            category = ObisCategory.LOAD_SURVEY
        ),
        OBIS_DAILY_LOAD_PROFILE to ObisMetadata("Daily Load Profile", category = ObisCategory.LOAD_SURVEY),
        OBIS_DAILY_LOAD_SCALER_PROFILE to ObisMetadata(
            "Daily Load Scaler Profile",
            category = ObisCategory.LOAD_SURVEY
        ),
        OBIS_EVENT_SCALER_PROFILE to ObisMetadata("Event Scaler Profile", category = ObisCategory.EVENT_PROFILE),

        "0.0.99.98.0.255" to ObisMetadata("Voltage Events Profile", category = ObisCategory.TAMPER),
        "0.0.99.98.1.255" to ObisMetadata("Current Events Profile", category = ObisCategory.TAMPER),
        "0.0.99.98.2.255" to ObisMetadata("Power Failure Events Profile", category = ObisCategory.TAMPER),
        "0.0.99.98.3.255" to ObisMetadata("Transaction Events Profile", category = ObisCategory.TAMPER),
        "0.0.99.98.4.255" to ObisMetadata("Other Events Profile", category = ObisCategory.TAMPER),
        "0.0.99.98.5.255" to ObisMetadata("Non-Rollover Events Profile", category = ObisCategory.TAMPER),
        "0.0.99.98.6.255" to ObisMetadata("Control Events Profile", category = ObisCategory.TAMPER),

        "0.0.96.11.0.255" to ObisMetadata("Voltage Event Code", category = ObisCategory.EVENT_CODE),
        "0.0.96.11.1.255" to ObisMetadata("Current Event Code", category = ObisCategory.EVENT_CODE),
        "0.0.96.11.3.255" to ObisMetadata("Transaction Event Code", category = ObisCategory.EVENT_CODE),
        "0.0.96.11.4.255" to ObisMetadata("Other Event Code", category = ObisCategory.EVENT_CODE),
        "0.0.96.11.5.255" to ObisMetadata("Non-Rollover Event Code", category = ObisCategory.EVENT_CODE),

        // These names are present in the SMRS load-JSON special cases. Their
        // units are semantic and do not depend on a vendor's RES payload.
        "1.0.72.27.0.255" to ObisMetadata("Voltage Y-N", "V", ObisCategory.LOAD_SURVEY),
        "1.0.32.27.0.255" to ObisMetadata("Voltage R-N", "V", ObisCategory.LOAD_SURVEY),
        "1.0.51.27.0.255" to ObisMetadata("Voltage B-N", "V", ObisCategory.LOAD_SURVEY),
        "1.0.31.27.0.255" to ObisMetadata("Current R", "A", ObisCategory.LOAD_SURVEY),
        "1.0.52.27.0.255" to ObisMetadata("Current B", "A", ObisCategory.LOAD_SURVEY),
        "1.0.71.27.0.255" to ObisMetadata("Current Y", "A", ObisCategory.LOAD_SURVEY),
        "1.0.12.27.0.255" to ObisMetadata("Average Voltage (V)", "V", ObisCategory.LOAD_SURVEY),
        "1.0.11.27.0.255" to ObisMetadata("Load Survey Object", category = ObisCategory.LOAD_SURVEY),
        "1.0.1.29.0.255" to ObisMetadata("Block Energy - Active Import (kWh)", "kWh", ObisCategory.LOAD_SURVEY),
        "1.0.9.29.0.255" to ObisMetadata("Block Energy - Apparent Import (kVAh)", "kVAh", ObisCategory.LOAD_SURVEY),
        // TODO: confirm with L&T documentation.
        "1.0.128.8.98.255" to ObisMetadata(
            "Vendor Value (L&T) [unverified: looks like meter temperature, °C]",
            category = ObisCategory.VENDOR_SPECIFIC
        ),
        "1.0.84.0.0.255" to ObisMetadata("Billing XML Object", category = ObisCategory.BILLING),
        "1.0.29.0.0.255" to ObisMetadata("Block-Load Active Energy", category = ObisCategory.LOAD_SURVEY),

        // The decompiled source identifies these as OBIS_LIST/vendor objects,
        // but does not contain authoritative labels or scaler/unit metadata.
        "0.0.40.0.0.255" to ObisMetadata("RES-Configured Object", category = ObisCategory.CONFIGURATION),
        "0.0.22.0.0.255" to ObisMetadata("RES-Configured Object", category = ObisCategory.CONFIGURATION),
        "1.0.0.4.2.255" to ObisMetadata("RES-Configured Object", category = ObisCategory.CONFIGURATION),
        "1.0.0.4.3.255" to ObisMetadata("RES-Configured Object", category = ObisCategory.CONFIGURATION),
        "0.0.15.0.0.255" to ObisMetadata("RES-Configured Object", category = ObisCategory.CONFIGURATION),
        "0.0.13.0.0.255" to ObisMetadata("RES-Configured Object", category = ObisCategory.CONFIGURATION),
        "0.128.141.141.141.255" to ObisMetadata(
            "Vendor-Specific Object",
            category = ObisCategory.VENDOR_SPECIFIC
        ),
        "0.128.140.128.128.255" to ObisMetadata(
            "Vendor-Specific Object",
            category = ObisCategory.VENDOR_SPECIFIC
        ),
        "0.128.141.128.128.255" to ObisMetadata(
            "Vendor-Specific Object",
            category = ObisCategory.VENDOR_SPECIFIC
        ),
        "0.128.160.128.128.255" to ObisMetadata(
            "Vendor-Specific Object",
            category = ObisCategory.VENDOR_SPECIFIC
        ),
        "0.128.151.128.128.255" to ObisMetadata(
            "Vendor-Specific Object",
            category = ObisCategory.VENDOR_SPECIFIC
        ),
        "1.0.128.8.0.255" to ObisMetadata(
            "Vendor-Specific Object",
            category = ObisCategory.VENDOR_SPECIFIC
        ),
        OBIS_BILLING_RESET_TYPE to ObisMetadata(
            "Billing Reset Type",
            category = ObisCategory.VENDOR_SPECIFIC
        ),
        OBIS_RESET_TYPE to ObisMetadata("Reset Type", category = ObisCategory.VENDOR_SPECIFIC),
        OBIS_MDC_UNI_BID to ObisMetadata("MDC_UNI-BID", category = ObisCategory.VENDOR_SPECIFIC),
        OBIS_AME_VENDOR_EVENT_PROFILE to ObisMetadata(
            "Vendor Event Profile",
            category = ObisCategory.VENDOR_SPECIFIC
        )
    )

    /**
     * The SMRS fallback calls this object `avon-t1` only for AME meters.
     * Keep that label vendor-scoped instead of making it global for Genus,
     * Secure, HPL, and other meter families.
     */
    private val VENDOR_RES_METADATA: Map<String, Map<String, ObisMetadata>> = mapOf(
        "ame" to mapOf(
            OBIS_AME_VENDOR_EVENT_PROFILE to ObisMetadata(
                "avon-t1",
                category = ObisCategory.VENDOR_SPECIFIC
            )
        )
    )

    fun metadataFor(
        obis: String,
        runtimeOverrides: Map<String, ObisMetadata> = emptyMap(),
        meterMake: String? = null
    ): ObisMetadata? {
        runtimeOverrides[obis]?.let { return it }
        val vendorKey = meterMake?.trim()?.lowercase()
        VENDOR_RES_METADATA[vendorKey]?.get(obis)?.let { return it }
        return STATIC_OBIS_METADATA[obis]
    }

    val DEFAULT_DEDICATED_KEY = byteArrayOf(
        0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x00, 0x01,
        0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09
    )

    val PROFILES = listOf(
        ProfilePair("Nameplate Profile", OBIS_NAMEPLATE_PROFILE, null, ObisCategory.NAMEPLATE),
        ProfilePair(
            "Instantaneous Profile",
            OBIS_INSTANTANEOUS_PROFILE,
            OBIS_INSTANTANEOUS_SCALER_PROFILE,
            ObisCategory.INSTANTANEOUS
        ),
        ProfilePair("Billing Profile", OBIS_BILLING_PROFILE, OBIS_BILLING_SCALER_PROFILE, ObisCategory.BILLING),
        ProfilePair(
            "Block Load Profile",
            OBIS_BLOCK_LOAD_PROFILE,
            OBIS_BLOCK_LOAD_SCALER_PROFILE,
            ObisCategory.LOAD_SURVEY
        ),
        ProfilePair(
            "Daily Load Profile",
            OBIS_DAILY_LOAD_PROFILE,
            OBIS_DAILY_LOAD_SCALER_PROFILE,
            ObisCategory.LOAD_SURVEY
        ),
        ProfilePair("Voltage Events", "0.0.99.98.0.255", OBIS_EVENT_SCALER_PROFILE, ObisCategory.TAMPER),
        ProfilePair("Current Events", "0.0.99.98.1.255", OBIS_EVENT_SCALER_PROFILE, ObisCategory.TAMPER),
        ProfilePair("Power Fail Events", "0.0.99.98.2.255", OBIS_EVENT_SCALER_PROFILE, ObisCategory.TAMPER),
        ProfilePair("Transaction Events", "0.0.99.98.3.255", OBIS_EVENT_SCALER_PROFILE, ObisCategory.TAMPER),
        ProfilePair("Other Events", "0.0.99.98.4.255", OBIS_EVENT_SCALER_PROFILE, ObisCategory.TAMPER),
        ProfilePair(
            "Non-Rollover Events",
            "0.0.99.98.5.255",
            OBIS_EVENT_SCALER_PROFILE,
            ObisCategory.TAMPER
        ),
        ProfilePair("Control Events", "0.0.99.98.6.255", OBIS_EVENT_SCALER_PROFILE, ObisCategory.TAMPER)
    )

    fun profileByName(name: String): ProfilePair =
        PROFILES.firstOrNull { it.name == name }
            ?: error("Unknown IS 15959 profile: $name")

    fun profileByDataObis(obis: String): ProfilePair =
        PROFILES.firstOrNull { it.dataObis == obis }
            ?: error("No IS 15959 profile mapping for $obis")

    fun parseKeyOrSystemTitle(input: String): ByteArray {
        val clean = input.trim()
        if (clean.contains("-")) {
            return clean.split("-")
                .filter { it.isNotBlank() }
                .map { ((it.toInt() xor 0xFF) and 0xFF).toByte() }
                .toByteArray()
        }
        if (clean.length == 32 && clean.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            return ByteArray(16) { i ->
                clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }
        return clean.toByteArray(Charsets.US_ASCII)
    }

    fun requireKey(input: String, name: String): ByteArray =
        parseKeyOrSystemTitle(input).also {
            require(it.size == 16) { "$name must be exactly 16 bytes." }
        }

    fun requireSystemTitle(input: String): ByteArray =
        parseKeyOrSystemTitle(input).also {
            require(it.size == 8) { "System title must be exactly 8 bytes." }
        }
}
