package com.utility.dlmsreader.ui

/** The six tappable summary sections and their compact card labels. */
internal enum class PreviewSection(val shortLabel: String, val title: String) {
    NAMEPLATE("Nameplate", "Nameplate"),
    INSTANTANEOUS("Instant", "Instantaneous Parameters"),
    BILLING("Billing", "Billing History"),
    DAILY("Daily", "Daily Load Survey"),
    BLOCK("Block", "Block Load Survey"),
    TAMPER("Tamper", "Tamper Events")
}
