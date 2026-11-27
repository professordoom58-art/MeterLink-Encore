package com.utility.dlmsreader.storage

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.utility.dlmsreader.dlms.MeterDataReport
import java.io.File

/**
 * Everything that talks to shared storage instead of SQLite.
 *
 * MeterLink writes a human-readable JSON copy of every reading into
 * `Documents/MeterLink` so it survives an uninstall, and it can re-import such
 * files either through MediaStore (API 29+) or a user-granted SAF folder tree
 * after a reinstall changes the app UID.
 *
 * This class owns the MediaStore queries and the recursive `DocumentsContract`
 * tree walk. [MeterReadingDatabase] supplies the row-level insert/prune
 * callbacks so persistence stays transactional in one place.
 */
internal class ExternalStorageSync(
    private val appContext: Context,
    private val database: MeterReadingDatabase
) {

    /**
     * One candidate JSON backup, exposed as lambdas so the MediaStore and SAF
     * implementations can share the import loop without opening streams twice.
     */
    data class BackupFile(
        val name: String,
        val lastModifiedMs: Long,
        val readText: () -> String?,
        /**
         * Removes a legacy-named copy after it was rewritten under the canonical
         * name. Null for files in a user-picked (SAF) folder: those belong to the
         * user and are never deleted by an import.
         */
        val delete: (() -> Unit)?
    )

    /** Imports MeterLink JSONs exposed by a user-granted SAF folder tree. */
    fun importTreeBlocking(treeUri: Uri): Int {
        val db = database.writableDatabase
        val knownFingerprints = database.loadKnownFingerprints(db)
        var imported = 0
        listTreeBackups(treeUri).forEach { backup ->
            if (database.importBackup(db, knownFingerprints, backup)) imported++
        }
        if (imported > 0) database.pruneOldReadings(db)
        return imported
    }

    /**
     * Lists MeterLink JSONs under a SAF tree. The user normally picks the
     * `MeterLink` folder itself, but picking `Documents` also works because a
     * direct child folder named `MeterLink` is descended into.
     */
    fun listTreeBackups(treeUri: Uri): List<BackupFile> {
        val resolver = appContext.contentResolver
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }
            .getOrNull() ?: return emptyList()
        val backups = mutableListOf<BackupFile>()

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
                            backups += BackupFile(
                                name = name,
                                lastModifiedMs = modified,
                                readText = {
                                    runCatching {
                                        resolver.openInputStream(documentUri)?.use { input ->
                                            input.readBytes().toString(Charsets.UTF_8)
                                        }
                                    }.getOrNull()
                                },
                                delete = null
                            )
                        }
                    }
                }
            }
        }

        collect(rootId, 0)
        return backups
    }

    /** Enumerates backups through MediaStore (API 29+) and the direct folder. */
    fun listExternalBackups(): List<BackupFile> {
        val backups = mutableListOf<BackupFile>()
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
                        backups += BackupFile(
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
                backups += BackupFile(
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
    fun externalDirectory(): File? = runCatching {
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
    fun writeBackup(report: MeterDataReport, scanTimestampMillis: Long): String {
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
    fun deleteBackup(serial: String, id: Long, scannedAtEpochMs: Long, path: String) {
        when {
            path.startsWith("content://") ->
                runCatching { appContext.contentResolver.delete(Uri.parse(path), null, null) }
            path.isNotBlank() -> runCatching { File(path).delete() }
        }
        // Both the current timestamp name and the legacy `_<id>` name may exist
        // for a row saved by an older build.
        val names = listOf(
            ExternalReadingBackup.buildMeterJsonFileName(serial, scannedAtEpochMs),
            ExternalReadingBackup.legacyFileName(serial, id)
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

    private companion object {
        const val SERIAL_OBIS = "0.0.96.1.0.255"
    }
}