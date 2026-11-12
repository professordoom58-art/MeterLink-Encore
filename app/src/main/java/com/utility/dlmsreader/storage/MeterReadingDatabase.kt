package com.utility.dlmsreader.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.utility.dlmsreader.dlms.MeterDataReport
import com.utility.dlmsreader.dlms.ProfileSection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Lightweight row used by the saved-readings list (never includes the BLOB). */
data class SavedReadingSummary(
    val id: Long,
    val serialNumber: String,
    val ldn: String,
    val manufacturer: String,
    val meterRtc: String,
    val scannedAtEpochMs: Long,
    val scannedAtFormatted: String,
    val readType: String,
    val kwhReading: String,
    val summaryStats: String
)

/** Metadata extracted from a report before it is persisted. */
data class MeterReadingMetadata(
    val serialNumber: String,
    val ldn: String,
    val manufacturer: String,
    val meterRtc: String,
    val kwhReading: String,
    val summaryStats: String
)

private const val SERIAL_OBIS = "0.0.96.1.0.255"
private const val UNKNOWN_SERIAL = "Unknown"

/** Identity value for an OBIS code, or an empty string when it was not read. */
fun MeterDataReport.identityValue(obis: String): String =
    identity?.rows?.firstOrNull { it.obisCode == obis }?.formattedValue?.trim().orEmpty()

/** "7,443.000 kWh"-style cumulative active-energy reading. */
fun MeterDataReport.cumulativeKwhReading(): String =
    cumulativeReading("1.0.1.8.0.255", "kWh")

/** Cumulative reactive-energy reading (kVAh). */
fun MeterDataReport.cumulativeKvahReading(): String =
    cumulativeReading("1.0.9.8.0.255", "kVAh")

private fun MeterDataReport.cumulativeReading(obis: String, fallbackUnit: String): String {
    val row = cumulativeEnergies.firstOrNull { it.obisCode == obis }
        ?: cumulativeEnergies.firstOrNull { candidate ->
            when (fallbackUnit) {
                "kWh" -> candidate.parameterName.contains("active energy", ignoreCase = true) &&
                    !candidate.parameterName.contains("demand", ignoreCase = true)
                "kVAh" -> candidate.parameterName.contains("reactive energy", ignoreCase = true) ||
                    candidate.unit.equals(fallbackUnit, ignoreCase = true)
                else -> candidate.unit.equals(fallbackUnit, ignoreCase = true)
            }
        }
        ?: return "N/A"
    val reading = row.reading.trim()
    if (reading.isEmpty()) return "N/A"
    val unit = row.unit.ifBlank { fallbackUnit }
    return listOf(reading, unit).filter { it.isNotBlank() }.joinToString(" ")
}

/**
 * Values recorded in one profile row.
 *
 * An instantaneous snapshot keeps every parameter as a column of a single row,
 * so this value count is the parameter count, not the row count.
 */
fun ProfileSection.recordedValueCount(): Int =
    rows.firstOrNull()?.size?.takeIf { it > 0 } ?: columns.size

/** Number of instantaneous parameters in the captured snapshot. */
fun MeterDataReport.instantaneousParameterCount(): Int =
    instantaneous?.recordedValueCount() ?: 0

/**
 * Nameplate field count for the summary card.
 *
 * Falls back to the identity rows when the meter exposes no nameplate profile.
 */
fun MeterDataReport.nameplateFieldCount(): Int =
    identity?.nameplate?.recordedValueCount() ?: identity?.rows?.size ?: 0

/** Compact one-line statistics for the saved-readings list. */
fun MeterDataReport.summaryStats(): String = buildList {
    dailyLoad?.let { add("Daily: ${it.totalRows} rows") }
    blockLoad?.let { add("Block: ${it.totalRows} rows") }
    if (tamperEvents.isNotEmpty()) add("Tamper: ${tamperEvents.size}")
    instantaneousParameterCount().takeIf { it > 0 }?.let { add("$it params") }
    // Only the nameplate profile counts as nameplate fields; identity rows are
    // separate values and must not be relabelled here.
    (identity?.nameplate?.recordedValueCount() ?: 0).takeIf { it > 0 }
        ?.let { add("$it fields") }
    if (cumulativeEnergies.isNotEmpty()) add("${cumulativeEnergies.size} regs")
}.joinToString(" | ").ifEmpty { "No profile rows" }

/**
 * Combines the newest stored report for a meter with [incoming] before saving.
 *
 * A module read only fills the sections it fetched, so storing a partial
 * Instantaneous or Tamper read as-is would drop earlier sections such as a
 * 3,600-row block-load buffer. Sections missing from [incoming] are carried over
 * from [stored], while sections that were just read win. Reports that belong to
 * a different serial number are never blended.
 */
fun mergeSavedReport(stored: MeterDataReport?, incoming: MeterDataReport): MeterDataReport {
    if (stored == null) return incoming
    val storedSerial = stored.identityValue(SERIAL_OBIS)
    val incomingSerial = incoming.identityValue(SERIAL_OBIS)
    if (storedSerial.isNotBlank() &&
        incomingSerial.isNotBlank() &&
        storedSerial != incomingSerial
    ) {
        return incoming
    }
    val merged = stored.merge(incoming)
    // Errors accumulate across sessions; keep each distinct message once.
    return merged.copy(errors = merged.errors.distinct())
}

/** Extracts the metadata columns stored alongside the compressed report. */
fun MeterDataReport.toReadingMetadata(): MeterReadingMetadata = MeterReadingMetadata(
    serialNumber = identityValue("0.0.96.1.0.255").ifBlank { "Unknown" },
    ldn = identityValue("0.0.42.0.0.255").ifBlank { "Unknown" },
    manufacturer = identityValue("0.0.96.1.1.255").ifBlank { "Unknown" },
    meterRtc = identityValue("0.0.1.0.0.255").ifBlank { "N/A" },
    kwhReading = cumulativeKwhReading(),
    summaryStats = summaryStats()
)

/**
 * Persistent local store for meter readings.
 *
 * The complete [MeterDataReport] is GZIP-compressed JSON in a single BLOB so a
 * saved reading can be re-exported with any load-survey window later. The list
 * query reads only the metadata columns; the BLOB is loaded on demand.
 */
class MeterReadingDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {

    companion object {
        const val DATABASE_NAME = "meter_readings.db"
        const val DATABASE_VERSION = 1
        const val TABLE = "meter_readings"

        const val COL_ID = "id"
        const val COL_SERIAL = "serial_number"
        const val COL_LDN = "ldn"
        const val COL_MANUFACTURER = "manufacturer"
        const val COL_METER_RTC = "meter_rtc"
        const val COL_SCANNED_AT_EPOCH = "scanned_at_epoch_ms"
        const val COL_SCANNED_AT_FORMATTED = "scanned_at_formatted"
        const val COL_READ_TYPE = "read_type"
        const val COL_KWH = "kwh_reading"
        const val COL_SUMMARY = "summary_stats"
        const val COL_RAW_REPORT = "raw_report_gzip"

        private const val SCANNED_AT_PATTERN = "dd-MMM-yyyy HH:mm:ss"

        /** Newest-first history cap; older rows beyond this are pruned. */
        const val MAX_SAVED_READINGS = 50
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_SERIAL TEXT NOT NULL,
                $COL_LDN TEXT NOT NULL,
                $COL_MANUFACTURER TEXT NOT NULL,
                $COL_METER_RTC TEXT NOT NULL,
                $COL_SCANNED_AT_EPOCH INTEGER NOT NULL,
                $COL_SCANNED_AT_FORMATTED TEXT NOT NULL,
                $COL_READ_TYPE TEXT NOT NULL,
                $COL_KWH TEXT NOT NULL,
                $COL_SUMMARY TEXT NOT NULL,
                $COL_RAW_REPORT BLOB NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_${TABLE}_scanned ON $TABLE ($COL_SCANNED_AT_EPOCH DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    /**
     * Compresses and inserts a completed report, returning its row id.
     *
     * The report is merged with the newest saved report for the same serial
     * first, so a partial module read (Instantaneous, Tamper, ...) never wipes
     * out sections captured by an earlier read. Every save is a new history row;
     * only the newest [MAX_SAVED_READINGS] rows are kept.
     */
    suspend fun insertReport(
        report: MeterDataReport,
        readType: String,
        scannedAtEpochMs: Long = System.currentTimeMillis()
    ): Long = withContext(Dispatchers.IO) {
        val db = writableDatabase
        val incoming = report.toReadingMetadata()
        val merged = mergeSavedReport(latestSavedReport(db, incoming.serialNumber), report)
        val metadata = merged.toReadingMetadata()
        val scannedAt = SimpleDateFormat(SCANNED_AT_PATTERN, Locale.ENGLISH)
            .format(Date(scannedAtEpochMs))
        val values = ContentValues().apply {
            put(COL_SERIAL, metadata.serialNumber)
            put(COL_LDN, metadata.ldn)
            put(COL_MANUFACTURER, metadata.manufacturer)
            put(COL_METER_RTC, metadata.meterRtc)
            put(COL_SCANNED_AT_EPOCH, scannedAtEpochMs)
            put(COL_SCANNED_AT_FORMATTED, scannedAt)
            put(COL_READ_TYPE, readType)
            put(COL_KWH, metadata.kwhReading)
            put(COL_SUMMARY, metadata.summaryStats)
            put(COL_RAW_REPORT, MeterReportSerializer.toJsonBytesGzip(merged))
        }
        db.beginTransaction()
        try {
            val rowId = db.insertOrThrow(TABLE, null, values)
            pruneOldReadings(db)
            db.setTransactionSuccessful()
            rowId
        } finally {
            db.endTransaction()
        }
    }

    /** Newest saved report for [serialNumber], or null when the meter has none. */
    private fun latestSavedReport(db: SQLiteDatabase, serialNumber: String): MeterDataReport? {
        if (serialNumber.isBlank() || serialNumber == UNKNOWN_SERIAL) return null
        val rowId = db.query(
            TABLE,
            arrayOf(COL_ID),
            "$COL_SERIAL = ?",
            arrayOf(serialNumber),
            null,
            null,
            "$COL_SCANNED_AT_EPOCH DESC, $COL_ID DESC",
            "1"
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null } ?: return null
        return db.query(
            TABLE,
            arrayOf(COL_RAW_REPORT),
            "$COL_ID = ?",
            arrayOf(rowId.toString()),
            null,
            null,
            null
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            cursor.getBlob(0)?.let { MeterReportSerializer.fromJsonBytesGzip(it) }
        }
    }

    /** Keeps the reading history bounded so partial reads cannot grow it forever. */
    private fun pruneOldReadings(db: SQLiteDatabase) {
        db.execSQL(
            "DELETE FROM $TABLE WHERE $COL_ID NOT IN (" +
                "SELECT $COL_ID FROM $TABLE " +
                "ORDER BY $COL_SCANNED_AT_EPOCH DESC, $COL_ID DESC " +
                "LIMIT $MAX_SAVED_READINGS)"
        )
    }

    /** All saved readings, newest first, without touching the report BLOB. */
    suspend fun listSummaries(): List<SavedReadingSummary> = withContext(Dispatchers.IO) {
        val columns = arrayOf(
            COL_ID,
            COL_SERIAL,
            COL_LDN,
            COL_MANUFACTURER,
            COL_METER_RTC,
            COL_SCANNED_AT_EPOCH,
            COL_SCANNED_AT_FORMATTED,
            COL_READ_TYPE,
            COL_KWH,
            COL_SUMMARY
        )
        readableDatabase.query(
            TABLE,
            columns,
            null,
            null,
            null,
            null,
            "$COL_SCANNED_AT_EPOCH DESC, $COL_ID DESC"
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        SavedReadingSummary(
                            id = cursor.getLong(0),
                            serialNumber = cursor.getString(1),
                            ldn = cursor.getString(2),
                            manufacturer = cursor.getString(3),
                            meterRtc = cursor.getString(4),
                            scannedAtEpochMs = cursor.getLong(5),
                            scannedAtFormatted = cursor.getString(6),
                            readType = cursor.getString(7),
                            kwhReading = cursor.getString(8),
                            summaryStats = cursor.getString(9)
                        )
                    )
                }
            }
        }
    }

    /** Decompresses one saved report, or returns null when the row is gone. */
    suspend fun loadReport(id: Long): MeterDataReport? = withContext(Dispatchers.IO) {
        readableDatabase.query(
            TABLE,
            arrayOf(COL_RAW_REPORT),
            "$COL_ID = ?",
            arrayOf(id.toString()),
            null,
            null,
            null
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val bytes = cursor.getBlob(0) ?: return@use null
            MeterReportSerializer.fromJsonBytesGzip(bytes)
        }
    }

    suspend fun deleteReport(id: Long): Int = withContext(Dispatchers.IO) {
        writableDatabase.delete(TABLE, "$COL_ID = ?", arrayOf(id.toString()))
    }
}
