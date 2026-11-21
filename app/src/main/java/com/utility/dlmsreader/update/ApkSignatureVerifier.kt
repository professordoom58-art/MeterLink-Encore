package com.utility.dlmsreader.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

/**
 * Signature gate for the in-app GitHub updater.
 *
 * A downloaded APK is only handed to the system package installer after its
 * signing certificate and package name match the running app, so a hijacked
 * release feed or a tampered download cannot replace MeterLink with a build
 * signed by another key. The comparison itself is a pure function so it can be
 * unit tested without an Android runtime.
 */
object ApkSignatureVerifier {

    const val REJECTED_SIGNATURE = "Update rejected: APK signature mismatch"
    const val REJECTED_PACKAGE = "Update rejected: APK package name mismatch"
    const val REJECTED_UNREADABLE = "Update rejected: the downloaded APK could not be verified"

    /** Outcome of [verify]; [message] is user-facing when [accepted] is false. */
    data class Result(val accepted: Boolean, val message: String?) {
        companion object {
            internal fun accept() = Result(true, null)
            internal fun reject(message: String) = Result(false, message)
        }
    }

    /**
     * True when every signer of the downloaded APK is a signer of the installed
     * app. Empty input on either side always fails: an unsigned or unreadable
     * archive must never be treated as a match.
     */
    fun digestsMatch(installed: Collection<String>, archive: Collection<String>): Boolean {
        val expected = installed.map(::normalizeDigest).filter { it.isNotEmpty() }.toSet()
        val actual = archive.map(::normalizeDigest).filter { it.isNotEmpty() }.toSet()
        if (expected.isEmpty() || actual.isEmpty()) return false
        return expected.containsAll(actual)
    }

    /** Lower-case hex, colons and whitespace removed, for stable comparison. */
    fun normalizeDigest(digest: String): String =
        digest.replace(":", "").replace(" ", "").replace("-", "").lowercase()

    /** SHA-256 digests of the currently installed package's signers. */
    fun installedDigests(context: Context): List<String> =
        signerDigests(installedInfo(context, signingFlags()))

    /** Package name recorded inside the downloaded [apk], or `null`. */
    fun archivePackageName(context: Context, apk: File): String? =
        archiveInfo(context, apk.absolutePath, signingFlags())?.packageName

    /** SHA-256 digests of the downloaded [apk]'s signers. */
    fun archiveDigests(context: Context, apk: File): List<String> =
        signerDigests(archiveInfo(context, apk.absolutePath, signingFlags()))

    /**
     * Verifies [apk] against the running app: same package name and a signer
     * the installed app already trusts. Never throws; an unreadable archive is
     * a rejection.
     */
    fun verify(context: Context, apk: File): Result {
        if (!apk.isFile || apk.length() == 0L) return Result.reject(REJECTED_UNREADABLE)
        val installed = installedDigests(context)
        if (installed.isEmpty()) return Result.reject(REJECTED_UNREADABLE)
        val archiveInfo = archiveInfo(context, apk.absolutePath, signingFlags())
            ?: return Result.reject(REJECTED_UNREADABLE)
        if (archiveInfo.packageName != context.packageName) return Result.reject(REJECTED_PACKAGE)
        val archive = signerDigests(archiveInfo)
        if (archive.isEmpty()) return Result.reject(REJECTED_SIGNATURE)
        return if (digestsMatch(installed, archive)) {
            Result.accept()
        } else {
            Result.reject(REJECTED_SIGNATURE)
        }
    }

    /** Flags that expose signing certificates on this API level. */
    private fun signingFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

    @Suppress("DEPRECATION")
    private fun installedInfo(context: Context, flags: Int): PackageInfo? =
        runCatching { context.packageManager.getPackageInfo(context.packageName, flags) }.getOrNull()

    @Suppress("DEPRECATION")
    private fun archiveInfo(context: Context, path: String, flags: Int): PackageInfo? =
        runCatching { context.packageManager.getPackageArchiveInfo(path, flags) }.getOrNull()

    @Suppress("DEPRECATION")
    private fun signerDigests(info: PackageInfo?): List<String> {
        if (info == null) return emptyList()
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        }
        return signatures.orEmpty().mapNotNull { signature ->
            runCatching {
                MessageDigest.getInstance("SHA-256")
                    .digest(signature.toByteArray())
                    .joinToString("") { byte -> "%02x".format(byte) }
            }.getOrNull()
        }
    }
}
