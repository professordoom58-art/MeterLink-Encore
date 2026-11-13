package com.utility.dlmsreader

import com.utility.dlmsreader.update.MeterLinkUpdater
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Release tags (`v1.0.58`) against the installed build label (`v1.0.57`). */
class UpdateVersionTest {

    @Test
    fun `a higher release tag is newer`() {
        assertTrue(MeterLinkUpdater.isNewer("1.0.58", "v1.0.57"))
        assertTrue(MeterLinkUpdater.isNewer("v1.0.100", "v1.0.99"))
        assertTrue(MeterLinkUpdater.isNewer("v1.1.0", "v1.0.58"))
    }

    @Test
    fun `the installed release is not newer than itself`() {
        assertFalse(MeterLinkUpdater.isNewer("v1.0.57", "v1.0.57"))
        assertFalse(MeterLinkUpdater.isNewer("1.0.57", "1.0.57.0"))
    }

    @Test
    fun `an older or unparseable tag never offers an update`() {
        assertFalse(MeterLinkUpdater.isNewer("v1.0.56", "v1.0.57"))
        assertFalse(MeterLinkUpdater.isNewer("", "v1.0.57"))
        assertFalse(MeterLinkUpdater.isNewer("latest", "v1.0.57"))
    }
}
