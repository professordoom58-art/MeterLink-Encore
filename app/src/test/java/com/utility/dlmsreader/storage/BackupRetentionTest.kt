package com.utility.dlmsreader.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupRetentionTest {

    private val cap = MeterReadingDatabase.MAX_SAVED_READINGS

    @Test
    fun `history below the cap accepts any backup`() {
        assertTrue(BackupRetention.wouldBeKept(1L, keptCount = 0, oldestKeptEpochMs = null, cap = cap))
        assertTrue(BackupRetention.wouldBeKept(1L, keptCount = cap - 1, oldestKeptEpochMs = 500L, cap = cap))
    }

    @Test
    fun `full history accepts only backups newer than its oldest row`() {
        assertTrue(BackupRetention.wouldBeKept(501L, keptCount = cap, oldestKeptEpochMs = 500L, cap = cap))
        assertFalse(BackupRetention.wouldBeKept(500L, keptCount = cap, oldestKeptEpochMs = 500L, cap = cap))
        assertFalse(BackupRetention.wouldBeKept(10L, keptCount = cap, oldestKeptEpochMs = 500L, cap = cap))
    }

    /**
     * Simulates a restore of 120 backups into an empty history, in shuffled
     * order, with pruning to the newest [cap] at the end (as the database
     * does). The result must be exactly the newest 50, and a second restore
     * pass over the same files must import nothing.
     */
    @Test
    fun `restore keeps the newest backups and does not churn on the next launch`() {
        val backups = (1L..120L).shuffled(java.util.Random(7))
        val rows = mutableListOf<Long>()
        fun restorePass(): Int {
            var imported = 0
            for (scannedAt in backups) {
                if (scannedAt in rows) continue // fingerprint already known
                if (!BackupRetention.wouldBeKept(scannedAt, rows.size, rows.minOrNull(), cap)) continue
                rows += scannedAt
                imported++
            }
            rows.sortDescending()
            while (rows.size > cap) rows.removeAt(rows.lastIndex)
            return imported
        }
        restorePass()
        assertEquals((71L..120L).toList().sortedDescending(), rows)
        assertEquals(0, restorePass())
    }
}
