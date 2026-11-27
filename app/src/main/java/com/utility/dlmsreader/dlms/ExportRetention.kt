package com.utility.dlmsreader.dlms

import java.io.File

/**
 * Caps the PDFs, hex logs and Excel files that exports leave in the app's
 * private export folder (`getExternalFilesDir(DIRECTORY_DOCUMENTS)`). Every
 * Read All writes a PDF and a hex log there, and nothing else removes them.
 *
 * Only files whose names start with [prefix] and end with [suffix] are
 * considered, so the `Documents/MeterLink` JSON backups (a different, public
 * folder) and any other file are never touched.
 */
internal object ExportRetention {

    const val MAX_FILES_PER_TYPE = 50

    const val REPORT_PREFIX = "MeterLink_Report_"
    const val HEX_LOG_PREFIX = "MeterLink_HexLog_"

    /**
     * Deletes all but the newest [keep] matching files in [directory] (newest
     * by last-modified time, then by name, which carries the export stamp).
     * Returns how many were deleted. Failures are ignored: a file that cannot
     * be removed simply stays.
     */
    fun prune(directory: File, prefix: String, suffix: String, keep: Int = MAX_FILES_PER_TYPE): Int {
        val files = directory.listFiles { file ->
            file.isFile && file.name.startsWith(prefix) && file.name.endsWith(suffix, ignoreCase = true)
        } ?: return 0
        if (files.size <= keep) return 0
        return files
            .sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })
            .drop(keep)
            .count { runCatching { it.delete() }.getOrDefault(false) }
    }
}
