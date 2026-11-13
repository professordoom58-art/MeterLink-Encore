package com.utility.dlmsreader.storage

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.utility.dlmsreader.dlms.MeterDataReport
import com.utility.dlmsreader.dlms.ProfileSection
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    val summaryStats: String,
    val kvahReading: String = "N/A",
    val nameplateCount: Int = 0,
    val instantaneousCount: Int = 0,
    val billingCount: Int = 0,
    val dailyCount: Int = 0,
    val blockCount: Int = 0,
    val tamperCount: Int = 0
)

/** Metadata extracted from a report before it is persisted. */
data class MeterReadingMetadata(
    val serialNumber: String,
    val ldn: String,
    val manufacturer: String,
    val meterRtc: String,
    val kwhReading: String,
    val summaryStats: String,
    val kvahReading: String = "N/A",
    val nameplateCount: Int = 0,
    val instantaneousCount: Int = 0,
    val billingCount: Int = 0,
    val dailyCount: Int = 0,
    val blockCount: Int = 0,
    val tamperCount: Int = 0
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
    summaryStats = summaryStats(),
    kvahReading = cumulativeKvahReading(),
    nameplateCount = nameplateFieldCount(),
    instantaneousCount = instantaneousParameterCount(),
    billingCount = billing?.rows?.size ?: 0,
    dailyCount = dailyLoad?.rows?.size ?: 0,
    blockCount = blockLoad?.rows?.size ?: 0,
    tamperCount = tamperEvents.size
)

/**
 * Persistent local store for meter readings.
 *
 * The complete [MeterDataReport] is GZIP-compressed JSON in a single BLOB so a
 * saved reading can be re-exported with any load-survey window later. The list
 * query reads only the metadata columns; the BLOB is loaded on demand.
 */
class MeterReadingDatabase(
    private val appContext: Context
) : SQLiteOpenHelper(
    appContext.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {

    companion object {
        const val DATABASE_NAME = "meter_readings.db"
        const val DATABASE_VERSION = 2
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
        const val COL_KVAH = "kvah_reading"
        const val COL_NAMEPLATE_COUNT = "nameplate_count"
        const val COL_INSTANT_COUNT = "instantaneous_count"
        const val COL_BILLING_COUNT = "billing_count"
        const val COL_DAILY_COUNT = "daily_count"
        const val COL_BLOCK_COUNT = "block_count"
        const val COL_TAMPER_COUNT = "tamper_count"
        const val COL_FINGERPRINT = "content_fingerprint"
        const val COL_BACKUP_PATH = "backup_path"
        const val COL_RAW_REPORT = "raw_report_gzip"

        private const val SCANNED_AT_PATTERN = "dd-MMM-yyyy HH:mm:ss"

        /** Newest-first history cap; older rows beyond this are pruned. */
        const val MAX_SAVED_READINGS = 50
    }

    /** Guards the one-time `Documents/MeterLink` import across app launches. */
    private val restoreMutex = Mutex()
    private var externalRestoreDone = false

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
                $COL_KVAH TEXT NOT NULL DEFAULT '',
                $COL_NAMEPLATE_COUNT INTEGER NOT NULL DEFAULT 0,
                $COL_INSTANT_COUNT INTEGER NOT NULL DEFAULT 0,
                $COL_BILLING_COUNT INTEGER NOT NULL DEFAULT 0,
                $COL_DAILY_COUNT INTEGER NOT NULL DEFAULT 0,
                $COL_BLOCK_COUNT INTEGER NOT NULL DEFAULT 0,
                $COL_TAMPER_COUNT INTEGER NOT NULL DEFAULT 0,
                $COL_FINGERPRINT TEXT NOT NULL DEFAULT '',
                $COL_BACKUP_PATH TEXT NOT NULL DEFAULT '',
                $COL_RAW_REPORT BLOB NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_${TABLE}_scanned ON $TABLE ($COL_SCANNED_AT_EPOCH DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN $COL_KVAH TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN $COL_NAMEPLATE_COUNT INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN $COL_INSTANT_COUNT INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN $COL_BILLING_COUNT INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN $COL_DAILY_COUNT INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN $COL_BLOCK_COUNT INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN $COL_TAMPER_COUNT INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN $COL_FINGERPRINT TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN $COL_BACKUP_PATH TEXT NOT NULL DEFAULT ''")
            backfillV2(db)
        }
    }

    /** Recomputes the v2 metric columns for readings saved by older builds. */
    private fun backfillV2(db: SQLiteDatabase) {
        db.query(TABLE, arrayOf(COL_ID, COL_RAW_REPORT), null, null, null, null, null)
            .use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val bytes = cursor.getBlob(1) ?: continue
                    val report = runCatching {
                        MeterReportSerializer.fromJsonBytesGzip(bytes)
                    }.getOrNull() ?: continue
                    val metadata = report.toReadingMetadata()
                    val values = ContentValues().apply {
                        put(COL_KVAH, metadata.kvahReading)
                        put(COL_NAMEPLATE_COUNT, metadata.nameplateCount)
                        put(COL_INSTANT_COUNT, metadata.instantaneousCount)
                        put(COL_BILLING_COUNT, metadata.billingCount)
                        put(COL_DAILY_COUNT, metadata.dailyCount)
                        put(COL_BLOCK_COUNT, metadata.blockCount)
                        put(COL_TAMPER_COUNT, metadata.tamperCount)
                        put(COL_FINGERPRINT, ExternalReadingBackup.fingerprint(report))
                    }
                    db.update(TABLE, values, "$COL_ID = ?", arrayOf(id.toString()))
                }
            }
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
        db.beginTransaction()
        val rowId = try {
            val id = db.insertOrThrow(TABLE, null, readingValues(merged, readType, scannedAtEpochMs))
            pruneOldReadings(db)
            db.setTransactionSuccessful()
            id
        } finally {
            db.endTransaction()
        }
        // Persist a human-readable JSON copy in Documents/MeterLink so the
        // reading survives an uninstall (internal SQLite storage does not).
        // The file name carries the scan timestamp, not the changing row id.
        val backupPath = writeBackup(merged, scannedAtEpochMs)
        if (backupPath.isNotBlank()) {
            db.update(
                TABLE,
                ContentValues().apply { put(COL_BACKUP_PATH, backupPath) },
                "$COL_ID = ?",
                arrayOf(rowId.toString())
            )
        }
        rowId
    }

    /** Builds the full column set for a report row. */
    private fun readingValues(
        report: MeterDataReport,
        readType: String,
        scannedAtEpochMs: Long
    ): ContentValues {
        val metadata = report.toReadingMetadata()
        val scannedAt = SimpleDateFormat(SCANNED_AT_PATTERN, Locale.ENGLISH)
            .format(Date(scannedAtEpochMs))
        return ContentValues().apply {
            put(COL_SERIAL, metadata.serialNumber)
            put(COL_LDN, metadata.ldn)
            put(COL_MANUFACTURER, metadata.manufacturer)
            put(COL_METER_RTC, metadata.meterRtc)
            put(COL_SCANNED_AT_EPOCH, scannedAtEpochMs)
            put(COL_SCANNED_AT_FORMATTED, scannedAt)
            put(COL_READ_TYPE, readType)
            put(COL_KWH, metadata.kwhReading)
            put(COL_SUMMARY, metadata.summaryStats)
            put(COL_KVAH, metadata.kvahReading)
            put(COL_NAMEPLATE_COUNT, metadata.nameplateCount)
            put(COL_INSTANT_COUNT, metadata.instantaneousCount)
            put(COL_BILLING_COUNT, metadata.billingCount)
            put(COL_DAILY_COUNT, metadata.dailyCount)
            put(COL_BLOCK_COUNT, metadata.blockCount)
            put(COL_TAMPER_COUNT, metadata.tamperCount)
            put(COL_FINGERPRINT, ExternalReadingBackup.fingerprint(report))
            put(COL_RAW_REPORT, MeterReportSerializer.toJsonBytesGzip(report))
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

    /**
     * Keeps the reading history bounded so partial reads cannot grow it forever.
     * External backup files belonging to the dropped rows are removed too, so a
     * later startup scan cannot resurrect them.
     */
    private fun pruneOldReadings(db: SQLiteDatabase) {
        val keep = "SELECT $COL_ID FROM $TABLE " +
            "ORDER BY $COL_SCANNED_AT_EPOCH DESC, $COL_ID DESC LIMIT $MAX_SAVED_READINGS"
        val doomed = mutableListOf<BackupKey>()
        db.rawQuery(
            "SELECT $COL_ID, $COL_SERIAL, $COL_SCANNED_AT_EPOCH, $COL_BACKUP_PATH FROM $TABLE " +
                "WHERE $COL_ID NOT IN ($keep)",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                doomed += BackupKey(
                    id = cursor.getLong(0),
                    serial = cursor.getString(1),
                    scannedAtEpochMs = cursor.getLong(2),
                    path = cursor.getString(3)
                )
            }
        }
        db.execSQL("DELETE FROM $TABLE WHERE $COL_ID NOT IN ($keep)")
        doomed.forEach { deleteBackup(it) }
    }

    /** All saved readings, newest first, without touching the report BLOB. */
    suspend fun listSummaries(): List<SavedReadingSummary> = withContext(Dispatchers.IO) {
        restoreExternalBackupsIfNeeded()
        querySummaries()
    }

    /**
     * Rescans `Documents/MeterLink` on demand (the Refresh control) so files
     * copied in later, or hidden by a reinstall's new app UID until the user
     * grants folder access, appear without restarting the app.
     */
    suspend fun refreshExternalBackups(): List<SavedReadingSummary> = withContext(Dispatchers.IO) {
        restoreMutex.withLock {
            runCatching { restoreExternalBackupsBlocking() }
            externalRestoreDone = true
        }
        querySummaries()
    }

    /**
     * Imports every MeterLink JSON below a folder tree the user picked through
     * `ACTION_OPEN_DOCUMENT_TREE`. This is the scoped-storage fallback: after a
     * reinstall the new app UID cannot see JSONs written by the previous
     * install, but a user-granted tree exposes them again.
     */
    suspend fun importFromTree(treeUri: Uri): Int = withContext(Dispatchers.IO) {
        restoreMutex.withLock {
            val imported = runCatching { importTreeBlocking(treeUri) }.getOrDefault(0)
            externalRestoreDone = true
            imported
        }
    }

    private fun querySummaries(): List<SavedReadingSummary> {
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
            COL_SUMMARY,
            COL_KVAH,
            COL_NAMEPLATE_COUNT,
            COL_INSTANT_COUNT,
            COL_BILLING_COUNT,
            COL_DAILY_COUNT,
            COL_BLOCK_COUNT,
            COL_TAMPER_COUNT
        )
        return readableDatabase.query(
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
                            summaryStats = cursor.getString(9),
                            kvahReading = cursor.getString(10),
                            nameplateCount = cursor.getInt(11),
                            instantaneousCount = cursor.getInt(12),
                            billingCount = cursor.getInt(13),
                            dailyCount = cursor.getInt(14),
                            blockCount = cursor.getInt(15),
                            tamperCount = cursor.getInt(16)
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
        val db = writableDatabase
        var serial = UNKNOWN_SERIAL
        var scannedAtEpochMs = 0L
        var backupPath = ""
        db.query(
            TABLE,
            arrayOf(COL_SERIAL, COL_SCANNED_AT_EPOCH, COL_BACKUP_PATH),
            "$COL_ID = ?",
            arrayOf(id.toString()),
            null,
            null,
            null
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                serial = cursor.getString(0).orEmpty()
                scannedAtEpochMs = cursor.getLong(1)
                backupPath = cursor.getString(2).orEmpty()
            }
        }
        val deleted = db.delete(TABLE, "$COL_ID = ?", arrayOf(id.toString()))
        if (deleted > 0) {
            deleteBackup(BackupKey(id, serial, scannedAtEpochMs, backupPath))
        }
        deleted
    }

    // ------------------------------------------------------------------
    // Documents/MeterLink persistence
    // ------------------------------------------------------------------

    /** Restores any JSON backups missing from SQLite before the list is read. */
    private suspend fun restoreExternalBackupsIfNeeded() {
        if (externalRestoreDone) return
        restoreMutex.withLock {
            if (externalRestoreDone) return@withLock
            runCatching { restoreExternalBackupsBlocking() }
            externalRestoreDone = true
        }
    }

    private fun restoreExternalBackupsBlocking() {
        val db = writableDatabase
        val knownFingerprints = loadKnownFingerprints(db)
        var imported = 0
        listExternalBackups().forEach { backup ->
            if (importBackup(db, knownFingerprints, backup)) imported++
        }
        if (imported > 0) pruneOldReadings(db)
        // Create Documents/MeterLink on first launch even when it is empty.
        externalDirectory()
    }

    private fun loadKnownFingerprints(db: SQLiteDatabase): MutableSet<String> {
        val known = mutableSetOf<String>()
        db.query(TABLE, arrayOf(COL_FINGERPRINT), null, null, null, null, null)
            .use { cursor ->
                while (cursor.moveToNext()) {
                    cursor.getString(0)?.takeIf { it.isNotBlank() }?.let(known::add)
                }
            }
        return known
    }

    /**
     * Imports one backup file. The scan timestamp is recovered from the file
     * name (falling back to the file's last-modified time), so restored rows
     * keep the original reading date and the backup is renamed from the legacy
     * `_<id>` form to `Meterlink-<serial>-<date>.json`.
     */
    private fun importBackup(
        db: SQLiteDatabase,
        knownFingerprints: MutableSet<String>,
        backup: ExternalBackupFile
    ): Boolean {
        val text = backup.readText() ?: return false
        val report = ExternalReadingBackup.fromJson(text) ?: return false
        val fingerprint = ExternalReadingBackup.fingerprint(report)
        if (!knownFingerprints.add(fingerprint)) return false

        val scannedAt = ExternalReadingBackup.parseScanTimestamp(
            backup.name,
            backup.lastModifiedMs
        )
        val rowId = db.insertOrThrow(
            TABLE,
            null,
            readingValues(report, "Restored Backup", scannedAt)
        )
        val newPath = writeBackup(report, scannedAt)
        if (newPath.isNotBlank()) {
            db.update(
                TABLE,
                ContentValues().apply { put(COL_BACKUP_PATH, newPath) },
                "$COL_ID = ?",
                arrayOf(rowId.toString())
            )
        }
        val expectedName = ExternalReadingBackup.buildMeterJsonFileName(
            report.identityValue(SERIAL_OBIS),
            scannedAt
        )
        if (backup.name != expectedName) backup.delete()
        return true
    }

    /** Imports MeterLink JSONs exposed by a user-granted SAF folder tree. */
    private fun importTreeBlocking(treeUri: Uri): Int {
        val db = writableDatabase
        val knownFingerprints = loadKnownFingerprints(db)
        var imported = 0
        listTreeBackups(treeUri).forEach { backup ->
            if (importBackup(db, knownFingerprints, backup)) imported++
        }
        if (imported > 0) pruneOldReadings(db)
        return imported
    }

    /**
     * Lists MeterLink JSONs under a SAF tree. The user normally picks the
     * `MeterLink` folder itself, but picking `Documents` also works because a
     * direct child folder named `MeterLink` is descended into.
     */
    private fun listTreeBackups(treeUri: Uri): List<ExternalBackupFile> {
        val resolver = appContext.contentResolver
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }
            .getOrNull() ?: return emptyList()
        val backups = mutableListOf<ExternalBackupFile>()

        fun collect(parentId: String, depth: Int) {
            if (depth > 2) return
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
            runCatching {
                resolver.query(
                    children,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED
                    ),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val documentId = cursor.getString(0) ?: continue
                        val name = cursor.getString(1) ?: continue
                        val mimeType = cursor.getString(2).orEmpty()
                        val modified = if (cursor.isNull(3)) 0L else cursor.getLong(3)
                        if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                            if (name.equals(ExternalReadingBackup.FOLDER_NAME, ignoreCase = true)) {
                                collect(documentId, depth + 1)
                            }
                        } else if (ExternalReadingBackup.isMeterLinkJsonFile(name)) {
                            val documentUri = DocumentsContract.buildDocumentUriUsingTree(
                                treeUri,
                                documentId
                            )
                            backups += ExternalBackupFile(
                                name = name,
                                lastModifiedMs = modified,
                                readText = {
                                    runCatching {
                                        resolver.openInputStream(documentUri)?.use { input ->
                                            input.readBytes().toString(Charsets.UTF_8)
                                        }
                                    }.getOrNull()
                                },
                                delete = {
                                    runCatching {
                                        DocumentsContract.deleteDocument(resolver, documentUri)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        collect(rootId, 0)
        return backups
    }

    private data class BackupKey(
        val id: Long,
        val serial: String,
        val scannedAtEpochMs: Long,
        val path: String
    )

    private data class ExternalBackupFile(
        val name: String,
        val lastModifiedMs: Long,
        val readText: () -> String?,
        val delete: () -> Unit
    )

    /** Enumerates backups through MediaStore (API 29+) and the direct folder. */
    private fun listExternalBackups(): List<ExternalBackupFile> {
        val backups = mutableListOf<ExternalBackupFile>()
        val seenNames = mutableSetOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val resolver = appContext.contentResolver
                val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val relative = "${Environment.DIRECTORY_DOCUMENTS}/${ExternalReadingBackup.FOLDER_NAME}"
                resolver.query(
                    collection,
                    arrayOf(
                        MediaStore.Files.FileColumns._ID,
                        MediaStore.Files.FileColumns.DISPLAY_NAME,
                        MediaStore.Files.FileColumns.DATE_MODIFIED
                    ),
                    "(${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ? OR " +
                        "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?) AND " +
                        "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ?",
                    arrayOf(
                        "${ExternalReadingBackup.FILE_PREFIX}%${ExternalReadingBackup.FILE_SUFFIX}",
                        "${ExternalReadingBackup.LEGACY_FILE_PREFIX}%${ExternalReadingBackup.FILE_SUFFIX}",
                        "$relative%"
                    ),
                    null
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(0)
                        val name = cursor.getString(1) ?: continue
                        if (!seenNames.add(name)) continue
                        val uri = ContentUris.withAppendedId(collection, id)
                        backups += ExternalBackupFile(
                            name = name,
                            lastModifiedMs = cursor.getLong(2) * 1000L,
                            readText = {
                                runCatching {
                                    resolver.openInputStream(uri)?.use { input ->
                                        input.readBytes().toString(Charsets.UTF_8)
                                    }
                                }.getOrNull()
                            },
                            delete = { runCatching { resolver.delete(uri, null, null) } }
                        )
                    }
                }
            }
        }

        externalDirectory()?.let { directory ->
            ExternalReadingBackup.scan(directory).forEach { file ->
                if (!seenNames.add(file.name)) return@forEach
                backups += ExternalBackupFile(
                    name = file.name,
                    lastModifiedMs = file.lastModified(),
                    readText = { runCatching { file.readText(Charsets.UTF_8) }.getOrNull() },
                    delete = { runCatching { file.delete() } }
                )
            }
        }
        return backups
    }

    /** Public Documents directory, or null when shared storage is unavailable. */
    private fun externalDirectory(): File? = runCatching {
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            ExternalReadingBackup.FOLDER_NAME
        )
        val created = !directory.exists()
        if (created) directory.mkdirs()
        if (created && directory.isDirectory) {
            // Make the freshly created folder visible to file managers and to
            // the MediaStore index immediately, not only after a device reboot.
            runCatching {
                MediaScannerConnection.scanFile(
                    appContext,
                    arrayOf(directory.absolutePath),
                    null,
                    null
                )
            }
        }
        directory.takeIf { it.isDirectory }
    }.getOrNull()

    /** Writes the JSON backup and returns a content URI or absolute path. */
    private fun writeBackup(report: MeterDataReport, scanTimestampMillis: Long): String {
        val serial = report.identityValue(SERIAL_OBIS)
        val name = ExternalReadingBackup.buildMeterJsonFileName(serial, scanTimestampMillis)
        val json = ExternalReadingBackup.toJson(report)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeBackupViaMediaStore(name, json)?.let { return it }
        }
        val directory = externalDirectory() ?: return ""
        return runCatching {
            ExternalReadingBackup.write(directory, report, scanTimestampMillis).absolutePath
        }.getOrDefault("")
    }

    private fun writeBackupViaMediaStore(name: String, json: String): String? = runCatching {
        val resolver = appContext.contentResolver
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relative = "${Environment.DIRECTORY_DOCUMENTS}/${ExternalReadingBackup.FOLDER_NAME}"
        val existing = resolver.query(
            collection,
            arrayOf(MediaStore.Files.FileColumns._ID),
            "${MediaStore.Files.FileColumns.DISPLAY_NAME} = ? AND " +
                "${MediaStore.Files.FileColumns.RELATIVE_PATH} = ?",
            arrayOf(name, "$relative/"),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                ContentUris.withAppendedId(collection, cursor.getLong(0))
            } else {
                null
            }
        }
        val uri = existing ?: run {
            val values = ContentValues().apply {
                put(MediaStore.Files.FileColumns.DISPLAY_NAME, name)
                put(MediaStore.Files.FileColumns.MIME_TYPE, "application/json")
                put(MediaStore.Files.FileColumns.RELATIVE_PATH, relative)
                put(MediaStore.Files.FileColumns.IS_PENDING, 1)
            }
            resolver.insert(collection, values) ?: return@runCatching null
        }
        val written = resolver.openOutputStream(uri, "wt")?.use { stream ->
            stream.write(json.toByteArray(Charsets.UTF_8))
            true
        } ?: false
        if (!written) return@runCatching null
        if (existing == null) {
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Files.FileColumns.IS_PENDING, 0) },
                null,
                null
            )
        }
        uri.toString()
    }.getOrNull()

    /** Deletes the MediaStore entry and/or direct file backing one reading. */
    private fun deleteBackup(backup: BackupKey) {
        when {
            backup.path.startsWith("content://") ->
                runCatching { appContext.contentResolver.delete(Uri.parse(backup.path), null, null) }
            backup.path.isNotBlank() -> runCatching { File(backup.path).delete() }
        }
        // Both the current timestamp name and the legacy `_<id>` name may exist
        // for a row saved by an older build.
        val names = listOf(
            ExternalReadingBackup.buildMeterJsonFileName(
                backup.serial,
                backup.scannedAtEpochMs
            ),
            ExternalReadingBackup.legacyFileName(backup.serial, backup.id)
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val relative = "${Environment.DIRECTORY_DOCUMENTS}/${ExternalReadingBackup.FOLDER_NAME}"
                names.forEach { name ->
                    appContext.contentResolver.delete(
                        MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                        "${MediaStore.Files.FileColumns.DISPLAY_NAME} = ? AND " +
                            "${MediaStore.Files.FileColumns.RELATIVE_PATH} = ?",
                        arrayOf(name, "$relative/")
                    )
                }
            }
        }
        externalDirectory()?.let { directory ->
            names.forEach { name -> runCatching { File(directory, name).delete() } }
        }
    }
}
