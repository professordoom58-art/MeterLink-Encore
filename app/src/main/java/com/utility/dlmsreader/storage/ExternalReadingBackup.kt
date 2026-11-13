package com.utility.dlmsreader.storage

import com.utility.dlmsreader.dlms.MeterDataReport
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

/**
 * File-system side of the persistent `Documents/MeterLink` backup.
 *
 * This object is deliberately free of Android imports so the naming, JSON
 * round-trip and duplicate detection can be unit tested on the JVM. The
 * database delegates direct-file writes and scans to it; the MediaStore path
 * uses the same names and JSON bytes.
 */
object ExternalReadingBackup {

    const val FOLDER_NAME = "MeterLink"

    /** Current readable name: `Meterlink-<serial>-<scan date>.json`. */
    const val FILE_PREFIX = "Meterlink-"

    /** Name written by builds up to 1.0.57; still read during restore. */
    const val LEGACY_FILE_PREFIX = "MeterLink_Reading_"

    const val FILE_SUFFIX = ".json"

    /** Filesystem-safe scan timestamp used both in names and restore ordering. */
    const val TIMESTAMP_PATTERN = "dd-MMM-yyyy_HH-mm-ss"

    private const val SERIAL_OBIS = "0.0.96.1.0.255"
    private const val UNKNOWN_SERIAL = "Unknown"

    fun sanitizeSerial(serial: String): String {
        val cleaned = serial.trim()
            .replace(Regex("""[^A-Za-z0-9._-]"""), "_")
            .trim('_')
            .trim('.')
        return cleaned.ifBlank { UNKNOWN_SERIAL }
    }

    /**
     * Builds `Meterlink-<serial>-<scan date>.json`, e.g.
     * `Meterlink-92717295-13-Nov-2026_13-23-21.json`.
     *
     * Colons are illegal on FAT32, so the time uses hyphens.
     */
    fun buildMeterJsonFileName(
        serial: String,
        scanTimestampMillis: Long = System.currentTimeMillis()
    ): String {
        val formatted = SimpleDateFormat(TIMESTAMP_PATTERN, Locale.US)
            .format(Date(scanTimestampMillis))
        return "$FILE_PREFIX${sanitizeSerial(serial)}-$formatted$FILE_SUFFIX"
    }

    /** True for both the current and the legacy MeterLink JSON names. */
    fun isMeterLinkJsonFile(name: String): Boolean {
        val lower = name.lowercase(Locale.US)
        return lower.endsWith(FILE_SUFFIX) &&
            (lower.startsWith(FILE_PREFIX.lowercase(Locale.US)) ||
                lower.startsWith(LEGACY_FILE_PREFIX.lowercase(Locale.US)))
    }

    /** Legacy `MeterLink_Reading_<serial>_<id>.json` name. */
    fun legacyFileName(serial: String, id: Long): String =
        "$LEGACY_FILE_PREFIX${sanitizeSerial(serial)}_$id$FILE_SUFFIX"

    /** Row id encoded in a legacy backup file name, or null for other files. */
    fun parseId(name: String): Long? {
        if (!name.startsWith(LEGACY_FILE_PREFIX) || !name.endsWith(FILE_SUFFIX)) return null
        val stem = name.removePrefix(LEGACY_FILE_PREFIX).removeSuffix(FILE_SUFFIX)
        return stem.substringAfterLast('_', "").toLongOrNull()
    }

    /**
     * Scan timestamp embedded in a current file name, or [fallbackMs] for
     * legacy names and unparseable files.
     */
    fun parseScanTimestamp(name: String, fallbackMs: Long = 0L): Long {
        val lower = name.lowercase(Locale.US)
        if (!lower.startsWith(FILE_PREFIX.lowercase(Locale.US))) return fallbackMs
        if (!lower.endsWith(FILE_SUFFIX)) return fallbackMs
        val stem = name.substring(FILE_PREFIX.length)
            .substring(0, name.length - FILE_PREFIX.length - FILE_SUFFIX.length)
        // A serial may itself contain '-', so the timestamp is always the last
        // five dash-separated tokens: dd, MMM, yyyy_HH, mm, ss.
        val parts = stem.split('-')
        if (parts.size < 5) return fallbackMs
        val stamp = parts.takeLast(5).joinToString("-")
        return runCatching {
            SimpleDateFormat(TIMESTAMP_PATTERN, Locale.US)
                .apply { isLenient = false }
                .parse(stamp)
                ?.time
        }.getOrNull() ?: fallbackMs
    }

    fun toJson(report: MeterDataReport): String =
        MeterReportSerializer.toJson(report).toString()

    fun fromJson(text: String): MeterDataReport? =
        runCatching { MeterReportSerializer.fromJson(JSONObject(text)) }.getOrNull()

    /** Writes a readable JSON backup into [directory] and returns the file. */
    fun write(
        directory: File,
        report: MeterDataReport,
        scanTimestampMillis: Long = System.currentTimeMillis()
    ): File {
        directory.mkdirs()
        val file = File(
            directory,
            buildMeterJsonFileName(report.identityValue(SERIAL_OBIS), scanTimestampMillis)
        )
        file.writeText(toJson(report), Charsets.UTF_8)
        return file
    }

    /** All current and legacy MeterLink JSON files, newest first. */
    fun scan(directory: File): List<File> =
        directory.listFiles { candidate ->
            candidate.isFile && isMeterLinkJsonFile(candidate.name)
        }?.sortedByDescending { it.lastModified() }.orEmpty()

    /**
     * Stable content identity used to avoid re-importing the same reading when
     * the SQLite database survives an app update but the backup files remain.
     */
    fun fingerprint(report: MeterDataReport): String = buildString {
        append(report.identityValue(SERIAL_OBIS)).append('|')
        append(report.cumulativeKwhReading()).append('|')
        append(report.cumulativeKvahReading()).append('|')
        append(report.identity?.rows?.joinToString(";") { "${it.obisCode}=${it.formattedValue}" }).append('|')
        append(profileFingerprint(report.instantaneous)).append('|')
        append(profileFingerprint(report.billing)).append('|')
        append(profileFingerprint(report.blockLoad)).append('|')
        append(profileFingerprint(report.dailyLoad)).append('|')
        append(report.tamperEvents.size).append('|')
        report.tamperEvents.firstOrNull()?.let {
            append("${it.compartment}/${it.dateTime}/${it.eventCode}")
        }
    }

    private fun profileFingerprint(
        section: com.utility.dlmsreader.dlms.ProfileSection?
    ): String = section?.let {
        "${it.title}:${it.rows.size}:" +
            (it.rows.firstOrNull()?.joinToString(",") ?: "") + ":" +
            (it.rows.lastOrNull()?.joinToString(",") ?: "")
    }.orEmpty()
}
