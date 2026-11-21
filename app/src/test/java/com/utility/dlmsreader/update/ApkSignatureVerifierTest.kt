package com.utility.dlmsreader.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkSignatureVerifierTest {

    private val installed =
        "1c75dbdf0508b8e7053e0724a52393fae27df8d94d4f4fb4be3bde8febbec2d3"

    @Test
    fun `the same signing digest is accepted`() {
        assertTrue(ApkSignatureVerifier.digestsMatch(listOf(installed), listOf(installed)))
    }

    @Test
    fun `digest comparison ignores case colons and separators`() {
        val upper = installed.uppercase().chunked(2).joinToString(":")
        assertTrue(ApkSignatureVerifier.digestsMatch(listOf(upper), listOf(installed)))
        assertTrue(ApkSignatureVerifier.digestsMatch(listOf(" $installed "), listOf(installed)))
    }

    @Test
    fun `a different signing key is rejected`() {
        val other = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
        assertFalse(ApkSignatureVerifier.digestsMatch(listOf(installed), listOf(other)))
        assertFalse(ApkSignatureVerifier.digestsMatch(listOf(installed), listOf(installed, other)))
    }

    @Test
    fun `empty digests are always rejected`() {
        assertFalse(ApkSignatureVerifier.digestsMatch(emptyList(), listOf(installed)))
        assertFalse(ApkSignatureVerifier.digestsMatch(listOf(installed), emptyList()))
        assertFalse(ApkSignatureVerifier.digestsMatch(emptyList(), emptyList()))
        assertFalse(ApkSignatureVerifier.digestsMatch(listOf(""), listOf("")))
        assertFalse(ApkSignatureVerifier.digestsMatch(listOf(":"), listOf(installed)))
    }

    @Test
    fun `rejection messages are user facing and specific`() {
        assertTrue(ApkSignatureVerifier.REJECTED_SIGNATURE.contains("signature mismatch"))
        assertTrue(ApkSignatureVerifier.REJECTED_PACKAGE.contains("package name"))
    }
}
