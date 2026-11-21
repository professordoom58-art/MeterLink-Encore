package com.utility.dlmsreader.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityGuardTest {

    @Test
    fun `a clean release report is not compromised`() {
        val report = SecurityGuard.Report(
            debuggable = false,
            debuggerAttached = false,
            signatureMatches = true
        )
        assertFalse(report.compromised)
    }

    @Test
    fun `a disabled signature check alone does not block a release build`() {
        val report = SecurityGuard.Report(
            debuggable = false,
            debuggerAttached = false,
            signatureMatches = null
        )
        assertFalse(report.compromised)
    }

    @Test
    fun `debuggable flag debugger and signature mismatch are each enough`() {
        assertTrue(
            SecurityGuard.Report(true, false, true).compromised
        )
        assertTrue(
            SecurityGuard.Report(false, true, true).compromised
        )
        assertTrue(
            SecurityGuard.Report(false, false, false).compromised
        )
    }

    @Test
    fun `blank or absent expected hash leaves the check disabled`() {
        assertNull(SecurityGuard.signatureMatches(null))
        assertNull(SecurityGuard.signatureMatches(null, ""))
        assertNull(SecurityGuard.signatureMatches(null, "   "))
        // A configured hash without a readable context must not silently pass.
        assertNull(SecurityGuard.signatureMatches(null, "ab:cd:ef"))
    }
}
