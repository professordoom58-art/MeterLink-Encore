package com.utility.dlmsreader.storage

import com.utility.dlmsreader.dlms.MeterDataReport
import java.io.File
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
    const val FILE_PREFIX = "MeterLink_Reading_"
    const val FILE_SUFFIX = ".json"

    private const val SERIAL_OBIS = "0.0.96.1.0.255"
    private const val UNKNOWN_SERIAL = "Unknown"

    fun sanitizeSerial(serial: String): String {
        val cleaned = serial.trim()
            .replace(Regex("""[^A-Za-z0-9._-]"""), "_")
            .trim('_')
            .trim('.')
        return cleaned.ifBlank { UNKNOWN_SERIAL }
    }

    fun fileName(serial: String, id: Long): String =
        "$FILE_PREFIX${sanitizeSerial(serial)}_$id$FILE_SUFFIX"

    /** Row id encoded in a backup file name, or null for unrelated files. */
    fun parseId(name: String): Long? {
        if (!name.startsWith(FILE_PREFIX) || !name.endsWith(FILE_SUFFIX)) return null
        val stem = name.removePrefix(FILE_PREFIX).removeSuffix(FILE_SUFFIX)
        return stem.substringAfterLast('_', "").toLongOrNull()
    }

    fun toJson(report: MeterDataReport): String =
        MeterReportSerializer.toJson(report).toString()

    fun fromJson(text: String): MeterDataReport? =
        runCatching { MeterReportSerializer.fromJson(JSONObject(text)) }.getOrNull()

    /** Writes a readable JSON backup into [directory] and returns the file. */
    fun write(directory: File, report: MeterDataReport, id: Long): File {
        directory.mkdirs()
        val file = File(directory, fileName(report.identityValue(SERIAL_OBIS), id))
        file.writeText(toJson(report), Charsets.UTF_8)
        return file
    }

    /** All `MeterLink_Reading_*.json` files, newest first. */
    fun scan(directory: File): List<File> =
        directory.listFiles { candidate ->
            candidate.isFile &&
                candidate.name.startsWith(FILE_PREFIX) &&
                candidate.name.endsWith(FILE_SUFFIX)
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
