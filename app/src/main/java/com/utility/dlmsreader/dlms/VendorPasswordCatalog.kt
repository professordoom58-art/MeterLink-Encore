package com.utility.dlmsreader.dlms

data class VendorProfile(
    val displayName: String,
    val defaultPasswords: List<String>,
    val defaultPrimaryPassword: String
)

object VendorPasswordCatalog {
    val VENDORS = listOf(
        VendorProfile(
            displayName = "L&T / Schneider",
            defaultPasswords = listOf("lnt1", "00000000", "12345678"),
            defaultPrimaryPassword = "lnt1"
        ),
        VendorProfile(
            displayName = "Genus Power",
            defaultPasswords = listOf("1A2B3C4D", "GENUS123", "11111111"),
            defaultPrimaryPassword = "1A2B3C4D"
        ),
        VendorProfile(
            displayName = "Secure Meters",
            defaultPasswords = listOf("ABCD0001", "00000000", "SECURE12"),
            defaultPrimaryPassword = "ABCD0001"
        ),
        VendorProfile(
            displayName = "HPL Electric",
            defaultPasswords = listOf("00000000", "HPL12345"),
            defaultPrimaryPassword = "HPL12345"
        ),
        VendorProfile(
            displayName = "AVON",
            defaultPasswords = listOf("1111111111111111", "12345678", "11111111"),
            defaultPrimaryPassword = "1111111111111111"
        ),
        VendorProfile(
            displayName = "Custom Password",
            defaultPasswords = emptyList(),
            defaultPrimaryPassword = ""
        )
    )
}
