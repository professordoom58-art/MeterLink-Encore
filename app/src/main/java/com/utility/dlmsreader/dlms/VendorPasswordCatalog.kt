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
            defaultPasswords = listOf("00000000", "GENUS123", "11111111"),
            defaultPrimaryPassword = "00000000"
        ),
        VendorProfile(
            displayName = "Secure Meters",
            defaultPasswords = listOf("00000000", "ABCD0001", "SECURE12"),
            defaultPrimaryPassword = "00000000"
        ),
        VendorProfile(
            displayName = "HPL Electric",
            defaultPasswords = listOf("00000000", "HPL12345"),
            defaultPrimaryPassword = "00000000"
        ),
        VendorProfile(
            displayName = "Generic / Default",
            defaultPasswords = listOf("00000000", "12345678", "11111111"),
            defaultPrimaryPassword = "00000000"
        ),
        VendorProfile(
            displayName = "Custom Password",
            defaultPasswords = emptyList(),
            defaultPrimaryPassword = ""
        )
    )
}
