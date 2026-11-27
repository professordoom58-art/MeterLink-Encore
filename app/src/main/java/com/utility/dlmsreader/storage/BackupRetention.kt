package com.utility.dlmsreader.storage

/**
 * Rules for the bounded SQLite history versus the unbounded JSON backups in
 * `Documents/MeterLink`.
 *
 * SQLite keeps only the newest [MeterReadingDatabase.MAX_SAVED_READINGS]
 * readings. The JSON backups are the user's long-term record and are never
 * removed by that cap. Restoring therefore skips backups that the cap would
 * prune straight away, so older backups do not push newer readings out of the
 * list or get re-imported on every launch.
 */
internal object BackupRetention {

    /**
     * True when a backup scanned at [scannedAtEpochMs] would stay in a history
     * that already holds [keptCount] rows whose oldest is [oldestKeptEpochMs].
     * A backup no newer than the oldest kept row is skipped once the history is
     * full: pruning keeps the newest rows, so it would be dropped again at once.
     */
    fun wouldBeKept(
        scannedAtEpochMs: Long,
        keptCount: Int,
        oldestKeptEpochMs: Long?,
        cap: Int
    ): Boolean = keptCount < cap || oldestKeptEpochMs == null || scannedAtEpochMs > oldestKeptEpochMs
}
