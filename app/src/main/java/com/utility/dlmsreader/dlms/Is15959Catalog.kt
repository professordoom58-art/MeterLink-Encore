package com.utility.dlmsreader.dlms

data class ProfilePair(
    val name: String,
    val dataObis: String,
    val scalerObis: String? = null
)

object Is15959Catalog {
    const val OBIS_INVOCATION_COUNTER_MR = "0.0.43.1.2.255"
    const val OBIS_INVOCATION_COUNTER_US = "0.0.43.1.3.255"

    val DEFAULT_DEDICATED_KEY = byteArrayOf(
        0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x00, 0x01,
        0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09
    )

    val PROFILES = listOf(
        ProfilePair("Nameplate Profile", "0.0.94.91.10.255", null),
        ProfilePair("Instantaneous Profile", "1.0.94.91.0.255", "1.0.94.91.3.255"),
        ProfilePair("Billing Profile", "1.0.98.1.0.255", "1.0.94.91.6.255"),
        ProfilePair("Block Load Profile", "1.0.99.1.0.255", "1.0.94.91.4.255"),
        ProfilePair("Daily Load Profile", "1.0.99.2.0.255", "1.0.94.91.5.255"),
        ProfilePair("Voltage Events", "0.0.99.98.0.255", "1.0.94.91.7.255"),
        ProfilePair("Current Events", "0.0.99.98.1.255", "1.0.94.91.7.255"),
        ProfilePair("Power Fail Events", "0.0.99.98.2.255", "1.0.94.91.7.255"),
        ProfilePair("Transaction Events", "0.0.99.98.3.255", "1.0.94.91.7.255"),
        ProfilePair("Other Events", "0.0.99.98.4.255", "1.0.94.91.7.255"),
        ProfilePair("Non-Rollover Events", "0.0.99.98.5.255", "1.0.94.91.7.255"),
        ProfilePair("Control Events", "0.0.99.98.6.255", "1.0.94.91.7.255")
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
