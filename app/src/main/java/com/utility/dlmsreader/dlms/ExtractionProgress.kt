package com.utility.dlmsreader.dlms

/**
 * Pure helpers for the percentage-only extraction progress banner.
 *
 * The UI must only ever see `<Section Name> <0..100>%`, so this object owns the
 * formatting and parsing in one testable place. Technical diagnostics (frame
 * numbers, byte counts, tier names) stay in the optical trace log.
 */
object ExtractionProgress {

    const val IDENTITY = "Identity"
    const val ENERGY = "Energy & MD"
    const val INSTANTANEOUS = "Instantaneous"
    const val BILLING = "Billing History"
    const val BLOCK_LOAD = "Block Load Survey"
    const val DAILY_LOAD = "Daily Load Survey"
    const val TAMPER = "Tamper Logs"

    private val TRAILING_PERCENT = Regex("""(\d{1,3})%\s*$""")
    private val EMBEDDED_PERCENT = Regex("""~(\d{1,3})%""")

    /** `Identity 42%` with the percentage clamped to 0..100. */
    fun format(section: String, percent: Int): String =
        "$section ${percent.coerceIn(0, 100)}%"

    /** Percentage for [step] of [total], clamped to 0..100. */
    fun percent(step: Int, total: Int): Int {
        if (total <= 0) return 0
        return ((step.toDouble() / total) * 100.0).toInt().coerceIn(0, 100)
    }

    fun step(section: String, step: Int, total: Int): String =
        format(section, percent(step, total))

    /** Parses the trailing percentage from a banner line such as `Block Load Survey 37%`. */
    fun parsePercent(text: String?): Int? {
        val value = text?.trim().orEmpty()
        return TRAILING_PERCENT.find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?.coerceIn(0, 100)
    }

    /** Parses the `~37%` estimate inside a transport frame-progress line. */
    fun parseEmbeddedPercent(text: String?): Int? {
        val value = text?.trim().orEmpty()
        return EMBEDDED_PERCENT.find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?.coerceIn(0, 100)
    }
}
