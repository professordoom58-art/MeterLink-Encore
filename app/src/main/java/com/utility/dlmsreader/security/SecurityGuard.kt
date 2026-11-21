package com.utility.dlmsreader.security

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import com.utility.dlmsreader.BuildConfig
import java.security.MessageDigest

/**
 * Lightweight, release-only tamper checks.
 *
 * Debug builds return an all-clear report so ordinary development and the
 * instrumentation-free unit tests are unaffected. In release builds the guard
 * answers three questions: is the installed APK still marked debuggable, is a
 * debugger attached, and does the signing certificate still match the expected
 * hash. Nothing here touches the DLMS engine, the USB transport or JSON/GZIP
 * serialization, so enabling it cannot change a reading.
 */
object SecurityGuard {

    /**
     * SHA-256 of the release signing certificate, hex encoded. Empty disables
     * the check so locally built or as-yet unsigned APKs still run.
     *
     * Take the value from the release keystore:
     * `apksigner verify --print-certs app-release.apk`
     * and paste the `Signer #1 certificate SHA-256 digest` here with or without
     * the colons. The keystore committed to this repository currently produces
     * `1c75dbdf0508b8e7053e0724a52393fae27df8d94d4f4fb4be3bde8febbec2d3`;
     * enabling it locks out any APK signed with a different key, so only fill
     * it in once the signing key is final.
     */
    const val EXPECTED_SIGNING_CERT_SHA256 = ""

    /** Result of [verify]. `compromised` is what callers normally act on. */
    data class Report(
        val debuggable: Boolean,
        val debuggerAttached: Boolean,
        /** `null` when no expected hash is configured, i.e. the check is off. */
        val signatureMatches: Boolean?
    ) {
        val compromised: Boolean
            get() = debuggable || debuggerAttached || signatureMatches == false
    }

    /** True when the installed package still carries `android:debuggable`. */
    fun isDebuggable(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** True when a Java debugger is attached or waiting to attach. */
    fun isDebuggerAttached(): Boolean =
        Debug.isDebuggerConnected() || Debug.waitingForDebugger()

    /**
     * Compares the installed signing certificate against [expected].
     *
     * Returns `null` when the check is disabled (blank hash, or no context as
     * in unit tests), `true` on a match and `false` on a mismatch or when the
     * certificate cannot be read.
     */
    fun signatureMatches(
        context: Context?,
        expected: String = EXPECTED_SIGNING_CERT_SHA256
    ): Boolean? {
        val normalized = expected.replace(":", "").replace(" ", "").lowercase()
        if (normalized.isEmpty() || context == null) return null
        val actual = signingCertSha256(context) ?: return false
        return actual.equals(normalized, ignoreCase = true)
    }

    /** Hex SHA-256 of the first signing certificate, or `null` on failure. */
    fun signingCertSha256(context: Context): String? = runCatching {
        val manager = context.packageManager
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo
                ?.let { info ->
                    if (info.hasMultipleSigners()) {
                        info.apkContentsSigners
                    } else {
                        info.signingCertificateHistory
                    }
                }
        } else {
            @Suppress("DEPRECATION")
            manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
        }
        val signer = signatures?.firstOrNull() ?: return null
        MessageDigest.getInstance("SHA-256")
            .digest(signer.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }
    }.getOrNull()

    /**
     * Runs the release checks. Debug builds always report a clean result so the
     * guard can never block development, USB reads or imported-file parsing.
     */
    fun verify(context: Context): Report {
        if (BuildConfig.DEBUG) return Report(false, false, null)
        return Report(
            debuggable = isDebuggable(context),
            debuggerAttached = isDebuggerAttached(),
            signatureMatches = signatureMatches(context)
        )
    }
}
